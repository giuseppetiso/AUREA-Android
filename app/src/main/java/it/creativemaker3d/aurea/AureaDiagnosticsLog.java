package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Registro tecnico locale di AUREA.
 *
 * Non conserva token, chiavi API, testo delle conversazioni, firme vocali o
 * dati biometrici. I messaggi vengono sanificati prima del salvataggio e il
 * registro è limitato agli eventi più recenti.
 */
final class AureaDiagnosticsLog {
    private static final String PREFS = "aurea_diagnostics";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_EVENTS = 80;
    private static final int MAX_MESSAGE = 360;

    private static final Pattern BEARER = Pattern.compile(
        "(?i)bearer\\s+[a-z0-9._~+\\-/=]+"
    );
    private static final Pattern GOOGLE_KEY = Pattern.compile(
        "AIza[a-zA-Z0-9_-]{20,}"
    );
    private static final Pattern TOKEN_ASSIGNMENT = Pattern.compile(
        "(?i)(token|api[_ -]?key|chiave[_ -]?api)\\s*[:=]\\s*[^\\s,;]+"
    );

    static final class Entry {
        final long time;
        final String level;
        final String component;
        final String message;

        Entry(long time, String level, String component, String message) {
            this.time = time;
            this.level = cleanStatic(level);
            this.component = cleanStatic(component);
            this.message = cleanStatic(message);
        }

        String label() {
            String formatted = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.MEDIUM,
                Locale.ITALIAN
            ).format(new Date(time));
            return formatted + " · " + level + " · " + component + "\n" + message;
        }
    }

    private final Context context;

    AureaDiagnosticsLog(Context context) {
        this.context = context.getApplicationContext();
    }

    void info(String component, String message) {
        append("INFO", component, message, null);
    }

    void warning(String component, String message) {
        append("AVVISO", component, message, null);
    }

    void error(String component, String message, Throwable error) {
        append("ERRORE", component, message, error);
    }

    int count() {
        return readArray().length();
    }

    int errorCount() {
        int count = 0;
        for (Entry entry : entries()) {
            if ("ERRORE".equals(entry.level)) {
                count++;
            }
        }
        return count;
    }

    List<Entry> entries() {
        ArrayList<Entry> result = new ArrayList<>();
        JSONArray array = readArray();
        for (int index = array.length() - 1; index >= 0; index--) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            result.add(new Entry(
                item.optLong("time", 0L),
                item.optString("level", "INFO"),
                item.optString("component", "AUREA"),
                item.optString("message", "")
            ));
        }
        return result;
    }

    void clear() {
        prefs().edit().remove(KEY_EVENTS).apply();
    }

    String reportSection(int maximum) {
        List<Entry> all = entries();
        if (all.isEmpty()) {
            return "Nessun errore tecnico registrato.";
        }

        StringBuilder report = new StringBuilder();
        int limit = Math.min(Math.max(1, maximum), all.size());
        for (int index = 0; index < limit; index++) {
            if (index > 0) {
                report.append("\n\n");
            }
            report.append(all.get(index).label());
        }
        return report.toString();
    }

    private synchronized void append(
            String level,
            String component,
            String message,
            Throwable error) {
        try {
            StringBuilder combined = new StringBuilder(clean(message));
            if (error != null) {
                String errorMessage = clean(error.getMessage());
                if (!errorMessage.isEmpty()) {
                    if (combined.length() > 0) {
                        combined.append(": ");
                    }
                    combined.append(errorMessage);
                } else if (combined.length() == 0) {
                    combined.append(error.getClass().getSimpleName());
                }
            }

            JSONArray current = readArray();
            JSONArray limited = new JSONArray();
            int first = Math.max(0, current.length() - (MAX_EVENTS - 1));
            for (int index = first; index < current.length(); index++) {
                limited.put(current.opt(index));
            }

            JSONObject item = new JSONObject();
            item.put("time", System.currentTimeMillis());
            item.put("level", clean(level));
            item.put("component", limit(sanitize(component), 80));
            item.put("message", limit(sanitize(combined.toString()), MAX_MESSAGE));
            limited.put(item);

            prefs().edit().putString(KEY_EVENTS, limited.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private JSONArray readArray() {
        try {
            String raw = prefs().getString(KEY_EVENTS, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String sanitize(String value) {
        String safe = clean(value)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replaceAll("\\s+", " ");
        safe = BEARER.matcher(safe).replaceAll("Bearer [RIMOSSO]");
        safe = GOOGLE_KEY.matcher(safe).replaceAll("[CHIAVE API RIMOSSA]");
        safe = TOKEN_ASSIGNMENT.matcher(safe).replaceAll("$1=[RIMOSSO]");
        return safe;
    }

    private String limit(String value, int maximum) {
        String clean = clean(value);
        return clean.length() <= maximum ? clean : clean.substring(0, maximum);
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
