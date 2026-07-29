package com.vitkkk.chromatic.audio;

import java.util.Arrays;

public final class PitchShifter {
    private static final double MIN_STAGE_FACTOR = 0.5;
    private static final double MAX_STAGE_FACTOR = 2.0;

    private PitchShifter() {}

    /**
     * Shifts a monophonic vocal while keeping its vocal-tract resonances (formants)
     * substantially fixed. Large shifts are split into octave-sized stages so TD-PSOLA
     * remains stable across the full chromatic range.
     */
    public static float[] shift(float[] input, double sourceFrequency, double targetFrequency,
                                int sampleRate, int targetLength) {
        if (input.length == 0 || targetLength <= 0) {
            return new float[Math.max(0, targetLength)];
        }
        if (!Double.isFinite(sourceFrequency) || sourceFrequency <= 0.0
                || !Double.isFinite(targetFrequency) || targetFrequency <= 0.0
                || sampleRate <= 0) {
            throw new IllegalArgumentException("Frequência de pitch inválida.");
        }

        float[] current = Arrays.copyOf(input, input.length);
        double currentFrequency = sourceFrequency;
        double remainingFactor = targetFrequency / currentFrequency;

        while (remainingFactor > MAX_STAGE_FACTOR) {
            double nextFrequency = currentFrequency * MAX_STAGE_FACTOR;
            current = shiftFormantPreservingOnce(current, currentFrequency, nextFrequency,
                    sampleRate, current.length);
            currentFrequency = nextFrequency;
            remainingFactor = targetFrequency / currentFrequency;
        }
        while (remainingFactor < MIN_STAGE_FACTOR) {
            double nextFrequency = currentFrequency * MIN_STAGE_FACTOR;
            current = shiftFormantPreservingOnce(current, currentFrequency, nextFrequency,
                    sampleRate, current.length);
            currentFrequency = nextFrequency;
            remainingFactor = targetFrequency / currentFrequency;
        }

        return shiftFormantPreservingOnce(current, currentFrequency, targetFrequency,
                sampleRate, targetLength);
    }

    /**
     * Legacy overload retained for compatibility. It does not have the source F0 needed
     * for formant-preserving TD-PSOLA, so it uses the former resample + SOLA path.
     */
    public static float[] shift(float[] input, double pitchFactor, int targetLength) {
        if (!Double.isFinite(pitchFactor) || pitchFactor <= 0.0) {
            throw new IllegalArgumentException("Fator de pitch inválido.");
        }
        return legacyShift(input, pitchFactor, targetLength);
    }

    private static float[] shiftFormantPreservingOnce(float[] input, double sourceFrequency,
                                                        double targetFrequency, int sampleRate,
                                                        int targetLength) {
        double factor = targetFrequency / sourceFrequency;
        factor = Math.max(MIN_STAGE_FACTOR, Math.min(MAX_STAGE_FACTOR, factor));

        double sourcePeriod = sampleRate / sourceFrequency;
        if (sourcePeriod < 12.0 || sourcePeriod > input.length / 2.0) {
            return legacyShift(input, factor, targetLength);
        }

        int[] marks = findPitchMarks(input, sourcePeriod);
        if (marks.length < 3) {
            return legacyShift(input, factor, targetLength);
        }

        double targetPeriod = sampleRate / targetFrequency;
        float[] output = new float[targetLength];
        float[] weights = new float[targetLength];
        double timelineScale = input.length / (double) targetLength;
        double outputMark = Math.max(0.0, marks[0] / timelineScale);

        while (outputMark < targetLength) {
            double desiredSourcePosition = outputMark * timelineScale;
            int markIndex = nearestMark(marks, desiredSourcePosition);
            int sourceMark = marks[markIndex];

            int leftPeriod = markIndex > 0
                    ? sourceMark - marks[markIndex - 1]
                    : (int) Math.round(sourcePeriod);
            int rightPeriod = markIndex + 1 < marks.length
                    ? marks[markIndex + 1] - sourceMark
                    : (int) Math.round(sourcePeriod);

            leftPeriod = clampPeriod(leftPeriod, sourcePeriod);
            rightPeriod = clampPeriod(rightPeriod, sourcePeriod);

            int sourceStart = Math.max(0, sourceMark - leftPeriod);
            int sourceEnd = Math.min(input.length, sourceMark + rightPeriod + 1);
            int outputCenter = (int) Math.round(outputMark);
            int outputStart = outputCenter - (sourceMark - sourceStart);

            for (int sourceIndex = sourceStart; sourceIndex < sourceEnd; sourceIndex++) {
                int outputIndex = outputStart + (sourceIndex - sourceStart);
                if (outputIndex < 0 || outputIndex >= targetLength) continue;

                float window = pitchSynchronousWindow(sourceIndex, sourceStart, sourceMark, sourceEnd);
                output[outputIndex] += input[sourceIndex] * window;
                weights[outputIndex] += window;
            }
            outputMark += targetPeriod;
        }

        // Unvoiced attacks, consonants and edge regions are safer through the old path.
        // Blend only where PSOLA has insufficient overlap, keeping the voiced body natural.
        float[] fallback = legacyShift(input, factor, targetLength);
        for (int i = 0; i < targetLength; i++) {
            float weight = weights[i];
            if (weight >= 0.65f) {
                output[i] /= weight;
            } else if (weight > 1e-5f) {
                float psola = output[i] / weight;
                float blend = weight / 0.65f;
                output[i] = psola * blend + fallback[i] * (1.0f - blend);
            } else {
                output[i] = fallback[i];
            }
        }
        return output;
    }

