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
    private TextView folderText;
    private TextView detectedSamplesText;
    private TextView statusText;
    private EditText sampleCountInput;
    private EditText noteCountInput;
    private EditText noteDurationInput;
    private EditText gapInput;
    private EditText fadeInput;
    private EditText fileNameInput;
    private Spinner startNoteSpinner;
    private Spinner startOctaveSpinner;
    private MaterialSwitch normalizeSwitch;
    private MaterialSwitch dumpSamplesSwitch;
    private ProgressBar progressBar;

    private Uri folderUri;
    private Uri generatedUri;
    private MediaPlayer mediaPlayer;

    private final ActivityResultLauncher<Uri> folderPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onFolderSelected);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        configureSpinners();

        selectFolderButton.setOnClickListener(view -> folderPicker.launch(folderUri));
        generateButton.setOnClickListener(view -> startGeneration());
        previewButton.setOnClickListener(view -> togglePreview());

        String savedUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_FOLDER_URI, null);
        if (!TextUtils.isEmpty(savedUri)) {
            folderUri = Uri.parse(savedUri);
            updateFolderUi();
            detectSamplesAsync();
        }
    }

    private void bindViews() {
        selectFolderButton = findViewById(R.id.selectFolderButton);
        generateButton = findViewById(R.id.generateButton);
        previewButton = findViewById(R.id.previewButton);
        folderText = findViewById(R.id.folderText);
        detectedSamplesText = findViewById(R.id.detectedSamplesText);
        statusText = findViewById(R.id.statusText);
        sampleCountInput = findViewById(R.id.sampleCountInput);
        noteCountInput = findViewById(R.id.noteCountInput);
        noteDurationInput = findViewById(R.id.noteDurationInput);
        gapInput = findViewById(R.id.gapInput);
        fadeInput = findViewById(R.id.fadeInput);
        fileNameInput = findViewById(R.id.fileNameInput);
        startNoteSpinner = findViewById(R.id.startNoteSpinner);
        startOctaveSpinner = findViewById(R.id.startOctaveSpinner);
        normalizeSwitch = findViewById(R.id.normalizeSwitch);
        dumpSamplesSwitch = findViewById(R.id.dumpSamplesSwitch);
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

    private void onFolderSelected(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        folderUri = uri;
        generatedUri = null;
        previewButton.setEnabled(false);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_FOLDER_URI, uri.toString()).apply();
        updateFolderUi();
        detectSamplesAsync();
    }

    private void updateFolderUi() {
        if (folderUri == null) {
            folderText.setText(R.string.no_folder);
            return;
        }
        DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
        String name = folder != null && folder.getName() != null ? folder.getName() : folderUri.toString();
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

        stopPreview();
        generatedUri = null;
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
                    setBusy(false);
                    previewButton.setEnabled(true);
                    statusText.setText(String.format(Locale.getDefault(),
                            "Pronto: %d notas, %d samples, %.2f s. O WAV foi salvo na pasta selecionada.",
                            result.noteCount, result.sampleCount, result.durationSeconds));
                    Toast.makeText(this, "Chromatic gerada com sucesso!", Toast.LENGTH_LONG).show();
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

    private ChromaticGenerator.Config readConfig() {
        ChromaticGenerator.Config config = new ChromaticGenerator.Config();
        config.requestedSampleCount = parseInteger(sampleCountInput, "Quantidade de samples");
        config.startNote = startNoteSpinner.getSelectedItemPosition();
        config.startOctave = (Integer) startOctaveSpinner.getSelectedItem();
        config.noteCount = parseInteger(noteCountInput, "Quantidade de notas");
        config.noteDurationMs = parseInteger(noteDurationInput, "Duração da nota");
        config.gapMs = parseInteger(gapInput, "Gap");
        config.fadeMs = parseInteger(fadeInput, "Fade");
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

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        selectFolderButton.setEnabled(!busy);
        generateButton.setEnabled(!busy);
        previewButton.setEnabled(!busy && generatedUri != null);
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
