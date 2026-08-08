package com.vitkkk.chromatic.audio;

/**
 * JNI bridge to the exact Praat 6.1.38 Manipulation engine embedded by
 * praat-parselmouth 0.4.1 in the original Windows application.
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
                                int sourceSampleRate,
                                double targetFrequency,
                                int dynamicHoldSamples,
                                int dynamicGlideSamples) {
        if (!AVAILABLE) {
            throw new IllegalStateException("O motor Praat 6.1.38 não pôde ser carregado"
                    + (LOAD_ERROR == null || LOAD_ERROR.isEmpty() ? "." : ": " + LOAD_ERROR));
        }
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("O sample não pode estar vazio.");
        }
        if (!Double.isFinite(targetFrequency) || targetFrequency <= 0.0) {
            throw new IllegalArgumentException("Frequência de destino inválida.");
        }
        if (sourceSampleRate < 8000 || sourceSampleRate > 192000) {
            throw new IllegalArgumentException("Sample rate inválido.");
        }

        float[] result = nativeShift(input, sourceSampleRate, targetFrequency,
                Math.max(0, dynamicHoldSamples), Math.max(0, dynamicGlideSamples));
        if (result == null || result.length == 0) {
            throw new IllegalStateException("O motor Praat retornou um sample vazio.");
        }
        for (int i = 0; i < result.length; i++) {
            if (!Float.isFinite(result[i])) result[i] = 0.0f;
        }
        return result;
    }

    private static native float[] nativeShift(float[] input,
                                               int sourceSampleRate,
                                               double targetFrequency,
                                               int holdSamples,
                                               int glideSamples);
}
