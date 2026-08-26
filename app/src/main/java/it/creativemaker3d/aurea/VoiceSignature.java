package it.creativemaker3d.aurea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Firma vocale locale v2 con VAD, qualità, timbro e prosodia. */
final class VoiceSignature {
    private static final int FRAME = 320;
    private static final int SEGMENTS = 12;
    private static final int BANDS = 20;
    private static final int SPECTRAL_FEATURES = BANDS * 3;
    private static final double MIN_SPEECH_SECONDS = 0.90;
    private static final double MAX_SPEECH_SECONDS = 4.80;

    static final class Analysis {
        final float[] signature;
        final String message;
        final float snrDb;
        final float speechSeconds;
        final float clippingRatio;

        private Analysis(
                float[] signature,
                String message,
                float snrDb,
                float speechSeconds,
                float clippingRatio) {
            this.signature = signature;
            this.message = message == null ? "" : message;
            this.snrDb = snrDb;
            this.speechSeconds = speechSeconds;
            this.clippingRatio = clippingRatio;
        }

        static Analysis failure(String message) {
            return new Analysis(null, message, 0f, 0f, 0f);
        }

        boolean accepted() {
            return signature != null;
        }
    }

    private VoiceSignature() {
    }

    static Analysis analyze(short[] pcm, int sampleRate) {
        if (pcm == null || sampleRate < 8000 || pcm.length < sampleRate / 2) {
            return Analysis.failure("Frase incompleta. Riprova con voce naturale.");
        }

        int frameCount = pcm.length / FRAME;
        if (frameCount < 12) {
            return Analysis.failure("Frase troppo breve. Ripetila per intero.");
        }
        double[] frameRms = new double[frameCount];
        double maximum = 0d;
        for (int index = 0; index < frameCount; index++) {
            frameRms[index] = rms(pcm, index * FRAME, FRAME);
            maximum = Math.max(maximum, frameRms[index]);
        }
        if (maximum < 170d) {
            return Analysis.failure("Voce troppo bassa. Avvicinati leggermente.");
        }

        double[] sorted = frameRms.clone();
        Arrays.sort(sorted);
        double noise = sorted[Math.min(sorted.length - 1, sorted.length / 5)];
        double threshold = Math.max(noise * 2.1d, noise + 95d);
        threshold = Math.min(threshold, maximum * 0.42d);

        boolean[] active = new boolean[frameCount];
        for (int index = 0; index < frameCount; index++) {
            active[index] = frameRms[index] >= threshold;
        }
        bridgeShortGaps(active, 4);
        int first = -1;
        int last = -1;
        for (int index = 0; index < frameCount; index++) {
            if (!active[index]) continue;
            if (first < 0) first = index;
            last = index;
        }
        if (first < 0 || last < first) {
            return Analysis.failure("Non ho rilevato la frase. Riprova senza fretta.");
        }
        first = Math.max(0, first - 2);
        last = Math.min(frameCount - 1, last + 2);
        int start = first * FRAME;
        int end = Math.min(pcm.length, (last + 1) * FRAME);
        int speechLength = end - start;
        float seconds = speechLength / (float) sampleRate;
        if (seconds < MIN_SPEECH_SECONDS) {
            return Analysis.failure("Frase troppo breve. Pronunciala completamente.");
        }
        if (seconds > MAX_SPEECH_SECONDS) {
            return Analysis.failure("Frase troppo lenta o con troppo rumore. Riprova.");
        }

        double speechRms = rms(pcm, start, speechLength);
        float snrDb = (float) (20d * Math.log10((speechRms + 1d) / (noise + 1d)));
        if (snrDb < 7.5f) {
            return Analysis.failure("Troppo rumore vicino al tablet. Attendi silenzio e riprova.");
        }

        int clipped = 0;
        for (int index = start; index < end; index++) {
            if (Math.abs((int) pcm[index]) >= 32700) clipped++;
        }
        float clipping = clipped / (float) speechLength;
        if (clipping > 0.008f) {
            return Analysis.failure("Voce troppo forte o troppo vicina. Allontanati un poco.");
        }

        float[] signature = features(pcm, start, speechLength, sampleRate);
        if (signature == null) {
            return Analysis.failure("Campione vocale non abbastanza chiaro. Riprova.");
        }
        return new Analysis(signature, "", snrDb, seconds, clipping);
    }

    static float[] create(short[] pcm, int sampleRate) {
        return analyze(pcm, sampleRate).signature;
    }

    static float[] mean(List<float[]> samples) {
        if (samples == null || samples.isEmpty()) return null;
        int size = samples.get(0).length;
        float[] result = new float[size];
        int valid = 0;
        for (float[] sample : samples) {
            if (sample == null || sample.length != size) continue;
            valid++;
            for (int index = 0; index < size; index++) result[index] += sample[index];
        }
        if (valid == 0) return null;
        for (int index = 0; index < size; index++) result[index] /= valid;
        normalize(result);
        return result;
    }

