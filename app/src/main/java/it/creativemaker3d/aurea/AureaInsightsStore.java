package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Archivio locale di AUREA Insights.
 *
 * Registra esclusivamente i cambi di stato delle entità scelte dall'utente e
 * genera possibili routine dopo almeno tre giorni distinti con orari simili.
 * Nessuna proposta viene eseguita automaticamente.
 */
final class AureaInsightsStore {
    private static final String PREFS = "aurea_insights";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SELECTED = "selected_entities";
    private static final String KEY_SNAPSHOT = "snapshot";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_SUGGESTIONS = "suggestions";
    private static final String KEY_DISMISSED = "dismissed";

    private static final long EVENT_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long ANALYSIS_WINDOW_MS = 21L * 24L * 60L * 60L * 1000L;
    private static final int TIME_SLOT_MINUTES = 120;
    private static final int MIN_DISTINCT_DAYS = 3;
    private static final int MAX_EVENTS = 600;
    private static final int MAX_SUGGESTIONS = 20;

    static final class Suggestion {
        final String id;
        final String entityId;
        final String name;
        final String targetState;
        final String actor;
        final int distinctDays;
        final int averageMinute;
        final long lastTime;
        final String description;
        final String memoryText;

        Suggestion(
                String id,
                String entityId,
                String name,
                String targetState,
                String actor,
                int distinctDays,
                int averageMinute,
                long lastTime,
                String description,
                String memoryText) {
            this.id = cleanStatic(id);
            this.entityId = cleanStatic(entityId);
            this.name = cleanStatic(name);
            this.targetState = cleanStatic(targetState);
            this.actor = cleanStatic(actor);
            this.distinctDays = distinctDays;
            this.averageMinute = averageMinute;
            this.lastTime = lastTime;
            this.description = cleanStatic(description);
            this.memoryText = cleanStatic(memoryText);
        }
    }

    static final class IngestResult {
        final int changes;
        final boolean newSuggestion;

        IngestResult(int changes, boolean newSuggestion) {
            this.changes = changes;
            this.newSuggestion = newSuggestion;
        }
    }

    private final Context context;

    AureaInsightsStore(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    Set<String> selectedEntities() {
        Set<String> stored = prefs().getStringSet(KEY_SELECTED, Collections.emptySet());
        return stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
    }

    void setSelectedEntities(Set<String> entityIds) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        if (entityIds != null) {
            for (String entityId : entityIds) {
                String value = clean(entityId);
                if (!value.isEmpty()) {
                    cleaned.add(value);
                }
            }
        }
        prefs().edit()
            .putStringSet(KEY_SELECTED, cleaned)
            .remove(KEY_SNAPSHOT)
            .apply();
    }

    int observationCount() {
        return readArray(KEY_EVENTS).length();
    }

    int suggestionCount() {
        return readArray(KEY_SUGGESTIONS).length();
    }

