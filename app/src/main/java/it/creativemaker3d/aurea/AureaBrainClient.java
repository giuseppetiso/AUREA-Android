package it.creativemaker3d.aurea;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motore conversazionale di AUREA Brain.
 *
 * Usa l'API Conversation di Home Assistant, mantiene una conversazione
 * separata per persona e applica conferme deterministiche alle azioni
 * sensibili e all'apprendimento di preferenze personali.
 */
final class AureaBrainClient {
    private static final String LEARNING_SAVE = "save";
    private static final String LEARNING_DELETE = "delete";
    private static final String LEARNING_CLEAR = "clear";

    private static final Pattern EXPLICIT_MEMORY_PATTERN = Pattern.compile(
        "^(?:aurea[\\s,]+)?(?:ricorda|memorizza|impara|tieni a mente)"
            + "(?:\\s+che)?\\s+(.+)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern CORRECTION_MEMORY_PATTERN = Pattern.compile(
        "^(?:no[\\s,]+)?(?:in realtà[\\s,]+)?"
            + "(?:preferisco|mi piace|di solito|normalmente)\\s+.+$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern DELETE_MEMORY_PATTERN = Pattern.compile(
        "^(?:aurea[\\s,]+)?(?:dimentica|cancella|rimuovi)"
            + "(?:\\s+(?:il ricordo|la preferenza|che))?\\s+(.+)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    static final class Result {
        final String answer;
        final boolean continueConversation;

        Result(String answer, boolean continueConversation) {
            this.answer = answer == null || answer.trim().isEmpty()
                ? "Fatto."
                : answer.trim();
            this.continueConversation = continueConversation;
        }
    }

    private final AureaBrainStore store;
    private final AureaLearningStore learningStore;

    private String haUrl;
    private String haToken;
    private String pendingSensitiveCommand = "";
    private String pendingSensitivePerson = "";
    private String pendingLearningAction = "";
    private String pendingLearningText = "";
    private String pendingLearningId = "";
    private String pendingLearningPerson = "";

    AureaBrainClient(Context context, String haUrl, String haToken) {
        store = new AureaBrainStore(context);
        learningStore = new AureaLearningStore(context);
        updateConnection(haUrl, haToken);
    }

    void updateConnection(String haUrl, String haToken) {
        this.haUrl = trimSlash(clean(haUrl));
        this.haToken = clean(haToken);
    }

