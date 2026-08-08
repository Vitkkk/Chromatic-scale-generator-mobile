package com.vitkkk.chromatic.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Writes monolithic DirectWave preset files with embedded mono PCM samples. */
public final class DwpWriter {
    private static final int VERSION = 37;
    private static final int SAMPLE_PADDING_FRAMES = 256;

    private static final byte[] PROGRAM_SETTINGS = hex(
            "f508000000006666e63e000000000000000000000000a8fcfa0300000000");
    private static final byte[] ZONE_PARAMS = hex(
            "000000006e000000000000803f0000003f0100000000020000");
    private static final byte[] SAMPLE_INFO = hex(
            "00000000000000000100000004000000000000000000000000000000000000000000000010000000");

    private static final byte[] ZONE_504 = hex("0000003f00006400");
    private static final byte[] ZONE_506 = hex(
            "0000000000000000000000000000803f00000000000000000000000002000000"
                    + "00000000120000800000000034072900");
    private static final byte[] ZONE_509 = hex("000000000000803f0000803fec51383e");
    private static final byte[] FIRST_MODULATION = hex("0200020000000000");

    private DwpWriter() {}

    public interface ProgressListener {
        void onZoneWritten(int completed, int total);
    }

    public static final class Zone {
        public final int midiNote;
        public final String name;
        public final float[] samples;
        public final int offset;
        public final int length;
        public final boolean loopEnabled;
        public final int loopStart;
        public final int loopEnd;

        public Zone(int midiNote, String name, float[] samples) {
            this(midiNote, name, samples, 0, samples == null ? 0 : samples.length,
                    false, 0, 0);
        }

        public Zone(int midiNote, String name, float[] samples, int offset, int length) {
            this(midiNote, name, samples, offset, length, false, 0, 0);
        }

        public Zone(int midiNote, String name, float[] samples, int offset, int length,
                    boolean loopEnabled, int loopStart, int loopEnd) {
            if (midiNote < 0 || midiNote > 127) {
                throw new IllegalArgumentException("A nota MIDI deve ficar entre 0 e 127.");
            }
            if (samples == null || offset < 0 || length <= 0 || offset + length > samples.length) {
                throw new IllegalArgumentException("Trecho de áudio inválido para a zona DirectWave.");
            }
            if (loopEnabled && (loopStart < 0 || loopEnd <= loopStart || loopEnd > length)) {
                throw new IllegalArgumentException("Pontos de loop inválidos para a zona DirectWave.");
            }
            this.midiNote = midiNote;
            this.name = name;
            this.samples = samples;
            this.offset = offset;
            this.length = length;
            this.loopEnabled = loopEnabled;
            this.loopStart = loopEnabled ? loopStart : 0;
            this.loopEnd = loopEnabled ? loopEnd : 0;
        }
    }

    public static void write(OutputStream output, String requestedProgramName, List<Zone> zones,
                             int sampleRate, ProgressListener listener) throws IOException {
        if (output == null) throw new IllegalArgumentException("Saída DWP inválida.");
        if (zones == null || zones.isEmpty()) {
            throw new IllegalArgumentException("O DWP precisa ter pelo menos uma nota.");
        }
        if (zones.size() > 128) {
            throw new IllegalArgumentException("O DirectWave aceita no máximo 128 notas MIDI distintas.");
        }
        if (sampleRate < 8000 || sampleRate > 384000) {
            throw new IllegalArgumentException("Sample rate inválido para o DWP.");
        }

        String programName = sanitizeAscii(requestedProgramName, "Chromatic", 96);
        byte[] global = buildGlobalSection(programName, zones.size());
        long programLength = global.length + chunkSize(0);
        for (int i = 0; i < zones.size(); i++) {
            programLength = checkedAdd(programLength,
                    chunkSize(zonePayloadSize(programName, zones.get(i), i)));
        }

        output.write("DwPr".getBytes(StandardCharsets.US_ASCII));
        writeIntLE(output, VERSION);
        writeChunk(output, 6, new byte[16]);
        writeChunkHeader(output, 1, programLength);
        output.write(global);

        for (int i = 0; i < zones.size(); i++) {
            writeZone(output, programName, zones.get(i), i, zones.size(), sampleRate);
            if (listener != null) listener.onZoneWritten(i + 1, zones.size());
        }
        writeChunk(output, 2, new byte[0]);
        output.flush();
    }