    List<Suggestion> suggestions() {
        ArrayList<Suggestion> result = new ArrayList<>();
        JSONArray array = readArray(KEY_SUGGESTIONS);
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            Suggestion suggestion = fromJson(item);
            if (suggestion != null) {
                result.add(suggestion);
            }
        }
        result.sort((left, right) -> Long.compare(right.lastTime, left.lastTime));
        return result;
    }

    Suggestion findSuggestion(String id) {
        String target = clean(id);
        for (Suggestion suggestion : suggestions()) {
            if (suggestion.id.equals(target)) {
                return suggestion;
            }
        }
        return null;
    }

    boolean acceptSuggestion(String id, String person) {
        Suggestion suggestion = findSuggestion(id);
        if (suggestion == null || suggestion.memoryText.isEmpty()) {
            return false;
        }
        boolean saved = new AureaLearningStore(context).add(person, suggestion.memoryText);
        dismissSuggestion(id);
        new AureaBrainStore(context).clearConversation(person);
        return saved;
    }

    void dismissSuggestion(String id) {
        String target = clean(id);
        if (target.isEmpty()) {
            return;
        }
        Set<String> dismissed = dismissedIds();
        dismissed.add(target);

        JSONArray remaining = new JSONArray();
        JSONArray current = readArray(KEY_SUGGESTIONS);
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item != null && !target.equals(clean(item.optString("id", "")))) {
                remaining.put(item);
            }
        }
        prefs().edit()
            .putStringSet(KEY_DISMISSED, dismissed)
            .putString(KEY_SUGGESTIONS, remaining.toString())
            .apply();
    }

    void clearHistory() {
        prefs().edit()
            .remove(KEY_SNAPSHOT)
            .remove(KEY_EVENTS)
            .remove(KEY_SUGGESTIONS)
            .remove(KEY_DISMISSED)
            .apply();
    }

    void deletePersonData(String person) {
        String target = normalizedActor(person);
        JSONArray filtered = new JSONArray();
        JSONArray current = readArray(KEY_EVENTS);
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String actor = normalizedActor(item.optString("actor", "Casa"));
            if (!actor.equalsIgnoreCase(target)) {
                filtered.put(item);
            }
        }
        prefs().edit().putString(KEY_EVENTS, filtered.toString()).apply();
        rebuildSuggestions(filtered);
    }

    String speechSummary() {
        List<Suggestion> current = suggestions();
        if (current.isEmpty()) {
            return "Non ho ancora rilevato abitudini sufficientemente ripetute. "
                + "Servono almeno tre giorni distinti con un comportamento simile.";
        }

        StringBuilder result = new StringBuilder("Ho rilevato ")
            .append(current.size() == 1 ? "una possibile abitudine. " : current.size() + " possibili abitudini. ");
        int limit = Math.min(3, current.size());
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                result.append(" Inoltre, ");
            }
            result.append(current.get(index).description);
        }
        result.append(" Puoi gestirle dalla schermata AUREA Insights.");
        return result.toString();
    }

    IngestResult ingestStates(JSONArray states, String actorName) {
        if (!isEnabled() || states == null) {
            return new IngestResult(0, false);
        }
        Set<String> selected = selectedEntities();
        if (selected.isEmpty()) {
            return new IngestResult(0, false);
        }

        JSONObject previous = readObject(KEY_SNAPSHOT);
        JSONObject next = new JSONObject();
        JSONArray events = pruneEvents(readArray(KEY_EVENTS));
        String actor = normalizedActor(actorName);
        int changes = 0;
        long now = System.currentTimeMillis();

        for (int index = 0; index < states.length(); index++) {
            JSONObject stateObject = states.optJSONObject(index);
            if (stateObject == null) {
                continue;
            }
            String entityId = clean(stateObject.optString("entity_id", ""));
            if (!selected.contains(entityId)) {
                continue;
            }

            String state = clean(stateObject.optString("state", ""));
            JSONObject attributes = stateObject.optJSONObject("attributes");
            String name = attributes == null
                ? entityId
                : clean(attributes.optString("friendly_name", entityId));
            if (name.isEmpty()) {
                name = entityId;
            }

            try {
                JSONObject snapshotItem = new JSONObject();
                snapshotItem.put("state", state);
                snapshotItem.put("name", name);
                next.put(entityId, snapshotItem);

                JSONObject previousItem = previous.optJSONObject(entityId);
                if (previousItem == null) {
                    continue;
                }
                String previousState = clean(previousItem.optString("state", ""));
                if (previousState.equals(state) || !isMeaningfulState(state)) {
                    continue;
                }

                JSONObject event = new JSONObject();
                event.put("time", now);
                event.put("entity_id", entityId);
                event.put("name", name);
                event.put("from", previousState);
                event.put("to", state);
                event.put("actor", actor);
                events.put(event);
                changes++;
            } catch (Exception ignored) {
            }
        }

        events = trimEvents(events);
        Set<String> oldIds = suggestionIds();
        boolean newSuggestion = rebuildSuggestions(events, oldIds);
        prefs().edit()
            .putString(KEY_SNAPSHOT, next.toString())
            .putString(KEY_EVENTS, events.toString())
            .apply();
        return new IngestResult(changes, newSuggestion);
    }

    private boolean rebuildSuggestions(JSONArray events) {
        return rebuildSuggestions(events, suggestionIds());
    }

    private boolean rebuildSuggestions(JSONArray events, Set<String> previousIds) {
        long threshold = System.currentTimeMillis() - ANALYSIS_WINDOW_MS;
        Map<String, List<JSONObject>> groups = new HashMap<>();

        for (int index = 0; index < events.length(); index++) {
            JSONObject event = events.optJSONObject(index);
            if (event == null || event.optLong("time", 0L) < threshold) {
                continue;
            }
            long time = event.optLong("time", 0L);
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(time);
            int minute = calendar.get(Calendar.HOUR_OF_DAY) * 60
                + calendar.get(Calendar.MINUTE);
            int slot = minute / TIME_SLOT_MINUTES;
            String key = normalizedActor(event.optString("actor", "Casa"))
                + "|" + clean(event.optString("entity_id", ""))
                + "|" + clean(event.optString("to", ""))
                + "|" + slot;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
        }

        ArrayList<JSONObject> generated = new ArrayList<>();
        Set<String> dismissed = dismissedIds();
        boolean createdNew = false;

        for (Map.Entry<String, List<JSONObject>> entry : groups.entrySet()) {
            List<JSONObject> group = entry.getValue();
            HashSet<String> days = new HashSet<>();
            int minuteSum = 0;
            long lastTime = 0L;
            JSONObject latest = null;

            for (JSONObject event : group) {
                long time = event.optLong("time", 0L);
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(time);
                days.add(calendar.get(Calendar.YEAR) + "-"
                    + calendar.get(Calendar.DAY_OF_YEAR));
                minuteSum += calendar.get(Calendar.HOUR_OF_DAY) * 60
                    + calendar.get(Calendar.MINUTE);
                if (time >= lastTime) {
                    lastTime = time;
                    latest = event;
                }
            }

            if (days.size() < MIN_DISTINCT_DAYS || latest == null) {
                continue;
            }

            String id = "routine_" + Integer.toHexString(entry.getKey().hashCode());
            if (dismissed.contains(id)) {
                continue;
            }

            int averageMinute = Math.max(0, Math.min(1439, minuteSum / group.size()));
            String name = clean(latest.optString("name", latest.optString("entity_id", "dispositivo")));
            String targetState = clean(latest.optString("to", ""));
            String actor = normalizedActor(latest.optString("actor", "Casa"));
            String timeLabel = formatMinute(averageMinute);
            String phrase = statePhrase(targetState);
            String description = "Ho osservato " + name + " " + phrase
                + " verso le " + timeLabel + " in " + days.size()
                + " giorni diversi.";
            String memoryText = "Di solito preferisco che " + name + " sia "
                + phrase + " verso le " + timeLabel;

            try {
                JSONObject suggestion = new JSONObject();
                suggestion.put("id", id);
                suggestion.put("entity_id", clean(latest.optString("entity_id", "")));
                suggestion.put("name", name);
                suggestion.put("target_state", targetState);
                suggestion.put("actor", actor);
                suggestion.put("distinct_days", days.size());
                suggestion.put("average_minute", averageMinute);
                suggestion.put("last_time", lastTime);
                suggestion.put("description", description);
                suggestion.put("memory_text", memoryText);
                generated.add(suggestion);
                if (!previousIds.contains(id)) {
                    createdNew = true;
                }
            } catch (Exception ignored) {
            }
        }

        generated.sort((left, right) -> Long.compare(
            right.optLong("last_time", 0L),
            left.optLong("last_time", 0L)
        ));
        JSONArray suggestions = new JSONArray();
        for (int index = 0; index < Math.min(MAX_SUGGESTIONS, generated.size()); index++) {
            suggestions.put(generated.get(index));
        }
        prefs().edit().putString(KEY_SUGGESTIONS, suggestions.toString()).apply();
        return createdNew;
    }

    private JSONArray pruneEvents(JSONArray source) {
        JSONArray result = new JSONArray();
        long threshold = System.currentTimeMillis() - EVENT_RETENTION_MS;
        for (int index = 0; index < source.length(); index++) {
            JSONObject event = source.optJSONObject(index);
            if (event != null && event.optLong("time", 0L) >= threshold) {
                result.put(event);
            }
        }
        return result;
    }

    private JSONArray trimEvents(JSONArray source) {
        if (source.length() <= MAX_EVENTS) {
            return source;
        }
        JSONArray result = new JSONArray();
        int first = Math.max(0, source.length() - MAX_EVENTS);
        for (int index = first; index < source.length(); index++) {
            result.put(source.opt(index));
        }
        return result;
    }

    private Suggestion fromJson(JSONObject item) {
        String id = clean(item.optString("id", ""));
        if (id.isEmpty()) {
            return null;
        }
        return new Suggestion(
            id,
            item.optString("entity_id", ""),
            item.optString("name", ""),
            item.optString("target_state", ""),
            item.optString("actor", "Casa"),
            item.optInt("distinct_days", 0),
            item.optInt("average_minute", 0),
            item.optLong("last_time", 0L),
            item.optString("description", ""),
            item.optString("memory_text", "")
        );
    }

    private Set<String> suggestionIds() {
        HashSet<String> ids = new HashSet<>();
        JSONArray current = readArray(KEY_SUGGESTIONS);
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item != null) {
                String id = clean(item.optString("id", ""));
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private Set<String> dismissedIds() {
        Set<String> stored = prefs().getStringSet(KEY_DISMISSED, Collections.emptySet());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    private JSONObject readObject(String key) {
        try {
            String raw = prefs().getString(key, "{}");
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray readArray(String key) {
        try {
            String raw = prefs().getString(key, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean isMeaningfulState(String state) {
        String value = clean(state).toLowerCase(Locale.ROOT);
        return !value.isEmpty()
            && !value.equals("unknown")
            && !value.equals("unavailable")
            && !value.equals("none");
    }

    private String normalizedActor(String actor) {
        String value = clean(actor);
        return value.isEmpty() || value.equalsIgnoreCase("Ospite") ? "Casa" : value;
    }

    private String statePhrase(String state) {
        String value = clean(state).toLowerCase(Locale.ROOT);
        switch (value) {
            case "on":
                return "acceso";
            case "off":
                return "spento";
            case "home":
                return "a casa";
            case "not_home":
                return "fuori casa";
            case "playing":
                return "in riproduzione";
            case "paused":
                return "in pausa";
            case "idle":
                return "inattivo";
            case "heat":
            case "heating":
                return "in riscaldamento";
            case "cool":
            case "cooling":
                return "in raffrescamento";
            default:
                return "allo stato " + value;
        }
    }

    private String formatMinute(int minute) {
        int hour = Math.max(0, Math.min(23, minute / 60));
        int minutes = Math.max(0, Math.min(59, minute % 60));
        return String.format(Locale.ITALIAN, "%02d:%02d", hour, minutes);
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
