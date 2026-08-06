package com.vitkkk.chromatic;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.vitkkk.chromatic.audio.ChromaticGenerator;
import com.vitkkk.chromatic.audio.DwpExporter;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "chromatic_generator";
    private static final String PREF_FOLDER_URI = "folder_uri";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private MaterialButton selectFolderButton;
    private MaterialButton generateButton;
    private MaterialButton previewButton;
    private MaterialButton dwpButton;
    private TextView folderText;
    private TextView detectedSamplesText;
    private TextView statusText;
    private EditText sampleCountInput;
    private EditText noteCountInput;
    private EditText noteDurationInput;
    private EditText gapInput;
    private EditText fadeInput;
    private EditText dynamicHoldInput;
    private EditText dynamicGlideInput;
    private EditText fileNameInput;
    private EditText dwpRangeStartInput;
    private EditText dwpRangeEndInput;
    private EditText dwpFileNameInput;
    private EditText dwpLoopStartInput;
    private EditText dwpLoopEndInput;
    private Spinner startNoteSpinner;
    private Spinner startOctaveSpinner;
    private MaterialSwitch dynamicPitchSwitch;
    private MaterialSwitch normalizeSwitch;
    private MaterialSwitch dumpSamplesSwitch;
    private MaterialSwitch dwpEntireSwitch;
    private MaterialSwitch dwpLoopSwitch;
    private ProgressBar progressBar;

    private Uri folderUri;
    private Uri generatedUri;
    private ChromaticGenerator.Config generatedConfig;
    private ChromaticGenerator.Result generatedResult;
    private MediaPlayer mediaPlayer;
    private boolean busy;

    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderSelected);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        configureSpinners();
        if (savedInstanceState == null) applyDesktopDefaults();

        selectFolderButton.setOnClickListener(view -> folderPicker.launch(folderUri));
        generateButton.setOnClickListener(view -> startGeneration());
        previewButton.setOnClickListener(view -> togglePreview());
        dwpButton.setOnClickListener(view -> startDwpExport());
        dynamicPitchSwitch.setOnCheckedChangeListener((button, checked) -> updateGenerationControls());
        dwpEntireSwitch.setOnCheckedChangeListener((button, checked) -> updateDwpControls());
        dwpLoopSwitch.setOnCheckedChangeListener((button, checked) -> updateDwpControls());

        String savedUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_FOLDER_URI, null);
        if (!TextUtils.isEmpty(savedUri)) {
            folderUri = Uri.parse(savedUri);
            updateFolderUi();
            detectSamplesAsync();
        }
        updateGenerationControls();
        updateDwpControls();
    }

    private void bindViews() {
        selectFolderButton = findViewById(R.id.selectFolderButton);
        generateButton = findViewById(R.id.generateButton);
        previewButton = findViewById(R.id.previewButton);
        dwpButton = findViewById(R.id.dwpButton);
        folderText = findViewById(R.id.folderText);
        detectedSamplesText = findViewById(R.id.detectedSamplesText);
        statusText = findViewById(R.id.statusText);
        sampleCountInput = findViewById(R.id.sampleCountInput);
        noteCountInput = findViewById(R.id.noteCountInput);
        noteDurationInput = findViewById(R.id.noteDurationInput);
        gapInput = findViewById(R.id.gapInput);
        fadeInput = findViewById(R.id.fadeInput);
        dynamicHoldInput = findViewById(R.id.dynamicHoldInput);
        dynamicGlideInput = findViewById(R.id.dynamicGlideInput);
        fileNameInput = findViewById(R.id.fileNameInput);
        dwpRangeStartInput = findViewById(R.id.dwpRangeStartInput);
        dwpRangeEndInput = findViewById(R.id.dwpRangeEndInput);
        dwpFileNameInput = findViewById(R.id.dwpFileNameInput);
        dwpLoopStartInput = findViewById(R.id.dwpLoopStartInput);
        dwpLoopEndInput = findViewById(R.id.dwpLoopEndInput);
        startNoteSpinner = findViewById(R.id.startNoteSpinner);
        startOctaveSpinner = findViewById(R.id.startOctaveSpinner);
        dynamicPitchSwitch = findViewById(R.id.dynamicPitchSwitch);
        normalizeSwitch = findViewById(R.id.normalizeSwitch);
        dumpSamplesSwitch = findViewById(R.id.dumpSamplesSwitch);
        dwpEntireSwitch = findViewById(R.id.dwpEntireSwitch);
        dwpLoopSwitch = findViewById(R.id.dwpLoopSwitch);
        progressBar = findViewById(R.id.progressBar);
    }

    private void configureSpinners() {
        String[] notes = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        Integer[] octaves = {1, 2, 3, 4, 5, 6};
        startNoteSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, notes));
        startOctaveSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, octaves));
        startNoteSpinner.setSelection(0);
        startOctaveSpinner.setSelection(1);
    }

    private void applyDesktopDefaults() {
        noteDurationInput.setText("0");
        fadeInput.setText("0");
        normalizeSwitch.setChecked(false);
    }

    private void onFolderSelected(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        folderUri = uri;
        resetGeneratedState();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_FOLDER_URI, uri.toString()).apply();
        updateFolderUi();
        detectSamplesAsync();
    }

    private void resetGeneratedState() {
        stopPreview();
        generatedUri = null;
        generatedConfig = null;
        generatedResult = null;
        previewButton.setEnabled(false);
        dwpButton.setEnabled(false);
        updateDwpControls();
    }

    private void updateFolderUi() {
        if (folderUri == null) {
            folderText.setText(R.string.no_folder);
            return;
        }
        DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
        String name = folder != null && folder.getName() != null
                ? folder.getName() : folderUri.toString();
        folderText.setText("Pasta: " + name);
    }

    private void detectSamplesAsync() {
        if (folderUri == null) return;
        detectedSamplesText.setText("Procurando samples…");
        executor.execute(() -> {
            try {
                DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
                int count = folder == null ? 0 : ChromaticGenerator.countNumberedSamples(folder);
                runOnUiThread(() -> detectedSamplesText.setText("Samples detectados: " + count));
            } catch (Exception error) {
                runOnUiThread(() -> detectedSamplesText.setText("Não foi possível ler a pasta."));
            }
        });
    }

    private void startGeneration() {
        if (folderUri == null) {
            showError("Selecione primeiro a pasta que contém 1.wav, 2.wav, 3.wav…");
            return;
        }

        final ChromaticGenerator.Config config;
        try {
            config = readConfig();
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }

        resetGeneratedState();
        setBusy(true);
        statusText.setText("Preparando geração…");
        progressBar.setProgress(0);

        executor.execute(() -> {
            try {
                ChromaticGenerator.Result result = ChromaticGenerator.generate(
                        this, getContentResolver(), folderUri, config,
                        (percent, message) -> runOnUiThread(() -> {
                            progressBar.setProgress(percent);
                            statusText.setText(message);
                        }));
                runOnUiThread(() -> {
                    generatedUri = result.outputUri;
                    generatedConfig = config;
                    generatedResult = result;
                    prepareDwpDefaults(config);
                    setBusy(false);
                    String dynamicText = config.dynamicPitchAttack ? " Ataque dinâmico aplicado." : "";
                    String durationText = config.noteDurationMs == 0
                            ? " Duração natural do PC." : " Duração personalizada aplicada depois do Praat.";
                    statusText.setText(String.format(Locale.getDefault(),
                            "Pronto: %d notas, %d samples, %.2f s.%s%s Agora você pode ouvir ou criar o DWP.",
                            result.noteCount, result.sampleCount, result.durationSeconds,
                            durationText, dynamicText));
                    Toast.makeText(this, "Chromatic gerada com sucesso!",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("Falha na geração.");
                    showError(error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage());
                });
            }
        });
    }

    private void prepareDwpDefaults(ChromaticGenerator.Config config) {
        dwpRangeStartInput.setText("1");
        dwpRangeEndInput.setText(String.valueOf(config.noteCount));
        dwpFileNameInput.setText(toDwpFileName(config.outputFileName));
        if (dwpLoopStartInput.getText() == null
                || dwpLoopStartInput.getText().toString().trim().isEmpty()) {
            dwpLoopStartInput.setText("35");
        }
        if (dwpLoopEndInput.getText() == null
                || dwpLoopEndInput.getText().toString().trim().isEmpty()) {
            dwpLoopEndInput.setText("90");
        }
        updateDwpControls();
    }

    private void startDwpExport() {
        if (generatedUri == null || generatedConfig == null || generatedResult == null) {
            showError("Gere uma chromatic antes de criar o DWP.");
            return;
        }

        final int firstNote;
        final int lastNote;
        final boolean loopEnabled = dwpLoopSwitch.isChecked();
        final int loopStartPercent;
        final int loopEndPercent;
        try {
            if (dwpEntireSwitch.isChecked()) {
                firstNote = 1;
                lastNote = generatedConfig.noteCount;
            } else {
                firstNote = parseInteger(dwpRangeStartInput, "Primeira nota do DWP");
                lastNote = parseInteger(dwpRangeEndInput, "Última nota do DWP");
            }
            if (loopEnabled) {
                loopStartPercent = parseInteger(dwpLoopStartInput, "Início do loop");
                loopEndPercent = parseInteger(dwpLoopEndInput, "Fim do loop");
            } else {
                loopStartPercent = 35;
                loopEndPercent = 90;
            }
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }

        String outputName = dwpFileNameInput.getText() == null
                ? "chromatic.dwp" : dwpFileNameInput.getText().toString();
        int startMidi = 12 * (generatedConfig.startOctave + 1) + generatedConfig.startNote;

        stopPreview();
        setBusy(true);
        progressBar.setProgress(0);
        statusText.setText("Preparando DirectWave…");

        executor.execute(() -> {
            try {
                DwpExporter.Result result = DwpExporter.export(
                        this, getContentResolver(), folderUri, generatedUri, outputName,
                        startMidi, generatedConfig.noteCount,
                        generatedResult.noteOffsets, generatedResult.noteLengths,
                        firstNote, lastNote,
                        loopEnabled, loopStartPercent, loopEndPercent,
                        (percent, message) -> runOnUiThread(() -> {
                            progressBar.setProgress(percent);
                            statusText.setText(message);
                        }));
                runOnUiThread(() -> {
                    setBusy(false);
                    String loopText = result.loopEnabled ? " Loop contínuo embutido." : "";
                    statusText.setText(String.format(Locale.getDefault(),
                            "DWP salvo: %d zonas, MIDI %d–%d. A primeira e a última cobrem o teclado inteiro.%s",
                            result.zoneCount, result.firstMidi, result.lastMidi, loopText));
                    Toast.makeText(this, "DirectWave .dwp criado com sucesso!",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    statusText.setText("Falha ao criar o DWP.");
                    showError(error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage());
                });
            }
        });
    }

    private ChromaticGenerator.Config readConfig() {
        ChromaticGenerator.Config config = new ChromaticGenerator.Config();
        config.requestedSampleCount = parseInteger(sampleCountInput, "Quantidade de samples");
        config.startNote = startNoteSpinner.getSelectedItemPosition();
        config.startOctave = (Integer) startOctaveSpinner.getSelectedItem();
        config.noteCount = parseInteger(noteCountInput, "Quantidade de notas");
        config.noteDurationMs = parseInteger(noteDurationInput, "Duração da nota");
        config.gapMs = parseInteger(gapInput, "Gap");
        config.fadeMs = parseInteger(fadeInput, "Fade");
        config.dynamicPitchAttack = dynamicPitchSwitch.isChecked();
        config.dynamicHoldMs = config.dynamicPitchAttack
                ? parseInteger(dynamicHoldInput, "Pitch original") : 20;
        config.dynamicGlideMs = config.dynamicPitchAttack
                ? parseInteger(dynamicGlideInput, "Transição dinâmica") : 45;
        config.normalize = normalizeSwitch.isChecked();
        config.dumpSamples = dumpSamplesSwitch.isChecked();
        config.outputFileName = fileNameInput.getText() == null
                ? "chromatic.wav" : fileNameInput.getText().toString();
        return config;
    }

    private int parseInteger(EditText input, String label) {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        if (value.isEmpty()) throw new IllegalArgumentException(label + " não pode ficar vazio.");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " precisa ser um número inteiro.");
        }
    }

    private void togglePreview() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            stopPreview();
            return;
        }
        if (generatedUri == null) {
            showError("Gere uma chromatic antes de ouvir.");
            return;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, generatedUri);
            mediaPlayer.setOnPreparedListener(player -> {
                player.start();
                previewButton.setText(R.string.stop_preview);
            });
            mediaPlayer.setOnCompletionListener(player -> stopPreview());
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                stopPreview();
                showError("Não foi possível reproduzir o WAV gerado.");
                return true;
            });
            mediaPlayer.prepareAsync();
            statusText.setText("Carregando prévia…");
        } catch (Exception error) {
            stopPreview();
            showError("Não foi possível abrir a prévia: " + error.getMessage());
        }
    }

    private void stopPreview() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (previewButton != null) previewButton.setText(R.string.preview);
    }

    private void setBusy(boolean isBusy) {
        busy = isBusy;
        progressBar.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        selectFolderButton.setEnabled(!isBusy);
        generateButton.setEnabled(!isBusy);
        boolean hasResult = generatedUri != null && generatedConfig != null && generatedResult != null;
        previewButton.setEnabled(!isBusy && hasResult);
        dwpButton.setEnabled(!isBusy && hasResult);
        updateGenerationControls();
        updateDwpControls();
    }

    private void updateGenerationControls() {
        dynamicPitchSwitch.setEnabled(!busy);
        boolean dynamicAvailable = !busy && dynamicPitchSwitch.isChecked();
        dynamicHoldInput.setEnabled(dynamicAvailable);
        dynamicGlideInput.setEnabled(dynamicAvailable);
    }

    private void updateDwpControls() {
        boolean hasResult = generatedUri != null && generatedConfig != null && generatedResult != null;
        boolean available = !busy && hasResult;
        dwpEntireSwitch.setEnabled(available);
        dwpFileNameInput.setEnabled(available);
        dwpLoopSwitch.setEnabled(available);

        boolean customRange = available && !dwpEntireSwitch.isChecked();
        dwpRangeStartInput.setEnabled(customRange);
        dwpRangeEndInput.setEnabled(customRange);

        boolean customLoop = available && dwpLoopSwitch.isChecked();
        dwpLoopStartInput.setEnabled(customLoop);
        dwpLoopEndInput.setEnabled(customLoop);
    }

    private String toDwpFileName(String wavName) {
        String name = wavName == null ? "chromatic" : wavName.trim();
        if (name.isEmpty()) name = "chromatic";
        if (name.toLowerCase(Locale.ROOT).endsWith(".wav")) {
            name = name.substring(0, name.length() - 4);
        }
        return name + ".dwp";
    }

    private void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        executor.shutdownNow();
        super.onDestroy();
    }
}
