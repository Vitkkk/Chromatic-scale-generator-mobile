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
        final double fundamental;

        SourceSample(float[] audio, double fundamental) {
            this.audio = audio;
            this.fundamental = fundamental;
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
            throw new IOException("O motor Rubber Band R3 não está disponível nesta instalação.");
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
            int percent = (int) Math.round((i / (double) sampleCount) * 20.0);
            progress(listener, percent, "Detectando o pitch do sample " + (i + 1)
                    + " de " + sampleCount + "…");
            DocumentFile file = files.get((i + 1) + ".wav");
            WavIO.WavData wav = WavIO.read(resolver, file.getUri());
            float[] resampled = WavIO.resampleLinear(wav.samples, wav.sampleRate, OUTPUT_SAMPLE_RATE);
            float[] trimmed = WavIO.trimSilence(resampled, 0.0025f, OUTPUT_SAMPLE_RATE / 200);
            double fundamental = PitchDetector.detectFundamental(trimmed, OUTPUT_SAMPLE_RATE);
            if (!Double.isFinite(fundamental)) {
                throw new IOException("Não consegui detectar o pitch de " + (i + 1)
                        + ".wav. Use um sample vocal limpo, sem silêncio longo ou instrumental.");
            }
            sources[i] = new SourceSample(trimmed, fundamental);
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
            int percent = 20 + (int) Math.round((note / (double) config.noteCount) * 73.0);
            progress(listener, percent, (config.dynamicPitchAttack
                    ? "Aplicando glide nativo na nota " : "Processando em alta qualidade a nota ")
                    + (note + 1) + " de " + config.noteCount + "…");

            SourceSample source = sources[note % sampleCount];
            int absoluteSemitone = config.startNote + note;
            int octave = config.startOctave + Math.floorDiv(absoluteSemitone, 12);
            int noteInOctave = Math.floorMod(absoluteSemitone, 12);
            int midi = 12 * (octave + 1) + noteInOctave;
            double targetFrequency = 440.0 * Math.pow(2.0, (midi - 69) / 12.0);

            float[] pitched = NativePitchShifter.shift(
                    source.audio,
                    source.fundamental,
                    targetFrequency,
                    OUTPUT_SAMPLE_RATE,
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
        progress(listener, 100, "Concluído.");

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
        if (config.startOctave < 0 || config.startOctave > 8) {
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