    Result process(String spokenCommand, String recognizedPerson) {
        String command = clean(spokenCommand);
        String person = normalizedPerson(recognizedPerson);

        if (command.isEmpty()) {
            return new Result("Non ho ricevuto alcuna richiesta.", false);
        }

        Result pendingLearningResult = handlePendingLearning(command, person);
        if (pendingLearningResult != null) {
            return pendingLearningResult;
        }

        if (!pendingSensitiveCommand.isEmpty()) {
            if (!pendingSensitivePerson.equalsIgnoreCase(person)) {
                clearPendingSensitiveAction();
            } else if (isAffirmative(command)) {
                String confirmed = pendingSensitiveCommand;
                clearPendingSensitiveAction();
                return sendToHomeAssistant(confirmed, person, true);
            } else if (isNegative(command)) {
                String cancelled = pendingSensitiveCommand;
                clearPendingSensitiveAction();
                store.appendDecision(
                    person,
                    cancelled,
                    "Operazione annullata dall'utente.",
                    "cancelled",
                    true,
                    store.agentId()
                );
                return new Result("Operazione annullata.", false);
            } else {
                return new Result(
                    "Per sicurezza, rispondi sì per confermare oppure no per annullare.",
                    true
                );
            }
        }

        if (asksForMemories(command)) {
            return new Result(learningStore.speechSummary(person), true);
        }

        if (asksToClearAllMemories(command)) {
            int count = learningStore.count(person);
            if (count == 0) {
                return new Result(
                    "Non ho preferenze apprese da cancellare per il tuo profilo.",
                    true
                );
            }
            beginLearningConfirmation(
                LEARNING_CLEAR,
                person,
                "tutte le preferenze apprese",
                ""
            );
            return new Result(
                "Confermi che devo cancellare tutte le " + count
                    + " preferenze apprese del tuo profilo? Rispondi sì o no.",
                true
            );
        }

        String deleteTarget = extractDeleteTarget(command);
        if (!deleteTarget.isEmpty()) {
            AureaLearningStore.Memory memory = learningStore.findBestMatch(
                person,
                deleteTarget
            );
            if (memory == null) {
                return new Result(
                    "Non trovo una preferenza corrispondente a " + deleteTarget + ".",
                    true
                );
            }
            beginLearningConfirmation(
                LEARNING_DELETE,
                person,
                memory.text,
                memory.id
            );
            return new Result(
                "Confermi che devo dimenticare: " + memory.text
                    + "? Rispondi sì o no.",
                true
            );
        }

        String memoryCandidate = extractMemoryCandidate(command);
        if (!memoryCandidate.isEmpty()) {
            beginLearningConfirmation(
                LEARNING_SAVE,
                person,
                memoryCandidate,
                ""
            );
            return new Result(
                "Vuoi che memorizzi per il tuo profilo: " + memoryCandidate
                    + "? Rispondi sì o no.",
                true
            );
        }

        if (isSensitive(command)) {
            pendingSensitiveCommand = command;
            pendingSensitivePerson = person;
            store.appendDecision(
                person,
                command,
                "In attesa di conferma vocale.",
                "confirmation_required",
                true,
                store.agentId()
            );
            return new Result(
                "Questa azione può essere sensibile. Confermi che devo eseguire: "
                    + command + "? Rispondi sì o no.",
                true
            );
        }

        return sendToHomeAssistant(command, person, false);
    }

    private Result handlePendingLearning(String command, String person) {
        if (pendingLearningAction.isEmpty()) {
            return null;
        }
        if (!pendingLearningPerson.equalsIgnoreCase(person)) {
            clearPendingLearning();
            return null;
        }

        if (isNegative(command)) {
            String cancelled = pendingLearningText;
            clearPendingLearning();
            store.appendDecision(
                person,
                cancelled,
                "Apprendimento annullato dall'utente.",
                "learning_cancelled",
                false,
                store.agentId()
            );
            return new Result("Non ho memorizzato alcuna modifica.", true);
        }

        if (!isAffirmative(command)) {
            return new Result(
                "Per modificare la memoria, rispondi sì per confermare oppure no per annullare.",
                true
            );
        }

        String action = pendingLearningAction;
        String text = pendingLearningText;
        String id = pendingLearningId;
        clearPendingLearning();

        if (LEARNING_SAVE.equals(action)) {
            boolean saved = learningStore.add(person, text);
            if (saved) {
                store.clearConversation(person);
                store.appendDecision(
                    person,
                    text,
                    "Preferenza personale memorizzata.",
                    "learning_saved",
                    false,
                    store.agentId()
                );
                return new Result(
                    "Ho memorizzato questa preferenza per il tuo profilo.",
                    true
                );
            }
            return new Result(
                "Questa preferenza era già presente oppure non è valida.",
                true
            );
        }

        if (LEARNING_DELETE.equals(action)) {
            boolean deleted = learningStore.delete(person, id);
            if (deleted) {
                store.clearConversation(person);
                store.appendDecision(
                    person,
                    text,
                    "Preferenza personale eliminata.",
                    "learning_deleted",
                    false,
                    store.agentId()
                );
                return new Result("Ho dimenticato quella preferenza.", true);
            }
            return new Result(
                "Non sono riuscita a trovare quella preferenza.",
                true
            );
        }

        if (LEARNING_CLEAR.equals(action)) {
            int count = learningStore.count(person);
            learningStore.clear(person);
            store.clearConversation(person);
            store.appendDecision(
                person,
                "Cancella tutte le preferenze apprese",
                "Eliminate " + count + " preferenze personali.",
                "learning_cleared",
                false,
                store.agentId()
            );
            return new Result(
                count == 1
                    ? "Ho cancellato la preferenza appresa del tuo profilo."
                    : "Ho cancellato tutte le preferenze apprese del tuo profilo.",
                true
            );
        }

        return new Result("Operazione di memoria non riconosciuta.", true);
    }

