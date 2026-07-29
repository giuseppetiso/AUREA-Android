package it.creativemaker3d.aurea;

import java.util.List;

final class VoiceSignature {
    private static final int SEGMENTS = 12;
    private static final double[] BAND_FREQUENCIES = {
        180.0, 260.0, 380.0, 540.0, 760.0,
        1050.0, 1450.0, 2000.0, 2750.0, 3600.0
    };

    private VoiceSignature() {
    }

    static float[] create(short[] pcm, int sampleRate) {
        if (pcm == null || pcm.length < sampleRate) {
            return null;
        }

        int frame = 320;
        int frameCount = pcm.length / frame;
        if (frameCount < 10) {
            return null;
        }

        double[] rms = new double[frameCount];
        double maximum = 0.0;
        for (int i = 0; i < frameCount; i++) {
            double value = rms(pcm, i * frame, frame);
            rms[i] = value;
            maximum = Math.max(maximum, value);
        }
        if (maximum < 220.0) {
            return null;
        }

        double threshold = Math.max(170.0, maximum * 0.18);
        int first = -1;
        int last = -1;
        for (int i = 0; i < frameCount; i++) {
            if (rms[i] >= threshold) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0 || last < first) {
            return null;
        }

        first = Math.max(0, first - 2);
        last = Math.min(frameCount - 1, last + 2);
        int start = first * frame;
        int end = Math.min(pcm.length, (last + 1) * frame);
        int speechLength = end - start;
        if (speechLength < sampleRate * 0.65) {
            return null;
        }

        int bands = BAND_FREQUENCIES.length;
        double[][] bandValues = new double[SEGMENTS][bands];
        double[] pitchValues = new double[SEGMENTS];
        double[] zcrValues = new double[SEGMENTS];
        double[] energyEnvelope = new double[SEGMENTS];

        for (int segment = 0; segment < SEGMENTS; segment++) {
            int segmentStart = start + speechLength * segment / SEGMENTS;
            int segmentEnd = start + speechLength * (segment + 1) / SEGMENTS;
            int length = Math.max(1, segmentEnd - segmentStart);

            double energy = rms(pcm, segmentStart, length);
            energyEnvelope[segment] = Math.log1p(energy);
            zcrValues[segment] = zeroCrossingRate(pcm, segmentStart, length);
            pitchValues[segment] = estimatePitch(
                pcm,
                segmentStart,
                length,
                sampleRate
            );

            double totalBandPower = 0.0;
            for (int band = 0; band < bands; band++) {
                double power = goertzelPower(
                    pcm,
                    segmentStart,
                    length,
                    sampleRate,
                    BAND_FREQUENCIES[band]
                );
                bandValues[segment][band] = power;
                totalBandPower += power;
            }
            totalBandPower = Math.max(totalBandPower, 1e-9);
            for (int band = 0; band < bands; band++) {
                bandValues[segment][band] = Math.log(
                    1e-9 + bandValues[segment][band] / totalBandPower
                );
            }
        }

        float[] vector = new float[bands * 2 + 4 + 8];
        int index = 0;
        for (int band = 0; band < bands; band++) {
            double mean = 0.0;
            for (int segment = 0; segment < SEGMENTS; segment++) {
                mean += bandValues[segment][band];
            }
            mean /= SEGMENTS;
            vector[index++] = (float) (mean / 8.0);
        }
        for (int band = 0; band < bands; band++) {
            double mean = 0.0;
            for (int segment = 0; segment < SEGMENTS; segment++) {
                mean += bandValues[segment][band];
            }
            mean /= SEGMENTS;
            double variance = 0.0;
            for (int segment = 0; segment < SEGMENTS; segment++) {
                double delta = bandValues[segment][band] - mean;
                variance += delta * delta;
            }
            variance /= SEGMENTS;
            vector[index++] = (float) (Math.sqrt(variance) / 4.0);
        }

        double pitchMean = meanPositive(pitchValues);
        double pitchStd = stdPositive(pitchValues, pitchMean);
        double zcrMean = mean(zcrValues);
        vector[index++] = (float) (pitchMean / 350.0);
        vector[index++] = (float) (pitchStd / 160.0);
        vector[index++] = (float) zcrMean;
        vector[index++] = (float) std(zcrValues, zcrMean);

        double envelopeMean = mean(energyEnvelope);
        double envelopeStd = Math.max(1e-6, std(energyEnvelope, envelopeMean));
        for (int i = 0; i < 8; i++) {
            int from = i * SEGMENTS / 8;
            int to = Math.max(from + 1, (i + 1) * SEGMENTS / 8);
            double value = 0.0;
            for (int segment = from; segment < to; segment++) {
                value += energyEnvelope[segment];
            }
            value /= (to - from);
            vector[index++] = (float) ((value - envelopeMean) / envelopeStd);
        }

        normalize(vector);
        return vector;
    }

