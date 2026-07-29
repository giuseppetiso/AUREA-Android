package it.creativemaker3d.aurea;

import android.content.Context;
import android.os.Handler;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupine.PorcupineManager;

final class PorcupineWakeWord {
    interface Listener {
        void onReady();
        void onDetected();
        void onError(String message);
    }

    private static final String MODEL_FILE = "porcupine_params_it_v4_0.pv";
    private static final String KEYWORD_FILE = "aurea_it_android.ppn";

    private static final String MODEL_URL =
        "https://raw.githubusercontent.com/Picovoice/porcupine/"
            + "v4.0/lib/common/porcupine_params_it.pv";

    private static final String TRAIN_URL =
        "https://rest.picovoice.ai/it/api/ppn";

    private final Context context;
    private final Handler main;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private PorcupineManager manager;
    private boolean ready;
    private boolean active;
    private boolean preparing;
    private boolean requestedStart;
    private boolean destroyed;
    private String initializedAccessKey;

    PorcupineWakeWord(
            Context context,
            Handler main,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.listener = listener;
    }

    synchronized boolean isReady() {
        return ready;
    }

    void initialize(String accessKey) {
        String normalizedKey = accessKey == null ? "" : accessKey.trim();
        if (normalizedKey.isEmpty()) {
            reportError("AccessKey Picovoice mancante");
            return;
        }

        synchronized (this) {
            if (destroyed) {
                return;
            }
            if (ready && normalizedKey.equals(initializedAccessKey)) {
                if (requestedStart) {
                    main.post(this::start);
                }
                return;
            }
            if (preparing) {
                return;
            }
            preparing = true;
        }

        worker.execute(() -> {
            PorcupineManager builtManager = null;
            try {
                File model = ensureItalianModel();
                File keyword = ensureKeywordModel(normalizedKey);

                builtManager = new PorcupineManager.Builder()
                    .setAccessKey(normalizedKey)
                    .setModelPath(model.getAbsolutePath())
                    .setKeywordPath(keyword.getAbsolutePath())
                    .setSensitivity(0.62f)
                    .setErrorCallback(error ->
                        main.post(() -> reportError(
                            "Errore ascolto parola Aurea: "
                                + safeMessage(error))))
                    .build(context, keywordIndex ->
                        main.post(listener::onDetected));

                PorcupineManager finalManager = builtManager;
                main.post(() -> {
                    synchronized (PorcupineWakeWord.this) {
                        if (destroyed) {
                            try {
                                finalManager.delete();
                            } catch (RuntimeException ignored) {
                            }
                            preparing = false;
                            return;
                        }

                        releaseManagerLocked();
                        manager = finalManager;
                        initializedAccessKey = normalizedKey;
                        ready = true;
                        preparing = false;
                    }

                    listener.onReady();
                    if (requestedStart) {
                        start();
                    }
                });
            } catch (Exception error) {
                if (builtManager != null) {
                    try {
                        builtManager.delete();
                    } catch (RuntimeException ignored) {
                    }
                }

                synchronized (this) {
                    preparing = false;
                    ready = false;
                }
                reportError(classifySetupError(error));
            }
        });
    }

    void start() {
        PorcupineManager current;

        synchronized (this) {
            requestedStart = true;
            if (destroyed || !ready || active || manager == null) {
                return;
            }
            current = manager;
        }

        try {
            current.start();
            synchronized (this) {
                if (!destroyed && current == manager) {
                    active = true;
                }
            }
        } catch (PorcupineException error) {
            synchronized (this) {
                active = false;
            }
            reportError(
                "Non riesco ad aprire il microfono per la parola Aurea: "
                    + safeMessage(error));
        }
    }

    void stop() {
        PorcupineManager current;
        boolean shouldStop;

        synchronized (this) {
            requestedStart = false;
            current = manager;
            shouldStop = active && current != null;
            active = false;
        }

        if (!shouldStop) {
            return;
        }

        try {
            current.stop();
        } catch (PorcupineException ignored) {
        }
    }

    void delete() {
        synchronized (this) {
            destroyed = true;
            requestedStart = false;
        }

        stop();

        synchronized (this) {
            releaseManagerLocked();
        }

        worker.shutdownNow();
    }

    static void deleteGeneratedKeyword(Context context) {
        File keyword = new File(context.getFilesDir(), KEYWORD_FILE);
        File temporary = new File(context.getFilesDir(), KEYWORD_FILE + ".tmp");

        if (keyword.exists()) {
            keyword.delete();
        }
        if (temporary.exists()) {
            temporary.delete();
        }
    }

