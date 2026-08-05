package com.vitkkk.chromatic.audio;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ChromaticGenerator {
    public static final int OUTPUT_SAMPLE_RATE = 48000;
    private static final String[] NOTE_NAMES = {
            "C", "Cs", "D", "Ds", "E", "F", "Fs", "G", "Gs", "A", "As", "B"
    };

    private ChromaticGenerator() {}

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    public static final class Config {
        public int requestedSampleCount;
        public int startNote;
        public int startOctave;
        public int noteCount;
        public int noteDurationMs;
        public int gapMs;
        public int fadeMs;
        public boolean dynamicPitchAttack;
        public int dynamicHoldMs;
        public int dynamicGlideMs;
        public boolean normalize;
        public boolean dumpSamples;
        public String outputFileName;
    }

    public static final class Result {
        public final Uri outputUri;
        public final int sampleCount;
        public final int noteCount;
        public final double durationSeconds;

        public Result(Uri outputUri, int sampleCount, int noteCount, double durationSeconds) {
            this.outputUri = outputUri;
            this.sampleCount = sampleCount;
            this.noteCount = noteCount;
            this.durationSeconds = durationSeconds;
        }
    }

    private static final class SourceSample {
        final float[] audio;
        final int sampleRate;

        SourceSample(float[] audio, int sampleRate) {
            this.audio = audio;
            this.sampleRate = sampleRate;
        }
    }

    public static int countNumberedSamples(DocumentFile folder) {
        Map<String, DocumentFile> files = fileMap(folder);
        int count = 0;
        while (files.containsKey((count + 1) + ".wav")) count++;
        return count;
    }

    public static Result generate(Context context, ContentResolver resolver, Uri folderUri,
                                  Config config, ProgressListener listener) throws Exception {
        validate(config);
        if (!NativePitchShifter.isAvailable()) {
            throw new IOException("O motor Praat 6.1.38 não está disponível nesta instalação.");
        }

        DocumentFile folder = DocumentFile.fromTreeUri(context, folderUri);
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            throw new IOException("A pasta selecionada não está mais disponível.");
        }
        if (!folder.canWrite()) {
            throw new IOException("A pasta selecionada não permite salvar arquivos.");
        }

        Map<String, DocumentFile> files = fileMap(folder);
        int detected = 0;
        while (files.containsKey((detected + 1) + ".wav")) detected++;
        if (detected == 0) {
            throw new IOException("Nenhum sample numerado foi encontrado. Use 1.wav, 2.wav, 3.wav…");
        }

        int sampleCount = config.requestedSampleCount <= 0 ? detected : config.requestedSampleCount;
        if (sampleCount > detected) {
            throw new IOException("Foram pedidos " + sampleCount + " samples, mas só " + detected
                    + " foram encontrados em sequência.");
        }

        SourceSample[] sources = new SourceSample[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int percent = (int) Math.round((i / (double) sampleCount) * 15.0);
            progress(listener, percent, "Carregando sample " + (i + 1)
                    + " de " + sampleCount + " sem alterar o áudio…");
            DocumentFile file = files.get((i + 1) + ".wav");
            WavIO.WavData wav = WavIO.read(resolver, file.getUri());
            // The desktop sends the full WAV to Praat. Do not trim silence and do not
            // run a separate F0 detector before To Manipulation.
            sources[i] = new SourceSample(wav.samples, wav.sampleRate);
        }

        int noteLength = msToSamples(config.noteDurationMs);
        int gapLength = msToSamples(config.gapMs);
        int dynamicHoldSamples = config.dynamicPitchAttack ? msToSamples(config.dynamicHoldMs) : 0;
        int dynamicGlideSamples = config.dynamicPitchAttack ? msToSamples(config.dynamicGlideMs) : 0;
        long totalLong = (long) config.noteCount * noteLength
                + (long) Math.max(0, config.noteCount - 1) * gapLength;
        if (totalLong > Integer.MAX_VALUE) {
            throw new IOException("A chromatic ficou grande demais para gerar no celular.");
        }
        float[] chromatic = new float[(int) totalLong];

        DocumentFile pitchedFolder = null;
        if (config.dumpSamples) {
            pitchedFolder = files.get("pitched_samples");
            if (pitchedFolder == null || !pitchedFolder.isDirectory()) {
                pitchedFolder = folder.createDirectory("pitched_samples");
            }
            if (pitchedFolder == null) {
                throw new IOException("Não foi possível criar a pasta pitched_samples.");
            }
        }

        int cursor = 0;
        for (int note = 0; note < config.noteCount; note++) {
            int percent = 15 + (int) Math.round((note / (double) config.noteCount) * 78.0);
            progress(listener, percent, (config.dynamicPitchAttack
                    ? "Editando PitchTier dinâmico no Praat: nota "
                    : "Ressintetizando com Praat 6.1.38: nota ")
                    + (note + 1) + " de " + config.noteCount + "…");

            SourceSample source = sources[note % sampleCount];
            int absoluteSemitone = config.startNote + note;
            int octave = config.startOctave + Math.floorDiv(absoluteSemitone, 12);
            int noteInOctave = Math.floorMod(absoluteSemitone, 12);

            // Use the exact frequency formula from chromatic_gen.py on Windows.
            int desktopStartingKey = config.startNote + 12 * (config.startOctave - 2);
            double targetFrequency = 32.703
                    * Math.pow(2.0, (note + desktopStartingKey + 12) / 12.0);

            float[] pitched = NativePitchShifter.shift(
                    source.audio,
                    source.sampleRate,
                    targetFrequency,
                    noteLength,
                    dynamicHoldSamples,
                    dynamicGlideSamples);
            applyFade(pitched, msToSamples(config.fadeMs));
            if (config.normalize) normalize(pitched, 0.94f);

            System.arraycopy(pitched, 0, chromatic, cursor, pitched.length);
            cursor += pitched.length;
            if (note < config.noteCount - 1) cursor += gapLength;

            if (pitchedFolder != null) {
                String name = String.format(Locale.US, "pitched_%02d_%s%d.wav",
                        note + 1, NOTE_NAMES[noteInOctave], octave);
                DocumentFile individual = replaceFile(pitchedFolder, name);
                WavIO.write(resolver, individual.getUri(), pitched, OUTPUT_SAMPLE_RATE);
            }
        }

        progress(listener, 95, "Salvando WAV final…");
        String outputName = sanitizeFileName(config.outputFileName);
        DocumentFile output = replaceFile(folder, outputName);
        WavIO.write(resolver, output.getUri(), chromatic, OUTPUT_SAMPLE_RATE);
        progress(listener, 100, "Concluído com o motor original do desktop.");

        return new Result(output.getUri(), sampleCount, config.noteCount,
                chromatic.length / (double) OUTPUT_SAMPLE_RATE);
    }

    private static DocumentFile replaceFile(DocumentFile folder, String fileName) throws IOException {
        DocumentFile existing = null;
        for (DocumentFile file : folder.listFiles()) {
            if (fileName.equalsIgnoreCase(file.getName())) {
                existing = file;
                break;
            }
        }
        if (existing != null && !existing.delete()) {
            throw new IOException("Não foi possível substituir " + fileName + ".");
        }
        DocumentFile created = folder.createFile("audio/wav", fileName);
        if (created == null) throw new IOException("Não foi possível criar " + fileName + ".");
        return created;
    }

    private static Map<String, DocumentFile> fileMap(DocumentFile folder) {
        Map<String, DocumentFile> map = new HashMap<>();
        for (DocumentFile file : folder.listFiles()) {
            String name = file.getName();
            if (name != null) map.put(name.toLowerCase(Locale.ROOT), file);
        }
        return map;
    }

    private static void validate(Config config) {
        if (config.requestedSampleCount < 0 || config.requestedSampleCount > 999) {
            throw new IllegalArgumentException("A quantidade de samples deve ser 0 (automático) ou um valor entre 1 e 999.");
        }
        if (config.startNote < 0 || config.startNote > 11) {
            throw new IllegalArgumentException("Nota inicial inválida.");
        }
        if (config.startOctave < 1 || config.startOctave > 6) {
            throw new IllegalArgumentException("Oitava inicial inválida.");
        }
        if (config.noteCount < 1 || config.noteCount > 120) {
            throw new IllegalArgumentException("A quantidade de notas deve estar entre 1 e 120.");
        }
        if (config.noteDurationMs < 40 || config.noteDurationMs > 10000) {
            throw new IllegalArgumentException("A duração de cada nota deve ficar entre 40 e 10000 ms.");
        }
        if (config.gapMs < 0 || config.gapMs > 10000) {
            throw new IllegalArgumentException("O gap deve ficar entre 0 e 10000 ms.");
        }
        if (config.fadeMs < 0 || config.fadeMs > config.noteDurationMs / 2) {
            throw new IllegalArgumentException("O fade deve ser menor que metade da duração da nota.");
        }
        if (config.dynamicHoldMs < 0 || config.dynamicHoldMs > 2000) {
            throw new IllegalArgumentException("O pitch original deve ficar entre 0 e 2000 ms.");
        }
        if (config.dynamicGlideMs < 0 || config.dynamicGlideMs > 2000) {
            throw new IllegalArgumentException("A transição dinâmica deve ficar entre 0 e 2000 ms.");
        }
        if (config.dynamicPitchAttack && config.dynamicHoldMs == 0 && config.dynamicGlideMs == 0) {
            throw new IllegalArgumentException("Defina algum tempo de pitch original ou de transição dinâmica.");
        }
    }

    private static String sanitizeFileName(String input) {
        String name = input == null ? "chromatic.wav" : input.trim();
        if (name.isEmpty()) name = "chromatic.wav";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.toLowerCase(Locale.ROOT).endsWith(".wav")) name += ".wav";
        return name;
    }

    private static int msToSamples(int milliseconds) {
        return (int) Math.round(milliseconds * OUTPUT_SAMPLE_RATE / 1000.0);
    }

    private static void applyFade(float[] audio, int fadeSamples) {
        int fade = Math.min(fadeSamples, audio.length / 2);
        for (int i = 0; i < fade; i++) {
            float gain = fade <= 1 ? 1.0f : i / (float) (fade - 1);
            audio[i] *= gain;
            audio[audio.length - 1 - i] *= gain;
        }
    }

    private static void normalize(float[] audio, float targetPeak) {
        float peak = 0.0f;
        for (float sample : audio) peak = Math.max(peak, Math.abs(sample));
        if (peak < 1e-6f) return;
        float gain = Math.min(8.0f, targetPeak / peak);
        for (int i = 0; i < audio.length; i++) {
            audio[i] = Math.max(-1.0f, Math.min(1.0f, audio[i] * gain));
        }
    }

    private static void progress(ProgressListener listener, int percent, String message) {
        if (listener != null) listener.onProgress(percent, message);
    }
}