    private static byte[] buildGlobalSection(String programName, int zoneCount) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(3000);
        byte[] settings = PROGRAM_SETTINGS.clone();
        putLongLE(settings, 14, zoneCount);
        writeChunk(out, 100, settings);
        writeChunk(out, 102, ascii(programName));
        writeChunk(out, 103, ascii(programName + ".dwp"));
        writeChunk(out, 104, new byte[10]);
        writeChunk(out, 105, new byte[18]);
        writeChunk(out, 106, new byte[17]);
        writeChunk(out, 107, new byte[17]);
        writeChunk(out, 108, new byte[20]);
        writeChunk(out, 108, new byte[20]);
        for (int i = 0; i < 4; i++) writeChunk(out, 109, new byte[4]);
        for (int i = 1; i <= 99; i++) {
            byte[] entry = new byte[13];
            putIntLE(entry, 0, i);
            putFloatLE(entry, 7, 1.0f);
            writeChunk(out, 110, entry);
        }
        return out.toByteArray();
    }

    private static long zonePayloadSize(String programName, Zone zone, int index) {
        String zoneName = zoneName(programName, zone, index);
        String samplePath = programName + "\\" + zoneName + ".wav";
        long audioBytes = ((long) zone.length + SAMPLE_PADDING_FRAMES * 2L) * 2L;

        long size = 0;
        size += chunkSize(25);
        size += chunkSize(ascii(zoneName).length);
        size += chunkSize(ascii(samplePath).length);
        size += chunkSize(40);
        size += chunkSize(8);
        size += chunkSize(14);
        size += chunkSize(48);
        size += chunkSize(20) * 2L;
        size += chunkSize(2);
        size += chunkSize(16);
        size += chunkSize(9) * 4L;
        size += chunkSize(16) * 2L;
        size += chunkSize(20) * 2L;
        size += chunkSize(8) * 16L;
        size += chunkSize(audioBytes);
        size += chunkSize(0);
        return size;
    }

    private static void writeZone(OutputStream out, String programName, Zone zone,
                                  int index, int zoneCount, int sampleRate) throws IOException {
        long payloadSize = zonePayloadSize(programName, zone, index);
        writeChunkHeader(out, 3, payloadSize);

        byte[] params = ZONE_PARAMS.clone();
        int lowKey = index == 0 ? 0 : zone.midiNote;
        int highKey = index == zoneCount - 1 ? 127 : zone.midiNote;
        params[0] = (byte) zone.midiNote;
        params[1] = (byte) lowKey;
        params[2] = (byte) highKey;
        writeChunk(out, 500, params);

        String zoneName = zoneName(programName, zone, index);
        writeChunk(out, 501, ascii(zoneName));
        writeChunk(out, 502, ascii(programName + "\\" + zoneName + ".wav"));

        byte[] sampleInfo = SAMPLE_INFO.clone();
        putIntLE(sampleInfo, 0, zone.length);
        putFloatLE(sampleInfo, 16, sampleRate);
        if (zone.loopEnabled) {
            /*
             * DirectWave stores the four sample-position fields in this order:
             * sample start, sample end, loop start and loop end. The second
             * uint32 selects the loop scheme; 1 is a forward/sustain loop.
             */
            putIntLE(sampleInfo, 4, 1);
            putIntLE(sampleInfo, 20, 0);
            putIntLE(sampleInfo, 24, zone.length);
            putIntLE(sampleInfo, 28, zone.loopStart);
            putIntLE(sampleInfo, 32, zone.loopEnd);
        }
        writeChunk(out, 503, sampleInfo);

        writeChunk(out, 504, ZONE_504);
        writeChunk(out, 505, new byte[14]);
        writeChunk(out, 506, ZONE_506);
        writeChunk(out, 507, new byte[20]);
        writeChunk(out, 507, new byte[20]);
        writeChunk(out, 508, new byte[2]);
        writeChunk(out, 509, ZONE_509);
        writeChunk(out, 510, new byte[9]);
        writeChunk(out, 511, new byte[9]);
        writeChunk(out, 512, new byte[9]);
        writeChunk(out, 513, new byte[9]);
        writeChunk(out, 514, new byte[16]);
        writeChunk(out, 514, new byte[16]);
        writeChunk(out, 515, new byte[20]);
        writeChunk(out, 515, new byte[20]);
        writeChunk(out, 516, FIRST_MODULATION);
        for (int i = 1; i < 16; i++) writeChunk(out, 516, new byte[8]);

        long audioLength = ((long) zone.length + SAMPLE_PADDING_FRAMES * 2L) * 2L;
        writeChunkHeader(out, 517, audioLength);
        writePcm16WithPadding(out, zone);
        writeChunk(out, 4, new byte[0]);
    }

    private static void writePcm16WithPadding(OutputStream out, Zone zone) throws IOException {
        byte[] zeroPadding = new byte[SAMPLE_PADDING_FRAMES * 2];
        out.write(zeroPadding);

        byte[] buffer = new byte[8192];
        int cursor = 0;
        int end = zone.offset + zone.length;
        for (int i = zone.offset; i < end; i++) {
            float value = Math.max(-1.0f, Math.min(1.0f, zone.samples[i]));
            short pcm = (short) Math.round(value * 32767.0f);
            buffer[cursor++] = (byte) (pcm & 0xff);
            buffer[cursor++] = (byte) ((pcm >>> 8) & 0xff);
            if (cursor == buffer.length) {
                out.write(buffer);
                cursor = 0;
            }
        }
        if (cursor > 0) out.write(buffer, 0, cursor);
        out.write(zeroPadding);
    }

    private static String zoneName(String programName, Zone zone, int index) {
        String fallback = "Note_" + zone.midiNote;
        String requested = zone.name == null || zone.name.trim().isEmpty() ? fallback : zone.name;
        String clean = sanitizeAscii(requested, fallback, 64);
        return sanitizeAscii(programName + "_" + clean + "_"
                        + String.format(java.util.Locale.US, "%03d", index + 1),
                fallback, 120);
    }

    private static String sanitizeAscii(String input, String fallback, int maxLength) {
        String text = input == null ? "" : input.trim();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length() && out.length() < maxLength; i++) {
            char ch = text.charAt(i);
            if (ch >= 32 && ch <= 126 && ch != '\\' && ch != '/' && ch != ':'
                    && ch != '*' && ch != '?' && ch != '"' && ch != '<' && ch != '>' && ch != '|') {
                out.append(ch);
            } else if (!Character.isWhitespace(ch)) {
                out.append('_');
            } else {
                out.append(' ');
            }
        }
        String clean = out.toString().trim();
        return clean.isEmpty() ? fallback : clean;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static long chunkSize(long payloadLength) {
        return 12L + payloadLength;
    }

    private static long checkedAdd(long left, long right) {
        long value = left + right;
        if (value < left) throw new IllegalArgumentException("O DWP ficou grande demais.");
        return value;
    }

    private static void writeChunk(OutputStream out, int tag, byte[] payload) throws IOException {
        writeChunkHeader(out, tag, payload.length);
        out.write(payload);
    }

    private static void writeChunkHeader(OutputStream out, int tag, long payloadLength)
            throws IOException {
        if (payloadLength < 0) throw new IllegalArgumentException("Tamanho de chunk inválido.");
        writeIntLE(out, tag);
        writeLongLE(out, payloadLength);
    }

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void writeLongLE(OutputStream out, long value) throws IOException {
        for (int i = 0; i < 8; i++) out.write((int) ((value >>> (8 * i)) & 0xff));
    }

    private static void putIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >>> 8) & 0xff);
        data[offset + 2] = (byte) ((value >>> 16) & 0xff);
        data[offset + 3] = (byte) ((value >>> 24) & 0xff);
    }

    private static void putLongLE(byte[] data, int offset, long value) {
        for (int i = 0; i < 8; i++) data[offset + i] = (byte) ((value >>> (8 * i)) & 0xff);
    }

    private static void putFloatLE(byte[] data, int offset, float value) {
        putIntLE(data, offset, Float.floatToIntBits(value));
    }

    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("Hex inválido.");
        byte[] output = new byte[value.length() / 2];
        for (int i = 0; i < output.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Hex inválido.");
            output[i] = (byte) ((high << 4) | low);
        }
        return output;
    }
}
