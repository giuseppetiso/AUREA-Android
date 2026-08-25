package it.creativemaker3d.aurea;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Base64;

import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.face.Face;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Confronto facciale passivo con le firme locali create da FaceGateActivity.
 * Non conserva bitmap e non considera il risultato un'autorizzazione di sicurezza.
 */
final class AureaPassiveFaceRecognizer {
    private static final String PREFS = "aurea_face_profiles";
    private static final String KEY_PROFILES = "profiles";
    private static final float MINIMUM_PASSIVE_SCORE = 0.82f;
    private static final float MINIMUM_PROFILE_GAP = 0.035f;

    static final class Match {
        final String name;
        final float score;

        Match(String name, float score) {
            this.name = clean(name);
            this.score = score;
        }
    }

    private static final class Profile {
        final String name;
        final float[] vector;
        final float threshold;

        Profile(String name, float[] vector, float threshold) {
            this.name = clean(name);
            this.vector = vector;
            this.threshold = threshold;
        }
    }

    private final Context context;
    private List<Profile> profiles = new ArrayList<>();

    AureaPassiveFaceRecognizer(Context context) {
        this.context = context.getApplicationContext();
        reload();
    }

    void reload() {
        profiles = loadProfiles(context);
    }

    int profileCount() {
        return profiles.size();
    }

    static int profileCount(Context context) {
        return loadProfiles(context.getApplicationContext()).size();
    }

