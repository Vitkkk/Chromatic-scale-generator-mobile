package com.vitkkk.chromatic.audio;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DwpExporter {
    private static final String[] NOTE_NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    private DwpExporter() {}

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    public static final class Result {
        public final Uri outputUri;
        public final int zoneCount;
        public final int firstMidi;
        public final int lastMidi;

        Result(Uri outputUri, int zoneCount, int firstMidi, int lastMidi) {
            this.outputUri = outputUri;
            this.zoneCount = zoneCount;
            this.firstMidi = firstMidi;
            this.lastMidi = lastMidi;
        }
    }

    public static Result export(Context context, ContentResolver resolver, Uri folderUri,
                                Uri chromaticUri, String requestedFileName,
                                int chromaticStartMidi, int chromaticNoteCount,
                                int noteDurationMs, int gapMs,
                                int firstNote, int lastNote,
                                ProgressListener listener) throws Exception {
        if (chromaticUri == null) throw new IllegalArgumentException("Gere a chromatic antes do DWP.");
        if (firstNote < 1 || lastNote < firstNote || lastNote > chromaticNoteCount) {
            throw new IllegalArgumentException("O intervalo do DWP deve ficar entre 1 e "
                    + chromaticNoteCount + ".");
        }

        int firstMidi = chromaticStartMidi + firstNote - 1;
        int lastMidi = chromaticStartMidi + lastNote - 1;
        if (firstMidi < 0 || lastMidi > 127) {
            throw new IllegalArgumentException("O intervalo escolhido ultrapassa as notas MIDI 0–127.");
        }

        progress(listener, 5, "Lendo a chromatic gerada…");
        WavIO.WavData wav = WavIO.read(resolver, chromaticUri);
        int noteLength = (int) Math.round(noteDurationMs * wav.sampleRate / 1000.0);
        int gapLength = (int) Math.round(gapMs * wav.sampleRate / 1000.0);
        if (noteLength <= 0) throw new IOException("A duração das notas do WAV é inválida.");

        long expected = (long) chromaticNoteCount * noteLength
                + (long) Math.max(0, chromaticNoteCount - 1) * gapLength;
        if (wav.samples.length < expected) {
            throw new IOException("O WAV gerado está menor que a configuração usada para criá-lo.");
        }

        String fileName = sanitizeFileName(requestedFileName);
        String programName = fileName.substring(0, fileName.length() - 4);
        List<DwpWriter.Zone> zones = new ArrayList<>();
        for (int note = firstNote - 1; note < lastNote; note++) {
            int offset = note * (noteLength + gapLength);
            if (offset < 0 || offset + noteLength > wav.samples.length) {
                throw new IOException("Não foi possível localizar a nota " + (note + 1) + " dentro do WAV.");
            }
            int midi = chromaticStartMidi + note;
            zones.add(new DwpWriter.Zone(midi, noteNameForFlStudio(midi),
                    wav.samples, offset, noteLength));
        }

        DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
        if (folder == null || !folder.exists() || !folder.isDirectory() || !folder.canWrite()) {
            throw new IOException("A pasta selecionada não permite salvar o DWP.");
        }

        progress(listener, 15, "Criando o arquivo DirectWave monolítico…");
        DocumentFile output = replaceFile(folder, fileName);
        try (OutputStream stream = resolver.openOutputStream(output.getUri(), "w")) {
            if (stream == null) throw new IOException("Não foi possível abrir o DWP para gravação.");
            DwpWriter.write(stream, programName, zones, wav.sampleRate,
                    (completed, total) -> progress(listener,
                            15 + (int) Math.round(completed * 83.0 / total),
                            "Embutindo nota " + completed + " de " + total + "…"));
        } catch (Exception error) {
            output.delete();
            throw error;
        }

        progress(listener, 100, "DWP concluído.");
        return new Result(output.getUri(), zones.size(), firstMidi, lastMidi);
    }

    private static DocumentFile replaceFile(DocumentFile folder, String fileName) throws IOException {
        for (DocumentFile file : folder.listFiles()) {
            if (fileName.equalsIgnoreCase(file.getName())) {
                if (!file.delete()) throw new IOException("Não foi possível substituir " + fileName + ".");
                break;
            }
        }
        DocumentFile created = folder.createFile("application/octet-stream", fileName);
        if (created == null) throw new IOException("Não foi possível criar " + fileName + ".");
        return created;
    }

    private static String sanitizeFileName(String input) {
        String name = input == null ? "chromatic.dwp" : input.trim();
        if (name.isEmpty()) name = "chromatic.dwp";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".dwp")) name += ".dwp";
        return name;
    }

    private static String noteNameForFlStudio(int midi) {
        int note = Math.floorMod(midi, 12);
        int octave = Math.floorDiv(midi, 12);
        return NOTE_NAMES[note] + octave;
    }

    private static void progress(ProgressListener listener, int percent, String message) {
        if (listener != null) listener.onProgress(percent, message);
    }
}
