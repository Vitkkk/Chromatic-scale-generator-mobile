package com.vitkkk.chromatic.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pitch-synchronous overlap-add resynthesis inspired by Praat's Manipulation
 * pipeline. The source is analysed once into a local pitch contour, voiced
 * intervals and correlation-aligned glottal pulses. Each requested note is
 * then resynthesized directly from the original waveform without cascading
 * multiple pitch-shift passes.
 */
public final class PitchShifter {
    private static final double PITCH_FLOOR_HZ = 60.0;
    private static final double PITCH_CEILING_HZ = 600.0;
    private static final double ANALYSIS_STEP_SECONDS = 0.010;
    private static final int ANALYSIS_FRAME_SIZE = 2048;
    private static final double YIN_THRESHOLD = 0.18;
    private static final double VOICED_CONFIDENCE = 0.55;
    private static final double PULSE_CORRELATION_THRESHOLD = 0.16;
    private static final double EDGE_FADE_SECONDS = 0.003;

    private PitchShifter() {}

    /** Reusable Praat-style analysis for one source sample. */
    public static final class Analysis {
        private final float[] audio;
        private final int sampleRate;
        private final double fallbackFrequency;
        private final PitchFrame[] frames;
        private final VoicedInterval[] intervals;
        private final int hop;

        private Analysis(float[] audio, int sampleRate, double fallbackFrequency,
                         PitchFrame[] frames, VoicedInterval[] intervals, int hop) {
            this.audio = audio;
            this.sampleRate = sampleRate;
            this.fallbackFrequency = fallbackFrequency;
            this.frames = frames;
            this.intervals = intervals;
            this.hop = hop;
        }
    }

    private static final class PitchFrame {
        final int center;
        double frequency;
        double confidence;
        boolean voiced;

        PitchFrame(int center, double frequency, double confidence, boolean voiced) {
            this.center = center;
            this.frequency = frequency;
            this.confidence = confidence;
            this.voiced = voiced;
        }
    }

    private static final class PitchEstimate {
        final double frequency;
        final double confidence;
        final double rms;

        PitchEstimate(double frequency, double confidence, double rms) {
            this.frequency = frequency;
            this.confidence = confidence;
            this.rms = rms;
        }
    }

    private static final class VoicedInterval {
        final int start;
        final int end;
        final int[] marks;
        final double pulseQuality;

        VoicedInterval(int start, int end, int[] marks, double pulseQuality) {
            this.start = start;
            this.end = end;
            this.marks = marks;
            this.pulseQuality = pulseQuality;
        }
    }

    private static final class PulseCandidate {
        final int index;
        final double correlation;

        PulseCandidate(int index, double correlation) {
            this.index = index;
            this.correlation = correlation;
        }
    }

