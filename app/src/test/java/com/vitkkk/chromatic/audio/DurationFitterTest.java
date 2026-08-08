package com.vitkkk.chromatic.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DurationFitterTest {
    private static final int SAMPLE_RATE = 48000;

    @Test
    public void zeroDurationKeepsNaturalLengthAndSamples() {
        float[] input = sine(220.0, 0.18);
        float[] output = DurationFitter.fit(input, 0, SAMPLE_RATE);

        assertEquals(input.length, output.length);
        for (int i = 0; i < input.length; i++) {
            assertEquals(input[i], output[i], 0.0f);
        }
    }

    @Test
    public void customLongDurationHasExactLengthAndFiniteAudio() {
        float[] input = sine(180.0, 0.22);
        int target = SAMPLE_RATE;
        float[] output = DurationFitter.fit(input, target, SAMPLE_RATE);

        assertEquals(target, output.length);
        float peak = 0.0f;
        for (float sample : output) {
            assertTrue(Float.isFinite(sample));
            peak = Math.max(peak, Math.abs(sample));
        }
        assertTrue(peak > 0.2f);
        assertTrue(peak <= 1.0f);
    }

    @Test
    public void customShortDurationOnlyCrops() {
        float[] input = sine(240.0, 0.30);
        int target = SAMPLE_RATE / 10;
        float[] output = DurationFitter.fit(input, target, SAMPLE_RATE);

        assertEquals(target, output.length);
        for (int i = 0; i < target; i++) {
            assertEquals(input[i], output[i], 0.0f);
        }
    }

    private static float[] sine(double frequency, double seconds) {
        int length = (int) Math.round(SAMPLE_RATE * seconds);
        float[] output = new float[length];
        for (int i = 0; i < length; i++) {
            output[i] = (float) (0.75 * Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE));
        }
        return output;
    }
}