    private void beginLearningConfirmation(
            String action,
            String person,
            String text,
            String id) {
        clearPendingSensitiveAction();
        pendingLearningAction = clean(action);
        pendingLearningPerson = normalizedPerson(person);
        pendingLearningText = clean(text);
        pendingLearningId = clean(id);
        store.appendDecision(
            person,
            text,
            "In attesa di conferma per modificare la memoria personale.",
            "learning_confirmation_required",
            false,
            store.agentId()
        );
    }

    private boolean asksForMemories(String command) {
        String value = normalizedText(command);
        return containsAny(
            value,
            "cosa ricordi di me",
            "che cosa ricordi di me",
            "quali preferenze ricordi",
            "mostrami i miei ricordi",
            "mostrami le mie preferenze",
            "dimmi cosa sai di me"
        );
    }

    private boolean asksToClearAllMemories(String command) {
        String value = normalizedText(command);
        return containsAny(
            value,
            "dimentica tutto di me",
            "cancella tutti i miei ricordi",
            "cancella tutte le mie preferenze",
            "dimentica tutte le mie preferenze",
            "azzera le mie preferenze",
            "azzera i miei ricordi"
        );
    }

    private String extractMemoryCandidate(String command) {
        String value = clean(command);
        Matcher explicit = EXPLICIT_MEMORY_PATTERN.matcher(value);
        if (explicit.matches()) {
            return cleanMemoryText(explicit.group(1));
        }

        Matcher correction = CORRECTION_MEMORY_PATTERN.matcher(value);
        if (correction.matches()) {
            String cleaned = value.replaceFirst(
                "(?iu)^(?:no[\\s,]+)?(?:in realtà[\\s,]+)?",
                ""
            );
            return cleanMemoryText(cleaned);
        }
        return "";
    }

    private String extractDeleteTarget(String command) {
        Matcher matcher = DELETE_MEMORY_PATTERN.matcher(clean(command));
        if (!matcher.matches()) {
            return "";
        }
        String target = cleanMemoryText(matcher.group(1));
        String normalized = normalizedText(target);
        if (normalized.equals("tutto")
                || normalized.equals("tutte")
                || normalized.contains("tutte le preferenze")
                || normalized.contains("tutti i ricordi")) {
            return "";
        }
        return target;
    }

