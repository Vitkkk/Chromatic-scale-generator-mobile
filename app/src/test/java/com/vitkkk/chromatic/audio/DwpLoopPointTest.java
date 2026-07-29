package com.vitkkk.chromatic.audio;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DwpLoopPointTest {
    @Test
    public void choosesOrderedLoopPointsNearRequestedRegion() {
        int sampleRate = 48000;
        float[] audio = new float[sampleRate];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) (0.6 * Math.sin(2.0 * Math.PI * 220.0 * i / sampleRate));
        }

        int[] loop = DwpExporter.chooseLoopPoints(audio, 0, audio.length,
                sampleRate, 35, 90);

        assertTrue(loop[0] >= audio.length * 0.30);
        assertTrue(loop[0] <= audio.length * 0.40);
        assertTrue(loop[1] >= audio.length * 0.84);
        assertTrue(loop[1] <= audio.length * 0.96);
        assertTrue(loop[1] - loop[0] >= sampleRate / 20);

        float start = audio[loop[0]];
        float end = audio[loop[1]];
        assertTrue(Math.abs(start - end) < 0.08f);
    }

    @Test
    public void supportsShortestAllowedChromaticNote() {
        int sampleRate = 48000;
        float[] audio = new float[1920];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) Math.sin(2.0 * Math.PI * 300.0 * i / sampleRate);
        }

        int[] loop = DwpExporter.chooseLoopPoints(audio, 0, audio.length,
                sampleRate, 35, 90);

        assertTrue(loop[0] >= 0);
        assertTrue(loop[1] > loop[0]);
        assertTrue(loop[1] <= audio.length);
    }
}
