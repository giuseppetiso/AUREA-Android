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

/**
 * Motore conversazionale di AUREA Brain.
 *
 * Usa l'API Conversation di Home Assistant, mantiene una conversazione
 * separata per persona e applica una conferma deterministica alle azioni
 * sensibili prima di inoltrarle all'agente.
 */
final class AureaBrainClient {
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

    private String haUrl;
    private String haToken;
    private String pendingSensitiveCommand = "";
    private String pendingSensitivePerson = "";

    AureaBrainClient(Context context, String haUrl, String haToken) {
        store = new AureaBrainStore(context);
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
