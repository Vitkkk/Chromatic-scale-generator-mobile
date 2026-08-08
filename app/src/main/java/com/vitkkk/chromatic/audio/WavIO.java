package com.vitkkk.chromatic.audio;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class WavIO {
    private WavIO() {}

    public static final class WavData {
        public final int sampleRate;
        public final float[] samples;

        public WavData(int sampleRate, float[] samples) {
            this.sampleRate = sampleRate;
            this.samples = samples;
        }
    }

    public static WavData read(ContentResolver resolver, Uri uri) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Não foi possível abrir o arquivo WAV.");
            return read(input);
        }
    }

    public static WavData read(InputStream input) throws IOException {
        byte[] bytes = readAll(input);
        if (bytes.length < 44 || !ascii(bytes, 0, 4).equals("RIFF") || !ascii(bytes, 8, 4).equals("WAVE")) {
            throw new IOException("Arquivo inválido: esperado WAV RIFF.");
        }

        int audioFormat = -1, channels = -1, sampleRate = -1, bits = -1, blockAlign = -1;
        int dataOffset = -1, dataSize = -1;
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String id = ascii(bytes, offset, 4);
            int size = littleInt(bytes, offset + 4);
            int data = offset + 8;
            if (size < 0 || data + size > bytes.length) break;
            if ("fmt ".equals(id) && size >= 16) {
                audioFormat = littleShort(bytes, data) & 0xffff;
                channels = littleShort(bytes, data + 2) & 0xffff;
                sampleRate = littleInt(bytes, data + 4);
                blockAlign = littleShort(bytes, data + 12) & 0xffff;
                bits = littleShort(bytes, data + 14) & 0xffff;
            } else if ("data".equals(id)) {
                dataOffset = data;
                dataSize = size;
            }
            offset = data + size + (size & 1);
        }

        if (dataOffset < 0 || sampleRate <= 0 || channels <= 0 || blockAlign <= 0) {
            throw new IOException("WAV sem blocos fmt/data válidos.");
        }
        if (audioFormat != 1 && audioFormat != 3) {
            throw new IOException("Formato WAV não suportado. Use PCM ou IEEE float.");
        }

        int frameCount = dataSize / blockAlign;
        float[] mono = new float[frameCount];
        int bytesPerSample = Math.max(1, bits / 8);
        for (int frame = 0; frame < frameCount; frame++) {
            double sum = 0.0;
            int frameOffset = dataOffset + frame * blockAlign;
            for (int channel = 0; channel < channels; channel++) {
                sum += decodeSample(bytes, frameOffset + channel * bytesPerSample, audioFormat, bits);
            }
            mono[frame] = clamp((float) (sum / channels));
        }
        return new WavData(sampleRate, mono);
    }

    public static void write(ContentResolver resolver, Uri uri, float[] samples, int sampleRate) throws IOException {
        try (OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("Não foi possível criar o WAV de saída.");
            write(output, samples, sampleRate);
        }
    }

    public static void write(OutputStream output, float[] samples, int sampleRate) throws IOException {
        int dataSize = samples.length * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataSize);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataSize);
        output.write(header.array());

        byte[] buffer = new byte[8192];
        int cursor = 0;
        for (float sample : samples) {
            short pcm = (short) Math.round(clamp(sample) * 32767.0f);
            buffer[cursor++] = (byte) (pcm & 0xff);
            buffer[cursor++] = (byte) ((pcm >>> 8) & 0xff);
            if (cursor >= buffer.length) {
                output.write(buffer, 0, cursor);
                cursor = 0;
            }
        }
        if (cursor > 0) output.write(buffer, 0, cursor);
        output.flush();
    }

    public static float[] resampleLinear(float[] input, int sourceRate, int targetRate) {
        if (sourceRate == targetRate || input.length < 2) return input.clone();
        int length = Math.max(1, (int) Math.round(input.length * (double) targetRate / sourceRate));
        float[] output = new float[length];
        double ratio = sourceRate / (double) targetRate;
        for (int i = 0; i < length; i++) {
            double source = i * ratio;
            int left = Math.min(input.length - 1, (int) source);
            int right = Math.min(input.length - 1, left + 1);
            float fraction = (float) (source - left);
            output[i] = input[left] + (input[right] - input[left]) * fraction;
        }
        return output;
    }

    public static float[] trimSilence(float[] input, float threshold, int padding) {
        int start = 0;
        while (start < input.length && Math.abs(input[start]) < threshold) start++;
        int end = input.length - 1;
        while (end > start && Math.abs(input[end]) < threshold) end--;
        start = Math.max(0, start - padding);
        end = Math.min(input.length - 1, end + padding);
        if (end <= start) return input.clone();
        float[] output = new float[end - start + 1];
        System.arraycopy(input, start, output, 0, output.length);
        return output;
    }

    private static double decodeSample(byte[] bytes, int offset, int format, int bits) throws IOException {
        if (format == 3 && bits == 32) return Float.intBitsToFloat(littleInt(bytes, offset));
        if (format != 1) throw new IOException("WAV float deve usar 32 bits.");
        switch (bits) {
            case 8:
                return ((bytes[offset] & 0xff) - 128) / 128.0;
            case 16:
                return littleShort(bytes, offset) / 32768.0;
            case 24: {
                int value = (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16);
                if ((value & 0x800000) != 0) value |= 0xff000000;
                return value / 8388608.0;
            }
            case 32:
                return littleInt(bytes, offset) / 2147483648.0;
            default:
                throw new IOException("Profundidade WAV não suportada: " + bits + " bits.");
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static short littleShort(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8));
    }

    private static int littleInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static float clamp(float value) {
        return Math.max(-1.0f, Math.min(1.0f, value));
    }
}
