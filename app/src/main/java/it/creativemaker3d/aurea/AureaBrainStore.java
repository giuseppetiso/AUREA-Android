package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Memoria locale e configurazione di AUREA Brain.
 *
 * Non contiene il token Home Assistant. Conserva soltanto l'agente scelto,
 * gli identificativi temporanei delle conversazioni e un registro locale
 * limitato delle decisioni. Ogni conversazione è separata per persona.
 */
final class AureaBrainStore {
    private static final String PREFS = "aurea_brain";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_AGENT_ID = "agent_id";
    private static final String KEY_DECISION_LOG = "decision_log";
    private static final String PREFIX_CONVERSATION = "conversation_";
    private static final String PREFIX_ACTIVITY = "activity_";

    private static final long CONVERSATION_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final int MAX_DECISIONS = 60;

    private final Context context;

    AureaBrainStore(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, true);
    }

    void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    String agentId() {
        return clean(prefs().getString(KEY_AGENT_ID, ""));
    }

    void setAgentId(String agentId) {
        prefs().edit().putString(KEY_AGENT_ID, clean(agentId)).apply();
    }

    String activeConversationId(String person) {
        String key = personKey(person);
        SharedPreferences preferences = prefs();
        long lastActivity = preferences.getLong(PREFIX_ACTIVITY + key, 0L);
        String conversationId = clean(
            preferences.getString(PREFIX_CONVERSATION + key, "")
        );

        if (conversationId.isEmpty()) {
            return "";
        }

        long age = System.currentTimeMillis() - lastActivity;
        if (lastActivity <= 0L || age < 0L || age > CONVERSATION_TIMEOUT_MS) {
            clearConversation(person);
            return "";
        }
        return conversationId;
    }

    void saveConversation(String person, String conversationId) {
        String cleaned = clean(conversationId);
        if (cleaned.isEmpty()) {
            return;
        }

        String key = personKey(person);
        prefs().edit()
            .putString(PREFIX_CONVERSATION + key, cleaned)
            .putLong(PREFIX_ACTIVITY + key, System.currentTimeMillis())
            .apply();
    }

    void clearConversation(String person) {
        String key = personKey(person);
        prefs().edit()
            .remove(PREFIX_CONVERSATION + key)
            .remove(PREFIX_ACTIVITY + key)
            .apply();
    }

    void clearAllConversations() {
        SharedPreferences preferences = prefs();
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(PREFIX_CONVERSATION)
                    || key.startsWith(PREFIX_ACTIVITY)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    boolean hasActiveConversation(String person) {
        return !activeConversationId(person).isEmpty();
    }

    int decisionCount() {
        return readDecisionLog().length();
    }

    void clearDecisionLog() {
        prefs().edit().remove(KEY_DECISION_LOG).apply();
    }

    void appendDecision(
            String person,
            String request,
            String response,
            String outcome,
            boolean sensitive,
            String agentId) {
        try {
            JSONArray current = readDecisionLog();
            JSONArray limited = new JSONArray();
            int first = Math.max(0, current.length() - (MAX_DECISIONS - 1));
            for (int index = first; index < current.length(); index++) {
                limited.put(current.opt(index));
            }

            JSONObject item = new JSONObject();
            item.put("time", System.currentTimeMillis());
            item.put("person", normalizedPerson(person));
            item.put("request", limit(clean(request), 500));
            item.put("response", limit(clean(response), 700));
            item.put("outcome", clean(outcome));
            item.put("sensitive", sensitive);
            item.put("agent_id", clean(agentId));
            limited.put(item);

            prefs().edit()
                .putString(KEY_DECISION_LOG, limited.toString())
                .apply();
        } catch (Exception ignored) {
        }
    }

    String initialContext(String person) {
        String identity = normalizedPerson(person);
        String learningContext = new AureaLearningStore(context)
            .promptContext(identity);
        return "Contesto AUREA, non ripeterlo integralmente all'utente: "
            + "stai parlando con " + identity + ", persona riconosciuta dal tablet AUREA. "
            + "Sei un assistente domestico prudente, concreto e in lingua italiana. "
            + "Usa soltanto entità e strumenti esposti da Home Assistant. "
            + "Non inventare stati o azioni. Se una richiesta è ambigua, fai una domanda breve. "
            + "Per serrature, allarme, alimentazione generale o operazioni potenzialmente pericolose, "
            + "richiedi conferma prima di agire. Mantieni il filo della conversazione e considera "
            + "che i ricordi appartengono esclusivamente a " + identity + "."
            + learningContext;
    }

    private JSONArray readDecisionLog() {
        try {
            String raw = prefs().getString(KEY_DECISION_LOG, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String personKey(String person) {
        byte[] bytes = normalizedPerson(person)
            .toLowerCase(Locale.ROOT)
            .getBytes(StandardCharsets.UTF_8);
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
        );
    }

    private String normalizedPerson(String person) {
        String cleaned = clean(person);
        return cleaned.isEmpty() ? "Ospite" : cleaned;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }
}