    private File ensureItalianModel() throws Exception {
        File destination = new File(context.getFilesDir(), MODEL_FILE);

        if (destination.isFile() && destination.length() > 100000L) {
            return destination;
        }

        File temporary = new File(context.getFilesDir(), MODEL_FILE + ".tmp");
        downloadToFile(MODEL_URL, temporary, null, null);
        replaceFile(temporary, destination);

        if (destination.length() < 100000L) {
            throw new IllegalStateException("modello italiano incompleto");
        }
        return destination;
    }

    private File ensureKeywordModel(String accessKey) throws Exception {
        File destination = new File(context.getFilesDir(), KEYWORD_FILE);

        if (destination.isFile() && destination.length() > 100L) {
            return destination;
        }

        JSONObject payload = new JSONObject();
        payload.put("platform", "android");
        payload.put("phrase", "Aurea");

        File temporary = new File(context.getFilesDir(), KEYWORD_FILE + ".tmp");

        downloadToFile(
            TRAIN_URL,
            temporary,
            accessKey,
            payload.toString());

        replaceFile(temporary, destination);

        if (destination.length() < 100L) {
            throw new IllegalStateException("modello della parola Aurea incompleto");
        }
        return destination;
    }

    private void downloadToFile(
            String url,
            File output,
            String accessKey,
            String jsonBody) throws Exception {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(url).openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);

        if (jsonBody == null) {
            connection.setRequestMethod("GET");
        } else {
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("x-api-key", accessKey);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);

            try (OutputStream request = connection.getOutputStream()) {
                request.write(payload);
            }
        }

        int code = connection.getResponseCode();
        InputStream response = code >= 200 && code < 300
            ? connection.getInputStream()
            : connection.getErrorStream();

        if (code < 200 || code >= 300) {
            String details = readSmallBody(response);
            connection.disconnect();
            throw new IllegalStateException(
                "HTTP " + code + (details.isEmpty() ? "" : ": " + details));
        }

        if (response == null) {
            connection.disconnect();
            throw new IllegalStateException("risposta vuota");
        }

        try (InputStream input = new BufferedInputStream(response, 8192);
             OutputStream file = new BufferedOutputStream(
                 new FileOutputStream(output), 8192)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                file.write(buffer, 0, count);
            }
            file.flush();
        } finally {
            connection.disconnect();
        }
    }

    private String readSmallBody(InputStream input) {
        if (input == null) {
            return "";
        }

        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int total = 0;
            int count;

            while ((count = stream.read(buffer)) != -1 && total < 4096) {
                int accepted = Math.min(count, 4096 - total);
                output.write(buffer, 0, accepted);
                total += accepted;
            }

            return output.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void replaceFile(File temporary, File destination) throws Exception {
        if (!temporary.isFile() || temporary.length() == 0L) {
            throw new IllegalStateException("file temporaneo vuoto");
        }

        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException(
                "impossibile sostituire " + destination.getName());
        }

        if (!temporary.renameTo(destination)) {
            try (InputStream input = new BufferedInputStream(
                     new FileInputStream(temporary));
                 OutputStream output = new BufferedOutputStream(
                     new FileOutputStream(destination))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }

            if (!temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private synchronized void releaseManagerLocked() {
        if (manager == null) {
            ready = false;
            active = false;
            return;
        }

        try {
            if (active) {
                manager.stop();
            }
        } catch (Exception ignored) {
        }

        try {
            manager.delete();
        } catch (RuntimeException ignored) {
        }

        manager = null;
        ready = false;
        active = false;
    }

    private String classifySetupError(Exception error) {
        String message = safeMessage(error);
        String lower = message.toLowerCase();

        if (lower.contains("401")
                || lower.contains("403")
                || lower.contains("accesskey")
                || lower.contains("api key")) {
            return "AccessKey Picovoice non valida. "
                + "Apri la configurazione e incollala nuovamente.";
        }

        if (lower.contains("http")
                || lower.contains("network")
                || lower.contains("connect")
                || lower.contains("timeout")
                || lower.contains("host")) {
            return "Non riesco a preparare la parola Aurea. "
                + "Controlla la connessione Internet e riprova.";
        }

        return "Errore configurazione parola Aurea: " + message;
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "errore sconosciuto";
        }

        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.trim();
    }

    private void reportError(String message) {
        main.post(() -> {
            if (!destroyed) {
                listener.onError(message);
            }
        });
    }
}
