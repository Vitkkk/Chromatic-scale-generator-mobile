package com.vitkkk.chromatic.audio;

/**
 * JNI bridge to Rubber Band R3. The native engine performs the pitch shift and
 * duration change together, so no unshifted audio is mixed into voiced regions.
 */
public final class NativePitchShifter {
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean available = false;
        String error = null;
        try {
            System.loadLibrary("chromatic_pitch");
            available = true;
        } catch (Throwable failure) {
            error = failure.getMessage();
        }
        AVAILABLE = available;
        LOAD_ERROR = error;
    }

    private NativePitchShifter() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static float[] shift(float[] input,
                                double sourceFrequency,
                                double targetFrequency,
                                int sampleRate,
                                int targetLength,
                                int dynamicHoldSamples,
                                int dynamicGlideSamples) {
        if (!AVAILABLE) {
            throw new IllegalStateException("O motor de pitch nativo não pôde ser carregado"
                    + (LOAD_ERROR == null || LOAD_ERROR.isEmpty() ? "." : ": " + LOAD_ERROR));
        }
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("O sample não pode estar vazio.");
        }
        if (!Double.isFinite(sourceFrequency) || sourceFrequency <= 0.0
                || !Double.isFinite(targetFrequency) || targetFrequency <= 0.0) {
            throw new IllegalArgumentException("Frequência de pitch inválida.");
        }
        if (sampleRate < 8000 || sampleRate > 192000 || targetLength <= 0) {
            throw new IllegalArgumentException("Sample rate ou duração inválida.");
        }

        double pitchScale = targetFrequency / sourceFrequency;
        float[] result = nativeShift(input, sampleRate, pitchScale, targetLength,
                Math.max(0, dynamicHoldSamples), Math.max(0, dynamicGlideSamples));
        if (result == null || result.length != targetLength) {
            throw new IllegalStateException("O motor nativo retornou uma duração inválida.");
        }
        for (int i = 0; i < result.length; i++) {
            if (!Float.isFinite(result[i])) result[i] = 0.0f;
        }
        return result;
    }

    private static native float[] nativeShift(float[] input,
                                               int sampleRate,
                                               double pitchScale,
                                               int targetLength,
                                               int holdSamples,
                                               int glideSamples);
}
