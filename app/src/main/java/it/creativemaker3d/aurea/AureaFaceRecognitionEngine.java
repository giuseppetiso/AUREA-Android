package it.creativemaker3d.aurea;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceRecognizerSF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Motore biometrico facciale locale: allineamento, controllo qualità,
 * embedding neurale e firma strutturale. Non conserva alcun fotogramma.
 */
final class AureaFaceRecognitionEngine implements AutoCloseable {
    static final String ENGINE_ID = "sface_2021dec_int8";
    private static final String MODEL = "face_recognition_sface_2021dec_int8.onnx";
    private static final int TEXTURE_IMAGE_SIZE = 64;
    private static final int PIXEL_GRID = 16;
    private static final int LBP_BLOCKS = 4;
    private static final int LBP_BINS = 16;
    static final int TEXTURE_SIZE =
        PIXEL_GRID * PIXEL_GRID + LBP_BLOCKS * LBP_BLOCKS * LBP_BINS;

    static final class Sample {
        final float[] embedding;
        final float[] texture;
        final float yaw;
        final float brightness;
        final float contrast;
        final float sharpness;

        Sample(
                float[] embedding,
                float[] texture,
                float yaw,
                float brightness,
                float contrast,
                float sharpness) {
            this.embedding = embedding;
            this.texture = texture;
            this.yaw = yaw;
            this.brightness = brightness;
            this.contrast = contrast;
            this.sharpness = sharpness;
        }
    }

    static final class Capture {
        final Sample sample;
        final String guidance;

        private Capture(Sample sample, String guidance) {
            this.sample = sample;
            this.guidance = guidance == null ? "" : guidance;
        }

        static Capture accepted(Sample sample) {
            return new Capture(sample, "");
        }

        static Capture rejected(String guidance) {
            return new Capture(null, guidance);
        }

        boolean accepted() {
            return sample != null;
        }
    }

    static final class ProfileScore {
        final String name;
        final float score;
        final float secondScore;
        final float required;

        ProfileScore(String name, float score, float secondScore, float required) {
            this.name = name == null ? "" : name.trim();
            this.score = score;
            this.secondScore = secondScore;
            this.required = required;
        }

        boolean accepted(int profileCount) {
            if (name.isEmpty() || score < required) return false;
            return profileCount <= 1 || score - secondScore >= 0.025f;
        }
    }

    private final FaceRecognizerSF recognizer;

    AureaFaceRecognitionEngine(Context context) {
        try {
            if (!OpenCVLoader.initLocal()) {
                throw new IllegalStateException("OpenCV non inizializzato");
            }
            File model = materializeModel(context.getApplicationContext());
            recognizer = FaceRecognizerSF.create(model.getAbsolutePath(), "");
        } catch (Exception error) {
            throw new IllegalStateException(
                "Motore facciale SFace non inizializzato",
                error
            );
        }
    }

