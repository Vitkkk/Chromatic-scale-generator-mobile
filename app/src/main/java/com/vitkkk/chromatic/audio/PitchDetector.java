package com.vitkkk.chromatic.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PitchDetector {
    private static final int FRAME_SIZE = 2048;
    private static final int MAX_FRAMES = 12;
    private static final double YIN_THRESHOLD = 0.16;

    private PitchDetector() {}

    public static double detectFundamental(float[] samples, int sampleRate) {
        if (samples.length < 512) {
            return Double.NaN;
        }

        int frameSize = Math.min(FRAME_SIZE, highestPowerOfTwo(samples.length));
        if (frameSize < 512) {
            return Double.NaN;
        }

        int usable = Math.max(1, samples.length - frameSize);
        int frames = Math.min(MAX_FRAMES, Math.max(1, samples.length / frameSize));
        List<Double> pitches = new ArrayList<>();

        for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
            int start = frames == 1 ? 0 : (int) Math.round(frameIndex * usable / (double) (frames - 1));
            double pitch = detectFrame(samples, start, frameSize, sampleRate);
            if (Double.isFinite(pitch) && pitch >= 45.0 && pitch <= 1200.0) {
                pitches.add(pitch);
            }
        }

        if (pitches.isEmpty()) {
            return Double.NaN;
        }

        Collections.sort(pitches);
        double median = pitches.get(pitches.size() / 2);
        List<Double> filtered = new ArrayList<>();
        for (double pitch : pitches) {
            double cents = 1200.0 * Math.log(pitch / median) / Math.log(2.0);
            if (Math.abs(cents) < 250.0) {
                filtered.add(pitch);
            }
        }
        if (filtered.isEmpty()) {
            return median;
        }
        Collections.sort(filtered);
        return filtered.get(filtered.size() / 2);
    }

    private static double detectFrame(float[] samples, int start, int frameSize, int sampleRate) {
        float[] frame = new float[frameSize];
        double mean = 0.0;
        for (int i = 0; i < frameSize; i++) {
            mean += samples[start + i];
        }
        mean /= frameSize;

        double energy = 0.0;
        for (int i = 0; i < frameSize; i++) {
            double window = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (frameSize - 1));
            float value = (float) ((samples[start + i] - mean) * window);
            frame[i] = value;
            energy += value * value;
        }
        if (Math.sqrt(energy / frameSize) < 0.004) {
            return Double.NaN;
        }

        int minTau = Math.max(2, sampleRate / 1200);
        int maxTau = Math.min(frameSize / 2, sampleRate / 45);
        if (maxTau <= minTau) {
            return Double.NaN;
        }

        double[] difference = new double[maxTau + 1];
        int comparisonLength = frameSize - maxTau;
        for (int tau = 1; tau <= maxTau; tau++) {
            double sum = 0.0;
            for (int i = 0; i < comparisonLength; i++) {
                double delta = frame[i] - frame[i + tau];
                sum += delta * delta;
            }
            difference[tau] = sum;
        }

        double[] normalized = new double[maxTau + 1];
        normalized[0] = 1.0;
        double running = 0.0;
        for (int tau = 1; tau <= maxTau; tau++) {
            running += difference[tau];
            normalized[tau] = running == 0.0 ? 1.0 : difference[tau] * tau / running;
        }

        int bestTau = -1;
        for (int tau = minTau; tau < maxTau; tau++) {
            if (normalized[tau] < YIN_THRESHOLD) {
                while (tau + 1 <= maxTau && normalized[tau + 1] < normalized[tau]) {
                    tau++;
                }
                bestTau = tau;
                break;
            }
        }

        if (bestTau < 0) {
            double bestValue = 1.0;
            for (int tau = minTau; tau <= maxTau; tau++) {
                if (normalized[tau] < bestValue) {
                    bestValue = normalized[tau];
                    bestTau = tau;
                }
            }
            if (bestTau < 0 || bestValue > 0.45) {
                return Double.NaN;
            }
        }

        double refinedTau = bestTau;
        if (bestTau > 1 && bestTau < maxTau) {
            double left = normalized[bestTau - 1];
            double center = normalized[bestTau];
            double right = normalized[bestTau + 1];
            double denominator = left - 2.0 * center + right;
            if (Math.abs(denominator) > 1e-12) {
                refinedTau += 0.5 * (left - right) / denominator;
            }
        }
        return sampleRate / refinedTau;
    }

    private static int highestPowerOfTwo(int value) {
        int result = 1;
        while (result <= value / 2) {
            result *= 2;
        }
        return result;
    }
}
