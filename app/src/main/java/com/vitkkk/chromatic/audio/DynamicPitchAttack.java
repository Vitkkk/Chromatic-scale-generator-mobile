package com.vitkkk.chromatic.audio;

import java.util.Arrays;

/** Adds a short original-pitch attack before a smoothly crossfaded tuned body. */
public final class DynamicPitchAttack {
    private DynamicPitchAttack() {}

    /**
     * Keeps the original signal for {@code holdSamples}, then crossfades to the tuned
     * signal over {@code glideSamples}. Requested durations are safely shortened when
     * the note itself is shorter, while always leaving the final sample fully tuned.
     */
    public static float[] apply(float[] original, float[] tuned,
                                int holdSamples, int glideSamples) {
        if (original == null || tuned == null || original.length != tuned.length) {
            throw new IllegalArgumentException("Os sinais original e afinado precisam ter a mesma duração.");
        }
        if (holdSamples < 0 || glideSamples < 0) {
            throw new IllegalArgumentException("As durações do ataque dinâmico não podem ser negativas.");
        }
        int length = tuned.length;
        if (length == 0) return new float[0];
        if (length == 1) return new float[]{tuned[0]};

        int hold = Math.min(holdSamples, length - 1);
        int remaining = length - hold;
        int glide = Math.min(glideSamples, Math.max(0, remaining - 1));

        if (glideSamples > 0 && glide < 2 && length >= 3) {
            hold = Math.min(hold, length - 3);
            glide = Math.min(glideSamples, length - hold - 1);
        }

        float[] output = Arrays.copyOf(tuned, length);
        if (hold > 0) System.arraycopy(original, 0, output, 0, hold);

        if (glide > 0) {
            for (int i = 0; i < glide; i++) {
                int index = hold + i;
                double linear = glide <= 1 ? 1.0 : i / (double) (glide - 1);
                double mix = smoothStep(linear);
                output[index] = (float) (original[index] * (1.0 - mix) + tuned[index] * mix);
            }
        }
        return output;
    }

    /** Maps the unprocessed source to the requested note duration without changing pitch. */
    public static float[] timeMapLinear(float[] input, int targetLength) {
        if (input == null) throw new IllegalArgumentException("O sample original não pode ser nulo.");
        if (targetLength < 0) throw new IllegalArgumentException("Duração de saída inválida.");
        float[] output = new float[targetLength];
        if (targetLength == 0 || input.length == 0) return output;
        if (input.length == 1) {
            Arrays.fill(output, input[0]);
            return output;
        }
        if (targetLength == 1) {
            output[0] = input[0];
            return output;
        }

        double scale = (input.length - 1) / (double) (targetLength - 1);
        for (int i = 0; i < targetLength; i++) {
            double source = i * scale;
            int left = Math.min(input.length - 1, (int) source);
            int right = Math.min(input.length - 1, left + 1);
            float fraction = (float) (source - left);
            output[i] = input[left] + (input[right] - input[left]) * fraction;
        }
        return output;
    }

    private static double smoothStep(double value) {
        double x = Math.max(0.0, Math.min(1.0, value));
        return x * x * (3.0 - 2.0 * x);
    }
}