    Capture capture(Bitmap frame, Face face) {
        if (frame == null || face == null) {
            return Capture.rejected("Volto non visibile.");
        }
        Rect bounds = face.getBoundingBox();
        if (bounds.width() < 125 || bounds.height() < 125) {
            return Capture.rejected("Avvicinati leggermente alla fotocamera.");
        }
        if (Math.abs(face.getHeadEulerAngleY()) > 29f) {
            return Capture.rejected("Ruota un po' il viso verso il centro.");
        }
        if (Math.abs(face.getHeadEulerAngleZ()) > 17f) {
            return Capture.rejected("Tieni la testa più dritta.");
        }
        Float leftEye = face.getLeftEyeOpenProbability();
        Float rightEye = face.getRightEyeOpenProbability();
        if (leftEye != null && rightEye != null
                && (leftEye < 0.30f || rightEye < 0.30f)) {
            return Capture.rejected("Tieni gli occhi aperti per un momento.");
        }

        Bitmap crop = null;
        Bitmap textureBitmap = null;
        try {
            crop = alignedCrop(frame, face);
            if (crop == null) {
                return Capture.rejected("Centra completamente il volto.");
            }
            Quality quality = quality(crop);
            if (quality.brightness < 38f) {
                return Capture.rejected("Serve un po' più di luce sul viso.");
            }
            if (quality.brightness > 224f) {
                return Capture.rejected("La luce sul viso è troppo forte.");
            }
            if (quality.contrast < 20f) {
                return Capture.rejected("Luce troppo uniforme: orientati verso la stanza.");
            }
            if (quality.sharpness < 5.2f) {
                return Capture.rejected("Resta fermo per un istante.");
            }

            float[] neural = faceEmbedding(frame, face);
            if (neural == null || neural.length < 128) {
                return Capture.rejected(
                    "Occhi, naso e bocca devono essere ben visibili."
                );
            }
            normalize(neural);

            textureBitmap = Bitmap.createScaledBitmap(
                crop,
                TEXTURE_IMAGE_SIZE,
                TEXTURE_IMAGE_SIZE,
                true
            );
            float[] texture = textureSignature(textureBitmap);
            return Capture.accepted(new Sample(
                neural,
                texture,
                face.getHeadEulerAngleY(),
                quality.brightness,
                quality.contrast,
                quality.sharpness
            ));
        } catch (Exception ignored) {
            return Capture.rejected("Riconoscimento momentaneamente non disponibile.");
        } finally {
            recycle(textureBitmap, crop, null);
            recycle(crop, null, null);
        }
    }

    ProfileScore bestProfile(
            Sample sample,
            List<AureaFaceProfileStore.Profile> profiles) {
        if (sample == null || profiles == null || profiles.isEmpty()) {
            return new ProfileScore("", -1f, -1f, 1f);
        }
        String bestName = "";
        float best = -1f;
        float second = -1f;
        float required = 1f;
        for (AureaFaceProfileStore.Profile profile : profiles) {
            if (!profile.isCalibratedV2()) continue;
            float score = profileScore(sample, profile.templates);
            if (score > best) {
                second = best;
                best = score;
                bestName = profile.name;
                required = profile.threshold;
            } else if (score > second) {
                second = score;
            }
        }
        return new ProfileScore(bestName, best, second, required);
    }

    static float calibratedThreshold(List<Sample> samples) {
        if (samples == null || samples.size() < 6) return 0.48f;
        ArrayList<Float> genuine = new ArrayList<>();
        for (int index = 0; index < samples.size(); index++) {
            ArrayList<AureaFaceProfileStore.Template> others = new ArrayList<>();
            for (int item = 0; item < samples.size(); item++) {
                if (item == index) continue;
                Sample sample = samples.get(item);
                others.add(new AureaFaceProfileStore.Template(
                    sample.embedding,
                    sample.texture
                ));
            }
            genuine.add(profileScore(samples.get(index), others));
        }
        Collections.sort(genuine);
        float lowerQuartile = genuine.get(Math.max(0, genuine.size() / 4));
        return clamp(lowerQuartile - 0.065f, 0.44f, 0.68f);
    }

    static boolean addsDiversity(List<Sample> samples, Sample candidate) {
        if (candidate == null) return false;
        if (samples == null || samples.isEmpty()) return true;
        float closest = -1f;
        for (Sample existing : samples) {
            closest = Math.max(closest, combinedSimilarity(candidate, existing));
        }
        return closest < 0.985f || samples.size() < 3;
    }

    private static float profileScore(
            Sample sample,
            List<AureaFaceProfileStore.Template> templates) {
        ArrayList<Float> scores = new ArrayList<>();
        for (AureaFaceProfileStore.Template template : templates) {
            float neural = cosine(sample.embedding, template.embedding);
            float texture = cosine(sample.texture, template.texture);
            if (neural < -0.5f || texture < -0.5f) continue;
            scores.add(clamp(neural * 0.76f + texture * 0.24f, -1f, 1f));
        }
        if (scores.isEmpty()) return -1f;
        scores.sort(Collections.reverseOrder());
        if (scores.size() == 1) return scores.get(0);
        int supportIndex = Math.min(2, scores.size() - 1);
        return scores.get(0) * 0.62f
            + scores.get(1) * 0.25f
            + scores.get(supportIndex) * 0.13f;
    }