    static float similarity(float[] first, float[] second) {
        if (first == null || second == null || first.length != second.length) return -1f;
        float complete = cosineRange(first, second, 0, first.length);
        int spectralEnd = Math.min(SPECTRAL_FEATURES, first.length);
        float spectral = cosineRange(first, second, 0, spectralEnd);
        float prosody = cosineRange(first, second, spectralEnd, first.length);
        if (complete < -0.5f || spectral < -0.5f || prosody < -0.5f) {
            return complete;
        }
        return clamp(complete * 0.45f + spectral * 0.42f + prosody * 0.13f, -1f, 1f);
    }

    static float profileScore(List<float[]> templates, float[] centroid, float[] query) {
        ArrayList<Float> scores = new ArrayList<>();
        if (templates != null) {
            for (float[] template : templates) {
                float score = similarity(template, query);
                if (score > -0.5f) scores.add(score);
            }
        }
        float centroidScore = similarity(centroid, query);
        if (scores.isEmpty()) return centroidScore;
        scores.sort(Collections.reverseOrder());
        float best = scores.get(0);
        float support = scores.size() > 1 ? scores.get(1) : centroidScore;
        return best * 0.58f + support * 0.24f + centroidScore * 0.18f;
    }

    static float calibratedThreshold(List<float[]> samples) {
        if (samples == null || samples.size() < 3) return 0.78f;
        ArrayList<Float> genuine = new ArrayList<>();
        for (int index = 0; index < samples.size(); index++) {
            ArrayList<float[]> others = new ArrayList<>(samples);
            float[] query = others.remove(index);
            genuine.add(profileScore(others, mean(others), query));
        }
        Collections.sort(genuine);
        float weakest = genuine.get(0);
        return clamp(weakest - 0.055f, 0.72f, 0.88f);
    }

    private static float[] features(
            short[] pcm,
            int start,
            int length,
            int sampleRate) {
        double[][] bands = new double[SEGMENTS][BANDS];
        double[] pitch = new double[SEGMENTS];
        double[] zcr = new double[SEGMENTS];
        double[] envelope = new double[SEGMENTS];
        for (int segment = 0; segment < SEGMENTS; segment++) {
            int segmentStart = start + length * segment / SEGMENTS;
            int segmentEnd = start + length * (segment + 1) / SEGMENTS;
            int segmentLength = Math.max(FRAME, segmentEnd - segmentStart);
            segmentLength = Math.min(segmentLength, pcm.length - segmentStart);
            if (segmentLength <= 0) return null;
            envelope[segment] = Math.log1p(rms(pcm, segmentStart, segmentLength));
            zcr[segment] = zeroCrossingRate(pcm, segmentStart, segmentLength);
            pitch[segment] = estimatePitch(pcm, segmentStart, segmentLength, sampleRate);

            double total = 0d;
            for (int band = 0; band < BANDS; band++) {
                double ratio = band / (double) (BANDS - 1);
                double frequency = 120d * Math.pow(7200d / 120d, ratio);
                double power = goertzelPower(
                    pcm, segmentStart, segmentLength, sampleRate, frequency
                );
                bands[segment][band] = power;
                total += power;
            }
            total = Math.max(total, 1e-12);
            for (int band = 0; band < BANDS; band++) {
                bands[segment][band] = Math.log1p(bands[segment][band] / total * 1e6);
            }
        }

        float[] vector = new float[SPECTRAL_FEATURES + 5 + SEGMENTS];
        int position = 0;
        for (int band = 0; band < BANDS; band++) {
            double mean = columnMean(bands, band);
            vector[position++] = (float) (mean / 12d);
        }
        for (int band = 0; band < BANDS; band++) {
            double mean = columnMean(bands, band);
            vector[position++] = (float) (columnStd(bands, band, mean) / 6d);
        }
        for (int band = 0; band < BANDS; band++) {
            double delta = 0d;
            for (int segment = 1; segment < SEGMENTS; segment++) {
                delta += Math.abs(bands[segment][band] - bands[segment - 1][band]);
            }
            vector[position++] = (float) (delta / ((SEGMENTS - 1) * 6d));
        }

        double pitchMean = meanPositive(pitch);
        double zcrMean = mean(zcr);
        int voiced = 0;
        for (double value : pitch) if (value > 0d) voiced++;
        vector[position++] = (float) (pitchMean / 350d);
        vector[position++] = (float) (stdPositive(pitch, pitchMean) / 160d);
        vector[position++] = voiced / (float) SEGMENTS;
        vector[position++] = (float) zcrMean;
        vector[position++] = (float) std(zcr, zcrMean);

        double envelopeMean = mean(envelope);
        double envelopeStd = Math.max(1e-6, std(envelope, envelopeMean));
        for (double value : envelope) {
            vector[position++] = (float) ((value - envelopeMean) / envelopeStd);
        }
        normalize(vector);
        return vector;
    }

