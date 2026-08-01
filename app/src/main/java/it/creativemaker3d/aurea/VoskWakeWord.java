package it.creativemaker3d.aurea;

import android.content.Context;
import android.os.Handler;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class VoskWakeWord implements RecognitionListener {
    private static final String MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip";
    private static final String MODEL_ARCHIVE = "vosk-model-small-it-0.22.zip";
    private static final String MODEL_ROOT = "vosk-model-small-it-0.22/";
    private static final String MODEL_DIRECTORY = "vosk-model-small-it-0.22";
    private static final float SAMPLE_RATE = 16000.0f;

    /*
     * Le parole foneticamente vicine impediscono al decoder ristretto di
     * trasformare automaticamente qualunque saluto in "aurea". L'attivazione
     * viene comunque accettata soltanto quando il risultato definitivo è
     * esattamente "aurea".
     */
    private static final String GRAMMAR =
        "[\"aurea\", \"ciao\", \"aura\", \"ora\", \"allora\", "
            + "\"ehi\", \"buongiorno\", \"buonasera\", \"[unk]\"]";

    interface Listener {
        void onPreparing();
        void onReady();
        void onDetected();
        void onError(String message);
    }

    private final Context context;
    private final Handler main;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Model model;
    private SpeechService speechService;
    private boolean initializing;
    private boolean ready;
    private boolean starting;
    private boolean listening;
    private boolean detectionPosted;
    private boolean deleted;
    private int startGeneration;

    VoskWakeWord(Context context, Handler main, Listener listener) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.listener = listener;
    }

    synchronized boolean isReady() {
        return ready && model != null && !deleted;
    }

    void initialize() {
        synchronized (this) {
            if (deleted || ready || initializing) {
                return;
            }
            initializing = true;
        }

        postPreparing();
        worker.execute(() -> {
            try {
                File modelDirectory = ensureModelDirectory();
                LibVosk.setLogLevel(LogLevel.INFO);
                Model loadedModel = new Model(modelDirectory.getAbsolutePath());

                synchronized (VoskWakeWord.this) {
                    if (deleted) {
                        initializing = false;
                        return;
                    }
                    model = loadedModel;
                    ready = true;
                    initializing = false;
                }

                main.post(() -> {
                    if (!deleted) {
                        listener.onReady();
                    }
                });
            } catch (Exception error) {
                synchronized (VoskWakeWord.this) {
                    initializing = false;
                    ready = false;
                }
                postError(
                    "Impossibile preparare l’ascolto locale di “Aurea”: "
                        + safeMessage(error));
            }
        });
    }

    void start() {
        final Model currentModel;
        final int generation;

        synchronized (this) {
            if (deleted || listening || starting) {
                return;
            }
            if (!ready || model == null) {
                initialize();
                return;
            }

            starting = true;
            detectionPosted = false;
            generation = ++startGeneration;
            currentModel = model;
        }

        worker.execute(() -> {
            SpeechService newService = null;
            try {
                Recognizer recognizer =
                    new Recognizer(currentModel, SAMPLE_RATE, GRAMMAR);
                newService = new SpeechService(recognizer, SAMPLE_RATE);

                final SpeechService serviceToStart = newService;
                main.post(() -> attachAndStart(serviceToStart, generation));
            } catch (Exception error) {
                if (newService != null) {
                    try {
                        newService.shutdown();
                    } catch (Exception ignored) {
                    }
                }
                synchronized (VoskWakeWord.this) {
                    if (generation == startGeneration) {
                        starting = false;
                        listening = false;
                    }
                }
                postError(
                    "Impossibile avviare il microfono locale: "
                        + safeMessage(error));
            }
        });
    }

    private void attachAndStart(SpeechService service, int generation) {
        synchronized (this) {
            if (deleted || generation != startGeneration) {
                try {
                    service.shutdown();
                } catch (Exception ignored) {
                }
                return;
            }

            speechService = service;
            starting = false;
            listening = true;
        }

        try {
            service.startListening(this);
        } catch (RuntimeException error) {
            synchronized (this) {
                if (speechService == service) {
                    speechService = null;
                    listening = false;
                }
            }
            try {
                service.shutdown();
            } catch (Exception ignored) {
            }
            postError(
                "Microfono locale momentaneamente occupato: "
                    + safeMessage(error));
        }
    }

    void stop() {
        SpeechService service;

        synchronized (this) {
            startGeneration++;
            starting = false;
            listening = false;
            detectionPosted = false;
            service = speechService;
            speechService = null;
        }

        if (service != null) {
            try {
                service.stop();
            } catch (Exception ignored) {
            }
            try {
                service.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    void delete() {
        synchronized (this) {
            if (deleted) {
                return;
            }
            deleted = true;
        }

        stop();
        worker.shutdownNow();

        synchronized (this) {
            model = null;
            ready = false;
            initializing = false;
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        // I risultati parziali possono cambiare mentre la parola viene pronunciata.
        // Non devono mai attivare AUREA.
    }

    @Override
    public void onResult(String hypothesis) {
        inspectFinalHypothesis(hypothesis);
    }

    @Override
    public void onFinalResult(String hypothesis) {
        inspectFinalHypothesis(hypothesis);
    }

    @Override
    public void onError(Exception error) {
        synchronized (this) {
            if (!listening || deleted) {
                return;
            }
            listening = false;
            speechService = null;
        }
        postError(
            "Errore nell’ascolto locale: " + safeMessage(error));
    }

    @Override
    public void onTimeout() {
        // L'ascolto wake-word non usa timeout.
    }

    private void inspectFinalHypothesis(String hypothesis) {
        if (hypothesis == null || hypothesis.trim().isEmpty()) {
            return;
        }

        String text;
        try {
            JSONObject result = new JSONObject(hypothesis);
            text = result.optString("text");
        } catch (Exception ignored) {
            text = hypothesis;
        }

        String normalized = text.toLowerCase(Locale.ITALIAN)
            .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();

        if (!"aurea".equals(normalized)) {
            return;
        }

        synchronized (this) {
            if (deleted || !listening || detectionPosted) {
                return;
            }
            detectionPosted = true;
        }

        main.post(() -> {
            if (deleted) {
                return;
            }
            stop();
            listener.onDetected();
        });
    }

    private File ensureModelDirectory() throws IOException {
        File modelDirectory = new File(context.getFilesDir(), MODEL_DIRECTORY);
        if (isCompleteModel(modelDirectory)) {
            return modelDirectory;
        }

        deleteRecursively(modelDirectory);
        if (!modelDirectory.mkdirs() && !modelDirectory.isDirectory()) {
            throw new IOException("Impossibile creare la cartella del modello");
        }

        File archive = new File(context.getCacheDir(), MODEL_ARCHIVE);
        downloadModel(archive);
        try {
            unzipModel(archive, modelDirectory);
        } finally {
            if (archive.exists()) {
                archive.delete();
            }
        }

        if (!isCompleteModel(modelDirectory)) {
            deleteRecursively(modelDirectory);
            throw new IOException("Download del modello incompleto");
        }
        return modelDirectory;
    }

    private boolean isCompleteModel(File directory) {
        return directory.isDirectory()
            && new File(directory, "am/final.mdl").isFile()
            && new File(directory, "conf/model.conf").isFile()
            && new File(directory, "graph/HCLr.fst").isFile();
    }

    private void downloadModel(File destination) throws IOException {
        if (destination.exists()) {
            destination.delete();
        }

        HttpURLConnection connection =
            (HttpURLConnection) new URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "AUREA-Android/0.2.22");

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("download HTTP " + code);
            }

            try (BufferedInputStream input =
                     new BufferedInputStream(connection.getInputStream(), 65536);
                 BufferedOutputStream output =
                     new BufferedOutputStream(
                         new FileOutputStream(destination),
                         65536)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("download interrotto");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
        } finally {
            connection.disconnect();
        }

        if (!destination.isFile() || destination.length() < 5_000_000L) {
            throw new IOException("archivio modello non valido");
        }
    }

    private void unzipModel(File archive, File destination) throws IOException {
        String destinationPath = destination.getCanonicalPath() + File.separator;

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(
                    new FileInputStream(archive),
                    65536))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];

            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("estrazione interrotta");
                }

                String name = entry.getName().replace('\\', '/');
                if (name.startsWith(MODEL_ROOT)) {
                    name = name.substring(MODEL_ROOT.length());
                }
                if (name.isEmpty()) {
                    zip.closeEntry();
                    continue;
                }

                File output = new File(destination, name);
                String outputPath = output.getCanonicalPath();
                if (!outputPath.startsWith(destinationPath)) {
                    throw new IOException("percorso non valido nel modello");
                }

                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IOException(
                            "Impossibile creare " + output.getName());
                    }
                } else {
                    File parent = output.getParentFile();
                    if (parent != null
                            && !parent.mkdirs()
                            && !parent.isDirectory()) {
                        throw new IOException(
                            "Impossibile creare " + parent.getName());
                    }

                    try (BufferedOutputStream fileOutput =
                             new BufferedOutputStream(
                                 new FileOutputStream(output),
                                 65536)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            fileOutput.write(buffer, 0, read);
                        }
                        fileOutput.flush();
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private void postPreparing() {
        main.post(() -> {
            if (!deleted) {
                listener.onPreparing();
            }
        });
    }

    private void postError(String message) {
        main.post(() -> {
            if (!deleted) {
                listener.onError(message);
            }
        });
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "errore sconosciuto";
        }
        return message.trim();
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
