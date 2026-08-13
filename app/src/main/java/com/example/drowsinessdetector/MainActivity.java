package com.example.drowsinessdetector;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;

import android.app.Dialog;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private TextView tvLightIndicator;
    private long lastLightUpdate = 0;

    // Score visualization mode
    private static final int VIZ_BAR   = 0;
    private static final int VIZ_METER = 1;
    private int scoreVizMode = VIZ_BAR;

    // Peak meter bars
    private View[] meterBars;
    private static final int[] BAR_COLORS_ACTIVE = {
            0xFFE53935, 0xFFE53935,
            0xFFEF6C00, 0xFFEF6C00,
            0xFFC8AB5A, 0xFFC8AB5A, 0xFFC8AB5A, 0xFFC8AB5A,
            0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71,
            0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71
    };
    private static final int BAR_COLOR_INACTIVE = 0xFF1A1E2A;

    // Alert messages
    private static final String[] ALERT_MESSAGES = {
            "Parece que está a ficar com sono. Se precisar, pare para descansar.",
            "Os seus olhos parecem pesados. Considere fazer uma pausa curta.",
            "Atenção à estrada. A sua segurança é o mais importante.",
            "Está a demonstrar sinais de cansaço. Mantenha-se alerta ou descanse."
    };

    // Score smoothing
    private final float[] scoreBuffer = new float[AppConstants.SMOOTH_WINDOW];
    private int scoreIndex = 0;
    private int scoreCount = 0;

    // UI refs
    private PreviewView      viewFinder;
    private TextView         tvStatus;
    private ProgressBar      pbLiveScore;
    private ImageView        ivDebugCrop;
    private View             ivFaceOverlay;
    private MaterialButton   btnStartStop;
    private ScrollView       layoutSettings;
    private LinearLayout     layoutHistory;
    private ConstraintLayout layoutMonitoring;
    private FrameLayout      cameraLoadingOverlay;
    private LinearLayout     peakMeterContainer;
    private TextView         tvScoreNumeric;
    private TextView         tvMeterLabel;
    private TextView         tvLiveAlertCount;
    private LinearLayout     rowAlertCount;
    private LinearLayout     rowScoreBar;
    private TextView         tvScoreInPanel;

    // Settings refs
    private LinearLayout optionScoreBar;
    private LinearLayout optionScoreMeter;
    private View         radioBar;
    private View         radioMeter;

    // Camera
    private ImageAnalysis   imageAnalysis;
    private ExecutorService cameraExecutor;

    // AI
    private FatigueClassifier classifier;

    // Audio
    private MediaPlayer  mediaPlayer;
    private TextToSpeech tts;
    private boolean      isAlarmPlaying = false;
    private long         lastVoiceTime  = 0;
    private boolean      alertEpisodeActive = false;  // true enquanto fadiga persistir

    // State
    private boolean isMonitoring        = false;
    private long    fatigueStartTime    = 0;
    private float   confidenceThreshold = AppConstants.DEFAULT_CONFIDENCE;
    private boolean useVoiceAlerts      = false;

    // Threshold adaptativo (calibração + sensor de luz)
    private ThresholdManager thresholdManager;

    // Launcher para CalibrationActivity
    private ActivityResultLauncher<Intent> calibrationLauncher;

    // Session
    private DriveSession      currentSession;
    private SessionRepository sessionRepository;

    // Session timer
    private final Handler  sessionTimerHandler  = new Handler(Looper.getMainLooper());
    private final Runnable sessionTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isMonitoring && currentSession != null) {
                TextView tvTimer = findViewById(R.id.tvSessionTimer);
                if (tvTimer != null) {
                    tvTimer.setText(formatDuration(currentSession.getElapsedSeconds()));
                }
                sessionTimerHandler.postDelayed(this, 1000);
            }
        }
    };

    // Lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bindViews();
        ivFaceOverlay = findViewById(R.id.ivFaceOverlay);
        buildMeterBars();

        sessionRepository = new SessionRepository(this);

        thresholdManager = new ThresholdManager(this);

        calibrationLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == CalibrationActivity.RESULT_CALIBRATED) {
                        confidenceThreshold = thresholdManager.getEffectiveThreshold();
                        syncSeekBarToThreshold();
                        Toast.makeText(this,
                                "Threshold personalizado: " +
                                        String.format(Locale.getDefault(), "%.2f", confidenceThreshold),
                                Toast.LENGTH_LONG).show();
                    }
                });

        initClassifier();
        initAudio();

        setupNavigation();
        setupSeekBar();
        setupDebugCropDrag();
        setupVoiceSwitch();
        setupClearHistoryButton();
        setupHelpButton();
        setupScoreVizOptions();
        setupCalibrateButton();

        btnStartStop.setOnClickListener(v -> toggleMonitoring());
        setMonitoringState(false);
        if (ivFaceOverlay != null) ivFaceOverlay.setVisibility(View.GONE);

        requestCameraPermission();
        checkAndShowOnboarding();
    }

    private void checkAndShowOnboarding() {
        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        boolean onboardingDone = prefs.getBoolean(AppConstants.KEY_ONBOARDING_DONE, false);
        if (!onboardingDone && allPermissionsGranted()) {
            showOnboardingDialog();
        }
    }

    private void onCameraPermissionGranted() {
        startCamera();
        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(AppConstants.KEY_ONBOARDING_DONE, false)) {
            showOnboardingDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        useVoiceAlerts = prefs.getBoolean(AppConstants.KEY_USE_VOICE, false);
        scoreVizMode   = prefs.getInt("scoreVizMode", VIZ_BAR);

        Switch swVoice = findViewById(R.id.switchVoiceMode);
        if (swVoice != null) swVoice.setChecked(useVoiceAlerts);
        applyScoreVizMode();

        thresholdManager.register();
        confidenceThreshold = thresholdManager.getEffectiveThreshold();

        restartCameraIfNeeded();
    }

    private void restartCameraIfNeeded() {
        if (!isMonitoring && allPermissionsGranted()) {
            if (imageAnalysis != null) {
                imageAnalysis.clearAnalyzer();
            }
            ProcessCameraProvider.getInstance(this).addListener(() -> {
                try {
                    ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                    provider.unbindAll();
                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    provider.bindToLifecycle(this,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview, imageAnalysis);
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao reiniciar câmara: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(this));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        thresholdManager.unregister();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sessionTimerHandler.removeCallbacks(sessionTimerRunnable);
        releaseResources();
    }

    // Init
    private void bindViews() {
        layoutMonitoring     = findViewById(R.id.layoutMonitoring);
        layoutSettings       = findViewById(R.id.layoutSettings);
        layoutHistory        = findViewById(R.id.layoutHistory);
        viewFinder           = findViewById(R.id.viewFinder);
        tvStatus             = findViewById(R.id.tvStatus);
        pbLiveScore          = findViewById(R.id.pbLiveScore);
        ivDebugCrop          = findViewById(R.id.ivDebugCrop);
        btnStartStop         = findViewById(R.id.btnStartStop);
        cameraLoadingOverlay = findViewById(R.id.cameraLoadingOverlay);
        peakMeterContainer   = findViewById(R.id.peakMeterContainer);
        tvScoreNumeric       = findViewById(R.id.tvScoreNumeric);
        tvMeterLabel         = findViewById(R.id.tvMeterLabel);
        tvLiveAlertCount     = findViewById(R.id.tvLiveAlertCount);
        rowAlertCount        = findViewById(R.id.rowAlertCount);
        rowScoreBar          = findViewById(R.id.rowScoreBar);
        tvScoreInPanel       = findViewById(R.id.tvScoreInPanel);
        tvLightIndicator     = findViewById(R.id.tvLightIndicator);

        optionScoreBar   = findViewById(R.id.optionScoreBar);
        optionScoreMeter = findViewById(R.id.optionScoreMeter);
        radioBar         = findViewById(R.id.radioBar);
        radioMeter       = findViewById(R.id.radioMeter);
    }

    private void buildMeterBars() {
        meterBars = new View[]{
                findViewById(R.id.bar16), findViewById(R.id.bar15),
                findViewById(R.id.bar14), findViewById(R.id.bar13),
                findViewById(R.id.bar12), findViewById(R.id.bar11),
                findViewById(R.id.bar10), findViewById(R.id.bar9),
                findViewById(R.id.bar8),  findViewById(R.id.bar7),
                findViewById(R.id.bar6),  findViewById(R.id.bar5),
                findViewById(R.id.bar4),  findViewById(R.id.bar3),
                findViewById(R.id.bar2),  findViewById(R.id.bar1)
        };
    }

    private void initAudio() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR) {
                tts.setLanguage(new Locale("pt", "PT"));
            }
        });
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm);
            if (mediaPlayer != null) mediaPlayer.setLooping(true);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao criar MediaPlayer", e);
        }
    }

    private void initClassifier() {
        try {
            classifier = new FatigueClassifier(this);
            cameraExecutor = Executors.newSingleThreadExecutor();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao inicializar classificador: " + e.getMessage(), e);
            classifier = null;
            runOnUiThread(() -> Toast.makeText(
                    this, "Erro ao carregar modelo de IA. Verifique se o ficheiro " +
                            "modelo_fadiga.tflite está em assets.", Toast.LENGTH_LONG).show());
        }
    }

    private void requestCameraPermission() {
        if (allPermissionsGranted()) {
            onCameraPermissionGranted();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    AppConstants.CAMERA_PERMISSION_CODE);
        }
    }

    // Calibrate button
    private void setupCalibrateButton() {
        MaterialButton btnCalibrate = findViewById(R.id.btnCalibrate);
        if (btnCalibrate == null) return;
        updateCalibrateButtonLabel(btnCalibrate);
        btnCalibrate.setOnClickListener(v -> {
            Intent intent = new Intent(this, CalibrationActivity.class);
            calibrationLauncher.launch(intent);
        });
    }

    private void updateCalibrateButtonLabel(MaterialButton btn) {
        if (thresholdManager.isCalibrated()) {
            btn.setText(String.format(Locale.getDefault(),
                    "RECALIBRAR  (%.2f)", thresholdManager.getPersonalThreshold()));
        } else {
            btn.setText("CALIBRAR (não calibrado)");
        }
    }

    private void syncSeekBarToThreshold() {
        SeekBar sb = findViewById(R.id.sbSensitivity);
        if (sb == null) return;
        int progress = (int)(thresholdManager.getPersonalThreshold() * 100);
        sb.setProgress(progress);
        MaterialButton btnCalibrate = findViewById(R.id.btnCalibrate);
        if (btnCalibrate != null) updateCalibrateButtonLabel(btnCalibrate);
    }

    // Score visualization mode
    private void setupScoreVizOptions() {
        if (optionScoreBar == null || optionScoreMeter == null) return;

        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        scoreVizMode = prefs.getInt("scoreVizMode", VIZ_BAR);
        showScoreWidgets(false);
        applyScoreVizMode();

        optionScoreBar.setOnClickListener(v -> setScoreVizMode(VIZ_BAR));
        optionScoreMeter.setOnClickListener(v -> setScoreVizMode(VIZ_METER));
    }

    private void setScoreVizMode(int mode) {
        scoreVizMode = mode;
        getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
                .edit().putInt("scoreVizMode", mode).apply();
        applyScoreVizMode();
        if (isMonitoring) showScoreWidgets(true);
    }

    private void applyScoreVizMode() {
        if (optionScoreBar == null) return;
        boolean barSelected = (scoreVizMode == VIZ_BAR);

        optionScoreBar.setBackgroundResource(barSelected
                ? R.drawable.option_selected : R.drawable.option_idle);
        optionScoreMeter.setBackgroundResource(barSelected
                ? R.drawable.option_idle : R.drawable.option_selected);

        if (radioBar   != null) radioBar.setBackgroundResource(barSelected  ? R.drawable.radio_on  : R.drawable.radio_off);
        if (radioMeter != null) radioMeter.setBackgroundResource(barSelected ? R.drawable.radio_off : R.drawable.radio_on);
    }

    private void showScoreWidgets(boolean show) {
        if (scoreVizMode == VIZ_METER) {
            if (peakMeterContainer != null) peakMeterContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            if (tvMeterLabel       != null) tvMeterLabel.setVisibility(show ? View.VISIBLE : View.GONE);
            if (tvScoreNumeric     != null) tvScoreNumeric.setVisibility(show ? View.VISIBLE : View.GONE);
            if (rowScoreBar        != null) rowScoreBar.setVisibility(View.GONE);
            if (pbLiveScore        != null) pbLiveScore.setVisibility(View.GONE);
        } else {
            if (rowScoreBar        != null) rowScoreBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (pbLiveScore        != null) pbLiveScore.setVisibility(show ? View.VISIBLE : View.GONE);
            if (peakMeterContainer != null) peakMeterContainer.setVisibility(View.GONE);
            if (tvMeterLabel       != null) tvMeterLabel.setVisibility(View.GONE);
            if (tvScoreNumeric     != null) tvScoreNumeric.setVisibility(View.GONE);
        }
    }

    private void updatePeakMeter(float score) {
        if (meterBars == null) return;
        int activeBars = Math.round(score * 16f);
        for (int i = 0; i < 16; i++) {
            if (meterBars[i] == null) continue;
            boolean lit = (15 - i) < activeBars;
            meterBars[i].setBackgroundColor(lit ? BAR_COLORS_ACTIVE[i] : BAR_COLOR_INACTIVE);
        }
    }

    // Navigation
    private void setupNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        bottomNav.setOnItemSelectedListener(item -> {
            hideAllLayouts();
            int id = item.getItemId();
            if (id == R.id.nav_monitoring) {
                layoutMonitoring.setVisibility(View.VISIBLE);
                if (isMonitoring) showScoreWidgets(true);
            } else if (id == R.id.nav_history) {
                layoutHistory.setVisibility(View.VISIBLE);
                refreshHistoryTab();
            } else if (id == R.id.nav_settings) {
                layoutSettings.setVisibility(View.VISIBLE);
                updateLowLightSuggestion();
                MaterialButton btnCalibrate = findViewById(R.id.btnCalibrate);
                if (btnCalibrate != null) updateCalibrateButtonLabel(btnCalibrate);
            }
            return true;
        });
    }

    private void hideAllLayouts() {
        layoutMonitoring.setVisibility(View.GONE);
        layoutSettings.setVisibility(View.GONE);
        layoutHistory.setVisibility(View.GONE);
    }

    // Settings
    private void setupSeekBar() {
        SeekBar  sb    = findViewById(R.id.sbSensitivity);
        TextView label = findViewById(R.id.tvSensitivityLabel);
        TextView mode  = findViewById(R.id.tvSensitivityMode);
        if (sb == null || label == null) return;

        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        // KEY_FATIGUE_THRESHOLD é escrito tanto pela calibração como pelo ajuste manual do slider.
        int saved = (int)(thresholdManager.getPersonalThreshold() * 100);
        sb.setProgress(saved);
        confidenceThreshold = thresholdManager.getEffectiveThreshold();
        updateSensitivityLabel(label, mode, saved);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                confidenceThreshold = (p / 100f) + thresholdManager.getCurrentLuxOffset();
                confidenceThreshold = Math.min(confidenceThreshold, 0.80f);
                updateSensitivityLabel(label, mode, p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                float newThreshold = s.getProgress() / 100f;
                getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putInt(AppConstants.KEY_SENSITIVITY, s.getProgress())
                        // guarda como threshold pessoal — sobrepõe o valor da calibração
                        .putFloat(AppConstants.KEY_FATIGUE_THRESHOLD, newThreshold)
                        .apply();
                // atualiza imediatamente (com offset de luz por cima)
                confidenceThreshold = thresholdManager.getEffectiveThreshold();
            }
        });
    }

    private void updateSensitivityLabel(TextView label, TextView mode, int progress) {
        if (label != null) label.setText(String.format(Locale.getDefault(), "%.2f", progress / 100f));
        if (mode  != null) {
            String cat = progress < 35 ? "Modo RIGOROSO"
                    : progress > 75    ? "Modo RELAXADO"
                    : "Modo EQUILIBRADO";
            mode.setText(cat);
        }
    }

    private void setupDebugCropDrag() {
        if (ivDebugCrop == null) return;
        final float[] delta = new float[2];
        ivDebugCrop.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    delta[0] = view.getX() - event.getRawX();
                    delta[1] = view.getY() - event.getRawY();
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    view.animate()
                            .x(event.getRawX() + delta[0])
                            .y(event.getRawY() + delta[1])
                            .setDuration(0).start();
                    break;
                default:
                    return false;
            }
            return true;
        });
    }

    private void setupVoiceSwitch() {
        Switch swVoice = findViewById(R.id.switchVoiceMode);
        if (swVoice == null) return;
        swVoice.setOnCheckedChangeListener((btn, isChecked) -> {
            useVoiceAlerts = isChecked;
            getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(AppConstants.KEY_USE_VOICE, isChecked).apply();
        });
    }

    private void setupClearHistoryButton() {
        MaterialButton btnClear = findViewById(R.id.btnClearHistory);
        if (btnClear == null) return;
        btnClear.setBackgroundTintList(ColorStateList.valueOf(0xFFB71C1C));
        btnClear.setTextColor(0xFFFFFFFF);
        btnClear.setOnClickListener(v -> {
            sessionRepository.clearAll();
            currentSession = null;
            refreshHistoryTab();
        });
    }

    // Monitoring state machine
    private void toggleMonitoring() {
        setMonitoringState(!isMonitoring);
    }

    private void setMonitoringState(boolean active) {
        isMonitoring = active;

        if (active) {
            currentSession   = new DriveSession();
            fatigueStartTime = 0;
            resetScoreBuffer();

            confidenceThreshold = thresholdManager.getEffectiveThreshold();

            btnStartStop.setEnabled(false);
            btnStartStop.setBackgroundTintList(ColorStateList.valueOf(0xFF8B2020));
            btnStartStop.setStrokeColorResource(R.color.red_stroke);

            if (rowAlertCount != null) rowAlertCount.setVisibility(View.GONE);
            startCountdown();

        } else {
            btnStartStop.setText("INICIAR MONITORIZAÇÃO");
            btnStartStop.setEnabled(true);
            btnStartStop.setBackgroundTintList(ColorStateList.valueOf(0xFF1A6B3A));

            if (ivDebugCrop != null) ivDebugCrop.setVisibility(View.GONE);
            showScoreWidgets(false);
            if (rowAlertCount != null) rowAlertCount.setVisibility(View.GONE);
            if (tvLightIndicator != null) tvLightIndicator.setVisibility(View.GONE);

            if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
            stopAlarm();
            stopVibration();
            fatigueStartTime = 0;
            resetScoreBuffer();
            sessionTimerHandler.removeCallbacks(sessionTimerRunnable);

            TextView tvTimer = findViewById(R.id.tvSessionTimer);
            if (tvTimer != null) tvTimer.setText("—");

            if (currentSession != null && currentSession.getEventCount() > 0) {
                sessionRepository.saveSession(currentSession);
                Toast.makeText(this,
                        currentSession.getEventCount() + " alerta(s) registado(s)",
                        Toast.LENGTH_SHORT).show();
            }

            tvStatus.setText("SISTEMA INATIVO");
            tvStatus.setBackgroundResource(R.drawable.status_chip_idle);
            tvStatus.setTextColor(0xFF5A5F6E);
            updatePeakMeter(0f);
            Log.d(TAG, "Monitorização pausada");
        }
    }

    private void resetScoreBuffer() {
        scoreIndex = 0;
        scoreCount = 0;
        alertEpisodeActive = false;
        for (int i = 0; i < AppConstants.SMOOTH_WINDOW; i++) scoreBuffer[i] = 0f;
    }

    private float smoothScore(float raw) {
        scoreBuffer[scoreIndex] = raw;
        scoreIndex = (scoreIndex + 1) % AppConstants.SMOOTH_WINDOW;
        if (scoreCount < AppConstants.SMOOTH_WINDOW) scoreCount++;
        float sum = 0f;
        for (int i = 0; i < scoreCount; i++) sum += scoreBuffer[i];
        return sum / scoreCount;
    }

    private void startCountdown() {
        if (classifier == null) {
            tvStatus.setText("ERRO: MODELO IA NÃO CARREGADO");
            tvStatus.setBackgroundResource(R.drawable.status_chip_alert);
            tvStatus.setTextColor(0xFFE53935);
            Toast.makeText(this,
                    "O modelo de IA não está disponível. Reinicie a aplicação.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        tvStatus.setText("Prepare-se…");
        tvStatus.setBackgroundResource(R.drawable.status_chip_idle);
        tvStatus.setTextColor(0xFF5A5F6E);
        btnStartStop.setText("3");

        handler.postDelayed(() -> btnStartStop.setText("2"), 1000);
        handler.postDelayed(() -> btnStartStop.setText("1"), 2000);
        handler.postDelayed(() -> {
            btnStartStop.setText("PARAR");
            btnStartStop.setEnabled(true);
            if (ivDebugCrop != null) ivDebugCrop.setVisibility(View.VISIBLE);
            showScoreWidgets(true);
            if (rowAlertCount != null) rowAlertCount.setVisibility(View.VISIBLE);
            updateLightIndicator();

            sessionTimerHandler.post(sessionTimerRunnable);

            if (imageAnalysis != null) {
                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (!isMonitoring || classifier == null) {
                        image.close();
                        return;
                    }
                    image.close();

                    runOnUiThread(() -> {
                        if (!isMonitoring || classifier == null) return;

                        final Bitmap bitmap = viewFinder.getBitmap();
                        if (bitmap == null) return;

                        cameraExecutor.execute(() -> {
                            if (!isMonitoring || classifier == null) return;
                            float score = classifier.analyzeImage(bitmap);
                            Bitmap debugBmp = classifier.getLastDebugBitmap();
                            runOnUiThread(() -> {
                                if (!isMonitoring) return;
                                if (ivDebugCrop != null && debugBmp != null)
                                    ivDebugCrop.setImageBitmap(debugBmp);
                                processFrame(score);
                            });
                        });
                    });
                });
            }
        }, 3000);
    }

    // Frame processing
    private void processFrame(float score) {
        if (!isMonitoring) return;

        confidenceThreshold = thresholdManager.getEffectiveThreshold();

        updateFaceOverlay();

        if (score == FatigueClassifier.NO_FACE) {
            tvStatus.setText("ERRO NO MODELO");
            tvStatus.setBackgroundResource(R.drawable.status_chip_warn);
            tvStatus.setTextColor(0xFFC8AB5A);
            return;
        }

        if (classifier != null && classifier.getLastLuminance() < AppConstants.LOW_LIGHT_THRESHOLD) {
            tvStatus.setText("POUCA LUZ — ANÁLISE EM CURSO");
            tvStatus.setBackgroundResource(R.drawable.status_chip_idle);
            tvStatus.setTextColor(0xFF5A5F6E);
        }

        updateUI(smoothScore(score));

        if (System.currentTimeMillis() - lastLightUpdate > 1000) {
            updateLightIndicator();
            lastLightUpdate = System.currentTimeMillis();
        }
    }

    private void updateFaceOverlay() {
        if (ivFaceOverlay == null || viewFinder == null || classifier == null) return;

        android.graphics.Rect faceRect = classifier.getLastFaceRect();
        if (faceRect == null) {
            ivFaceOverlay.setVisibility(View.GONE);
            return;
        }

        Bitmap lastFrame = viewFinder.getBitmap();
        if (lastFrame == null) {
            ivFaceOverlay.setVisibility(View.GONE);
            return;
        }

        float scaleX = (float) viewFinder.getWidth()  / lastFrame.getWidth();
        float scaleY = (float) viewFinder.getHeight() / lastFrame.getHeight();

        int left   = (int)(faceRect.left   * scaleX);
        int top    = (int)(faceRect.top    * scaleY);
        int right  = (int)(faceRect.right  * scaleX);
        int bottom = (int)(faceRect.bottom * scaleY);

        int w = right  - left;
        int h = bottom - top;

        int[] vfLoc = new int[2];
        viewFinder.getLocationOnScreen(vfLoc);
        int[] overlayParentLoc = new int[2];
        ((View) ivFaceOverlay.getParent()).getLocationOnScreen(overlayParentLoc);

        int offsetX = vfLoc[0] - overlayParentLoc[0];
        int offsetY = vfLoc[1] - overlayParentLoc[1];

        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) ivFaceOverlay.getLayoutParams();
        if (lp == null) {
            lp = new android.widget.FrameLayout.LayoutParams(w, h);
        } else {
            lp.width  = w;
            lp.height = h;
        }
        lp.leftMargin = left  + offsetX;
        lp.topMargin  = top   + offsetY;
        ivFaceOverlay.setLayoutParams(lp);
        ivFaceOverlay.setVisibility(View.VISIBLE);
    }

    private void updateUI(float score) {
        updatePeakMeter(score);

        String scoreStr = String.format(Locale.getDefault(), "%.2f", score);
        if (pbLiveScore    != null) pbLiveScore.setProgress((int)(score * 100));
        if (tvScoreNumeric != null) tvScoreNumeric.setText(scoreStr);
        if (tvScoreInPanel != null) tvScoreInPanel.setText(scoreStr);

        if (score > confidenceThreshold) {
            if (fatigueStartTime == 0) fatigueStartTime = System.currentTimeMillis();
        } else {
            fatigueStartTime = 0;
        }

        long duration = (fatigueStartTime == 0) ? 0 : (System.currentTimeMillis() - fatigueStartTime);

        int scoreColor;
        if (score < confidenceThreshold * 0.6f) {
            scoreColor = 0xFF2ECC71;
        } else if (score < confidenceThreshold) {
            scoreColor = 0xFFC8AB5A;
        } else {
            scoreColor = 0xFFE53935;
        }
        if (tvScoreInPanel != null) tvScoreInPanel.setTextColor(scoreColor);
        if (tvScoreNumeric != null) tvScoreNumeric.setTextColor(scoreColor);

        if (duration >= AppConstants.FATIGUE_DURATION_MS) {
            tvStatus.setText("FADIGA DETETADA");
            tvStatus.setBackgroundResource(R.drawable.status_chip_alert);
            tvStatus.setTextColor(0xFFE53935);
            if (ivDebugCrop != null) ivDebugCrop.setBackgroundColor(0xFF3A0808);
            triggerFatigueAlert(score, duration);
            if (tvLiveAlertCount != null && currentSession != null) {
                tvLiveAlertCount.setText(String.valueOf(currentSession.getEventCount()));
            }
        } else {
            tvStatus.setText("MOTORISTA ATENTO");
            tvStatus.setBackgroundResource(R.drawable.status_chip_ok);
            tvStatus.setTextColor(0xFF2ECC71);
            if (ivDebugCrop != null) {
                ivDebugCrop.setBackgroundColor(
                        score > (confidenceThreshold * 0.7f) ? 0xFF2A2000 : 0xFF0A2010);
            }
            stopAlarm();
            alertEpisodeActive = false;  // score voltou a normal
        }
    }

    // Alerts
    private void triggerFatigueAlert(float score, long duration) {
        // só regista um evento por episódio de fadiga (não por frame)
        if (!alertEpisodeActive) {
            alertEpisodeActive = true;
            if (currentSession != null) currentSession.recordEvent(score, duration);
        }
        triggerVibration();
        if (useVoiceAlerts) {
            long now = System.currentTimeMillis();
            if (now - lastVoiceTime > AppConstants.VOICE_COOLDOWN_MS) {
                tts.speak(getRandomMessage(), TextToSpeech.QUEUE_FLUSH, null, "fatigue");
                lastVoiceTime = now;
            }
        } else {
            if (!isAlarmPlaying && mediaPlayer != null) {
                mediaPlayer.start();
                isAlarmPlaying = true;
            }
        }
    }

    private void triggerVibration() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 500, 200, 500};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            vibrator.vibrate(pattern, -1);
        }
    }

    private void stopVibration() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null) v.cancel();
    }

    private void stopAlarm() {
        if (isAlarmPlaying && mediaPlayer != null) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            isAlarmPlaying = false;
        }
    }

    private String getRandomMessage() {
        return ALERT_MESSAGES[new Random().nextInt(ALERT_MESSAGES.length)];
    }

    // History tab
    private void refreshHistoryTab() {
        LinearLayout llCards    = findViewById(R.id.llSessionCards);
        LinearLayout emptyState = findViewById(R.id.layoutEmptyState);

        if (llCards == null) return;

        List<DriveSession.SessionSnapshot> history = sessionRepository.loadSnapshots();
        boolean hasCurrent = currentSession != null && currentSession.getEventCount() > 0;
        boolean hasHistory  = !history.isEmpty();

        if (!hasCurrent && !hasHistory) {
            if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
            llCards.setVisibility(View.GONE);
            return;
        }

        if (emptyState != null) emptyState.setVisibility(View.GONE);
        llCards.setVisibility(View.VISIBLE);
        llCards.removeAllViews();

        // Sessão atual
        if (hasCurrent) {
            llCards.addView(makeSectionLabel("SESSÃO ATUAL"));
            llCards.addView(buildSessionCard(currentSession.toSnapshot(), true));
        }

        // Histórico
        if (hasHistory) {
            llCards.addView(makeSectionLabel("HISTÓRICO"));
            for (DriveSession.SessionSnapshot snap : history) {
                llCards.addView(buildSessionCard(snap, false));
            }
        }
    }

    /**
     * Card expansível para uma sessão.
     *
     * @param snap      dados da sessão
     * @param isCurrent true = sessão em curso
     */
    private View buildSessionCard(DriveSession.SessionSnapshot snap, boolean isCurrent) {

        final int C_AMBER   = 0xFFC8AB5A;
        final int C_GREEN   = 0xFF2ECC71;
        final int C_RED     = 0xFFE53935;
        final int C_TEXT    = 0xFFD8D8D8;
        final int C_MUTED   = 0xFF8A8F9E;
        final int C_HINT    = 0xFF5A5F6E;
        final int C_SURFACE = isCurrent ? 0xFF100F0A : 0xFF0D1118;
        final int C_BORDER  = isCurrent ? C_AMBER    : 0xFF2A2E3D;

        // score médio
        float avg = 0f;
        if (snap.events != null && !snap.events.isEmpty()) {
            for (DriveSession.FatigueEvent e : snap.events) avg += e.score;
            avg /= snap.events.size();
        }
        final float finalAvg = avg;

        int alertColor = snap.alertCount == 0 ? C_GREEN
                : snap.alertCount <= 2  ? C_AMBER
                : C_RED;
        int avgColor   = avg == 0f  ? C_HINT
                : avg < .45f ? C_GREEN
                : avg < .65f ? C_AMBER
                : C_RED;

        // card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(cardLp);

        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(C_SURFACE);
        cardBg.setCornerRadius(dp(6));
        cardBg.setStroke(dp(1), C_BORDER);
        card.setBackground(cardBg);

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(13), dp(14), dp(13));
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // dot de severidade
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg =
                new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(alertColor);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.setMargins(0, dp(1), dp(12), 0);
        dot.setLayoutParams(dotLp);
        header.addView(dot);

        // bloco central
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // linha data/hora
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dateRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dateRowLp.setMargins(0, 0, 0, dp(6));
        dateRow.setLayoutParams(dateRowLp);

        String dateLabel = snap.date
                + (snap.endDate != null && !snap.endDate.isEmpty()
                ? "  →  " + snap.endDate : "");

        TextView tvDate = new TextView(this);
        tvDate.setText(dateLabel);
        tvDate.setTextColor(C_MUTED);
        tvDate.setTextSize(10);
        tvDate.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvDate.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        dateRow.addView(tvDate);

        if (isCurrent) {
            TextView pill = new TextView(this);
            pill.setText("EM CURSO");
            pill.setTextColor(C_AMBER);
            pill.setTextSize(9);
            pill.setTypeface(android.graphics.Typeface.MONOSPACE);
            pill.setPadding(dp(6), dp(1), dp(6), dp(1));
            android.graphics.drawable.GradientDrawable pillBg =
                    new android.graphics.drawable.GradientDrawable();
            pillBg.setColor(0x00000000);
            pillBg.setCornerRadius(dp(3));
            pillBg.setStroke(dp(1), C_AMBER);
            pill.setBackground(pillBg);
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pillLp.setMargins(dp(8), 0, 0, 0);
            pill.setLayoutParams(pillLp);
            dateRow.addView(pill);
        }
        center.addView(dateRow);

        // linha stats
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        statsRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        statsRow.addView(makeStat(String.valueOf(snap.alertCount), "alertas", alertColor, C_MUTED));
        statsRow.addView(makeStatSpacer());
        statsRow.addView(makeStat(formatDuration(snap.durationSeconds), "duração", C_TEXT, C_MUTED));
        statsRow.addView(makeStatSpacer());
        statsRow.addView(makeStat(
                finalAvg > 0 ? String.format(Locale.getDefault(), "%.2f", finalAvg) : "—",
                "avg", avgColor, C_MUTED));
        center.addView(statsRow);

        header.addView(center);

        // seta de navegação
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(C_HINT);
        arrow.setTextSize(20);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arrowLp.setMargins(dp(10), 0, 0, 0);
        arrow.setLayoutParams(arrowLp);
        header.addView(arrow);

        card.addView(header);

        // clique abre SessionDetailActivity
        card.setOnClickListener(v -> {
            String json = new com.google.gson.Gson().toJson(snap);
            Intent intent = new Intent(this, SessionDetailActivity.class);
            intent.putExtra(SessionDetailActivity.EXTRA_SNAPSHOT_JSON, json);
            startActivity(intent);
        });

        // ripple
        android.util.TypedValue rippleVal = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, rippleVal, true);
        if (rippleVal.resourceId != 0) {
            card.setForeground(ContextCompat.getDrawable(this, rippleVal.resourceId));
        }

        return card;
    }

    // stat inline (valor + label)
    private LinearLayout makeStat(String value, String label, int valColor, int lblColor) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.HORIZONTAL);
        col.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextColor(valColor);
        tvVal.setTextSize(16);
        tvVal.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        col.addView(tvVal);

        TextView tvLbl = new TextView(this);
        tvLbl.setText(" " + label);
        tvLbl.setTextColor(lblColor);
        tvLbl.setTextSize(10);
        tvLbl.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(tvLbl);

        return col;
    }

    private View makeStatSpacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(16), 1));
        return v;
    }

    // label de secção ("SESSÃO ATUAL" / "HISTÓRICO")
    private TextView makeSectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF5A5F6E);
        tv.setTextSize(9);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(4), 0, dp(6));
        tv.setLayoutParams(lp);
        return tv;
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        return String.format(Locale.getDefault(), "%dm%02ds", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density);
    }

    // Low light suggestion
    private void updateLowLightSuggestion() {
        View    tip = findViewById(R.id.tvLowLightTip);
        SeekBar sb  = findViewById(R.id.sbSensitivity);
        if (tip == null || sb == null) return;
        boolean isLowLight = classifier != null
                && classifier.getLastLuminance() < AppConstants.LOW_LIGHT_THRESHOLD;
        tip.setVisibility(isLowLight ? View.VISIBLE : View.GONE);
    }

    // Help / Onboarding
    private void setupHelpButton() {
        MaterialButton btnHelp = findViewById(R.id.btnHelp);
        if (btnHelp != null) btnHelp.setOnClickListener(v -> showOnboardingDialog());
    }

    private void showOnboardingDialog() {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(R.layout.dialog_onboarding);
        dialog.setCancelable(false);

        ViewPager2     pager   = dialog.findViewById(R.id.onboardingPager);
        TextView       btnSkip = dialog.findViewById(R.id.btnSkip);
        MaterialButton btnNext = dialog.findViewById(R.id.btnNext);
        MaterialButton btnPrev = dialog.findViewById(R.id.btnPrevious);
        View dot1 = dialog.findViewById(R.id.dot1);
        View dot2 = dialog.findViewById(R.id.dot2);
        View dot3 = dialog.findViewById(R.id.dot3);
        View dot4 = dialog.findViewById(R.id.dot4);
        View[] dots = {dot1, dot2, dot3, dot4};

        if (pager == null || btnNext == null) { dialog.show(); return; }

        OnboardingAdapter adapter = new OnboardingAdapter(this);
        pager.setAdapter(adapter);
        pager.setOffscreenPageLimit(3);

        final int[] currentPage = {0};
        final int   totalPages  = OnboardingAdapter.STEPS.length;

        Runnable updateState = () -> {
            int page = currentPage[0];
            for (int i = 0; i < dots.length; i++) {
                if (dots[i] == null) continue;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dots[i].getLayoutParams();
                if (i == page) {
                    lp.width = dp(20);
                    dots[i].setBackgroundResource(R.drawable.dot_active);
                } else {
                    lp.width = dp(6);
                    dots[i].setBackgroundResource(R.drawable.dot_inactive);
                }
                dots[i].setLayoutParams(lp);
            }
            if (btnPrev != null)
                btnPrev.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
            btnNext.setText(page == totalPages - 1 ? "COMEÇAR" : "SEGUINTE");
            if (btnSkip != null)
                btnSkip.setVisibility(page == totalPages - 1 ? View.INVISIBLE : View.VISIBLE);
        };

        updateState.run();

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                currentPage[0] = position;
                updateState.run();
            }
        });

        btnNext.setOnClickListener(v -> {
            if (currentPage[0] < totalPages - 1) {
                pager.setCurrentItem(currentPage[0] + 1, true);
            } else {
                finishOnboarding(dialog);
            }
        });

        if (btnPrev != null) btnPrev.setOnClickListener(v -> {
            if (currentPage[0] > 0) pager.setCurrentItem(currentPage[0] - 1, true);
        });

        if (btnSkip != null) btnSkip.setOnClickListener(v -> finishOnboarding(dialog));

        dialog.show();
    }

    private void finishOnboarding(Dialog dialog) {
        getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(AppConstants.KEY_ONBOARDING_DONE, true).apply();
        dialog.dismiss();
        new Handler(Looper.getMainLooper()).postDelayed(this::suggestCalibration, 400);
    }

    private void suggestCalibration() {
        Log.d(TAG, "suggestCalibration chamado. Calibrado? " + thresholdManager.isCalibrated());
        if (!thresholdManager.isCalibrated()) {
            runOnUiThread(() -> {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Calibração pessoal")
                        .setMessage("Recomendamos calibrar o detector ao seu rosto antes de começar.\n\n"
                                + "É rápido (10 segundos) e melhora a precisão dos alertas.")
                        .setPositiveButton("Calibrar agora", (d, which) -> {
                            Intent intent = new Intent(MainActivity.this, CalibrationActivity.class);
                            calibrationLauncher.launch(intent);
                        })
                        .setNegativeButton("Mais tarde", null)
                        .setCancelable(false)
                        .show();
            });
        }
    }

    // Camera
    private void startCamera() {
        if (cameraLoadingOverlay != null) {
            cameraLoadingOverlay.setVisibility(View.VISIBLE);
        }

        final Handler timeoutHandler = new Handler(Looper.getMainLooper());
        final Runnable timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (cameraLoadingOverlay != null &&
                        cameraLoadingOverlay.getVisibility() == View.VISIBLE) {
                    cameraLoadingOverlay.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this,
                            "Não foi possível iniciar a câmara. Reinicie a aplicação.",
                            Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Câmara não entrou em streaming após timeout.");
                }
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, 8000);

        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();

                viewFinder.getPreviewStreamState().observe(this, state -> {
                    if (state == PreviewView.StreamState.STREAMING) {
                        timeoutHandler.removeCallbacks(timeoutRunnable);
                        if (cameraLoadingOverlay != null) {
                            cameraLoadingOverlay.animate()
                                    .alpha(0f).setDuration(400)
                                    .withEndAction(() -> cameraLoadingOverlay.setVisibility(View.GONE))
                                    .start();
                        }
                    }
                });

                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
                Log.d(TAG, "Câmara iniciada com sucesso. imageAnalysis = " + imageAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "Erro crítico ao iniciar câmara: " + e.getMessage(), e);
                imageAnalysis = null;
                timeoutHandler.removeCallbacks(timeoutRunnable);
                if (cameraLoadingOverlay != null) cameraLoadingOverlay.setVisibility(View.GONE);
                runOnUiThread(() -> Toast.makeText(this,
                        "Falha ao abrir a câmara. Reinicie a app.", Toast.LENGTH_LONG).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Utilities
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void releaseResources() {
        if (imageAnalysis  != null) imageAnalysis.clearAnalyzer();
        if (mediaPlayer    != null) { mediaPlayer.release(); mediaPlayer = null; }
        if (classifier     != null) { classifier.close(); classifier = null; }
        if (tts            != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (cameraExecutor != null) cameraExecutor.shutdown();
        stopVibration();
    }

    private void updateLightIndicator() {
        if (tvLightIndicator == null || thresholdManager == null) return;
        float offset = thresholdManager.getCurrentLuxOffset();

        if (offset >= 0.12f) {
            tvLightIndicator.setText("POUCA LUZ");
            tvLightIndicator.setTextColor(0xFFE53935); // vermelho, alinhado com status_chip_alert
            tvLightIndicator.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvLightIndicator.setVisibility(View.VISIBLE);
        } else if (offset >= 0.06f) {
            tvLightIndicator.setText("PENUMBRA");
            tvLightIndicator.setTextColor(0xFFC8AB5A); // âmbar, alinhado com status_chip_warn
            tvLightIndicator.setTypeface(android.graphics.Typeface.MONOSPACE);
            tvLightIndicator.setVisibility(View.VISIBLE);
        } else {
            tvLightIndicator.setVisibility(View.GONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == AppConstants.CAMERA_PERMISSION_CODE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                onCameraPermissionGranted();
            } else {
                Toast.makeText(this,
                        "A câmara é necessária para detectar fadiga.",
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}