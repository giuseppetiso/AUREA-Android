package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Conserva soltanto qualità e punteggi, mai firme, immagini o audio. */
final class AureaRecognitionDiagnostics {
    private static final String PREFS = "aurea_recognition_diagnostics";
    private static final long FACE_WRITE_INTERVAL_MS = 20_000L;

    private AureaRecognitionDiagnostics() {
    }

    static void recordFace(
            Context context,
            float score,
            float required,
            boolean accepted,
            AureaFaceRecognitionEngine.Sample sample) {
        if (context == null || sample == null) return;
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (!accepted && now - prefs.getLong("face_time", 0L) < FACE_WRITE_INTERVAL_MS) {
            return;
        }
        prefs.edit()
            .putLong("face_time", now)
            .putFloat("face_score", score)
            .putFloat("face_required", required)
            .putBoolean("face_accepted", accepted)
            .putFloat("face_brightness", sample.brightness)
            .putFloat("face_contrast", sample.contrast)
            .putFloat("face_sharpness", sample.sharpness)
            .apply();
    }

    static void recordVoice(
            Context context,
            float score,
            float required,
            boolean accepted,
            VoiceSignature.Analysis analysis) {
        if (context == null || analysis == null) return;
        prefs(context).edit()
            .putLong("voice_time", System.currentTimeMillis())
            .putFloat("voice_score", score)
            .putFloat("voice_required", required)
            .putBoolean("voice_accepted", accepted)
            .putFloat("voice_snr", analysis.snrDb)
            .putFloat("voice_seconds", analysis.speechSeconds)
            .apply();
    }

    static String faceSummary(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getLong("face_time", 0L) <= 0L) return "nessun confronto v2";
        return String.format(
            Locale.ITALIAN,
            "ultimo punteggio %.3f/%.3f · luce %.0f · contrasto %.0f · nitidezza %.1f · %s",
            prefs.getFloat("face_score", 0f),
            prefs.getFloat("face_required", 0f),
            prefs.getFloat("face_brightness", 0f),
            prefs.getFloat("face_contrast", 0f),
            prefs.getFloat("face_sharpness", 0f),
            prefs.getBoolean("face_accepted", false) ? "riconosciuto" : "non certo"
        );
    }

    static String voiceSummary(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getLong("voice_time", 0L) <= 0L) return "nessun confronto v2";
        return String.format(
            Locale.ITALIAN,
            "ultimo punteggio %.3f/%.3f · segnale %.1f dB · durata %.1f s · %s",
            prefs.getFloat("voice_score", 0f),
            prefs.getFloat("voice_required", 0f),
            prefs.getFloat("voice_snr", 0f),
            prefs.getFloat("voice_seconds", 0f),
            prefs.getBoolean("voice_accepted", false) ? "riconosciuta" : "non certa"
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        );
    }
}