    private static float combinedSimilarity(Sample first, Sample second) {
        return cosine(first.embedding, second.embedding) * 0.76f
            + cosine(first.texture, second.texture) * 0.24f;
    }

    private float[] faceEmbedding(Bitmap frame, Face face) {
        PointF rightEye = landmark(face, FaceLandmark.RIGHT_EYE);
        PointF leftEye = landmark(face, FaceLandmark.LEFT_EYE);
        PointF nose = landmark(face, FaceLandmark.NOSE_BASE);
        PointF mouthRight = landmark(face, FaceLandmark.MOUTH_RIGHT);
        PointF mouthLeft = landmark(face, FaceLandmark.MOUTH_LEFT);
        if (rightEye == null || leftEye == null || nose == null
                || mouthRight == null || mouthLeft == null) {
            return null;
        }

        Rect bounds = face.getBoundingBox();
        float[] descriptor = {
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height(),
            rightEye.x,
            rightEye.y,
            leftEye.x,
            leftEye.y,
            nose.x,
            nose.y,
            mouthRight.x,
            mouthRight.y,
            mouthLeft.x,
            mouthLeft.y,
            1f
        };

        Mat rgba = new Mat();
        Mat bgr = new Mat();
        Mat faceBox = new Mat(1, 15, CvType.CV_32FC1);
        Mat aligned = new Mat();
        Mat feature = new Mat();
        try {
            Utils.bitmapToMat(frame, rgba);
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            faceBox.put(0, 0, descriptor);
            recognizer.alignCrop(bgr, faceBox, aligned);
            if (aligned.empty()) return null;
            recognizer.feature(aligned, feature);
            if (feature.empty()) return null;
            int size = (int) (feature.total() * feature.channels());
            if (size < 128) return null;
            float[] embedding = new float[size];
            feature.get(0, 0, embedding);
            return embedding;
        } finally {
            feature.release();
            aligned.release();
            faceBox.release();
            bgr.release();
            rgba.release();
        }
    }

    private static PointF landmark(Face face, int type) {
        FaceLandmark landmark = face.getLandmark(type);
        return landmark == null ? null : landmark.getPosition();
    }

    private static File materializeModel(Context context) throws Exception {
        File directory = new File(context.getNoBackupFilesDir(), "aurea_models");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("cartella modelli non disponibile");
        }
        File model = new File(directory, MODEL);
        if (model.isFile() && model.length() > 8_000_000L) return model;

        File temporary = new File(directory, MODEL + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IllegalStateException("modello temporaneo bloccato");
        }
        try (InputStream input = context.getAssets().open(MODEL);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        if (model.exists() && !model.delete()) {
            temporary.delete();
            throw new IllegalStateException("vecchio modello bloccato");
        }
        if (!temporary.renameTo(model)) {
            temporary.delete();
            throw new IllegalStateException("installazione modello non riuscita");
        }
        return model;
    }

