package it.creativemaker3d.aurea;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.speech.SpeechRecognizer;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Esegue controlli diagnostici senza modificare Home Assistant.
 *
 * Le sole richieste di rete sono GET verso Home Assistant e verso il file
 * version.json del canale firmato aurea-latest.
 */
final class AureaDiagnosticsProbe {
    private static final String HA_PREFS = "aurea";
    private static final String KEY_HA_URL = "ha_url";
    private static final String KEY_HA_TOKEN = "ha_token";
    private static final String SIGNED_VERSION_URL =
        "https://raw.githubusercontent.com/giuseppetiso/AUREA-Android/"
            + "aurea-latest/version.json";

    enum Status {
        OK,
        WARNING,
        ERROR,
        INFO
    }

    static final class Check {
        final String title;
        final Status status;
        final String detail;

        Check(String title, Status status, String detail) {
            this.title = cleanStatic(title);
            this.status = status == null ? Status.INFO : status;
            this.detail = cleanStatic(detail);
        }
    }

    static final class Snapshot {
        final long time;
        final List<Check> checks;
        final String installedVersion;
        final String signedVersion;
        final int errors;
        final int warnings;

        Snapshot(
                long time,
                List<Check> checks,
                String installedVersion,
                String signedVersion,
                int errors,
                int warnings) {
            this.time = time;
            this.checks = checks;
            this.installedVersion = cleanStatic(installedVersion);
            this.signedVersion = cleanStatic(signedVersion);
            this.errors = errors;
            this.warnings = warnings;
        }

        String headline() {
            if (errors > 0) {
                return "ATTENZIONE · " + errors + " problemi bloccanti";
            }
            if (warnings > 0) {
                return "FUNZIONANTE · " + warnings + " avvisi da controllare";
            }
            return "TUTTI I CONTROLLI SUPERATI";
        }

        String report(AureaDiagnosticsLog log) {
            StringBuilder report = new StringBuilder();
            report.append("AUREA DIAGNOSTICS 1.0\n");
            report.append("Generato: ").append(
                DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.MEDIUM,
                    Locale.ITALIAN
                ).format(new Date(time))
            ).append("\n");
            report.append("Esito: ").append(headline()).append("\n");
            report.append("Versione installata: ").append(installedVersion).append("\n");
            report.append("Canale firmato: ")
                .append(signedVersion.isEmpty() ? "non disponibile" : signedVersion)
                .append("\n\nCONTROLLI\n");

            for (Check check : checks) {
                report.append("[").append(statusLabel(check.status)).append("] ")
                    .append(check.title).append("\n")
                    .append(check.detail).append("\n\n");
            }

            report.append("ULTIMI EVENTI TECNICI\n");
            report.append(log == null
                ? "Registro non disponibile."
                : log.reportSection(12));
            report.append("\n\nPRIVACY\n");
            report.append(
                "Il rapporto non contiene token Home Assistant, chiavi API, immagini, "
                    + "firme vocali o testo delle conversazioni."
            );
            return report.toString();
        }

        private static String statusLabel(Status status) {
            if (status == Status.OK) return "OK";
            if (status == Status.WARNING) return "AVVISO";
            if (status == Status.ERROR) return "ERRORE";
            return "INFO";
        }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    private final Context context;
    private final AureaDiagnosticsLog log;

    AureaDiagnosticsProbe(Context context) {
        this.context = context.getApplicationContext();
        this.log = new AureaDiagnosticsLog(context);
    }

