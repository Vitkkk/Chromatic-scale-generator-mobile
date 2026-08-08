package com.vitkkk.chromatic.audio;

import java.util.Arrays;

/**
 * Optional post-process used only when the user explicitly requests a fixed
 * note duration. The Praat pitch engine always runs first at its natural
 * desktop duration, so this class can never change pitch analysis or cause an
 * octave decision inside Praat.
 */
final class DurationFitter {
    private DurationFitter() {}

    static float[] fit(float[] input, int targetLength, int sampleRate) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("Áudio vazio para ajustar a duração.");
        }
        if (targetLength <= 0 || targetLength == input.length) return input.clone();
        if (targetLength < input.length) return Arrays.copyOf(input, targetLength);

        // Very short or nearly silent samples are safer with zero padding than
        // with an invented repeating period.
        if (input.length < Math.max(128, sampleRate / 50) || peak(input) < 1e-5f) {
            return Arrays.copyOf(input, targetLength);
        }

        int[] loop;
        try {
            loop = DwpExporter.chooseLoopPoints(input, 0, input.length, sampleRate, 35, 90);
        } catch (RuntimeException ignored) {
            return Arrays.copyOf(input, targetLength);
        }

        int loopStart = loop[0];
        int loopEnd = loop[1];
        int loopLength = loopEnd - loopStart;
        int tailLength = input.length - loopEnd;
        if (loopStart < 0 || loopLength < 64 || tailLength < 0
                || targetLength <= loopStart + tailLength + 64) {
            return Arrays.copyOf(input, targetLength);
        }

        float[] output = new float[targetLength];
        int tailStart = targetLength - tailLength;
        int firstPassEnd = Math.min(loopEnd, tailStart);
        System.arraycopy(input, 0, output, 0, firstPassEnd);

        int crossfade = Math.min(Math.max(16, sampleRate / 250), loopLength / 4);
        for (int index = firstPassEnd; index < targetLength; index++) {
            int phase = Math.floorMod(index - loopStart, loopLength);
            if (phase < crossfade) {
                float fromEnd = input[loopEnd - crossfade + phase];
                float fromStart = input[loopStart + phase];
                float mix = (phase + 1.0f) / (crossfade + 1.0f);
                output[index] = fromEnd * (1.0f - mix) + fromStart * mix;
            } else {
                output[index] = input[loopStart + phase];
            }
        }

        // Restore the natural release/consonant at the end. Blend into it so a
        // custom duration does not introduce a click at the loop-to-tail join.
        int tailCrossfade = Math.min(crossfade, tailLength);
        for (int i = 0; i < tailLength; i++) {
            int outputIndex = tailStart + i;
            float tailSample = input[loopEnd + i];
            if (i < tailCrossfade) {
                float mix = (i + 1.0f) / (tailCrossfade + 1.0f);
                output[outputIndex] = output[outputIndex] * (1.0f - mix) + tailSample * mix;
            } else {
                output[outputIndex] = tailSample;
            }
        }
        return output;
    }

    private static float peak(float[] samples) {
        float peak = 0.0f;
        for (float sample : samples) peak = Math.max(peak, Math.abs(sample));
        return peak;
    }
}