    /**
     * Performs the expensive pitch/pulse analysis once. The supplied source
     * frequency is only a fallback; the local contour is not forced to its
     * octave, because that can create period-doubled ("demonic") artefacts.
     */
    public static Analysis analyze(float[] input, double sourceFrequency, int sampleRate) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("O sample não pode estar vazio.");
        }
        if (!Double.isFinite(sourceFrequency) || sourceFrequency <= 0.0 || sampleRate <= 0) {
            throw new IllegalArgumentException("Frequência de pitch inválida.");
        }

        float[] audio = Arrays.copyOf(input, input.length);
        removeDc(audio);
        int hop = Math.max(1, (int) Math.round(sampleRate * ANALYSIS_STEP_SECONDS));
        PitchFrame[] frames = analyzePitchTrack(audio, sampleRate, hop);
        stabilizePitchTrack(frames, sourceFrequency);
        double stableFallback = stableReferenceFrequency(frames, sourceFrequency);
        VoicedInterval[] intervals = buildVoicedIntervals(audio, frames, stableFallback,
                sampleRate, hop);
        return new Analysis(audio, sampleRate, stableFallback, frames, intervals, hop);
    }

    public static float[] shift(float[] input, double sourceFrequency, double targetFrequency,
                                int sampleRate, int targetLength) {
        return shift(analyze(input, sourceFrequency, sampleRate), targetFrequency, targetLength);
    }

    /** Resynthesizes directly from the original analysed sample. */
    public static float[] shift(Analysis analysis, double targetFrequency, int targetLength) {
        if (analysis == null) throw new IllegalArgumentException("Análise de pitch inválida.");
        if (!Double.isFinite(targetFrequency) || targetFrequency <= 0.0 || targetLength <= 0) {
            throw new IllegalArgumentException("Frequência ou duração de saída inválida.");
        }

        float[] input = analysis.audio;
        int sampleRate = analysis.sampleRate;
        double factor = targetFrequency / analysis.fallbackFrequency;

        // Two different fallbacks are intentional:
        // - originalTimeline preserves consonants/unvoiced attacks;
        // - pitchedFallback is already at the target F0 and fills PSOLA holes.
        // Mixing the original waveform into a voiced hole was the cause of the
        // audible source-pitch / octave-down ghost in earlier builds.
        float[] originalTimeline = timeMapLinear(input, targetLength);
        float[] pitchedFallback = legacyShift(input, factor, targetLength);
        if (analysis.intervals.length == 0) return pitchedFallback;

        float[] overlap = new float[targetLength];
        float[] weights = new float[targetLength];
        float[] qualities = new float[targetLength];
        double timeScale = targetLength / (double) input.length;
        double targetPeriod = sampleRate / targetFrequency;
        if (targetPeriod < 2.0 || targetPeriod > targetLength * 2.0) {
            return pitchedFallback;
        }

        for (VoicedInterval interval : analysis.intervals) {
            if (interval.marks.length < 2 || interval.pulseQuality <= 0.02) continue;
            double outputStart = interval.start * timeScale;
            double outputEnd = interval.end * timeScale;
            double targetMark = interval.marks[0] * timeScale;
            while (targetMark - targetPeriod >= outputStart) targetMark -= targetPeriod;
            while (targetMark < outputStart) targetMark += targetPeriod;

            for (; targetMark < outputEnd; targetMark += targetPeriod) {
                double desiredSource = targetMark / timeScale;
                int markIndex = nearestMark(interval.marks, desiredSource);
                int sourceMark = interval.marks[markIndex];

                int localFallback = (int) Math.round(sampleRate /
                        localFrequency(analysis, sourceMark));
                int leftSourcePeriod = markIndex > 0
                        ? sourceMark - interval.marks[markIndex - 1] : localFallback;
                int rightSourcePeriod = markIndex + 1 < interval.marks.length
                        ? interval.marks[markIndex + 1] - sourceMark : localFallback;

                leftSourcePeriod = clampPeriod(leftSourcePeriod, localFallback);
                rightSourcePeriod = clampPeriod(rightSourcePeriod, localFallback);
                int left = Math.max(2, (int) Math.round(Math.min(leftSourcePeriod, targetPeriod)));
                int right = Math.max(2, (int) Math.round(Math.min(rightSourcePeriod, targetPeriod)));
                int outputCenter = (int) Math.round(targetMark);

                for (int offset = -left; offset <= right; offset++) {
                    int sourceIndex = sourceMark + offset;
                    int outputIndex = outputCenter + offset;
                    if (sourceIndex < 0 || sourceIndex >= input.length
                            || outputIndex < 0 || outputIndex >= targetLength) continue;
                    float window = offset <= 0
                            ? raisedCosine(offset + left, left)
                            : raisedCosine(right - offset, right);
                    overlap[outputIndex] += input[sourceIndex] * window;
                    weights[outputIndex] += window;
                    qualities[outputIndex] += window * (float) interval.pulseQuality;
                }
            }
        }

        float[] output = new float[targetLength];
        int edgeFade = Math.max(1, (int) Math.round(sampleRate * EDGE_FADE_SECONDS));
        for (int i = 0; i < targetLength; i++) {
            double sourcePosition = i / timeScale;
            float voicedMix = voicedMixAt(analysis.intervals, sourcePosition, edgeFade);
            if (voicedMix <= 1e-5f) {
                output[i] = originalTimeline[i];
                continue;
            }

            float voicedSignal = pitchedFallback[i];
            if (weights[i] > 1e-5f) {
                float psola = overlap[i] / weights[i];
                float coverage = smoothStep(Math.min(1.0f, weights[i] / 0.52f));
                float quality = Math.max(0.0f,
                        Math.min(1.0f, qualities[i] / Math.max(1e-5f, weights[i])));
                float psolaMix = coverage * quality;
                voicedSignal = psola * psolaMix + pitchedFallback[i] * (1.0f - psolaMix);
            }
            output[i] = voicedSignal * voicedMix + originalTimeline[i] * (1.0f - voicedMix);
        }
        removeDc(output);
        softLimit(output, 1.8f);
        return output;
    }

    /** Legacy overload retained for compatibility with older callers. */
    public static float[] shift(float[] input, double pitchFactor, int targetLength) {
        if (!Double.isFinite(pitchFactor) || pitchFactor <= 0.0) {
            throw new IllegalArgumentException("Fator de pitch inválido.");
        }
        return legacyShift(input, pitchFactor, targetLength);
    }

    private static PitchFrame[] analyzePitchTrack(float[] input, int sampleRate, int hop) {
        int frameCount = Math.max(1, (int) Math.ceil(input.length / (double) hop));
        PitchFrame[] frames = new PitchFrame[frameCount];
        int frameSize = Math.min(ANALYSIS_FRAME_SIZE, input.length);
        if ((frameSize & 1) != 0) frameSize--;
        frameSize = Math.max(512, frameSize);

        for (int index = 0; index < frameCount; index++) {
            int center = Math.min(input.length - 1, index * hop + hop / 2);
            int start = center - frameSize / 2;
            start = Math.max(0, Math.min(Math.max(0, input.length - frameSize), start));
            int actualSize = Math.min(frameSize, input.length - start);
            PitchEstimate estimate = detectPitchFrame(input, start, actualSize, sampleRate);
            boolean voiced = Double.isFinite(estimate.frequency)
                    && estimate.confidence >= VOICED_CONFIDENCE && estimate.rms >= 0.0035;
            frames[index] = new PitchFrame(center, estimate.frequency,
                    estimate.confidence, voiced);
        }
        return frames;
    }

    private static PitchEstimate detectPitchFrame(float[] input, int start, int length,
                                                   int sampleRate) {
        if (length < 512) return new PitchEstimate(Double.NaN, 0.0, 0.0);
        double[] frame = new double[length];
        double mean = 0.0;
        for (int i = 0; i < length; i++) mean += input[start + i];
        mean /= length;

        double energy = 0.0;
        for (int i = 0; i < length; i++) {
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / Math.max(1, length - 1));
            double value = (input[start + i] - mean) * window;
            frame[i] = value;
            energy += value * value;
        }
        double rms = Math.sqrt(energy / length);
        if (rms < 0.0015) return new PitchEstimate(Double.NaN, 0.0, rms);

        int minTau = Math.max(2, (int) Math.floor(sampleRate / PITCH_CEILING_HZ));
        int maxTau = Math.min(length / 2, (int) Math.ceil(sampleRate / PITCH_FLOOR_HZ));
        if (maxTau <= minTau) return new PitchEstimate(Double.NaN, 0.0, rms);

        int comparisonLength = length - maxTau;
        double[] difference = new double[maxTau + 1];
        for (int tau = 1; tau <= maxTau; tau++) {
            double sum = 0.0;
            for (int i = 0; i < comparisonLength; i++) {
                double delta = frame[i] - frame[i + tau];
                sum += delta * delta;
            }
            difference[tau] = sum;
        }

        double running = 0.0;
        double[] normalized = new double[maxTau + 1];
        normalized[0] = 1.0;
        for (int tau = 1; tau <= maxTau; tau++) {
            running += difference[tau];
            normalized[tau] = running <= 1e-15 ? 1.0 : difference[tau] * tau / running;
        }

        int bestTau = -1;
        for (int tau = minTau; tau < maxTau; tau++) {
            if (normalized[tau] < YIN_THRESHOLD) {
                while (tau + 1 <= maxTau && normalized[tau + 1] < normalized[tau]) tau++;
                bestTau = tau;
                break;
            }
        }
        if (bestTau < 0) {
            double bestCost = Double.POSITIVE_INFINITY;
            for (int tau = minTau; tau <= maxTau; tau++) {
                if (tau > minTau && tau < maxTau
                        && normalized[tau] <= normalized[tau - 1]
                        && normalized[tau] <= normalized[tau + 1]) {
                    // A small octave cost, like Praat's, discourages selecting a
                    // doubled period unless it is clearly the stronger candidate.
                    double frequency = sampleRate / (double) tau;
                    double octaveBias = 0.012 * Math.log(PITCH_CEILING_HZ / frequency) / Math.log(2.0);
                    double cost = normalized[tau] + octaveBias;
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestTau = tau;
                    }
                }
            }
            if (bestTau < 0 || normalized[bestTau] > 0.48) {
                double confidence = bestTau < 0 ? 0.0 : Math.max(0.0, 1.0 - normalized[bestTau]);
                return new PitchEstimate(Double.NaN, confidence, rms);
            }
        }

        double refinedTau = bestTau;
        if (bestTau > minTau && bestTau < maxTau) {
            double left = normalized[bestTau - 1];
            double center = normalized[bestTau];
            double right = normalized[bestTau + 1];
            double denominator = left - 2.0 * center + right;
            if (Math.abs(denominator) > 1e-12) {
                refinedTau += 0.5 * (left - right) / denominator;
            }
        }
        double frequency = sampleRate / refinedTau;
        return new PitchEstimate(frequency, 1.0 - normalized[bestTau], rms);
    }

    private static void stabilizePitchTrack(PitchFrame[] frames, double fallbackFrequency) {
        if (frames.length == 0) return;
        double reference = stableReferenceFrequency(frames, fallbackFrequency);

        // Map every raw estimate to the octave nearest the robust local median,
        // rather than blindly forcing it toward the separate global detector.
        for (PitchFrame frame : frames) {
            if (!frame.voiced || !Double.isFinite(frame.frequency)) continue;
            frame.frequency = nearestOctave(frame.frequency, reference);
        }

        // Forward/backward continuity passes suppress isolated half-frequency
        // frames that otherwise make pulse tracking skip every second cycle.
        stabilizePass(frames, reference, false);
        stabilizePass(frames, reference, true);

        double[] original = new double[frames.length];
        for (int i = 0; i < frames.length; i++) original[i] = frames[i].frequency;
        for (int i = 0; i < frames.length; i++) {
            if (!frames[i].voiced) continue;
            double[] values = new double[5];
            int count = 0;
            for (int j = Math.max(0, i - 2); j <= Math.min(frames.length - 1, i + 2); j++) {
                if (frames[j].voiced && Double.isFinite(original[j])) values[count++] = original[j];
            }
            if (count > 0) {
                Arrays.sort(values, 0, count);
                frames[i].frequency = values[count / 2];
            }
        }

        // Join a one-frame hole, but remove isolated one-frame voiced islands.
        for (int i = 1; i + 1 < frames.length; i++) {
            if (!frames[i].voiced && frames[i - 1].voiced && frames[i + 1].voiced) {
                frames[i].voiced = true;
                frames[i].frequency = geometricMean(frames[i - 1].frequency,
                        frames[i + 1].frequency);
                frames[i].confidence = Math.min(frames[i - 1].confidence, frames[i + 1].confidence);
            } else if (frames[i].voiced && !frames[i - 1].voiced && !frames[i + 1].voiced
                    && frames[i].confidence < 0.78) {
                frames[i].voiced = false;
            }
        }
    }

    private static void stabilizePass(PitchFrame[] frames, double reference, boolean backwards) {
        double previous = reference;
        for (int step = 0; step < frames.length; step++) {
            int index = backwards ? frames.length - 1 - step : step;
            PitchFrame frame = frames[index];
            if (!frame.voiced || !Double.isFinite(frame.frequency)) continue;
            double continuityReference = geometricMean(previous, reference);
            double adjusted = nearestOctave(frame.frequency, continuityReference);
            double jump = Math.abs(log2(adjusted / previous));
            if (jump > 0.58 && frame.confidence < 0.80) {
                frame.voiced = false;
                continue;
            }
            frame.frequency = adjusted;
            previous = adjusted;
        }
    }

    private static double stableReferenceFrequency(PitchFrame[] frames, double fallbackFrequency) {
        double[] values = new double[frames.length];
        int count = 0;
        for (PitchFrame frame : frames) {
            if (frame.voiced && Double.isFinite(frame.frequency)) values[count++] = frame.frequency;
        }
        if (count == 0) return clampFrequency(fallbackFrequency);
        Arrays.sort(values, 0, count);
        double median = values[count / 2];
        if (count < 3) return nearestOctave(fallbackFrequency, median);
        return clampFrequency(median);
    }

    private static VoicedInterval[] buildVoicedIntervals(float[] audio, PitchFrame[] frames,
                                                           double fallbackFrequency,
                                                           int sampleRate, int hop) {
        List<VoicedInterval> intervals = new ArrayList<>();
        int index = 0;
        while (index < frames.length) {
            while (index < frames.length && !frames[index].voiced) index++;
            if (index >= frames.length) break;
            int first = index;
            while (index + 1 < frames.length && frames[index + 1].voiced) index++;
            int last = index;

            int start = Math.max(0, frames[first].center - hop / 2);
            int end = Math.min(audio.length, frames[last].center + hop / 2 + 1);
            int minimum = Math.max(24, (int) Math.round(1.5 * sampleRate / fallbackFrequency));
            int[] marks = new int[0];
            double quality = 0.0;
            if (end - start >= minimum) {
                marks = findCorrelationPulses(audio, frames, start, end,
                        fallbackFrequency, sampleRate, hop);
                marks = repairPulseSequence(audio, marks, frames, start, end,
                        fallbackFrequency, sampleRate, hop);
                quality = pulseSequenceQuality(audio, marks, frames,
                        fallbackFrequency, sampleRate, hop);
            }
            // Keep the voiced interval even if pulse extraction failed. The
            // target-pitched SOLA fallback can then fill it without leaking the
            // original F0 into the vowel.
            intervals.add(new VoicedInterval(start, end, marks, quality));
            index++;
        }
        return intervals.toArray(new VoicedInterval[0]);
    }

    private static int[] findCorrelationPulses(float[] audio, PitchFrame[] frames,
                                                int start, int end, double fallbackFrequency,
                                                int sampleRate, int hop) {
        int midpoint = (start + end) / 2;
        double midpointFrequency = frequencyAt(frames, midpoint, hop, fallbackFrequency);
        int midpointPeriod = Math.max(8, (int) Math.round(sampleRate / midpointFrequency));
        int anchor = strongestExtremum(audio,
                Math.max(start, midpoint - midpointPeriod / 2),
                Math.min(end, midpoint + midpointPeriod / 2 + 1));
        if (anchor < 0) return new int[0];

        List<Integer> backward = new ArrayList<>();
        int current = anchor;
        int weakSteps = 0;
        while (true) {
            double frequency = frequencyAt(frames, current, hop, fallbackFrequency);
            double period = sampleRate / frequency;
            int min = Math.max(start, (int) Math.round(current - 1.22 * period));
            int max = Math.min(current - 1, (int) Math.round(current - 0.78 * period));
            PulseCandidate candidate = bestCorrelatedPulse(audio, current, min, max,
                    period, frames, hop, fallbackFrequency, sampleRate);
            if (candidate.index < start) break;
            if (candidate.correlation < PULSE_CORRELATION_THRESHOLD && ++weakSteps > 1) break;
            if (candidate.correlation >= PULSE_CORRELATION_THRESHOLD) weakSteps = 0;
            backward.add(candidate.index);
            current = candidate.index;
        }

        List<Integer> forward = new ArrayList<>();
        current = anchor;
        weakSteps = 0;
        while (true) {
            double frequency = frequencyAt(frames, current, hop, fallbackFrequency);
            double period = sampleRate / frequency;
            int min = Math.max(current + 1, (int) Math.round(current + 0.78 * period));
            int max = Math.min(end - 1, (int) Math.round(current + 1.22 * period));
            PulseCandidate candidate = bestCorrelatedPulse(audio, current, min, max,
                    period, frames, hop, fallbackFrequency, sampleRate);
            if (candidate.index >= end || candidate.index <= current) break;
            if (candidate.correlation < PULSE_CORRELATION_THRESHOLD && ++weakSteps > 1) break;
            if (candidate.correlation >= PULSE_CORRELATION_THRESHOLD) weakSteps = 0;
            forward.add(candidate.index);
            current = candidate.index;
        }

        int[] marks = new int[backward.size() + 1 + forward.size()];
        int out = 0;
        for (int i = backward.size() - 1; i >= 0; i--) marks[out++] = backward.get(i);
        marks[out++] = anchor;
        for (int value : forward) marks[out++] = value;
        return marks;
    }

    private static int[] repairPulseSequence(float[] audio, int[] marks, PitchFrame[] frames,
                                              int start, int end, double fallbackFrequency,
                                              int sampleRate, int hop) {
        if (marks.length < 2) return marks;
        List<Integer> repaired = new ArrayList<>();
        repaired.add(marks[0]);
        for (int i = 1; i < marks.length; i++) {
            int next = marks[i];
            int current = repaired.get(repaired.size() - 1);
            int guard = 0;
            while (guard++ < 4) {
                int midpoint = (current + next) / 2;
                double expected = sampleRate / frequencyAt(frames, midpoint, hop, fallbackFrequency);
                int gap = next - current;
                if (gap <= 1.55 * expected) break;
                int predicted = (int) Math.round(current + expected);
                int radius = Math.max(2, (int) Math.round(expected * 0.16));
                PulseCandidate inserted = bestCorrelatedPulse(audio, current,
                        Math.max(current + 1, predicted - radius),
                        Math.min(next - 1, predicted + radius), expected,
                        frames, hop, fallbackFrequency, sampleRate);
                int value = inserted.index;
                if (value <= current || value >= next) break;
                repaired.add(value);
                current = value;
            }
            double expected = sampleRate / frequencyAt(frames, (current + next) / 2,
                    hop, fallbackFrequency);
            if (next - current >= 0.52 * expected && next >= start && next < end) {
                repaired.add(next);
            }
        }
        int[] result = new int[repaired.size()];
        for (int i = 0; i < result.length; i++) result[i] = repaired.get(i);
        return result;
    }

    private static double pulseSequenceQuality(float[] audio, int[] marks, PitchFrame[] frames,
                                               double fallbackFrequency, int sampleRate, int hop) {
        if (marks.length < 3) return 0.0;
        double correlationSum = 0.0;
        double periodErrorSum = 0.0;
        int count = 0;
        for (int i = 1; i < marks.length; i++) {
            int first = marks[i - 1];
            int second = marks[i];
            int gap = second - first;
            double expected = sampleRate / frequencyAt(frames, (first + second) / 2,
                    hop, fallbackFrequency);
            int radius = Math.max(6, (int) Math.round(0.42 * Math.min(gap, expected)));
            double correlation = normalizedCorrelation(audio, first, second, radius);
            correlationSum += Math.max(0.0, correlation);
            periodErrorSum += Math.abs(Math.log(Math.max(1e-9, gap / expected)));
            count++;
        }
        if (count == 0) return 0.0;
        double correlation = correlationSum / count;
        double periodPenalty = Math.exp(-3.5 * periodErrorSum / count);
        return Math.max(0.0, Math.min(1.0, correlation * periodPenalty));
    }

    private static PulseCandidate bestCorrelatedPulse(float[] audio, int reference,
                                                        int min, int max, double referencePeriod,
                                                        PitchFrame[] frames, int hop,
                                                        double fallbackFrequency, int sampleRate) {
        if (min > max || min < 0 || max >= audio.length) return new PulseCandidate(-1, -1.0);
        int best = -1;
        double bestCorrelation = -1.0;
        for (int candidate = min; candidate <= max; candidate++) {
            double candidateFrequency = frequencyAt(frames, candidate, hop, fallbackFrequency);
            double candidatePeriod = sampleRate / candidateFrequency;
            int radius = Math.max(6,
                    (int) Math.round(0.50 * Math.min(referencePeriod, candidatePeriod)));
            double correlation = normalizedCorrelation(audio, reference, candidate, radius);
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                best = candidate;
            }
        }

        if (best >= 0) {
            int localRadius = Math.max(1, (int) Math.round(referencePeriod * 0.04));
            int localStart = Math.max(min, best - localRadius);
            int localEnd = Math.min(max + 1, best + localRadius + 1);
            int extremum = strongestExtremum(audio, localStart, localEnd);
            if (extremum >= 0) {
                int radius = Math.max(6, (int) Math.round(referencePeriod * 0.45));
                double refined = normalizedCorrelation(audio, reference, extremum, radius);
                if (refined >= bestCorrelation - 0.025) {
                    best = extremum;
                    bestCorrelation = refined;
                }
            }
        }
        return new PulseCandidate(best, bestCorrelation);
    }

    private static double normalizedCorrelation(float[] audio, int first, int second, int radius) {
        int left = Math.min(radius, Math.min(first, second));
        int right = Math.min(radius, Math.min(audio.length - 1 - first, audio.length - 1 - second));
        if (left + right < 12) return -1.0;

        double dot = 0.0;
        double energyA = 1e-12;
        double energyB = 1e-12;
        int length = left + right + 1;
        for (int offset = -left; offset <= right; offset++) {
            double phase = (offset + left) / (double) Math.max(1, length - 1);
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * phase);
            double a = audio[first + offset] * window;
            double b = audio[second + offset] * window;
            dot += a * b;
            energyA += a * a;
            energyB += b * b;
        }
        return dot / Math.sqrt(energyA * energyB);
    }

    private static int strongestExtremum(float[] input, int start, int endExclusive) {
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

    private static double frequencyAt(PitchFrame[] frames, int sample, int hop,
                                      double fallbackFrequency) {
        if (frames.length == 0) return fallbackFrequency;
        double position = (sample - hop / 2.0) / hop;
        int left = (int) Math.floor(position);
        int right = left + 1;
        left = Math.max(0, Math.min(frames.length - 1, left));
        right = Math.max(0, Math.min(frames.length - 1, right));
        PitchFrame a = frames[left];
        PitchFrame b = frames[right];
        if (!a.voiced && !b.voiced) return fallbackFrequency;
        if (!a.voiced) return b.frequency;
        if (!b.voiced) return a.frequency;
        double fraction = Math.max(0.0, Math.min(1.0, position - Math.floor(position)));
        return a.frequency + (b.frequency - a.frequency) * fraction;
    }

    private static double localFrequency(Analysis analysis, int sample) {
        return frequencyAt(analysis.frames, sample, analysis.hop, analysis.fallbackFrequency);
    }

    private static double nearestOctave(double frequency, double reference) {
        if (!Double.isFinite(frequency) || frequency <= 0.0) return Double.NaN;
        if (!Double.isFinite(reference) || reference <= 0.0) return clampFrequency(frequency);
        double best = clampFrequency(frequency);
        double bestDistance = Math.abs(log2(best / reference));
        for (int octave = -4; octave <= 4; octave++) {
            double candidate = frequency * Math.pow(2.0, octave);
            if (candidate < PITCH_FLOOR_HZ || candidate > PITCH_CEILING_HZ) continue;
            double distance = Math.abs(log2(candidate / reference));
            if (distance < bestDistance - 1e-9
                    || (Math.abs(distance - bestDistance) < 1e-9 && candidate > best)) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return clampFrequency(best);
    }

    private static double clampFrequency(double frequency) {
        return Math.max(PITCH_FLOOR_HZ, Math.min(PITCH_CEILING_HZ, frequency));
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

    private static int clampPeriod(int measured, int nominal) {
        int min = Math.max(4, (int) Math.round(nominal * 0.62));
        int max = Math.max(min + 1, (int) Math.round(nominal * 1.38));
        return Math.max(min, Math.min(max, measured));
    }

    private static float raisedCosine(int position, int length) {
        if (length <= 0) return 1.0f;
        double phase = Math.max(0.0, Math.min(1.0, position / (double) length));
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * phase));
    }

    private static float voicedMixAt(VoicedInterval[] intervals, double sourcePosition,
                                     int edgeFadeSamples) {
        for (VoicedInterval interval : intervals) {
            if (sourcePosition < interval.start || sourcePosition >= interval.end) continue;
            double left = sourcePosition - interval.start;
            double right = interval.end - sourcePosition;
            double edge = Math.min(left, right) / Math.max(1.0, edgeFadeSamples);
            return smoothStep((float) Math.max(0.0, Math.min(1.0, edge)));
        }
        return 0.0f;
    }

    private static float smoothStep(float value) {
        value = Math.max(0.0f, Math.min(1.0f, value));
        return value * value * (3.0f - 2.0f * value);
    }

    private static double geometricMean(double first, double second) {
        if (!Double.isFinite(first) || first <= 0.0) return second;
        if (!Double.isFinite(second) || second <= 0.0) return first;
        return Math.sqrt(first * second);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private static float[] timeMapLinear(float[] input, int targetLength) {
        float[] output = new float[targetLength];
        if (input.length == 1) {
            Arrays.fill(output, input[0]);
            return output;
        }
        double scale = (input.length - 1) / (double) Math.max(1, targetLength - 1);
        for (int i = 0; i < targetLength; i++) {
            double source = i * scale;
            int left = Math.min(input.length - 1, (int) source);
            int right = Math.min(input.length - 1, left + 1);
            float fraction = (float) (source - left);
            output[i] = input[left] + (input[right] - input[left]) * fraction;
        }
        return output;
    }

    private static void removeDc(float[] audio) {
        if (audio.length == 0) return;
        double mean = 0.0;
        for (float value : audio) mean += value;
        mean /= audio.length;
        if (Math.abs(mean) < 1e-7) return;
        for (int i = 0; i < audio.length; i++) audio[i] -= (float) mean;
    }

    private static void softLimit(float[] audio, float limit) {
        for (int i = 0; i < audio.length; i++) {
            if (audio[i] > limit) audio[i] = limit;
            else if (audio[i] < -limit) audio[i] = -limit;
        }
    }

    /**
     * Target-pitched fallback: resample changes pitch, SOLA restores the
     * requested duration without undoing that pitch change.
     */
    private static float[] legacyShift(float[] input, double pitchFactor, int targetLength) {
        if (input.length == 0 || targetLength <= 0) return new float[Math.max(0, targetLength)];
        pitchFactor = Math.max(0.10, Math.min(10.0, pitchFactor));
        float[] pitched = resampleForPitch(input, pitchFactor);
        if (pitched.length == targetLength) return pitched;
        return timeStretchSola(pitched, targetLength);
    }

    private static float[] resampleForPitch(float[] input, double factor) {
        int outputLength = Math.max(64, (int) Math.round(input.length / factor));
        float[] output = new float[outputLength];
        for (int i = 0; i < outputLength; i++) {
            double source = i * factor;
            int index = (int) Math.floor(source);
            double fraction = source - index;
            float y0 = input[Math.max(0, index - 1)];
            float y1 = input[Math.max(0, Math.min(input.length - 1, index))];
            float y2 = input[Math.max(0, Math.min(input.length - 1, index + 1))];
            float y3 = input[Math.max(0, Math.min(input.length - 1, index + 2))];
            output[i] = cubicInterpolate(y0, y1, y2, y3, fraction);
        }
        return output;
    }

    private static float cubicInterpolate(float y0, float y1, float y2, float y3,
                                          double fraction) {
        double a0 = y3 - y2 - y0 + y1;
        double a1 = y0 - y1 - a0;
        double a2 = y2 - y0;
        double a3 = y1;
        return (float) (((a0 * fraction + a1) * fraction + a2) * fraction + a3);
    }

    private static float[] timeStretchSola(float[] input, int targetLength) {
        if (targetLength <= 0) return new float[0];
        if (input.length == 0) return new float[targetLength];
        if (input.length < 128) return repeatToLength(input, targetLength);
        if (targetLength <= 96) return Arrays.copyOf(input, targetLength);

        int frame = chooseFrameSize(input.length, targetLength);
        int overlap = frame / 2;
        int synthesisHop = frame - overlap;
        int search = Math.max(24, overlap / 3);
        double stretch = targetLength / (double) input.length;
        double analysisHop = synthesisHop / Math.max(0.05, stretch);

        float[] source = input;
        if (source.length < frame + search + 1) {
            source = repeatToLength(source, frame + search + 1);
        }

        float[] output = new float[targetLength + frame];
        float[] weight = new float[targetLength + frame];
        int firstLength = Math.min(frame, targetLength);
        for (int i = 0; i < firstLength; i++) {
            float window = hann(i, frame);
            output[i] += source[i] * window;
            weight[i] += window;
        }

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

            int best = findBestOverlap(output, weight, outputPosition, source,
                    minCandidate, maxCandidate, overlap);
            int copyLength = Math.min(frame, targetLength - outputPosition);
            for (int i = 0; i < copyLength; i++) {
                float window = hann(i, frame);
                output[outputPosition + i] += source[best + i] * window;
                weight[outputPosition + i] += window;
            }

            sourcePosition = best + analysisHop;
            outputPosition += synthesisHop;
            if (sourcePosition + frame >= source.length && outputPosition < targetLength) {
                sourcePosition = Math.max(0.0, source.length - frame - search);
            }
        }

        float[] result = new float[targetLength];
        for (int i = 0; i < targetLength; i++) {
            result[i] = weight[i] > 1e-6f ? output[i] / weight[i] : 0.0f;
        }
        return result;
    }

    private static int findBestOverlap(float[] output, float[] weight, int outputPosition,
                                       float[] input, int minCandidate, int maxCandidate,
                                       int overlap) {
        int best = minCandidate;
        double bestScore = -Double.MAX_VALUE;
        int step = maxCandidate - minCandidate > 512 ? 2 : 1;
        for (int candidate = minCandidate; candidate <= maxCandidate; candidate += step) {
            double dot = 0.0;
            double energyA = 1e-9;
            double energyB = 1e-9;
            int count = 0;
            for (int i = 0; i < overlap; i++) {
                int outputIndex = outputPosition + i;
                if (outputIndex >= output.length || candidate + i >= input.length) break;
                if (weight[outputIndex] <= 1e-6f) continue;
                float a = output[outputIndex] / weight[outputIndex];
                float b = input[candidate + i];
                dot += a * b;
                energyA += a * a;
                energyB += b * b;
                count++;
            }
            double score = count < 12 ? -1.0 : dot / Math.sqrt(energyA * energyB);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static float hann(int index, int length) {
        if (length <= 1) return 1.0f;
        return (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * index / (length - 1)));
    }

    private static int chooseFrameSize(int inputLength, int targetLength) {
        int minimum = Math.min(inputLength, targetLength);
        if (minimum >= 8192) return 2048;
        if (minimum >= 4096) return 1024;
        if (minimum >= 2048) return 512;
        if (minimum >= 1024) return 256;
        return 128;
    }

    private static float[] repeatToLength(float[] input, int length) {
        float[] output = new float[length];
        if (input.length == 0) return output;
        for (int i = 0; i < length; i++) output[i] = input[i % input.length];
        return output;
    }
}