    Snapshot run() {
        ArrayList<Check> checks = new ArrayList<>();
        String installedVersion = BuildConfig.VERSION_NAME
            + " (" + BuildConfig.VERSION_CODE + ")";
        String signedVersion = "";

        addDeviceCheck(checks);
        addPermissionChecks(checks);
        addWakeWordCheck(checks);
        addIdentityChecks(checks);
        addFeatureChecks(checks);

        SharedPreferences prefs = context.getSharedPreferences(
            HA_PREFS,
            Context.MODE_PRIVATE
        );
        String haUrl = trimSlash(prefs.getString(
            KEY_HA_URL,
            "http://192.168.178.72:8123"
        ));
        String token = clean(prefs.getString(KEY_HA_TOKEN, ""));
        addHomeAssistantChecks(checks, haUrl, token);
        addAgentCheck(checks, haUrl, token);

        try {
            HttpResult version = get(SIGNED_VERSION_URL, "");
            if (version.code == 200) {
                JSONObject json = new JSONObject(version.body);
                int availableCode = json.optInt("versionCode", 0);
                String availableName = clean(json.optString("versionName", ""));
                signedVersion = availableName + " (" + availableCode + ")";
                if (availableCode > BuildConfig.VERSION_CODE) {
                    checks.add(new Check(
                        "Canale aggiornamenti firmato",
                        Status.WARNING,
                        "È disponibile AUREA " + signedVersion
                            + "; installata " + installedVersion + "."
                    ));
                } else if (availableCode == BuildConfig.VERSION_CODE) {
                    checks.add(new Check(
                        "Canale aggiornamenti firmato",
                        Status.OK,
                        "Versione installata e APK firmato coincidono: "
                            + installedVersion + "."
                    ));
                } else {
                    checks.add(new Check(
                        "Canale aggiornamenti firmato",
                        Status.WARNING,
                        "Il canale firmato riporta " + signedVersion
                            + ", precedente alla versione installata."
                    ));
                }
            } else {
                checks.add(new Check(
                    "Canale aggiornamenti firmato",
                    Status.WARNING,
                    "Risposta HTTP " + version.code + "."
                ));
            }
        } catch (Exception error) {
            checks.add(new Check(
                "Canale aggiornamenti firmato",
                Status.WARNING,
                "Controllo online non riuscito: " + safeMessage(error) + "."
            ));
            log.warning("Aggiornamenti", "Canale firmato non raggiungibile");
        }

        int errors = 0;
        int warnings = 0;
        for (Check check : checks) {
            if (check.status == Status.ERROR) errors++;
            if (check.status == Status.WARNING) warnings++;
        }
        return new Snapshot(
            System.currentTimeMillis(),
            checks,
            installedVersion,
            signedVersion,
            errors,
            warnings
        );
    }

    private void addDeviceCheck(List<Check> checks) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        String resolution = metrics.widthPixels + "×" + metrics.heightPixels;
        checks.add(new Check(
            "Tablet e sistema",
            Status.OK,
            "Android " + Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT
                + " · schermo rilevato " + resolution
                + " · densità " + metrics.densityDpi + " dpi."
        ));