    private static void bridgeShortGaps(boolean[] active, int maximumGap) {
        int lastActive = -1;
        for (int index = 0; index < active.length; index++) {
            if (!active[index]) continue;
            if (lastActive >= 0 && index - lastActive - 1 <= maximumGap) {
                for (int fill = lastActive + 1; fill < index; fill++) active[fill] = true;
            }
            lastActive = index;
        }
    }

    private static float cosineRange(float[] first, float[] second, int start, int end) {
        int safeEnd = Math.min(Math.min(first.length, second.length), end);
        if (start >= safeEnd) return -1f;
        double dot = 0d;
        double firstNorm = 0d;
        double secondNorm = 0d;
        for (int index = start; index < safeEnd; index++) {
            dot += first[index] * second[index];
            firstNorm += first[index] * first[index];
            secondNorm += second[index] * second[index];
        }
        if (firstNorm <= 0d || secondNorm <= 0d) return -1f;
        return (float) (dot / Math.sqrt(firstNorm * secondNorm));
    }

    private static double rms(short[] samples, int start, int length) {
        int end = Math.min(samples.length, start + length);
        if (start < 0 || start >= end) return 0d;
        double sum = 0d;
        for (int index = start; index < end; index++) {
            double value = samples[index];
            sum += value * value;
        }
        return Math.sqrt(sum / (end - start));
    }

    private static double zeroCrossingRate(short[] samples, int start, int length) {
        int end = Math.min(samples.length, start + length);
        if (start < 0 || start + 1 >= end) return 0d;
        int crossings = 0;
        short previous = samples[start];
        for (int index = start + 1; index < end; index++) {
            short current = samples[index];
            if ((previous < 0 && current >= 0) || (previous >= 0 && current < 0)) {
                crossings++;
            }
            previous = current;
        }
        return crossings / (double) (end - start - 1);
    }

    private static double estimatePitch(
            short[] samples, int start, int length, int sampleRate) {
        int end = Math.min(samples.length, start + length);
        int available = end - start;
        if (available < 300) return 0d;
        int minimumLag = Math.max(1, sampleRate / 420);
        int maximumLag = Math.min(available / 2, sampleRate / 70);
        double best = 0d;
        int bestLag = 0;
        for (int lag = minimumLag; lag <= maximumLag; lag++) {
            double cross = 0d;
            double energyA = 0d;
            double energyB = 0d;
            for (int index = start + lag; index < end; index += 2) {
                double first = samples[index];
                double second = samples[index - lag];
                cross += first * second;
                energyA += first * first;
                energyB += second * second;
            }
            double denominator = Math.sqrt(energyA * energyB);
            if (denominator <= 0d) continue;
            double correlation = cross / denominator;
            if (correlation > best) {
                best = correlation;
                bestLag = lag;
            }
        }
        return bestLag == 0 || best < 0.28d ? 0d : sampleRate / (double) bestLag;
    }

    private static double goertzelPower(
            short[] samples, int start, int length, int sampleRate, double frequency) {
        int end = Math.min(samples.length, start + length);
        if (start < 0 || start >= end || frequency >= sampleRate / 2d) return 0d;
        double coefficient = 2d * Math.cos(2d * Math.PI * frequency / sampleRate);
        double s1 = 0d;
        double s2 = 0d;
        double previousInput = 0d;
        for (int index = start; index < end; index++) {
            double input = samples[index] / 32768d;
            double emphasized = input - 0.97d * previousInput;
            previousInput = input;
            double s0 = emphasized + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return Math.max(0d, s1 * s1 + s2 * s2 - coefficient * s1 * s2);
    }

    private static double columnMean(double[][] values, int column) {
        double sum = 0d;
        for (double[] value : values) sum += value[column];
        return sum / values.length;
    }

    private static double columnStd(double[][] values, int column, double mean) {
        double variance = 0d;
        for (double[] value : values) {
            double delta = value[column] - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / values.length);
    }

    private static double mean(double[] values) {
        double sum = 0d;
        for (double value : values) sum += value;
        return values.length == 0 ? 0d : sum / values.length;
    }

    private static double std(double[] values, double mean) {
        if (values.length == 0) return 0d;
        double variance = 0d;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / values.length);
    }

    private static double meanPositive(double[] values) {
        double sum = 0d;
        int count = 0;
        for (double value : values) {
            if (value > 0d) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0d : sum / count;
    }

    private static double stdPositive(double[] values, double mean) {
        double variance = 0d;
        int count = 0;
        for (double value : values) {
            if (value > 0d) {
                double delta = value - mean;
                variance += delta * delta;
                count++;
            }
        }
        return count == 0 ? 0d : Math.sqrt(variance / count);
    }

    private static void normalize(float[] vector) {
        double norm = 0d;
        for (float value : vector) norm += value * value;
        norm = Math.sqrt(norm);
        if (norm <= 1e-9d) return;
        for (int index = 0; index < vector.length; index++) {
            vector[index] /= (float) norm;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
