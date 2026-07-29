package com.vitkkk.chromatic.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PitchShifterTest {
    private static final int SAMPLE_RATE = 48000;
    private static final double SOURCE_FREQUENCY = 120.0;

    @Test
    public void reachesTargetPitchAndKeepsRequestedLength() {
        float[] source = harmonicVoice(SOURCE_FREQUENCY, 0.6);
        double[] factors = {0.5, 0.75, 1.5, 2.0, 4.0};

        for (double factor : factors) {
            double target = SOURCE_FREQUENCY * factor;
            float[] shifted = PitchShifter.shift(source, SOURCE_FREQUENCY, target,
                    SAMPLE_RATE, source.length);

            assertEquals(source.length, shifted.length);
            double measured = measurePitchNear(shifted, target);
            assertTrue("Pitch não detectado para fator " + factor, Double.isFinite(measured));
            assertEquals("Pitch incorreto para fator " + factor,
                    target, measured, Math.max(1.5, target * 0.018));
        }
    }

    @Test
    public void createsExactCustomDuration() {
        float[] source = harmonicVoice(SOURCE_FREQUENCY, 0.35);
        int requestedLength = SAMPLE_RATE * 2 / 5;

        float[] shifted = PitchShifter.shift(source, SOURCE_FREQUENCY, 180.0,
                SAMPLE_RATE, requestedLength);

        assertEquals(requestedLength, shifted.length);
        for (float sample : shifted) {
            assertTrue(Float.isFinite(sample));
            assertTrue(Math.abs(sample) <= 2.0f);
        }
    }

    private static double measurePitchNear(float[] audio, double expectedFrequency) {
        int start = audio.length / 4;
        int end = audio.length * 3 / 4;
        int minLag = Math.max(2, (int) Math.floor(SAMPLE_RATE / (expectedFrequency * 1.30)));
        int maxLag = Math.min(end - start - 1,
                (int) Math.ceil(SAMPLE_RATE / (expectedFrequency * 0.70)));

        double bestCorrelation = -Double.MAX_VALUE;
        int bestLag = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double dot = 0.0;
            double energyA = 1e-12;
            double energyB = 1e-12;
            for (int i = start; i + lag < end; i++) {
                double a = audio[i];
                double b = audio[i + lag];
                dot += a * b;
                energyA += a * a;
                energyB += b * b;
            }
            double correlation = dot / Math.sqrt(energyA * energyB);
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestLag = lag;
            }
        }
        return bestLag > 0 ? SAMPLE_RATE / (double) bestLag : Double.NaN;
    }

    private static float[] harmonicVoice(double frequency, double seconds) {
        int length = (int) Math.round(seconds * SAMPLE_RATE);
        float[] audio = new float[length];
        int attack = Math.min(length / 4, SAMPLE_RATE / 50);

        for (int i = 0; i < length; i++) {
            double phase = 2.0 * Math.PI * frequency * i / SAMPLE_RATE;
            double envelope = attack <= 1 || i >= attack ? 1.0 : i / (double) attack;
            audio[i] = (float) (envelope * (
                    0.68 * Math.sin(phase)
                            + 0.22 * Math.sin(2.0 * phase)
                            + 0.10 * Math.sin(3.0 * phase)));
        }
        return audio;
    }
}