        File files = context.getFilesDir();
        long freeMb = files == null ? 0L : files.getUsableSpace() / (1024L * 1024L);
        checks.add(new Check(
            "Spazio locale AUREA",
            freeMb < 250L ? Status.WARNING : Status.OK,
            "Spazio disponibile nell'archivio applicazione: circa " + freeMb + " MB."
        ));
    }

    private void addPermissionChecks(List<Check> checks) {
        boolean microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
        boolean recognizer = SpeechRecognizer.isRecognitionAvailable(context);
        checks.add(new Check(
            "Microfono e riconoscimento vocale",
            microphone && recognizer ? Status.OK : Status.ERROR,
            "Permesso microfono: " + yesNo(microphone)
                + " · motore riconoscimento Android: " + available(recognizer) + "."
        ));

        boolean cameraPermission = context.checkSelfPermission(Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
        boolean frontCamera = context.getPackageManager().hasSystemFeature(
            PackageManager.FEATURE_CAMERA_FRONT
        );
        checks.add(new Check(
            "Fotocamera frontale",
            cameraPermission && frontCamera ? Status.OK : Status.WARNING,
            "Permesso fotocamera: " + yesNo(cameraPermission)
                + " · fotocamera frontale: " + available(frontCamera) + "."
        ));

        checks.add(new Check(
            "Sintesi vocale",
            Status.INFO,
            "Disponibile il test manuale “Prova voce AUREA”; il test non registra audio."
        ));
    }

    private void addWakeWordCheck(List<Check> checks) {
        File model = new File(context.getFilesDir(), "vosk-model-small-it-0.22");
        boolean complete = model.isDirectory()
            && new File(model, "am/final.mdl").isFile()
            && new File(model, "conf/model.conf").isFile()
            && new File(model, "graph/HCLr.fst").isFile();
        checks.add(new Check(
            "Wake word locale “Aurea”",
            complete ? Status.OK : Status.WARNING,
            complete
                ? "Modello italiano Vosk presente e completo."
                : "Modello locale assente o incompleto; verrà preparato dalla dashboard."
        ));
    }

    private void addIdentityChecks(List<Check> checks) {
        ArrayList<String> names = faceProfileNames();
        VoiceProfileStore voices = new VoiceProfileStore(context);
        int voicesCount = 0;
        for (String name : names) {
            if (voices.hasProfile(name)) voicesCount++;
        }

        IdentitySessionStore identity = new IdentitySessionStore(context);
        String trusted = clean(identity.trustedPerson());
        Status status = names.isEmpty() || voicesCount == 0
            ? Status.WARNING
            : Status.OK;
        checks.add(new Check(
            "Profili personali locali",
            status,
            "Volti registrati: " + names.size()
                + " · voci registrate: " + voicesCount
                + " · profilo attivo: "
                + (trusted.isEmpty() ? "nessuno" : trusted) + "."
        ));
    }

    private void addFeatureChecks(List<Check> checks) {
        String person = RegisteredUserAccess.currentPerson(context);
        AureaBrainStore brain = new AureaBrainStore(context);
        checks.add(new Check(
            "AUREA Brain",
            brain.isEnabled() ? Status.OK : Status.WARNING,
            "Brain: " + (brain.isEnabled() ? "attivo" : "disattivato")
                + " · decisioni registrate: " + brain.decisionCount()
                + " · conversazione corrente: "
                + (brain.hasActiveConversation(person) ? "attiva" : "non attiva") + "."
        ));

        AureaInsightsStore insights = new AureaInsightsStore(context);
        checks.add(new Check(
            "AUREA Insights",
            insights.isEnabled() && !insights.selectedEntities().isEmpty()
                ? Status.OK
                : Status.INFO,
            "Osservazione: " + (insights.isEnabled() ? "attiva" : "disattivata")
                + " · entità selezionate: " + insights.selectedEntities().size()
                + " · cambi osservati: " + insights.observationCount()
                + " · proposte: " + insights.suggestionCount() + "."
        ));

        int memories = new AureaLearningStore(context).count(person);
        int drafts = AureaRoutineDraftAccess.countForPerson(
            new AureaRoutineDraftStore(context),
            person
        );
        checks.add(new Check(
            "Memoria e Routine Studio",
            Status.OK,
            "Profilo: " + (person.isEmpty() ? "non riconosciuto" : person)
                + " · preferenze apprese: " + memories
                + " · bozze personali: " + drafts + "."
        ));

        AureaDiagnosticsLog diagnosticsLog = new AureaDiagnosticsLog(context);
        checks.add(new Check(
            "Registro tecnico",
            diagnosticsLog.errorCount() > 0 ? Status.WARNING : Status.OK,
            "Eventi recenti: " + diagnosticsLog.count()
                + " · errori: " + diagnosticsLog.errorCount() + "."
        ));
    }

    private void addHomeAssistantChecks(
            List<Check> checks,
            String haUrl,
            String token) {
        if (haUrl.isEmpty()) {
            checks.add(new Check(
                "Connessione Home Assistant",
                Status.ERROR,
                "Indirizzo Home Assistant non configurato."
            ));
            return;
        }
        if (token.isEmpty()) {
            checks.add(new Check(
                "Connessione Home Assistant",
                Status.ERROR,
                "Token dedicato AUREA non configurato."
            ));
            return;
        }

        try {
            HttpResult result = get(haUrl + "/api/", token);
            if (result.code >= 200 && result.code < 300) {
                checks.add(new Check(
                    "Connessione Home Assistant",
                    Status.OK,
                    "API raggiungibile su " + safeHaAddress(haUrl)
                        + " · autenticazione accettata."
                ));
            } else if (result.code == 401 || result.code == 403) {
                checks.add(new Check(
                    "Connessione Home Assistant",
                    Status.ERROR,
                    "API raggiungibile, ma il token è rifiutato (HTTP "
                        + result.code + ")."
                ));
            } else {
                checks.add(new Check(
                    "Connessione Home Assistant",
                    Status.ERROR,
                    "Risposta API HTTP " + result.code + "."
                ));
            }
        } catch (Exception error) {
            checks.add(new Check(
                "Connessione Home Assistant",
                Status.ERROR,
                "Connessione non riuscita: " + safeMessage(error) + "."
            ));
            log.error("Home Assistant", "Connessione diagnostica non riuscita", error);
        }
    }

    private void addAgentCheck(
            List<Check> checks,
            String haUrl,
            String token) {
        AureaBrainStore brain = new AureaBrainStore(context);
        String agent = clean(brain.agentId());
        if (!brain.isEnabled()) {
            checks.add(new Check(
                "Agente conversazionale",
                Status.WARNING,
                "AUREA Brain è disattivato."
            ));
            return;
        }
        if (agent.isEmpty() || agent.equalsIgnoreCase("ha")) {
            checks.add(new Check(
                "Agente conversazionale",
                Status.WARNING,
                "È selezionato l'agente predefinito Home Assistant, non un agente Gemini dedicato."
            ));
            return;
        }
        if (haUrl.isEmpty() || token.isEmpty()) {
            checks.add(new Check(
                "Agente conversazionale",
                Status.ERROR,
                "Impossibile verificare " + agent + " senza connessione Home Assistant."
            ));
            return;
        }

        try {
            String encoded = URLEncoder.encode(
                agent,
                StandardCharsets.UTF_8.name()
            ).replace("+", "%20");
            HttpResult result = get(haUrl + "/api/states/" + encoded, token);
            if (result.code == 200) {
                JSONObject state = new JSONObject(result.body);
                String currentState = clean(state.optString("state", ""));
                checks.add(new Check(
                    "Agente Gemini / conversazione",
                    currentState.equals("unavailable")
                        ? Status.WARNING
                        : Status.OK,
                    "Agente selezionato: " + agent
                        + " · stato Home Assistant: "
                        + (currentState.isEmpty() ? "presente" : currentState) + "."
                ));
            } else if (result.code == 404) {
                checks.add(new Check(
                    "Agente Gemini / conversazione",
                    Status.ERROR,
                    "L'entità selezionata non esiste più: " + agent + "."
                ));
            } else {
                checks.add(new Check(
                    "Agente Gemini / conversazione",
                    Status.WARNING,
                    "Verifica agente non riuscita: HTTP " + result.code + "."
                ));
            }
        } catch (Exception error) {
            checks.add(new Check(
                "Agente Gemini / conversazione",
                Status.WARNING,
                "Verifica agente non riuscita: " + safeMessage(error) + "."
            ));
            log.error("Gemini", "Verifica agente non riuscita", error);
        }
    }

    private ArrayList<String> faceProfileNames() {
        ArrayList<String> names = new ArrayList<>();
        try {
            String raw = context.getSharedPreferences(
                "aurea_face_profiles",
                Context.MODE_PRIVATE
            ).getString("profiles", "{}");
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            JSONArray array = root.names();
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    String name = clean(array.optString(index, ""));
                    if (!name.isEmpty()) names.add(name);
                }
            }
        } catch (Exception error) {
            log.error("Profili", "Lettura elenco volti non riuscita", error);
        }
        return names;
    }

    private HttpResult get(String address, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        if (!clean(token).isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();
        return new HttpResult(code, body);
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private String safeHaAddress(String value) {
        String clean = trimSlash(value);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        return clean;
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "" : clean(error.getMessage());
        if (message.isEmpty()) {
            return error == null ? "errore sconosciuto" : error.getClass().getSimpleName();
        }
        return message.length() > 140 ? message.substring(0, 140) : message;
    }

    private String yesNo(boolean value) {
        return value ? "concesso" : "mancante";
    }

    private String available(boolean value) {
        return value ? "disponibile" : "non disponibile";
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
