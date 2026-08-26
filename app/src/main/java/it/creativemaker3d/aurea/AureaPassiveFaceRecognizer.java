package it.creativemaker3d.aurea;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;

import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.face.Face;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Confronto facciale passivo locale, multi-campione e senza immagini salvate. */
final class AureaPassiveFaceRecognizer implements AutoCloseable {
    static final class Match {
        final String name;
        final float score;

        Match(String name, float score) {
            this.name = clean(name);
            this.score = score;
        }
    }

    private final AureaFaceProfileStore store;
    private final AureaFaceRecognitionEngine engine;
    private final Context context;
    private List<AureaFaceProfileStore.Profile> profiles = new ArrayList<>();

    AureaPassiveFaceRecognizer(Context context) {
        Context application = context.getApplicationContext();
        this.context = application;
        store = new AureaFaceProfileStore(application);
        AureaFaceRecognitionEngine created;
        try {
            created = new AureaFaceRecognitionEngine(application);
        } catch (Exception ignored) {
            created = null;
        }
        engine = created;
        reload();
    }

    void reload() {
        profiles = store.loadProfiles();
    }

    int profileCount() {
        if (engine == null) return 0;
        int count = 0;
        for (AureaFaceProfileStore.Profile profile : profiles) {
            if (profile.isCalibratedV2()) count++;
        }
        return count;
    }

    static int profileCount(Context context) {
        return new AureaFaceProfileStore(context).profileCount();
    }

    static int calibratedProfileCount(Context context) {
        return new AureaFaceProfileStore(context).calibratedProfileCount();
    }

    Match recognize(ImageProxy proxy, Face face) {
        if (engine == null || proxy == null || face == null || profileCount() == 0) {
            return null;
        }
        Bitmap source = null;
        Bitmap rotated = null;
        try {
            source = grayscaleBitmap(proxy);
            rotated = rotateBitmap(source, proxy.getImageInfo().getRotationDegrees());
            AureaFaceRecognitionEngine.Capture capture = engine.capture(rotated, face);
            if (!capture.accepted()) return null;
            AureaFaceRecognitionEngine.ProfileScore result =
                engine.bestProfile(capture.sample, profiles);
            boolean accepted = result.accepted(profileCount());
            AureaRecognitionDiagnostics.recordFace(
                context,
                result.score,
                result.required,
                accepted,
                capture.sample
            );
            if (!accepted) return null;
            return new Match(result.name, result.score);
        } catch (Exception ignored) {
            return null;
        } finally {
            recycle(rotated);
            if (source != rotated) recycle(source);
        }
    }

    static boolean isUsableFace(Face face) {
        if (face == null) return false;
        Rect box = face.getBoundingBox();
        if (box.width() < 115 || box.height() < 115) return false;
        if (Math.abs(face.getHeadEulerAngleY()) > 30f
                || Math.abs(face.getHeadEulerAngleZ()) > 18f) return false;
        Float leftEye = face.getLeftEyeOpenProbability();
        Float rightEye = face.getRightEyeOpenProbability();
        return leftEye == null || rightEye == null
            || (leftEye >= 0.30f && rightEye >= 0.30f);
    }

    private static Bitmap grayscaleBitmap(ImageProxy proxy) {
        ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = proxy.getWidth();
        int height = proxy.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int rowOffset = y * rowStride;
            for (int x = 0; x < width; x++) {
                int index = rowOffset + x * pixelStride;
                int luminance = index < buffer.limit() ? buffer.get(index) & 0xff : 0;
                pixels[y * width + x] = Color.rgb(luminance, luminance, luminance);
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap rotateBitmap(Bitmap source, int degrees) {
        if (degrees == 0) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(
            source, 0, 0, source.getWidth(), source.getHeight(), matrix, true
        );
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public void close() {
        if (engine != null) engine.close();
    }
}
