package com.example.drowsinessdetector;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private ThresholdManager thresholdManager;

    // Launcher que recebe o resultado da CalibrationActivity
    private ActivityResultLauncher<Intent> calibrationLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        thresholdManager = new ThresholdManager(this);

        // Regista o launcher antes de qualquer clique
        calibrationLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == CalibrationActivity.RESULT_CALIBRATED) {
                        // Atualiza UI após calibração bem-sucedida
                        updateThresholdUI();
                    }
                });

        setupVoiceSwitch();
        setupSeekBar();
        setupCalibrateButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        thresholdManager.register();
        updateThresholdUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        thresholdManager.unregister();
    }

    // =========================================================================
    // Voz
    // =========================================================================

    private void setupVoiceSwitch() {
        Switch swVoice = findViewById(R.id.switchVoiceMode);
        if (swVoice == null) return;

        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        swVoice.setChecked(prefs.getBoolean(AppConstants.KEY_USE_VOICE, false));

        swVoice.setOnCheckedChangeListener((btn, isChecked) ->
                prefs.edit().putBoolean(AppConstants.KEY_USE_VOICE, isChecked).apply());
    }

    // =========================================================================
    // SeekBar de sensibilidade
    // =========================================================================

    private void setupSeekBar() {
        SeekBar  sb    = findViewById(R.id.sbSensitivity);
        TextView label = findViewById(R.id.tvSensitivityLabel);
        if (sb == null) return;

        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);

        // Parte do threshold pessoal se calibrado, senão do valor guardado
        int initialProgress = thresholdManager.isCalibrated()
                ? (int)(thresholdManager.getPersonalThreshold() * 100)
                : prefs.getInt(AppConstants.KEY_SENSITIVITY, AppConstants.DEFAULT_SENSITIVITY);

        sb.setProgress(initialProgress);
        updateSensitivityLabel(label, initialProgress);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSensitivityLabel(label, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(AppConstants.KEY_SENSITIVITY, seekBar.getProgress()).apply();
                updateThresholdUI();
            }
        });
    }

    private void updateSensitivityLabel(TextView label, int progress) {
        if (label == null) return;
        String mode = progress < 35 ? "RIGOROSO" : progress > 75 ? "RELAXADO" : "EQUILIBRADO";
        label.setText(String.format(Locale.getDefault(),
                "Rigor da IA: %s (%.2f)", mode, progress / 100f));
    }

    // =========================================================================
    // Botão de calibração
    // =========================================================================

    private void setupCalibrateButton() {
        MaterialButton btn = findViewById(R.id.btnCalibrate);
        if (btn == null) return;

        updateCalibrateButtonLabel(btn);

        btn.setOnClickListener(v -> {
            Intent intent = new Intent(this, CalibrationActivity.class);
            calibrationLauncher.launch(intent);
        });
    }

    // =========================================================================
    // Atualiza toda a UI de threshold
    // =========================================================================

    private void updateThresholdUI() {
        // Label do SeekBar
        SeekBar  sb    = findViewById(R.id.sbSensitivity);
        TextView label = findViewById(R.id.tvSensitivityLabel);
        if (sb != null && thresholdManager.isCalibrated()) {
            int p = (int)(thresholdManager.getPersonalThreshold() * 100);
            sb.setProgress(p);
            updateSensitivityLabel(label, p);
        }

        // Threshold efectivo (inclui offset de luz)
        TextView tvInfo = findViewById(R.id.tvThresholdInfo);
        if (tvInfo != null) {
            float effective = thresholdManager.getEffectiveThreshold();
            float offset    = thresholdManager.getCurrentLuxOffset();
            if (offset > 0f) {
                tvInfo.setText(String.format(Locale.getDefault(),
                        "%.2f  (+%.2f luz)", effective, offset));
            } else {
                tvInfo.setText(String.format(Locale.getDefault(), "%.2f", effective));
            }
        }

        // Botão de calibrar
        MaterialButton btn = findViewById(R.id.btnCalibrate);
        if (btn != null) updateCalibrateButtonLabel(btn);
    }

    private void updateCalibrateButtonLabel(MaterialButton btn) {
        if (thresholdManager.isCalibrated()) {
            btn.setText(String.format(Locale.getDefault(),
                    "RECALIBRAR  (pessoal: %.2f)",
                    thresholdManager.getPersonalThreshold()));
            btn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF1A3A6B));
        } else {
            btn.setText("CALIBRAR  (não calibrado — a usar 0.45)");
            // Destaca a cor quando ainda não foi calibrado
            btn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(0xFF6B1A1A));
        }
    }
}