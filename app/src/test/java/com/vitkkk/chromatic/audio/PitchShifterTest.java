package com.vitkkk.chromatic.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class PitchShifterTest {
    private static final int SAMPLE_RATE = 48000;
    private static final double SOURCE_FREQUENCY = 120.0;

    @Test
    public void reachesTargetPitchAndKeepsRequestedLength() {
        float[] source = harmonicVoice(SOURCE_FREQUENCY, 0.6);
        PitchShifter.Analysis analysis = PitchShifter.analyze(
                source, SOURCE_FREQUENCY, SAMPLE_RATE);
        double[] factors = {0.5, 0.75, 1.5, 2.0, 4.0};

        for (double factor : factors) {
            double target = SOURCE_FREQUENCY * factor;
            float[] shifted = PitchShifter.shift(analysis, target, source.length);

            assertEquals(source.length, shifted.length);
            double measured = measurePitchNear(shifted, target,
                    shifted.length / 4, shifted.length * 3 / 4);
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
        assertFiniteAudio(shifted);
    }

    @Test
    public void turnsMovingSourcePitchIntoStableTarget() {
        float[] source = chirpedVoice(105.0, 145.0, 0.7);
        PitchShifter.Analysis analysis = PitchShifter.analyze(source, 125.0, SAMPLE_RATE);
        float[] shifted = PitchShifter.shift(analysis, 220.0, source.length);

        assertFiniteAudio(shifted);
        double firstHalf = measurePitchNear(shifted, 220.0,
                shifted.length / 5, shifted.length / 2);
        double secondHalf = measurePitchNear(shifted, 220.0,
                shifted.length / 2, shifted.length * 4 / 5);
        assertEquals(220.0, firstHalf, 4.0);
        assertEquals(220.0, secondHalf, 4.0);
        assertEquals(firstHalf, secondHalf, 3.0);
    }

    @Test
    public void handlesMinimumFortyMillisecondSample() {
        float[] source = harmonicVoice(SOURCE_FREQUENCY, 0.04);
        PitchShifter.Analysis analysis = PitchShifter.analyze(
                source, SOURCE_FREQUENCY, SAMPLE_RATE);
        float[] shifted = PitchShifter.shift(analysis, 180.0, source.length);

        assertEquals(source.length, shifted.length);
        assertFiniteAudio(shifted);
        assertEquals(180.0, measurePitchNear(shifted, 180.0,
                0, shifted.length), 5.0);
    }

    @Test
    public void doesNotLeakSourcePitchBehindRaisedNote() {
        float[] source = harmonicVoice(110.0, 0.8);
        int noisyStart = source.length * 45 / 100;
        int noisyEnd = source.length * 55 / 100;
        Random random = new Random(7L);
        for (int i = noisyStart; i < noisyEnd; i++) {
            source[i] = (float) (0.03 * (random.nextDouble() * 2.0 - 1.0));
        }

        PitchShifter.Analysis analysis = PitchShifter.analyze(source, 110.0, SAMPLE_RATE);
        float[] shifted = PitchShifter.shift(analysis, 220.0, source.length);

        assertFiniteAudio(shifted);
        double targetEnergy = spectralMagnitude(shifted, 220.0,
                shifted.length / 5, shifted.length * 4 / 5);
        double sourceGhost = spectralMagnitude(shifted, 110.0,
                shifted.length / 5, shifted.length * 4 / 5);
        assertTrue("A frequência original vazou como uma voz grave atrás da nota",
                sourceGhost < targetEnergy * 0.40);
    }

    @Test
    public void rejectsOctaveAmbiguousPeriodDoubling() {
        float[] source = alternatingCycleVoice(120.0, 0.8);
        PitchShifter.Analysis analysis = PitchShifter.analyze(source, 120.0, SAMPLE_RATE);
        float[] shifted = PitchShifter.shift(analysis, 240.0, source.length);

        assertFiniteAudio(shifted);
        int fifth = shifted.length / 5;
        assertEquals(240.0, measurePitchNear(shifted, 240.0,
                fifth, fifth * 2), 8.0);
        assertEquals(240.0, measurePitchNear(shifted, 240.0,
                fifth * 2, fifth * 3), 8.0);
        assertEquals(240.0, measurePitchNear(shifted, 240.0,
                fifth * 3, fifth * 4), 8.0);

        double targetEnergy = spectralMagnitude(shifted, 240.0, fifth, fifth * 4);
        double demonicSubharmonic = spectralMagnitude(shifted, 120.0, fifth, fifth * 4);
        assertTrue("A sequência de pulsos pulou ciclos e criou uma oitava abaixo",
                demonicSubharmonic < targetEnergy * 0.45);
    }

    @Test
    public void deepDownShiftHasNoNearSilentPitchCollapses() {
        float[] source = harmonicVoice(240.0, 0.7);
        PitchShifter.Analysis analysis = PitchShifter.analyze(source, 240.0, SAMPLE_RATE);
        float[] shifted = PitchShifter.shift(analysis, 60.0, source.length);

        assertFiniteAudio(shifted);
        assertEquals(60.0, measurePitchNear(shifted, 60.0,
                shifted.length / 5, shifted.length * 4 / 5), 5.0);

        int window = SAMPLE_RATE / 100;
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = 0.0;
        for (int start = shifted.length / 5;
             start + window < shifted.length * 4 / 5;
             start += window) {
            double rms = rms(shifted, start, start + window);
            minimum = Math.min(minimum, rms);
            maximum = Math.max(maximum, rms);
        }
        assertTrue("O pitch grave abriu buracos quase silenciosos entre os pulsos",
                maximum > 1e-6 && minimum / maximum > 0.10);
    }

    private static void assertFiniteAudio(float[] audio) {
        for (float sample : audio) {
            assertTrue(Float.isFinite(sample));
            assertTrue(Math.abs(sample) <= 2.0f);
        }
    }

    private static double measurePitchNear(float[] audio, double expectedFrequency,
                                           int start, int end) {
        start = Math.max(0, start);
        end = Math.min(audio.length, end);
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

    private static double spectralMagnitude(float[] audio, double frequency,
                                            int start, int end) {
        double real = 0.0;
        double imaginary = 0.0;
        for (int i = start; i < end; i++) {
            double phase = 2.0 * Math.PI * frequency * i / SAMPLE_RATE;
            real += audio[i] * Math.cos(phase);
            imaginary -= audio[i] * Math.sin(phase);
        }
        return 2.0 * Math.hypot(real, imaginary) / Math.max(1, end - start);
    }

    private static double rms(float[] audio, int start, int end) {
        double energy = 0.0;
        for (int i = start; i < end; i++) energy += audio[i] * audio[i];
        return Math.sqrt(energy / Math.max(1, end - start));
    }

    private static float[] harmonicVoice(double frequency, double seconds) {
        int length = (int) Math.round(seconds * SAMPLE_RATE);
        float[] audio = new float[length];
        int attack = Math.min(length / 4, SAMPLE_RATE / 50);

        for (int i = 0; i < length; i++) {
            double phase = 2.0 * Math.PI * frequency * i / SAMPLE_RATE;
            double envelope = attack <= 1 || i >= attack ? 1.0 : i / (double) attack;
            audio[i] = (float) (envelope * (
                    0.62 * Math.sin(phase)
                            + 0.24 * Math.sin(2.0 * phase)
                            + 0.10 * Math.sin(3.0 * phase)
                            + 0.04 * Math.sin(5.0 * phase)));
        }
        return audio;
    }

    private static float[] chirpedVoice(double startFrequency, double endFrequency,
                                         double seconds) {
        int length = (int) Math.round(seconds * SAMPLE_RATE);
        float[] audio = new float[length];
        double phase = 0.0;
        for (int i = 0; i < length; i++) {
            double position = i / (double) Math.max(1, length - 1);
            double frequency = startFrequency + (endFrequency - startFrequency) * position;
            phase += 2.0 * Math.PI * frequency / SAMPLE_RATE;
            double attack = Math.min(1.0, i / (0.015 * SAMPLE_RATE));
            double release = Math.min(1.0, (length - 1 - i) / (0.020 * SAMPLE_RATE));
            double envelope = Math.max(0.0, attack * release);
            audio[i] = (float) (envelope * (
                    0.66 * Math.sin(phase)
                            + 0.23 * Math.sin(2.0 * phase)
                            + 0.11 * Math.sin(3.0 * phase)));
        }
        return audio;
    }

    private static float[] alternatingCycleVoice(double frequency, double seconds) {
        int length = (int) Math.round(seconds * SAMPLE_RATE);
        int period = Math.max(1, (int) Math.round(SAMPLE_RATE / frequency));
        float[] audio = new float[length];
        for (int i = 0; i < length; i++) {
            double phase = 2.0 * Math.PI * frequency * i / SAMPLE_RATE;
            int cycle = i / period;
            double alternatingGain = (cycle & 1) == 0 ? 1.0 : 0.50;
            double attack = Math.min(1.0, i / (0.015 * SAMPLE_RATE));
            double release = Math.min(1.0, (length - 1 - i) / (0.020 * SAMPLE_RATE));
            audio[i] = (float) (attack * release * alternatingGain * (
                    0.65 * Math.sin(phase)
                            + 0.23 * Math.sin(2.0 * phase)
                            + 0.12 * Math.sin(3.0 * phase)));
        }
        return audio;
    }
}