    private static Bitmap alignedCrop(Bitmap source, Face face) {
        Rect bounds = face.getBoundingBox();
        int centerX = bounds.centerX();
        int centerY = Math.round(bounds.centerY() + bounds.height() * 0.03f);
        int square = Math.round(Math.max(bounds.width(), bounds.height()) * 1.34f);
        square = Math.min(square, Math.min(source.getWidth(), source.getHeight()));
        if (square < 100) return null;
        int left = clampInt(centerX - square / 2, 0, source.getWidth() - square);
        int top = clampInt(centerY - square / 2, 0, source.getHeight() - square);
        Bitmap crop = Bitmap.createBitmap(source, left, top, square, square);

        FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
        if (leftEye == null || rightEye == null) return crop;
        PointF leftPoint = leftEye.getPosition();
        PointF rightPoint = rightEye.getPosition();
        float angle = (float) Math.toDegrees(Math.atan2(
            rightPoint.y - leftPoint.y,
            rightPoint.x - leftPoint.x
        ));
        if (Math.abs(angle) < 1f) return crop;
        Matrix matrix = new Matrix();
        matrix.postRotate(-angle);
        Bitmap rotated = Bitmap.createBitmap(
            crop, 0, 0, crop.getWidth(), crop.getHeight(), matrix, true
        );
        if (rotated == crop) return crop;
        int size = Math.min(rotated.getWidth(), rotated.getHeight());
        Bitmap centered = Bitmap.createBitmap(
            rotated,
            (rotated.getWidth() - size) / 2,
            (rotated.getHeight() - size) / 2,
            size,
            size
        );
        if (centered != rotated) rotated.recycle();
        crop.recycle();
        return centered;
    }

    private static Quality quality(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(1, Math.min(width, height) / 96);
        double sum = 0d;
        double sumSquared = 0d;
        double gradient = 0d;
        int count = 0;
        for (int y = step; y < height; y += step) {
            for (int x = step; x < width; x += step) {
                int value = Color.red(bitmap.getPixel(x, y));
                int left = Color.red(bitmap.getPixel(x - step, y));
                int top = Color.red(bitmap.getPixel(x, y - step));
                sum += value;
                sumSquared += value * value;
                gradient += Math.abs(value - left) + Math.abs(value - top);
                count++;
            }
        }
        if (count == 0) return new Quality(0f, 0f, 0f);
        double mean = sum / count;
        double variance = Math.max(0d, sumSquared / count - mean * mean);
        return new Quality(
            (float) mean,
            (float) Math.sqrt(variance),
            (float) (gradient / (count * 2d))
        );
    }

    private static float[] textureSignature(Bitmap bitmap) {
        int[] pixels = new int[TEXTURE_IMAGE_SIZE * TEXTURE_IMAGE_SIZE];
        bitmap.getPixels(
            pixels, 0, TEXTURE_IMAGE_SIZE, 0, 0,
            TEXTURE_IMAGE_SIZE, TEXTURE_IMAGE_SIZE
        );
        float[] gray = equalizedGray(pixels);
        float[] vector = new float[TEXTURE_SIZE];
        appendPixelGrid(gray, vector, 0);
        appendLbp(gray, vector, PIXEL_GRID * PIXEL_GRID);
        normalize(vector);
        return vector;
    }

    private static float[] equalizedGray(int[] pixels) {
        int[] histogram = new int[256];
        int[] raw = new int[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            int value = Color.red(pixels[index]);
            raw[index] = value;
            histogram[value]++;
        }
        int[] cumulative = new int[256];
        int running = 0;
        for (int index = 0; index < 256; index++) {
            running += histogram[index];
            cumulative[index] = running;
        }
        int first = 0;
        while (first < 255 && histogram[first] == 0) first++;
        int minimum = cumulative[first];
        int denominator = Math.max(1, pixels.length - minimum);
        float[] result = new float[pixels.length];
        for (int index = 0; index < raw.length; index++) {
            result[index] = clamp(
                (cumulative[raw[index]] - minimum) * 255f / denominator,
                0f,
                255f
            );
        }
        return result;
    }

