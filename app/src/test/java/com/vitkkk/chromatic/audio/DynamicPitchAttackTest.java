package com.vitkkk.chromatic.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DynamicPitchAttackTest {
    @Test
    public void preservesOriginalAttackThenReachesTunedSignal() {
        float[] original = new float[1000];
        float[] tuned = new float[1000];
        for (int i = 0; i < original.length; i++) {
            original[i] = (float) Math.sin(2.0 * Math.PI * i / 100.0);
            tuned[i] = (float) Math.sin(2.0 * Math.PI * i / 50.0);
        }

        float[] output = DynamicPitchAttack.apply(original, tuned, 120, 240);
        assertEquals(tuned.length, output.length);
        for (int i = 0; i < 120; i++) assertEquals(original[i], output[i], 0.0f);
        for (int i = 360; i < output.length; i++) assertEquals(tuned[i], output[i], 0.0f);

        for (float sample : output) assertTrue(Float.isFinite(sample));
    }

    @Test
    public void safelyFitsDurationsInsideVeryShortNotes() {
        float[] original = new float[16];
        float[] tuned = new float[16];
        for (int i = 0; i < tuned.length; i++) {
            original[i] = -0.5f;
            tuned[i] = 0.5f;
        }

        float[] output = DynamicPitchAttack.apply(original, tuned, 1000, 1000);
        assertEquals(16, output.length);
        assertEquals(tuned[tuned.length - 1], output[output.length - 1], 0.0f);
    }

    @Test
    public void timeMappingKeepsRequestedLengthAndEndpoints() {
        float[] input = {-1.0f, 0.0f, 1.0f};
        float[] output = DynamicPitchAttack.timeMapLinear(input, 9);
        assertEquals(9, output.length);
        assertEquals(-1.0f, output[0], 0.0f);
        assertEquals(1.0f, output[8], 0.0f);
    }
}
