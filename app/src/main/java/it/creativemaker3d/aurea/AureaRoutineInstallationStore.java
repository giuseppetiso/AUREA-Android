package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Registro locale e non sensibile delle automazioni installate da AUREA. */
final class AureaRoutineInstallationStore {
    private static final String PREFS = "aurea_routine_installer";
    private static final String KEY_RECORDS = "records";
    private static final int MAX_RECORDS = 60;

    static final class Record {
        final String draftId;
        final String automationId;
        final String alias;
        final String entityId;
        final String person;
        final long installedAt;

        Record(
                String draftId,
                String automationId,
                String alias,
                String entityId,
                String person,
                long installedAt) {
            this.draftId = cleanStatic(draftId);
            this.automationId = cleanStatic(automationId);
            this.alias = cleanStatic(alias);
            this.entityId = cleanStatic(entityId);
            this.person = cleanStatic(person);
            this.installedAt = installedAt;
        }
    }

    private final Context context;

    AureaRoutineInstallationStore(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void record(
            AureaRoutineDraftStore.Draft draft,
            String automationId,
            String person) {
        if (draft == null || clean(automationId).isEmpty()) {
            return;
        }

        ArrayList<Record> records = new ArrayList<>(list());
        records.removeIf(item -> item.draftId.equals(draft.id));
        records.add(0, new Record(
            draft.id,
            automationId,
            draft.alias,
            draft.entityId,
            person,
            System.currentTimeMillis()
        ));
        if (records.size() > MAX_RECORDS) {
            records = new ArrayList<>(records.subList(0, MAX_RECORDS));
        }
        write(records);
    }

    Record findByDraft(String draftId) {
        String target = clean(draftId);
        for (Record record : list()) {
            if (record.draftId.equals(target)) {
                return record;
            }
        }
        return null;
    }

    int countForPerson(String person) {
        int count = 0;
        String target = clean(person);
        for (Record record : list()) {
            if (record.person.equalsIgnoreCase(target)) {
                count++;
            }
        }
        return count;
    }

    List<Record> list() {
        ArrayList<Record> result = new ArrayList<>();
        JSONArray array = readArray();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String draftId = clean(item.optString("draft_id", ""));
            String automationId = clean(item.optString("automation_id", ""));
            if (draftId.isEmpty() || automationId.isEmpty()) {
                continue;
            }
            result.add(new Record(
                draftId,
                automationId,
                item.optString("alias", ""),
                item.optString("entity_id", ""),
                item.optString("person", ""),
                item.optLong("installed_at", 0L)
            ));
        }
        return result;
    }

    private void write(List<Record> records) {
        JSONArray array = new JSONArray();
        if (records != null) {
            for (Record record : records) {
                try {
                    JSONObject item = new JSONObject();
                    item.put("draft_id", record.draftId);
                    item.put("automation_id", record.automationId);
                    item.put("alias", record.alias);
                    item.put("entity_id", record.entityId);
                    item.put("person", record.person);
                    item.put("installed_at", record.installedAt);
                    array.put(item);
                } catch (Exception ignored) {
                }
            }
        }
        prefs().edit().putString(KEY_RECORDS, array.toString()).apply();
    }

    private JSONArray readArray() {
        try {
            String raw = prefs().getString(KEY_RECORDS, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