    private String cleanMemoryText(String value) {
        String result = clean(value)
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ");
        while (result.endsWith(".") || result.endsWith(",")
                || result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        if (result.length() > 220) {
            result = result.substring(0, 220).trim();
        }
        return result.length() < 3 ? "" : result;
    }

    private Result sendToHomeAssistant(
            String command,
            String person,
            boolean sensitiveConfirmed) {
        boolean brainEnabled = store.isEnabled();
        String agentId = brainEnabled ? store.agentId() : "";
        String conversationId = brainEnabled
            ? store.activeConversationId(person)
            : "";

        try {
            if (haUrl.isEmpty() || haToken.isEmpty()) {
                throw new IllegalStateException("Configurazione Home Assistant incompleta");
            }

            URL endpoint = new URL(haUrl + "/api/conversation/process");
            HttpURLConnection connection =
                (HttpURLConnection) endpoint.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty(
                "Authorization",
                "Bearer " + haToken
            );
            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            );
            connection.setDoOutput(true);

            JSONObject request = new JSONObject();
            String requestText = command;
            if (brainEnabled && conversationId.isEmpty() && !agentId.isEmpty()) {
                requestText = store.initialContext(person)
                    + "\n\nRichiesta dell'utente: " + command;
            }

            request.put("text", requestText);
            request.put("language", "it");
            if (!agentId.isEmpty()) {
                request.put("agent_id", agentId);
            }
            if (!conversationId.isEmpty()) {
                request.put("conversation_id", conversationId);
            }

            try (OutputStream output = connection.getOutputStream()) {
                output.write(
                    request.toString().getBytes(StandardCharsets.UTF_8)
                );
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            String body = readAll(stream);

            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + body);
            }

            JSONObject root = new JSONObject(body);
            String returnedConversationId = clean(
                root.optString("conversation_id", "")
            );
            if (brainEnabled && !returnedConversationId.isEmpty()) {
                store.saveConversation(person, returnedConversationId);
            }

            boolean continueConversation =
                root.optBoolean("continue_conversation", false);
            JSONObject response = root.optJSONObject("response");
            String responseType = response == null
                ? "unknown"
                : clean(response.optString("response_type", "unknown"));
            String answer = extractSpeech(response);

            store.appendDecision(
                person,
                command,
                answer,
                responseType,
                sensitiveConfirmed,
                agentId
            );
            return new Result(answer, continueConversation);
        } catch (Exception error) {
            store.appendDecision(
                person,
                command,
                error.getClass().getSimpleName(),
                "error",
                sensitiveConfirmed,
                agentId
            );
            return new Result(
                "Non riesco a comunicare con il cervello di Home Assistant.",
                false
            );
        }
    }

    private String extractSpeech(JSONObject response) {
        if (response == null) {
            return "Fatto.";
        }

        JSONObject speech = response.optJSONObject("speech");
        if (speech == null) {
            return "Fatto.";
        }

        JSONObject plain = speech.optJSONObject("plain");
        if (plain != null) {
            String value = clean(plain.optString("speech", ""));
            if (!value.isEmpty()) {
                return value;
            }
        }

        JSONObject ssml = speech.optJSONObject("ssml");
        if (ssml != null) {
            String value = clean(ssml.optString("speech", ""));
            if (!value.isEmpty()) {
                return stripSsml(value);
            }
        }
        return "Fatto.";
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

    private boolean isSensitive(String command) {
        String value = normalizedText(command);
        boolean hasAction = containsAny(
            value,
            "apri",
            "sblocca",
            "disattiva",
            "attiva",
            "accendi",
            "spegni",
            "avvia",
            "ferma",
            "cancella",
            "elimina"
        );
        boolean hasSensitiveTarget = containsAny(
            value,
            "serratura",
            "portone",
            "cancello",
            "allarme",
            "antifurto",
            "corrente generale",
            "alimentazione generale",
            "interruttore generale",
            "forno",
            "piano cottura",
            "caldaia"
        );
        return hasAction && hasSensitiveTarget;
    }

    private boolean isAffirmative(String command) {
        String value = normalizedText(command);
        return value.equals("si")
            || value.equals("confermo")
            || value.equals("procedi")
            || value.equals("fallo")
            || value.equals("esegui")
            || value.startsWith("si ")
            || value.contains("confermo");
    }

    private boolean isNegative(String command) {
        String value = normalizedText(command);
        return value.equals("no")
            || value.equals("annulla")
            || value.equals("lascia perdere")
            || value.equals("non farlo")
            || value.startsWith("no ")
            || value.contains("annulla");
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalizedText(String value) {
        String normalized = Normalizer.normalize(
            clean(value).toLowerCase(Locale.ROOT),
            Normalizer.Form.NFD
        );
        return normalized.replaceAll("\\p{M}+", "")
            .replaceAll("[^a-z0-9 ]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String stripSsml(String value) {
        return value.replaceAll("<[^>]+>", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private void clearPendingSensitiveAction() {
        pendingSensitiveCommand = "";
        pendingSensitivePerson = "";
    }

    private void clearPendingLearning() {
        pendingLearningAction = "";
        pendingLearningText = "";
        pendingLearningId = "";
        pendingLearningPerson = "";
    }

    private String normalizedPerson(String value) {
        String person = clean(value);
        return person.isEmpty() ? "Ospite" : person;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
