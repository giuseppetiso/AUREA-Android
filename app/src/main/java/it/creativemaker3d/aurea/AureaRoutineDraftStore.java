package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Archivio locale delle bozze create da AUREA Routine Studio.
 *
 * Le bozze non vengono inviate a Home Assistant. Il relativo YAML può essere
 * soltanto visualizzato e copiato dall'utente per una revisione manuale.
 */
final class AureaRoutineDraftStore {
    private static final String PREFS = "aurea_routine_studio";
    private static final String KEY_DRAFTS = "drafts";
    private static final int MAX_DRAFTS = 30;

    static final List<String> WEEKDAY_CODES = Collections.unmodifiableList(
        Arrays.asList("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    );

    static final class Draft {
        final String id;
        final String sourceSuggestionId;
        final String alias;
        final String entityId;
        final String entityName;
        final String targetState;
        final String time;
        final String actor;
        final List<String> weekdays;
        final long createdAt;
        final long updatedAt;

        Draft(
                String id,
                String sourceSuggestionId,
                String alias,
                String entityId,
                String entityName,
                String targetState,
                String time,
                String actor,
                List<String> weekdays,
                long createdAt,
                long updatedAt) {
            this.id = cleanStatic(id);
            this.sourceSuggestionId = cleanStatic(sourceSuggestionId);
            this.alias = cleanStatic(alias);
            this.entityId = cleanStatic(entityId);
            this.entityName = cleanStatic(entityName);
            this.targetState = cleanStatic(targetState);
            this.time = normalizeTimeStatic(time);
            this.actor = cleanStatic(actor);
            this.weekdays = normalizeWeekdays(weekdays);
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        Draft withEditing(String newAlias, String newTime, List<String> newWeekdays) {
            return new Draft(
                id,
                sourceSuggestionId,
                newAlias,
                entityId,
                entityName,
                targetState,
                newTime,
                actor,
                newWeekdays,
                createdAt,
                System.currentTimeMillis()
            );
        }
    }

    private static final class ActionSpec {
        final String action;
        final String dataKey;
        final String dataValue;
        final String spokenAction;

        ActionSpec(String action, String dataKey, String dataValue, String spokenAction) {
            this.action = action;
            this.dataKey = dataKey;
            this.dataValue = dataValue;
            this.spokenAction = spokenAction;
        }
    }

    private final Context context;

    AureaRoutineDraftStore(Context context) {
        this.context = context.getApplicationContext();
    }

    int count() {
        return readArray().length();
    }

    List<Draft> list() {
        ArrayList<Draft> result = new ArrayList<>();
        JSONArray array = readArray();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            Draft draft = item == null ? null : fromJson(item);
            if (draft != null) {
                result.add(draft);
            }
        }
        result.sort((left, right) -> Long.compare(right.updatedAt, left.updatedAt));
        return result;
    }

    Draft find(String id) {
        String target = clean(id);
        if (target.isEmpty()) {
            return null;
        }
        for (Draft draft : list()) {
            if (draft.id.equals(target)) {
                return draft;
            }
        }
        return null;
    }

    Draft createFromSuggestion(
            AureaInsightsStore.Suggestion suggestion,
            String person) {
        if (suggestion == null || !isSupported(suggestion)) {
            return null;
        }

        ActionSpec spec = actionSpec(suggestion.entityId, suggestion.targetState);
        if (spec == null) {
            return null;
        }

        String personKey = clean(person).toLowerCase(Locale.ROOT);
        String id = "draft_" + Integer.toHexString(
            (suggestion.id + "|" + personKey).hashCode()
        );
        Draft existing = find(id);
        long now = System.currentTimeMillis();
        long createdAt = existing == null ? now : existing.createdAt;
        List<String> days = existing == null
            ? new ArrayList<>()
            : existing.weekdays;

        String time = formatMinute(suggestion.averageMinute);
        String alias = existing == null
            ? "AUREA - " + capitalize(spec.spokenAction) + " "
                + suggestion.name + " alle " + time
            : existing.alias;

        Draft draft = new Draft(
            id,
            suggestion.id,
            alias,
            suggestion.entityId,
            suggestion.name,
            suggestion.targetState,
            existing == null ? time : existing.time,
            clean(person).isEmpty() ? suggestion.actor : person,
            days,
            createdAt,
            now
        );
        save(draft);
        return draft;
    }

    boolean save(Draft draft) {
        if (draft == null
                || draft.id.isEmpty()
                || draft.alias.isEmpty()
                || draft.entityId.isEmpty()
                || actionSpec(draft.entityId, draft.targetState) == null) {
            return false;
        }

        ArrayList<Draft> drafts = new ArrayList<>(list());
        boolean replaced = false;
        for (int index = 0; index < drafts.size(); index++) {
            if (drafts.get(index).id.equals(draft.id)) {
                drafts.set(index, draft);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            drafts.add(draft);
        }
        drafts.sort((left, right) -> Long.compare(right.updatedAt, left.updatedAt));
        if (drafts.size() > MAX_DRAFTS) {
            drafts = new ArrayList<>(drafts.subList(0, MAX_DRAFTS));
        }
        write(drafts);
        return true;
    }

    void delete(String id) {
        String target = clean(id);
        ArrayList<Draft> remaining = new ArrayList<>();
        for (Draft draft : list()) {
            if (!draft.id.equals(target)) {
                remaining.add(draft);
            }
        }
        write(remaining);
    }

    void clear() {
        prefs().edit().remove(KEY_DRAFTS).apply();
    }

    boolean isSupported(AureaInsightsStore.Suggestion suggestion) {
        return suggestion != null
            && actionSpec(suggestion.entityId, suggestion.targetState) != null;
    }

    String unsupportedReason(AureaInsightsStore.Suggestion suggestion) {
        if (suggestion == null) {
            return "Proposta non disponibile";
        }
        String domain = domainOf(suggestion.entityId);
        if (domain.equals("person")) {
            return "La presenza di una persona può diventare una condizione, non un'azione diretta.";
        }
        if (domain.equals("climate")) {
            return "Lo stato osservato del climatizzatore non identifica ancora una modalità impostabile.";
        }
        return "Questo tipo di stato non è ancora convertibile in un'azione sicura.";
    }

    String summary(Draft draft) {
        if (draft == null) {
            return "Bozza non disponibile";
        }
        ActionSpec spec = actionSpec(draft.entityId, draft.targetState);
        String action = spec == null ? "controllare" : spec.spokenAction;
        return capitalize(action) + " " + draft.entityName
            + " alle " + draft.time + " · " + weekdayLabel(draft.weekdays);
    }

    String buildYaml(Draft draft) {
        if (draft == null) {
            return "";
        }
        ActionSpec spec = actionSpec(draft.entityId, draft.targetState);
        if (spec == null) {
            return "";
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("alias: ").append(yamlQuote(draft.alias)).append('\n');
        yaml.append("description: ")
            .append(yamlQuote(
                "Bozza generata da AUREA Routine Studio per "
                    + safeActor(draft.actor)
                    + ". Verificare prima di attivare."
            ))
            .append('\n');
        yaml.append("triggers:\n");
        yaml.append("  - trigger: time\n");
        yaml.append("    at: ").append(yamlQuote(draft.time + ":00")).append('\n');
        if (!draft.weekdays.isEmpty()) {
            yaml.append("    weekday:\n");
            for (String day : draft.weekdays) {
                yaml.append("      - ").append(day).append('\n');
            }
        }
        yaml.append("conditions: []\n");
        yaml.append("actions:\n");
        yaml.append("  - action: ").append(spec.action).append('\n');
        yaml.append("    target:\n");
        yaml.append("      entity_id: ").append(draft.entityId).append('\n');
        if (!spec.dataKey.isEmpty()) {
            yaml.append("    data:\n");
            yaml.append("      ").append(spec.dataKey).append(": ")
                .append(spec.dataValue).append('\n');
        }
        yaml.append("mode: single\n");
        return yaml.toString();
    }

    String weekdayLabel(List<String> weekdays) {
        List<String> days = normalizeWeekdays(weekdays);
        if (days.isEmpty() || days.size() == 7) {
            return "tutti i giorni";
        }
        ArrayList<String> labels = new ArrayList<>();
        for (String day : days) {
            labels.add(dayLabel(day));
        }
        return String.join(", ", labels);
    }

    private ActionSpec actionSpec(String entityId, String targetState) {
        String domain = domainOf(entityId);
        String state = clean(targetState).toLowerCase(Locale.ROOT);

        if (domain.equals("light")
                || domain.equals("switch")
                || domain.equals("fan")
                || domain.equals("input_boolean")) {
            if (state.equals("on")) {
                return new ActionSpec(domain + ".turn_on", "", "", "accendere");
            }
            if (state.equals("off")) {
                return new ActionSpec(domain + ".turn_off", "", "", "spegnere");
            }
            return null;
        }

        if (domain.equals("media_player")) {
            if (state.equals("on")) {
                return new ActionSpec("media_player.turn_on", "", "", "accendere");
            }
            if (state.equals("off")) {
                return new ActionSpec("media_player.turn_off", "", "", "spegnere");
            }
            if (state.equals("playing")) {
                return new ActionSpec("media_player.media_play", "", "", "avviare");
            }
            if (state.equals("paused")) {
                return new ActionSpec("media_player.media_pause", "", "", "mettere in pausa");
            }
            return null;
        }

        if (domain.equals("climate")) {
            String mode = state;
            if (state.equals("heating")) {
                mode = "heat";
            } else if (state.equals("cooling")) {
                mode = "cool";
            }
            if (mode.equals("heat")
                    || mode.equals("cool")
                    || mode.equals("auto")
                    || mode.equals("off")) {
                String spoken = mode.equals("off")
                    ? "spegnere"
                    : "impostare la modalità " + mode;
                return new ActionSpec(
                    "climate.set_hvac_mode",
                    "hvac_mode",
                    mode,
                    spoken
                );
            }
        }
        return null;
    }

    private String domainOf(String entityId) {
        String value = clean(entityId);
        int separator = value.indexOf('.');
        return separator <= 0 ? "" : value.substring(0, separator);
    }

    private Draft fromJson(JSONObject item) {
        String id = clean(item.optString("id", ""));
        if (id.isEmpty()) {
            return null;
        }
        ArrayList<String> weekdays = new ArrayList<>();
        JSONArray days = item.optJSONArray("weekdays");
        if (days != null) {
            for (int index = 0; index < days.length(); index++) {
                weekdays.add(days.optString(index, ""));
            }
        }
        return new Draft(
            id,
            item.optString("source_suggestion_id", ""),
            item.optString("alias", ""),
            item.optString("entity_id", ""),
            item.optString("entity_name", ""),
            item.optString("target_state", ""),
            item.optString("time", "00:00"),
            item.optString("actor", "Casa"),
            weekdays,
            item.optLong("created_at", 0L),
            item.optLong("updated_at", 0L)
        );
    }

    private JSONObject toJson(Draft draft) {
        JSONObject item = new JSONObject();
        try {
            item.put("id", draft.id);
            item.put("source_suggestion_id", draft.sourceSuggestionId);
            item.put("alias", draft.alias);
            item.put("entity_id", draft.entityId);
            item.put("entity_name", draft.entityName);
            item.put("target_state", draft.targetState);
            item.put("time", draft.time);
            item.put("actor", draft.actor);
            item.put("created_at", draft.createdAt);
            item.put("updated_at", draft.updatedAt);
            JSONArray weekdays = new JSONArray();
            for (String day : draft.weekdays) {
                weekdays.put(day);
            }
            item.put("weekdays", weekdays);
        } catch (Exception ignored) {
        }
        return item;
    }

    private JSONArray readArray() {
        try {
            String raw = prefs().getString(KEY_DRAFTS, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void write(List<Draft> drafts) {
        JSONArray array = new JSONArray();
        if (drafts != null) {
            for (Draft draft : drafts) {
                if (draft != null) {
                    array.put(toJson(draft));
                }
            }
        }
        prefs().edit().putString(KEY_DRAFTS, array.toString()).apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String formatMinute(int minute) {
        int normalized = Math.max(0, Math.min(1439, minute));
        return String.format(
            Locale.ITALIAN,
            "%02d:%02d",
            normalized / 60,
            normalized % 60
        );
    }

    private String dayLabel(String code) {
        switch (code) {
            case "mon":
                return "lun";
            case "tue":
                return "mar";
            case "wed":
                return "mer";
            case "thu":
                return "gio";
            case "fri":
                return "ven";
            case "sat":
                return "sab";
            case "sun":
                return "dom";
            default:
                return code;
        }
    }

    private String yamlQuote(String value) {
        String safe = clean(value)
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ");
        return "\"" + safe + "\"";
    }

    private String safeActor(String actor) {
        String value = clean(actor);
        return value.isEmpty() ? "Casa" : value;
    }

    private String capitalize(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) {
            return clean;
        }
        return clean.substring(0, 1).toUpperCase(Locale.ITALIAN) + clean.substring(1);
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeTimeStatic(String value) {
        String clean = cleanStatic(value);
        if (clean.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            return clean;
        }
        return "00:00";
    }

    private static List<String> normalizeWeekdays(List<String> weekdays) {
        Set<String> selected = new LinkedHashSet<>();
        if (weekdays != null) {
            for (String candidate : weekdays) {
                String day = cleanStatic(candidate).toLowerCase(Locale.ROOT);
                if (WEEKDAY_CODES.contains(day)) {
                    selected.add(day);
                }
            }
        }
        ArrayList<String> ordered = new ArrayList<>();
        for (String code : WEEKDAY_CODES) {
            if (selected.contains(code)) {
                ordered.add(code);
            }
        }
        return Collections.unmodifiableList(ordered);
    }
}