    private static int[] findPitchMarks(float[] input, double nominalPeriod) {
        int period = Math.max(12, (int) Math.round(nominalPeriod));
        int radius = Math.max(3, (int) Math.round(nominalPeriod * 0.30));
        int anchorSearchEnd = Math.min(input.length, Math.max(period * 6, input.length / 5));
        if (anchorSearchEnd <= 0) return new int[0];

        int anchor = strongestPeak(input, 0, anchorSearchEnd);
        int estimatedCount = Math.max(4, (int) Math.ceil(input.length / nominalPeriod) + 4);
        int[] forward = new int[estimatedCount];
        int forwardCount = 0;
        forward[forwardCount++] = anchor;

        int current = anchor;
        while (current + nominalPeriod < input.length - 1) {
            int expected = (int) Math.round(current + nominalPeriod);
            int next = strongestPeak(input,
                    Math.max(current + period / 2, expected - radius),
                    Math.min(input.length, expected + radius + 1));
            if (next <= current) break;
            if (forwardCount == forward.length) {
                forward = Arrays.copyOf(forward, forward.length * 2);
            }
            forward[forwardCount++] = next;
            current = next;
        }

        int[] backward = new int[estimatedCount];
        int backwardCount = 0;
        current = anchor;
        while (current - nominalPeriod > 0) {
            int expected = (int) Math.round(current - nominalPeriod);
            int previous = strongestPeak(input,
                    Math.max(0, expected - radius),
                    Math.min(current - period / 2 + 1, expected + radius + 1));
            if (previous < 0 || previous >= current) break;
            if (backwardCount == backward.length) {
                backward = Arrays.copyOf(backward, backward.length * 2);
            }
            backward[backwardCount++] = previous;
            current = previous;
        }

        int[] marks = new int[backwardCount + forwardCount];
        int out = 0;
        for (int i = backwardCount - 1; i >= 0; i--) marks[out++] = backward[i];
        System.arraycopy(forward, 0, marks, out, forwardCount);
        return marks;
    }

    private static int strongestPeak(float[] input, int start, int endExclusive) {
        start = Math.max(0, start);
        endExclusive = Math.min(input.length, endExclusive);
        if (start >= endExclusive) return -1;

        int best = start;
        float bestValue = -1.0f;
        for (int i = start; i < endExclusive; i++) {
            float value = Math.abs(input[i]);
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    private static int nearestMark(int[] marks, double position) {
        int low = 0;
        int high = marks.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (marks[mid] < position) low = mid + 1;
            else high = mid - 1;
        }
        if (low <= 0) return 0;
        if (low >= marks.length) return marks.length - 1;
        return position - marks[low - 1] <= marks[low] - position ? low - 1 : low;
    }

    private static int clampPeriod(int period, double nominalPeriod) {
        int min = Math.max(8, (int) Math.round(nominalPeriod * 0.55));
        int max = Math.max(min + 1, (int) Math.round(nominalPeriod * 1.45));
        return Math.max(min, Math.min(max, period));
    }

    private static float pitchSynchronousWindow(int index, int start, int center,
                                                  int endExclusive) {
        if (index <= center) {
            int length = Math.max(1, center - start);
            double phase = (index - start) / (double) length;
            return (float) (0.5 - 0.5 * Math.cos(Math.PI * phase));
        }
        int length = Math.max(1, endExclusive - 1 - center);
        double phase = (index - center) / (double) length;
        return (float) (0.5 + 0.5 * Math.cos(Math.PI * phase));
    }

    private static float[] legacyShift(float[] input, double pitchFactor, int targetLength) {
        if (input.length == 0 || targetLength <= 0) {
            return new float[Math.max(0, targetLength)];
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
        if (targetLength <= 0) return new float[0];
        if (input.length < 128) return repeatToLength(input, targetLength);

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

            int best = findBestOverlap(output, outputPosition, source,
                    minCandidate, maxCandidate, overlap);
            int overlapLength = Math.min(overlap, targetLength - outputPosition);
            for (int i = 0; i < overlapLength; i++) {
                float alpha = overlapLength <= 1 ? 1.0f : i / (float) (overlapLength - 1);
                output[outputPosition + i] = output[outputPosition + i] * (1.0f - alpha)
                        + source[best + i] * alpha;
            }

            int copyStart = overlapLength;
            int copyLength = Math.min(frame - copyStart,
                    targetLength - outputPosition - copyStart);
            if (copyLength > 0) {
                System.arraycopy(source, best + copyStart, output,
                        outputPosition + copyStart, copyLength);
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
        if (input.length == 0) return output;
        for (int i = 0; i < length; i++) output[i] = input[i % input.length];
        return output;
    }
}
