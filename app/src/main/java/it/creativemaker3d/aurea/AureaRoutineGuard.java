package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Controllo preventivo in sola lettura delle bozze Routine Studio.
 *
 * Il validatore non chiama servizi Home Assistant e non modifica entità. Legge
 * soltanto lo stato dell'entità indicata, verifica la struttura locale della
 * bozza e rileva conflitti tra bozze dello stesso profilo.
 */
final class AureaRoutineGuard {
    private static final String HA_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String DEFAULT_HA_URL = "http://192.168.178.72:8123";

    private static final Pattern ENTITY_PATTERN = Pattern.compile(
        "^[a-z0-9_]+\\.[a-z0-9_]+$"
    );
    private static final Pattern TIME_PATTERN = Pattern.compile(
        "^(?:[01]\\d|2[0-3]):[0-5]\\d$"
    );

    private static final Set<String> VALID_DAYS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("mon", "tue", "wed", "thu", "fri", "sat", "sun"))
    );

    private static final String[] SENSITIVE_TERMS = {
        "serratura", "portone", "cancello", "garage", "allarme",
        "antifurto", "sirena", "corrente generale", "corrente_generale",
        "presa generale", "presa_generale", "quadro elettrico",
        "quadro_elettrico", "forno", "piano cottura", "piano_cottura",
        "caldaia", "gas", "valvola acqua", "valvola_acqua"
    };

    enum Level {
        SAFE,
        WARNING,
        BLOCKED
    }

    static final class Finding {
        final Level level;
        final String title;
        final String detail;

        Finding(Level level, String title, String detail) {
            this.level = level == null ? Level.WARNING : level;
            this.title = cleanStatic(title);
            this.detail = cleanStatic(detail);
        }
    }

    static final class Report {
        final String draftId;
        final long checkedAt;
        final List<Finding> findings;

        Report(String draftId, long checkedAt, List<Finding> findings) {
            this.draftId = cleanStatic(draftId);
            this.checkedAt = checkedAt;
            this.findings = findings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(findings));
        }

        boolean isBlocked() {
            for (Finding finding : findings) {
                if (finding.level == Level.BLOCKED) {
                    return true;
                }
            }
            return false;
        }

        boolean hasWarnings() {
            for (Finding finding : findings) {
                if (finding.level == Level.WARNING) {
                    return true;
                }
            }
            return false;
        }

        Level overallLevel() {
            if (isBlocked()) {
                return Level.BLOCKED;
            }
            return hasWarnings() ? Level.WARNING : Level.SAFE;
        }

        String statusLabel() {
            switch (overallLevel()) {
                case BLOCKED:
                    return "BLOCCATA";
                case WARNING:
                    return "DA CONTROLLARE";
                default:
                    return "SUPERATA";
            }
        }

        String formatted() {
            StringBuilder text = new StringBuilder();
            text.append("Esito Routine Guard: ").append(statusLabel()).append('\n');
            if (checkedAt > 0L) {
                text.append("Controllo: ")
                    .append(DateFormat.getDateTimeInstance(
                        DateFormat.SHORT,
                        DateFormat.SHORT
                    ).format(new Date(checkedAt)))
                    .append("\n\n");
            }
            for (Finding finding : findings) {
                text.append(symbol(finding.level))
                    .append(' ')
                    .append(finding.title);
                if (!finding.detail.isEmpty()) {
                    text.append("\n   ").append(finding.detail);
                }
                text.append("\n\n");
            }
            return text.toString().trim();
        }

        private static String symbol(Level level) {
            switch (level) {
                case BLOCKED:
                    return "⛔";
                case WARNING:
                    return "⚠";
                default:
                    return "✓";
            }
        }
    }

    private final Context context;

    AureaRoutineGuard(Context context) {
        this.context = context.getApplicationContext();
    }

    Report inspect(
            AureaRoutineDraftStore.Draft draft,
            List<AureaRoutineDraftStore.Draft> profileDrafts) {
        ArrayList<Finding> findings = new ArrayList<>();
        if (draft == null) {
            findings.add(blocked("Bozza assente", "Routine Studio non ha fornito una bozza valida."));
            return new Report("", System.currentTimeMillis(), findings);
        }

        validateLocalStructure(draft, findings);
        validateSensitiveTarget(draft, findings);
        validateConflicts(draft, profileDrafts, findings);
        validateYaml(draft, findings);
        validateHomeAssistantEntity(draft, findings);

        if (findings.isEmpty()) {
            findings.add(safe("Controllo completato", "Nessun problema rilevato."));
        }
        return new Report(draft.id, System.currentTimeMillis(), findings);
    }

    private void validateLocalStructure(
            AureaRoutineDraftStore.Draft draft,
            List<Finding> findings) {
        if (draft.alias.isEmpty()) {
            findings.add(blocked("Nome mancante", "Assegna un nome alla bozza."));
        } else if (draft.alias.length() > 100) {
            findings.add(warning(
                "Nome molto lungo",
                "Riduci il nome per renderlo leggibile nell'elenco automazioni."
            ));
        } else {
            findings.add(safe("Nome valido", draft.alias));
        }

        if (!TIME_PATTERN.matcher(draft.time).matches()) {
            findings.add(blocked("Orario non valido", "Usa il formato HH:MM tra 00:00 e 23:59."));
        } else {
            findings.add(safe("Orario valido", draft.time));
        }

        if (!ENTITY_PATTERN.matcher(draft.entityId).matches()) {
            findings.add(blocked(
                "Entity ID non valido",
                "Il valore deve avere il formato dominio.nome_entità."
            ));
        }

        for (String day : draft.weekdays) {
            if (!VALID_DAYS.contains(clean(day).toLowerCase(Locale.ROOT))) {
                findings.add(blocked("Giorno non valido", clean(day)));
            }
        }
        if (draft.weekdays.isEmpty()) {
            findings.add(safe("Calendario", "Esecuzione prevista tutti i giorni."));
        } else {
            findings.add(safe(
                "Calendario",
                "Giorni selezionati: " + draft.weekdays.size()
            ));
        }

        if (!isSupportedCombination(draft.entityId, draft.targetState)) {
            findings.add(blocked(
                "Azione incompatibile",
                "Lo stato richiesto non è supportato per il dominio "
                    + domainOf(draft.entityId) + "."
            ));
        }
    }

    private void validateSensitiveTarget(
            AureaRoutineDraftStore.Draft draft,
            List<Finding> findings) {
        String searchable = normalizeSearch(
            draft.entityId + " " + draft.entityName + " " + draft.alias
        );
        for (String term : SENSITIVE_TERMS) {
            if (searchable.contains(normalizeSearch(term))) {
                findings.add(blocked(
                    "Dispositivo sensibile",
                    "La bozza riguarda “" + term
                        + "”. Routine Guard non permette la copia automatica di azioni sensibili."
                ));
                return;
            }
        }
        findings.add(safe(
            "Categoria consentita",
            "Non sono stati rilevati dispositivi sensibili nel nome o nell'Entity ID."
        ));
    }

    private void validateConflicts(
            AureaRoutineDraftStore.Draft draft,
            List<AureaRoutineDraftStore.Draft> profileDrafts,
            List<Finding> findings) {
        boolean foundConflict = false;
        if (profileDrafts != null) {
            for (AureaRoutineDraftStore.Draft other : profileDrafts) {
                if (other == null || other.id.equals(draft.id)) {
                    continue;
                }
                if (other.alias.equalsIgnoreCase(draft.alias)) {
                    findings.add(warning(
                        "Nome duplicato",
                        "Un'altra bozza del profilo usa lo stesso nome."
                    ));
                }
                if (!other.entityId.equals(draft.entityId)
                        || !other.time.equals(draft.time)
                        || !daysOverlap(draft.weekdays, other.weekdays)) {
                    continue;
                }

                foundConflict = true;
                if (!other.targetState.equalsIgnoreCase(draft.targetState)) {
                    findings.add(blocked(
                        "Conflitto diretto",
                        "Un'altra bozza comanda la stessa entità allo stesso orario "
                            + "verso uno stato diverso: " + other.alias
                    ));
                } else {
                    findings.add(warning(
                        "Possibile duplicato",
                        "Un'altra bozza ripete la stessa azione allo stesso orario: "
                            + other.alias
                    ));
                }
            }
        }
        if (!foundConflict) {
            findings.add(safe(
                "Nessun conflitto temporale",
                "Non risultano altre bozze sulla stessa entità e fascia oraria."
            ));
        }
    }

    private void validateYaml(
            AureaRoutineDraftStore.Draft draft,
            List<Finding> findings) {
        String yaml = new AureaRoutineDraftStore(context).buildYaml(draft);
        if (yaml.isEmpty()
                || !yaml.contains("alias:")
                || !yaml.contains("triggers:")
                || !yaml.contains("actions:")
                || !yaml.contains("target:")
                || !yaml.contains("mode: single")) {
            findings.add(blocked(
                "YAML incompleto",
                "Routine Studio non ha generato tutti i blocchi obbligatori."
            ));
            return;
        }
        if (!yaml.contains("entity_id: " + draft.entityId)) {
            findings.add(blocked(
                "Entity ID assente dal YAML",
                "Il codice generato non punta all'entità selezionata."
            ));
            return;
        }
        findings.add(safe(
            "Struttura YAML coerente",
            "Alias, trigger, azione, target e modalità sono presenti."
        ));
    }

    private void validateHomeAssistantEntity(
            AureaRoutineDraftStore.Draft draft,
            List<Finding> findings) {
        SharedPreferences prefs = context.getSharedPreferences(HA_PREFS, Context.MODE_PRIVATE);
        String haUrl = trimSlash(prefs.getString(KEY_HA_URL, DEFAULT_HA_URL));
        String token = clean(prefs.getString(KEY_HA_TOKEN, ""));
        if (haUrl.isEmpty() || token.isEmpty()) {
            findings.add(blocked(
                "Connessione Home Assistant incompleta",
                "Routine Guard non può verificare l'entità senza URL e token locali."
            ));
            return;
        }

        HttpURLConnection connection = null;
        try {
            URL endpoint = new URL(haUrl + "/api/states/" + draft.entityId);
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            String body = readAll(stream);

            if (code == 404) {
                findings.add(blocked(
                    "Entità non trovata",
                    draft.entityId + " non esiste attualmente in Home Assistant."
                ));
                return;
            }
            if (code < 200 || code >= 300) {
                findings.add(blocked(
                    "Verifica Home Assistant non riuscita",
                    "Risposta HTTP " + code + ". La copia resta bloccata."
                ));
                return;
            }

            JSONObject state = new JSONObject(body);
            String returnedId = clean(state.optString("entity_id", ""));
            if (!returnedId.equals(draft.entityId)) {
                findings.add(blocked(
                    "Risposta incoerente",
                    "Home Assistant ha restituito un'Entity ID differente."
                ));
                return;
            }

            String currentState = clean(state.optString("state", ""));
            if (currentState.equalsIgnoreCase("unavailable")
                    || currentState.equalsIgnoreCase("unknown")) {
                findings.add(warning(
                    "Entità non disponibile",
                    "L'entità esiste ma lo stato attuale è “" + currentState + "”."
                ));
            } else {
                findings.add(safe(
                    "Entità confermata in Home Assistant",
                    draft.entityId + " · stato attuale: " + currentState
                ));
            }

            JSONObject attributes = state.optJSONObject("attributes");
            validateCapabilities(draft, attributes, findings);
        } catch (Exception error) {
            findings.add(blocked(
                "Home Assistant non raggiungibile",
                safeMessage(error) + ". La copia resta bloccata."
            ));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void validateCapabilities(
            AureaRoutineDraftStore.Draft draft,
            JSONObject attributes,
            List<Finding> findings) {
        String domain = domainOf(draft.entityId);
        if (!domain.equals("climate")) {
            return;
        }
        String requested = normalizeClimateMode(draft.targetState);
        JSONArray modes = attributes == null ? null : attributes.optJSONArray("hvac_modes");
        if (modes == null || modes.length() == 0) {
            findings.add(warning(
                "Modalità clima non dichiarate",
                "Home Assistant non espone l'elenco hvac_modes per questa entità."
            ));
            return;
        }
        for (int index = 0; index < modes.length(); index++) {
            if (requested.equalsIgnoreCase(clean(modes.optString(index, "")))) {
                findings.add(safe(
                    "Modalità clima supportata",
                    "Home Assistant dichiara la modalità “" + requested + "”."
                ));
                return;
            }
        }
        findings.add(blocked(
            "Modalità clima non supportata",
            "L'entità non dichiara la modalità “" + requested + "”."
        ));
    }

    private boolean isSupportedCombination(String entityId, String targetState) {
        String domain = domainOf(entityId);
        String state = clean(targetState).toLowerCase(Locale.ROOT);
        if (domain.equals("light")
                || domain.equals("switch")
                || domain.equals("fan")
                || domain.equals("input_boolean")) {
            return state.equals("on") || state.equals("off");
        }
        if (domain.equals("media_player")) {
            return state.equals("on")
                || state.equals("off")
                || state.equals("playing")
                || state.equals("paused");
        }
        if (domain.equals("climate")) {
            String mode = normalizeClimateMode(state);
            return mode.equals("heat")
                || mode.equals("cool")
                || mode.equals("auto")
                || mode.equals("off");
        }
        return false;
    }

    private boolean daysOverlap(List<String> first, List<String> second) {
        Set<String> left = normalizedDays(first);
        Set<String> right = normalizedDays(second);
        left.retainAll(right);
        return !left.isEmpty();
    }

    private Set<String> normalizedDays(List<String> days) {
        HashSet<String> result = new HashSet<>();
        if (days == null || days.isEmpty()) {
            result.addAll(VALID_DAYS);
            return result;
        }
        for (String day : days) {
            String value = clean(day).toLowerCase(Locale.ROOT);
            if (VALID_DAYS.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private String domainOf(String entityId) {
        String value = clean(entityId);
        int separator = value.indexOf('.');
        return separator <= 0 ? "" : value.substring(0, separator);
    }

    private String normalizeClimateMode(String state) {
        String value = clean(state).toLowerCase(Locale.ROOT);
        if (value.equals("heating")) {
            return "heat";
        }
        if (value.equals("cooling")) {
            return "cool";
        }
        return value;
    }

    private String normalizeSearch(String value) {
        return clean(value)
            .toLowerCase(Locale.ROOT)
            .replace('-', ' ')
            .replace('.', ' ')
            .replaceAll("\\s+", " ");
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private Finding safe(String title, String detail) {
        return new Finding(Level.SAFE, title, detail);
    }

    private Finding warning(String title, String detail) {
        return new Finding(Level.WARNING, title, detail);
    }

    private Finding blocked(String title, String detail) {
        return new Finding(Level.BLOCKED, title, detail);
    }

    private String safeMessage(Exception error) {
        String message = error == null ? "errore sconosciuto" : clean(error.getMessage());
        return message.isEmpty() ? "errore sconosciuto" : message;
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String clean(String value) {
        return cleanStatic(value);
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
