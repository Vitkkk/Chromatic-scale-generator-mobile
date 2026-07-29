package com.vitkkk.chromatic.audio;

import java.util.Arrays;

public final class PitchShifter {
    private PitchShifter() {}

    public static float[] shift(float[] input, double pitchFactor, int targetLength) {
        if (input.length == 0 || targetLength <= 0) {
            return new float[Math.max(0, targetLength)];
        }
        if (!Double.isFinite(pitchFactor) || pitchFactor <= 0.0) {
            throw new IllegalArgumentException("Fator de pitch inválido.");
        }
        pitchFactor = Math.max(0.10, Math.min(10.0, pitchFactor));
        float[] pitched = resampleForPitch(input, pitchFactor);
        if (pitched.length >= targetLength) {
            return Arrays.copyOf(pitched, targetLength);
        }
        return timeStretchSola(pitched, targetLength);
    }

    private static float[] resampleForPitch(float[] input, double factor) {
        int outputLength = Math.max(64, (int) Math.round(input.length / factor));
        float[] output = new float[outputLength];
        for (int i = 0; i < outputLength; i++) {
            double source = i * factor;
            int left = Math.min(input.length - 1, (int) source);
            int right = Math.min(input.length - 1, left + 1);
            float fraction = (float) (source - left);
            output[i] = input[left] + (input[right] - input[left]) * fraction;
        }
        return output;
    }

    private static float[] timeStretchSola(float[] input, int targetLength) {
        if (targetLength <= 0) {
            return new float[0];
        }
        if (input.length < 128) {
            return repeatToLength(input, targetLength);
        }

        int frame = chooseFrameSize(input.length);
        int overlap = frame / 4;
        int synthesisHop = frame - overlap;
        int search = Math.max(32, overlap / 2);
        double stretch = targetLength / (double) input.length;
        double analysisHop = synthesisHop / Math.max(0.05, stretch);

        float[] source = input;
        if (source.length < frame + search + 1) {
            source = repeatToLength(source, frame + search + 1);
        }

        float[] output = new float[targetLength + frame];
        int firstCopy = Math.min(frame, targetLength);
        System.arraycopy(source, 0, output, 0, firstCopy);

        double sourcePosition = analysisHop;
        int outputPosition = synthesisHop;

        while (outputPosition < targetLength) {
            int expected = (int) Math.round(sourcePosition);
            int maxStart = Math.max(0, source.length - frame);
            int minCandidate = Math.max(0, expected - search);
            int maxCandidate = Math.min(maxStart, expected + search);
            if (minCandidate > maxCandidate) {
                minCandidate = maxCandidate = Math.max(0, Math.min(maxStart, expected));
            }

            int best = findBestOverlap(output, outputPosition, source, minCandidate, maxCandidate, overlap);
            int overlapLength = Math.min(overlap, targetLength - outputPosition);
            for (int i = 0; i < overlapLength; i++) {
                float alpha = overlapLength <= 1 ? 1.0f : i / (float) (overlapLength - 1);
                output[outputPosition + i] = output[outputPosition + i] * (1.0f - alpha)
                        + source[best + i] * alpha;
            }

            int copyStart = overlapLength;
            int copyLength = Math.min(frame - copyStart, targetLength - outputPosition - copyStart);
            if (copyLength > 0) {
                System.arraycopy(source, best + copyStart, output, outputPosition + copyStart, copyLength);
            }

            sourcePosition = best + analysisHop;
            outputPosition += synthesisHop;
            if (sourcePosition + frame >= source.length && outputPosition < targetLength) {
                sourcePosition = Math.max(0.0, source.length - frame - search);
            }
        }
        return Arrays.copyOf(output, targetLength);
    }

    private static int findBestOverlap(float[] output, int outputPosition, float[] input,
                                       int minCandidate, int maxCandidate, int overlap) {
        int best = minCandidate;
        double bestScore = -Double.MAX_VALUE;
        int step = maxCandidate - minCandidate > 512 ? 2 : 1;

        for (int candidate = minCandidate; candidate <= maxCandidate; candidate += step) {
            double dot = 0.0;
            double energyA = 1e-9;
            double energyB = 1e-9;
            for (int i = 0; i < overlap; i++) {
                float a = output[outputPosition + i];
                float b = input[candidate + i];
                dot += a * b;
                energyA += a * a;
                energyB += b * b;
            }
            double score = dot / Math.sqrt(energyA * energyB);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int chooseFrameSize(int length) {
        if (length >= 8192) return 2048;
        if (length >= 4096) return 1024;
        if (length >= 2048) return 512;
        return 256;
    }

    private static float[] repeatToLength(float[] input, int length) {
        float[] output = new float[length];
        if (input.length == 0) {
            return output;
        }
        for (int i = 0; i < length; i++) {
            output[i] = input[i % input.length];
        }
        return output;
    }
}