    static float[] mean(List<float[]> samples) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        int size = samples.get(0).length;
        float[] result = new float[size];
        for (float[] sample : samples) {
            if (sample == null || sample.length != size) {
                return null;
            }
            for (int i = 0; i < size; i++) {
                result[i] += sample[i];
            }
        }
        for (int i = 0; i < size; i++) {
            result[i] /= samples.size();
        }
        normalize(result);
        return result;
    }

    static float similarity(float[] first, float[] second) {
        if (first == null || second == null || first.length != second.length) {
            return -1f;
        }
        double dot = 0.0;
        double normFirst = 0.0;
        double normSecond = 0.0;
        for (int i = 0; i < first.length; i++) {
            dot += first[i] * second[i];
            normFirst += first[i] * first[i];
            normSecond += second[i] * second[i];
        }
        if (normFirst <= 0.0 || normSecond <= 0.0) {
            return -1f;
        }
        return (float) (dot / Math.sqrt(normFirst * normSecond));
    }

    private static double rms(short[] samples, int start, int length) {
        int end = Math.min(samples.length, start + length);
        if (start < 0 || start >= end) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = start; i < end; i++) {
            double value = samples[i];
            sum += value * value;
        }
        return Math.sqrt(sum / (end - start));
    }

    private static double zeroCrossingRate(
            short[] samples,
            int start,
            int length) {
        int end = Math.min(samples.length, start + length);
        if (start < 0 || start + 1 >= end) {
            return 0.0;
        }
        int crossings = 0;
        short previous = samples[start];
        for (int i = start + 1; i < end; i++) {
            short current = samples[i];
            if ((previous < 0 && current >= 0)
                    || (previous >= 0 && current < 0)) {
                crossings++;
            }
            previous = current;
        }
        return crossings / (double) (end - start - 1);
    }

    private static double estimatePitch(
            short[] samples,
            int start,
            int length,
            int sampleRate) {
        int end = Math.min(samples.length, start + length);
        int available = end - start;
        if (available < 300) {
            return 0.0;
        }

        int minimumLag = Math.max(1, sampleRate / 400);
        int maximumLag = Math.min(available / 2, sampleRate / 75);
        double bestCorrelation = 0.0;
        int bestLag = 0;

        for (int lag = minimumLag; lag <= maximumLag; lag++) {
            double cross = 0.0;
            double energyA = 0.0;
            double energyB = 0.0;
            for (int i = start + lag; i < end; i += 2) {
                double a = samples[i];
                double b = samples[i - lag];
                cross += a * b;
                energyA += a * a;
                energyB += b * b;
            }
            double denominator = Math.sqrt(energyA * energyB);
            if (denominator <= 0.0) {
                continue;
            }
            double correlation = cross / denominator;
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestLag = lag;
            }
        }

        if (bestLag == 0 || bestCorrelation < 0.28) {
            return 0.0;
        }
        return sampleRate / (double) bestLag;
    }

    private static double goertzelPower(
            short[] samples,
            int start,
            int length,
            int sampleRate,
            double frequency) {
        int end = Math.min(samples.length, start + length);
        if (start < 0 || start >= end) {
            return 0.0;
        }
        double coefficient = 2.0 * Math.cos(
            2.0 * Math.PI * frequency / sampleRate
        );
        double s0;
        double s1 = 0.0;
        double s2 = 0.0;
        double previousInput = 0.0;

        for (int i = start; i < end; i++) {
            double input = samples[i] / 32768.0;
            double emphasized = input - 0.97 * previousInput;
            previousInput = input;
            s0 = emphasized + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return Math.max(0.0, s1 * s1 + s2 * s2 - coefficient * s1 * s2);
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double std(double[] values, double mean) {
        if (values.length == 0) {
            return 0.0;
        }
        double variance = 0.0;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / values.length);
    }

    private static double meanPositive(double[] values) {
        double sum = 0.0;
        int count = 0;
        for (double value : values) {
            if (value > 0.0) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static double stdPositive(double[] values, double mean) {
        double variance = 0.0;
        int count = 0;
        for (double value : values) {
            if (value > 0.0) {
                double delta = value - mean;
                variance += delta * delta;
                count++;
            }
        }
        return count == 0 ? 0.0 : Math.sqrt(variance / count);
    }

    private static void normalize(float[] vector) {
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm <= 1e-9) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}
