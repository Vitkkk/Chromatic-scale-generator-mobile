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
        public final boolean loopEnabled;

        Result(Uri outputUri, int zoneCount, int firstMidi, int lastMidi, boolean loopEnabled) {
            this.outputUri = outputUri;
            this.zoneCount = zoneCount;
            this.firstMidi = firstMidi;
            this.lastMidi = lastMidi;
            this.loopEnabled = loopEnabled;
        }
    }

    public static Result export(Context context, ContentResolver resolver, Uri folderUri,
                                Uri chromaticUri, String requestedFileName,
                                int chromaticStartMidi, int chromaticNoteCount,
                                int noteDurationMs, int gapMs,
                                int firstNote, int lastNote,
                                boolean loopEnabled, int loopStartPercent, int loopEndPercent,
                                ProgressListener listener) throws Exception {
        if (chromaticUri == null) throw new IllegalArgumentException("Gere a chromatic antes do DWP.");
        if (firstNote < 1 || lastNote < firstNote || lastNote > chromaticNoteCount) {
            throw new IllegalArgumentException("O intervalo do DWP deve ficar entre 1 e "
                    + chromaticNoteCount + ".");
        }
        validateLoop(loopEnabled, loopStartPercent, loopEndPercent);

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
            if (loopEnabled) {
                int[] loop = chooseLoopPoints(wav.samples, offset, noteLength, wav.sampleRate,
                        loopStartPercent, loopEndPercent);
                zones.add(new DwpWriter.Zone(midi, noteNameForFlStudio(midi),
                        wav.samples, offset, noteLength, true, loop[0], loop[1]));
            } else {
                zones.add(new DwpWriter.Zone(midi, noteNameForFlStudio(midi),
                        wav.samples, offset, noteLength));
            }
        }

        DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
        if (folder == null || !folder.exists() || !folder.isDirectory() || !folder.canWrite()) {
            throw new IOException("A pasta selecionada não permite salvar o DWP.");
        }

        progress(listener, 15, loopEnabled
                ? "Criando DirectWave monolítico com loops…"
                : "Criando o arquivo DirectWave monolítico…");
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
        return new Result(output.getUri(), zones.size(), firstMidi, lastMidi, loopEnabled);
    }

    static int[] chooseLoopPoints(float[] samples, int offset, int length, int sampleRate,
                                  int startPercent, int endPercent) {
        if (samples == null || offset < 0 || length <= 0 || offset + length > samples.length) {
            throw new IllegalArgumentException("Áudio inválido para calcular o loop.");
        }

        int minimumLoop = Math.max(sampleRate / 20, Math.min(length / 4, 256));
        int startTarget = clamp((int) Math.round(length * startPercent / 100.0),
                32, Math.max(32, length - minimumLoop - 32));
        int endTarget = clamp((int) Math.round(length * endPercent / 100.0),
                startTarget + minimumLoop, length - 16);

        int zeroRadius = Math.max(8, sampleRate / 100);
        int absoluteStart = nearestUpwardZeroCrossing(samples, offset + startTarget,
                offset + 16, offset + length - minimumLoop, zeroRadius);
        int loopStart = absoluteStart - offset;

        int minEnd = loopStart + minimumLoop;
        int maxEnd = length - 8;
        endTarget = clamp(endTarget, minEnd, maxEnd);
        int phaseRadius = Math.max(16, sampleRate / 40);
        int compareWindow = Math.max(16, Math.min(sampleRate / 250, minimumLoop / 5));
        int loopEnd = matchingPhaseEnd(samples, offset, loopStart, endTarget,
                minEnd, maxEnd, phaseRadius, compareWindow);

        if (loopEnd <= loopStart) {
            loopEnd = Math.min(length - 8, loopStart + minimumLoop);
        }
        return new int[]{loopStart, loopEnd};
    }

    private static int nearestUpwardZeroCrossing(float[] samples, int target,
                                                  int minimum, int maximum, int radius) {
        int from = Math.max(minimum, target - radius);
        int to = Math.min(maximum, target + radius);
        int best = clamp(target, from, to);
        int bestDistance = Integer.MAX_VALUE;
        for (int i = Math.max(from, 1); i <= to; i++) {
            float previous = samples[i - 1];
            float current = samples[i];
            if (previous <= 0.0f && current > 0.0f) {
                int distance = Math.abs(i - target);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
        }
        return best;
    }

    private static int matchingPhaseEnd(float[] samples, int offset, int loopStart,
                                        int targetEnd, int minimumEnd, int maximumEnd,
                                        int searchRadius, int window) {
        int from = Math.max(minimumEnd, targetEnd - searchRadius);
        int to = Math.min(maximumEnd, targetEnd + searchRadius);
        int absoluteStart = offset + loopStart;
        int best = clamp(targetEnd, from, to);
        double bestScore = Double.POSITIVE_INFINITY;

        for (int candidate = from; candidate <= to; candidate++) {
            int absoluteCandidate = offset + candidate;
            int usableBefore = Math.min(window,
                    Math.min(absoluteStart - offset, absoluteCandidate - offset));
            int usableAfter = Math.min(window,
                    Math.min(offset + maximumEnd - absoluteStart,
                            offset + maximumEnd - absoluteCandidate));
            if (usableBefore + usableAfter < 16) continue;

            double difference = 0.0;
            double energy = 1e-9;
            for (int k = -usableBefore; k < usableAfter; k++) {
                double a = samples[absoluteStart + k];
                double b = samples[absoluteCandidate + k];
                double delta = a - b;
                difference += delta * delta;
                energy += a * a + b * b;
            }
            double score = difference / energy
                    + Math.abs(candidate - targetEnd) * 1e-7;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static void validateLoop(boolean enabled, int startPercent, int endPercent) {
        if (!enabled) return;
        if (startPercent < 5 || startPercent > 85) {
            throw new IllegalArgumentException("O início do loop deve ficar entre 5% e 85%.");
        }
        if (endPercent < 15 || endPercent > 98) {
            throw new IllegalArgumentException("O fim do loop deve ficar entre 15% e 98%.");
        }
        if (endPercent - startPercent < 10) {
            throw new IllegalArgumentException("O loop precisa ter pelo menos 10% da duração da nota.");
        }
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

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void progress(ProgressListener listener, int percent, String message) {
        if (listener != null) listener.onProgress(percent, message);
    }
}
