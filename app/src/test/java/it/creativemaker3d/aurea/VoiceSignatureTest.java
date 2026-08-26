package it.creativemaker3d.aurea;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class VoiceSignatureTest {
    private static final int SAMPLE_RATE = 16000;

    @Test
    public void silenceIsRejected() {
        VoiceSignature.Analysis result = VoiceSignature.analyze(
            new short[SAMPLE_RATE * 3], SAMPLE_RATE
        );
        assertTrue(!result.accepted());
    }

    @Test
    public void trimsSilenceAndAcceptsClearSpeech() {
        VoiceSignature.Analysis result = VoiceSignature.analyze(
            syntheticPhrase(126d, 0.0d, 0.01d), SAMPLE_RATE
        );
        assertTrue(result.message, result.accepted());
        assertNotNull(result.signature);
        assertTrue(result.speechSeconds > 1.0f);
        assertTrue(result.snrDb > 7.5f);
    }

    @Test
    public void sameSpeakerScoresAboveDifferentPitchProfile() {
        float[] first = VoiceSignature.analyze(
            syntheticPhrase(126d, 0.0d, 0.012d), SAMPLE_RATE
        ).signature;
        float[] second = VoiceSignature.analyze(
            syntheticPhrase(131d, 0.35d, 0.014d), SAMPLE_RATE
        ).signature;
        float[] different = VoiceSignature.analyze(
            syntheticPhrase(224d, 0.18d, 0.010d), SAMPLE_RATE
        ).signature;
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(different);
        float sameScore = VoiceSignature.similarity(first, second);
        float differentScore = VoiceSignature.similarity(first, different);
        assertTrue(
            "same=" + sameScore + " different=" + differentScore,
            sameScore > differentScore
        );
        assertTrue(VoiceSignature.calibratedThreshold(
            Arrays.asList(first, second, first.clone(), second.clone())
        ) >= 0.72f);
    }

    private static short[] syntheticPhrase(
            double fundamental,
            double phase,
            double noiseRatio) {
        int total = SAMPLE_RATE * 4;
        short[] pcm = new short[total];
        int start = SAMPLE_RATE / 2;
        int end = start + SAMPLE_RATE * 5 / 2;
        long seed = 17L;
        for (int index = start; index < end; index++) {
            double time = (index - start) / (double) SAMPLE_RATE;
            double syllables = 0.62d
                + 0.30d * Math.sin(2d * Math.PI * 2.4d * time)
                + 0.08d * Math.sin(2d * Math.PI * 4.8d * time);
            double signal = Math.sin(2d * Math.PI * fundamental * time + phase)
                + 0.55d * Math.sin(2d * Math.PI * fundamental * 2d * time + phase)
                + 0.32d * Math.sin(2d * Math.PI * 720d * time)
                + 0.22d * Math.sin(2d * Math.PI * 1180d * time);
            seed = seed * 1103515245L + 12345L;
            double noise = (((seed >>> 16) & 0x7fff) / 16384d - 1d) * noiseRatio;
            pcm[index] = (short) Math.max(
                Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, (signal * syllables + noise) * 5200d)
            );
        }
        return pcm;
    }
}