    Match recognize(ImageProxy proxy, Face face) {
        if (proxy == null || face == null || profiles.isEmpty()) return null;
        Rect box = face.getBoundingBox();
        if (!isUsableFace(face)) return null;

        Bitmap source = null;
        Bitmap rotated = null;
        try {
            source = grayscaleBitmap(proxy);
            rotated = rotateBitmap(source, proxy.getImageInfo().getRotationDegrees());
            float[] signature = FaceSignature.create(rotated, box);
            if (signature == null) return null;

            Profile best = null;
            float bestScore = -1f;
            float secondScore = -1f;
            for (Profile profile : profiles) {
                float score = FaceSignature.similarity(profile.vector, signature);
                if (score > bestScore) {
                    secondScore = bestScore;
                    bestScore = score;
                    best = profile;
                } else if (score > secondScore) {
                    secondScore = score;
                }
            }
            if (best == null) return null;
            float required = Math.max(best.threshold, MINIMUM_PASSIVE_SCORE);
            if (bestScore < required) return null;
            if (profiles.size() > 1 && bestScore - secondScore < MINIMUM_PROFILE_GAP) {
                return null;
            }
            return new Match(best.name, bestScore);
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
        if (box.width() < 80 || box.height() < 80) return false;
        if (Math.abs(face.getHeadEulerAngleY()) > 32f
                || Math.abs(face.getHeadEulerAngleZ()) > 24f) return false;
        Float leftEye = face.getLeftEyeOpenProbability();
        Float rightEye = face.getRightEyeOpenProbability();
        return leftEye == null || rightEye == null
            || (leftEye >= 0.28f && rightEye >= 0.28f);
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

    private static List<Profile> loadProfiles(Context context) {
        ArrayList<Profile> result = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_PROFILES, "{}");
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray names = root.names();
            if (names == null) return result;
            for (int i = 0; i < names.length(); i++) {
                String name = clean(names.optString(i, ""));
                JSONObject stored = name.isEmpty() ? null : root.optJSONObject(name);
                if (stored == null) continue;
                float[] vector = decodeVector(stored.optString("vector", ""));
                float threshold = (float) stored.optDouble("threshold", 0.80);
                if (vector != null && vector.length == FaceSignature.VECTOR_SIZE) {
                    result.add(new Profile(name, vector, threshold));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static float[] decodeVector(String encoded) {
        try {
            byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
            if (data.length % 4 != 0) return null;
            ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[data.length / 4];
            for (int i = 0; i < vector.length; i++) vector[i] = bytes.getFloat();
            return vector;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class FaceSignature {
        private static final int IMAGE_SIZE = 64;
        private static final int PIXEL_GRID = 16;
        private static final int LBP_BLOCKS = 4;
        private static final int LBP_BINS = 16;
        static final int VECTOR_SIZE =
            PIXEL_GRID * PIXEL_GRID + LBP_BLOCKS * LBP_BLOCKS * LBP_BINS;

        static float[] create(Bitmap source, Rect detectedBounds) {
            if (source == null || detectedBounds == null) return null;
            int centerX = detectedBounds.centerX();
            int centerY = detectedBounds.centerY();
            int square = Math.round(
                Math.max(detectedBounds.width(), detectedBounds.height()) * 1.22f
            );
            square = Math.min(square, Math.min(source.getWidth(), source.getHeight()));
            if (square < 80) return null;
            int left = clampInt(centerX - square / 2, 0, source.getWidth() - square);
            int top = clampInt(centerY - square / 2, 0, source.getHeight() - square);

            Bitmap crop = null;
            Bitmap scaled = null;
            try {
                crop = Bitmap.createBitmap(source, left, top, square, square);
                scaled = Bitmap.createScaledBitmap(crop, IMAGE_SIZE, IMAGE_SIZE, true);
                int[] pixels = new int[IMAGE_SIZE * IMAGE_SIZE];
                scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE);
                float[] gray = equalizedGray(pixels);
                float[] vector = new float[VECTOR_SIZE];
                appendPixelGrid(gray, vector, 0);
                appendLbp(gray, vector, PIXEL_GRID * PIXEL_GRID);
                normalize(vector);
                return vector;
            } catch (Exception ignored) {
                return null;
            } finally {
                if (scaled != null && scaled != crop) recycle(scaled);
                recycle(crop);
            }
        }

        static float similarity(float[] first, float[] second) {
            if (first == null || second == null || first.length != second.length) return -1f;
            double dot = 0d;
            double firstNorm = 0d;
            double secondNorm = 0d;
            for (int i = 0; i < first.length; i++) {
                dot += first[i] * second[i];
                firstNorm += first[i] * first[i];
                secondNorm += second[i] * second[i];
            }
            if (firstNorm <= 0d || secondNorm <= 0d) return -1f;
            return (float) (dot / Math.sqrt(firstNorm * secondNorm));
        }

        private static float[] equalizedGray(int[] pixels) {
            int[] histogram = new int[256];
            int[] raw = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int value = Color.red(pixels[i]);
                raw[i] = value;
                histogram[value]++;
            }
            int[] cumulative = new int[256];
            int running = 0;
            for (int i = 0; i < 256; i++) {
                running += histogram[i];
                cumulative[i] = running;
            }
            int firstNonZero = 0;
            while (firstNonZero < 255 && histogram[firstNonZero] == 0) firstNonZero++;
            int minimum = cumulative[firstNonZero];
            int denominator = Math.max(1, pixels.length - minimum);
            float[] result = new float[pixels.length];
            for (int i = 0; i < raw.length; i++) {
                result[i] = Math.max(
                    0f,
                    Math.min(255f, (cumulative[raw[i]] - minimum) * 255f / denominator)
                );
            }
            return result;
        }

        private static void appendPixelGrid(float[] gray, float[] vector, int offset) {
            int cell = IMAGE_SIZE / PIXEL_GRID;
            float[] values = new float[PIXEL_GRID * PIXEL_GRID];
            float mean = 0f;
            int position = 0;
            for (int gridY = 0; gridY < PIXEL_GRID; gridY++) {
                for (int gridX = 0; gridX < PIXEL_GRID; gridX++) {
                    float sum = 0f;
                    for (int y = 0; y < cell; y++) {
                        int row = (gridY * cell + y) * IMAGE_SIZE;
                        for (int x = 0; x < cell; x++) {
                            sum += gray[row + gridX * cell + x];
                        }
                    }
                    float value = sum / (cell * cell);
                    values[position++] = value;
                    mean += value;
                }
            }
            mean /= values.length;
            float variance = 0f;
            for (float value : values) {
                float delta = value - mean;
                variance += delta * delta;
            }
            float standardDeviation = (float) Math.sqrt(
                variance / Math.max(1, values.length - 1)
            );
            standardDeviation = Math.max(8f, standardDeviation);
            for (int i = 0; i < values.length; i++) {
                vector[offset + i] = (values[i] - mean) / standardDeviation;
            }
        }

        private static void appendLbp(float[] gray, float[] vector, int offset) {
            int blockSize = IMAGE_SIZE / LBP_BLOCKS;
            for (int y = 1; y < IMAGE_SIZE - 1; y++) {
                for (int x = 1; x < IMAGE_SIZE - 1; x++) {
                    float center = gray[y * IMAGE_SIZE + x];
                    int code = 0;
                    code |= gray[(y - 1) * IMAGE_SIZE + (x - 1)] >= center ? 1 : 0;
                    code |= gray[(y - 1) * IMAGE_SIZE + x] >= center ? 2 : 0;
                    code |= gray[(y - 1) * IMAGE_SIZE + (x + 1)] >= center ? 4 : 0;
                    code |= gray[y * IMAGE_SIZE + (x + 1)] >= center ? 8 : 0;
                    code |= gray[(y + 1) * IMAGE_SIZE + (x + 1)] >= center ? 16 : 0;
                    code |= gray[(y + 1) * IMAGE_SIZE + x] >= center ? 32 : 0;
                    code |= gray[(y + 1) * IMAGE_SIZE + (x - 1)] >= center ? 64 : 0;
                    code |= gray[y * IMAGE_SIZE + (x - 1)] >= center ? 128 : 0;
                    int blockX = Math.min(LBP_BLOCKS - 1, x / blockSize);
                    int blockY = Math.min(LBP_BLOCKS - 1, y / blockSize);
                    int bin = code >>> 4;
                    int index = offset
                        + (blockY * LBP_BLOCKS + blockX) * LBP_BINS + bin;
                    vector[index] += 1f;
                }
            }
            for (int block = 0; block < LBP_BLOCKS * LBP_BLOCKS; block++) {
                int start = offset + block * LBP_BINS;
                float sum = 0f;
                for (int bin = 0; bin < LBP_BINS; bin++) sum += vector[start + bin];
                if (sum <= 0f) continue;
                for (int bin = 0; bin < LBP_BINS; bin++) vector[start + bin] /= sum;
            }
        }

        private static void normalize(float[] vector) {
            double norm = 0d;
            for (float value : vector) norm += value * value;
            if (norm <= 0d) return;
            float scale = (float) (1d / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) vector[i] *= scale;
        }

        private static int clampInt(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