    private static void appendPixelGrid(float[] gray, float[] vector, int offset) {
        int cell = TEXTURE_IMAGE_SIZE / PIXEL_GRID;
        float[] values = new float[PIXEL_GRID * PIXEL_GRID];
        float mean = 0f;
        int position = 0;
        for (int gridY = 0; gridY < PIXEL_GRID; gridY++) {
            for (int gridX = 0; gridX < PIXEL_GRID; gridX++) {
                float sum = 0f;
                for (int y = 0; y < cell; y++) {
                    int row = (gridY * cell + y) * TEXTURE_IMAGE_SIZE;
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
        float deviation = (float) Math.sqrt(variance / values.length + 1e-6f);
        for (int index = 0; index < values.length; index++) {
            vector[offset + index] = (values[index] - mean) / deviation;
        }
    }

    private static void appendLbp(float[] gray, float[] vector, int offset) {
        int blockSize = TEXTURE_IMAGE_SIZE / LBP_BLOCKS;
        for (int y = 1; y < TEXTURE_IMAGE_SIZE - 1; y++) {
            for (int x = 1; x < TEXTURE_IMAGE_SIZE - 1; x++) {
                float center = gray[y * TEXTURE_IMAGE_SIZE + x];
                int code = 0;
                code |= gray[(y - 1) * TEXTURE_IMAGE_SIZE + x - 1] >= center ? 1 : 0;
                code |= gray[(y - 1) * TEXTURE_IMAGE_SIZE + x] >= center ? 2 : 0;
                code |= gray[(y - 1) * TEXTURE_IMAGE_SIZE + x + 1] >= center ? 4 : 0;
                code |= gray[y * TEXTURE_IMAGE_SIZE + x + 1] >= center ? 8 : 0;
                code |= gray[(y + 1) * TEXTURE_IMAGE_SIZE + x + 1] >= center ? 16 : 0;
                code |= gray[(y + 1) * TEXTURE_IMAGE_SIZE + x] >= center ? 32 : 0;
                code |= gray[(y + 1) * TEXTURE_IMAGE_SIZE + x - 1] >= center ? 64 : 0;
                code |= gray[y * TEXTURE_IMAGE_SIZE + x - 1] >= center ? 128 : 0;
                int blockX = Math.min(LBP_BLOCKS - 1, x / blockSize);
                int blockY = Math.min(LBP_BLOCKS - 1, y / blockSize);
                int bin = (code ^ (code >>> 4)) & (LBP_BINS - 1);
                int index = offset
                    + (blockY * LBP_BLOCKS + blockX) * LBP_BINS
                    + bin;
                vector[index] += 1f;
            }
        }
        int blockVector = LBP_BINS;
        for (int block = 0; block < LBP_BLOCKS * LBP_BLOCKS; block++) {
            float sum = 0f;
            int start = offset + block * blockVector;
            for (int bin = 0; bin < blockVector; bin++) sum += vector[start + bin];
            if (sum <= 0f) continue;
            for (int bin = 0; bin < blockVector; bin++) vector[start + bin] /= sum;
        }
    }

    static float cosine(float[] first, float[] second) {
        if (first == null || second == null || first.length != second.length) return -1f;
        double dot = 0d;
        double normFirst = 0d;
        double normSecond = 0d;
        for (int index = 0; index < first.length; index++) {
            dot += first[index] * second[index];
            normFirst += first[index] * first[index];
            normSecond += second[index] * second[index];
        }
        if (normFirst <= 0d || normSecond <= 0d) return -1f;
        return (float) (dot / Math.sqrt(normFirst * normSecond));
    }

    private static void normalize(float[] vector) {
        double norm = 0d;
        for (float value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm <= 0d) return;
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= (float) norm;
        }
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void recycle(Bitmap bitmap, Bitmap exceptFirst, Bitmap exceptSecond) {
        if (bitmap != null && bitmap != exceptFirst && bitmap != exceptSecond
                && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    @Override
    public void close() {
        // FaceRecognizerSF non espone close(); l'istanza vive quanto il controller.
    }

    private static final class Quality {
        final float brightness;
        final float contrast;
        final float sharpness;

        Quality(float brightness, float contrast, float sharpness) {
            this.brightness = brightness;
            this.contrast = contrast;
            this.sharpness = sharpness;
        }
    }
}
