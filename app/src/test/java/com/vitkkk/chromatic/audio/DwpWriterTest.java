package com.vitkkk.chromatic.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DwpWriterTest {
    @Test
    public void writesMonolithicZonesWithExtendedEdgeMappingAndLoops() throws Exception {
        float[] note = new float[16800];
        for (int i = 0; i < note.length; i++) {
            note[i] = (float) (0.45 * Math.sin(2.0 * Math.PI * 220.0 * i / 48000.0));
        }

        List<DwpWriter.Zone> zones = new ArrayList<>();
        zones.add(new DwpWriter.Zone(60, "C5", note, 0, note.length,
                true, 5000, 14000));
        zones.add(new DwpWriter.Zone(61, "C#5", note));
        zones.add(new DwpWriter.Zone(62, "D5", note, 0, note.length,
                true, 5200, 14200));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DwpWriter.write(output, "Test Chromatic", zones, 48000, null);
        byte[] bytes = output.toByteArray();

        assertEquals("DwPr", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(37, int32(bytes, 4));

        Chunk global = chunk(bytes, 8);
        assertEquals(6, global.tag);
        assertEquals(16, global.length);

        Chunk program = chunk(bytes, global.end());
        assertEquals(1, program.tag);
        assertEquals(bytes.length, program.end());

        List<Chunk> programChunks = children(bytes, program);
        assertEquals(100, programChunks.get(0).tag);
        assertEquals(3, int64(bytes, programChunks.get(0).dataOffset + 14));

        List<Chunk> zoneChunks = new ArrayList<>();
        for (Chunk candidate : programChunks) {
            if (candidate.tag == 3) zoneChunks.add(candidate);
        }
        assertEquals(3, zoneChunks.size());

        for (int index = 0; index < zoneChunks.size(); index++) {
            int midi = 60 + index;
            List<Chunk> fields = children(bytes, zoneChunks.get(index));
            Chunk zoneParams = find(fields, 500);
            assertEquals(midi, bytes[zoneParams.dataOffset] & 0xff);
            assertEquals(index == 0 ? 0 : midi,
                    bytes[zoneParams.dataOffset + 1] & 0xff);
            assertEquals(index == zoneChunks.size() - 1 ? 127 : midi,
                    bytes[zoneParams.dataOffset + 2] & 0xff);

            Chunk sampleInfo = find(fields, 503);
            assertEquals(note.length, int32(bytes, sampleInfo.dataOffset));
            assertEquals(1, int32(bytes, sampleInfo.dataOffset + 8));
            assertEquals(4, int32(bytes, sampleInfo.dataOffset + 12));
            assertEquals(48000.0f, float32(bytes, sampleInfo.dataOffset + 16), 0.01f);
            assertEquals(16, int32(bytes, sampleInfo.dataOffset + 36));

            if (index == 1) {
                assertEquals(0, int32(bytes, sampleInfo.dataOffset + 4));
                assertEquals(0, int32(bytes, sampleInfo.dataOffset + 20));
                assertEquals(0, int32(bytes, sampleInfo.dataOffset + 24));
                assertEquals(0, int32(bytes, sampleInfo.dataOffset + 28));
                assertEquals(0, int32(bytes, sampleInfo.dataOffset + 32));
            } else {
                assertEquals(1, int32(bytes, sampleInfo.dataOffset + 4));
                assertEquals(0, int32(bytes, sampleInfo.dataOffset + 20));
                assertEquals(note.length, int32(bytes, sampleInfo.dataOffset + 24));
                assertEquals(index == 0 ? 5000 : 5200,
                        int32(bytes, sampleInfo.dataOffset + 28));
                assertEquals(index == 0 ? 14000 : 14200,
                        int32(bytes, sampleInfo.dataOffset + 32));
            }

            Chunk pcm = find(fields, 517);
            assertEquals((note.length + 512L) * 2L, pcm.length);
            for (int i = 0; i < 512; i++) {
                assertEquals(0, bytes[pcm.dataOffset + i]);
                assertEquals(0, bytes[pcm.end() - 1 - i]);
            }
            boolean hasAudio = false;
            for (int i = pcm.dataOffset + 512; i < pcm.end() - 512; i++) {
                if (bytes[i] != 0) {
                    hasAudio = true;
                    break;
                }
            }
            assertTrue(hasAudio);
            assertEquals(4, fields.get(fields.size() - 1).tag);
        }

        assertEquals(2, programChunks.get(programChunks.size() - 1).tag);
    }

    @Test
    public void singleZoneCoversEntireKeyboard() throws Exception {
        float[] note = new float[4000];
        List<DwpWriter.Zone> zones = new ArrayList<>();
        zones.add(new DwpWriter.Zone(69, "A5", note));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DwpWriter.write(output, "Single", zones, 48000, null);
        byte[] bytes = output.toByteArray();

        Chunk global = chunk(bytes, 8);
        Chunk program = chunk(bytes, global.end());
        List<Chunk> programChunks = children(bytes, program);
        Chunk onlyZone = null;
        for (Chunk candidate : programChunks) if (candidate.tag == 3) onlyZone = candidate;
        assertTrue(onlyZone != null);
        Chunk params = find(children(bytes, onlyZone), 500);
        assertEquals(69, bytes[params.dataOffset] & 0xff);
        assertEquals(0, bytes[params.dataOffset + 1] & 0xff);
        assertEquals(127, bytes[params.dataOffset + 2] & 0xff);
    }

    private static Chunk find(List<Chunk> chunks, int tag) {
        for (Chunk chunk : chunks) if (chunk.tag == tag) return chunk;
        throw new AssertionError("Chunk não encontrado: " + tag);
    }

    private static List<Chunk> children(byte[] bytes, Chunk parent) {
        List<Chunk> chunks = new ArrayList<>();
        int cursor = parent.dataOffset;
        while (cursor < parent.end()) {
            Chunk chunk = chunk(bytes, cursor);
            chunks.add(chunk);
            cursor = chunk.end();
        }
        assertEquals(parent.end(), cursor);
        return chunks;
    }

    private static Chunk chunk(byte[] bytes, int offset) {
        int tag = int32(bytes, offset);
        long length = int64(bytes, offset + 4);
        if (length < 0 || length > Integer.MAX_VALUE) throw new AssertionError("Chunk grande demais no teste.");
        int dataOffset = offset + 12;
        int end = dataOffset + (int) length;
        if (end < dataOffset || end > bytes.length) throw new AssertionError("Chunk fora do arquivo.");
        return new Chunk(tag, length, dataOffset);
    }

    private static int int32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | (bytes[offset + 3] << 24);
    }

    private static long int64(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (bytes[offset + i] & 0xffL);
        return value;
    }

    private static float float32(byte[] bytes, int offset) {
        return Float.intBitsToFloat(int32(bytes, offset));
    }

    private static final class Chunk {
        final int tag;
        final long length;
        final int dataOffset;

        Chunk(int tag, long length, int dataOffset) {
            this.tag = tag;
            this.length = length;
            this.dataOffset = dataOffset;
        }

        int end() {
            return dataOffset + (int) length;
        }
    }
}
