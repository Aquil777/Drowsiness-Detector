package com.example.drowsinessdetector;

import android.Manifest;
import android.content.Context;
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

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ── Score visualization mode ──────────────────────────────────────────────
    private static final int VIZ_BAR   = 0;
    private static final int VIZ_METER = 1;
    private int scoreVizMode = VIZ_BAR;

    // Peak meter bars (16 elements, index 0 = top/highest)
    private View[] meterBars;
    private static final int[] BAR_COLORS_ACTIVE = {
            0xFFE53935, 0xFFE53935,                           // 16,15 — red zone
            0xFFEF6C00, 0xFFEF6C00,                           // 14,13 — orange
            0xFFC8AB5A, 0xFFC8AB5A, 0xFFC8AB5A, 0xFFC8AB5A,  // 12–9  — amber
            0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71,   // 8–5   — green
            0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71, 0xFF2ECC71    // 4–1   — green
    };
    private static final int BAR_COLOR_INACTIVE = 0xFF1A1E2A;

    // ── Alert messages ────────────────────────────────────────────────────────
    private static final String[] ALERT_MESSAGES = {
            "Parece que está a ficar com sono. Se precisar, pare para descansar.",
            "Os seus olhos parecem pesados. Considere fazer uma pausa curta.",
            "Atenção à estrada. A sua segurança é o mais importante.",
            "Está a demonstrar sinais de cansaço. Mantenha-se alerta ou descanse."
    };

    // ── Score smoothing ───────────────────────────────────────────────────────
    private final float[] scoreBuffer = new float[AppConstants.SMOOTH_WINDOW];
    private int scoreIndex = 0;
    private int scoreCount = 0;

    // ── UI refs ───────────────────────────────────────────────────────────────
    private PreviewView     viewFinder;
    private TextView        tvStatus;
    private ProgressBar     pbLiveScore;
    private ImageView       ivDebugCrop;
    private MaterialButton  btnStartStop;
    private ScrollView      layoutSettings;
    private LinearLayout    layoutHistory;
    private ConstraintLayout layoutMonitoring;
    private FrameLayout     cameraLoadingOverlay;
    private LinearLayout    peakMeterContainer;
    private TextView        tvScoreNumeric;
    private TextView        tvMeterLabel;
    private TextView        tvLiveAlertCount;
    private LinearLayout    rowAlertCount;
    private LinearLayout    rowScoreBar;
    private TextView        tvScoreInPanel;

    // Settings refs
    private LinearLayout    optionScoreBar;
    private LinearLayout    optionScoreMeter;
    private View            radioBar;
    private View            radioMeter;

    // ── Camera ────────────────────────────────────────────────────────────────
    private ImageAnalysis   imageAnalysis;
    private ExecutorService cameraExecutor;

    // ── AI ────────────────────────────────────────────────────────────────────
    private FatigueClassifier classifier;

    // ── Audio ─────────────────────────────────────────────────────────────────
    private MediaPlayer  mediaPlayer;
    private TextToSpeech tts;
    private boolean      isAlarmPlaying = false;
    private long         lastVoiceTime  = 0;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isMonitoring        = false;
    private long    fatigueStartTime    = 0;
    private float   confidenceThreshold = AppConstants.DEFAULT_CONFIDENCE;
    private boolean useVoiceAlerts      = false;

    // ── Session ───────────────────────────────────────────────────────────────
    private DriveSession      currentSession;
    private SessionRepository sessionRepository;

    // ── Session timer ─────────────────────────────────────────────────────────
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

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bindViews();
        buildMeterBars();

        sessionRepository = new SessionRepository(this);

        // initClassifier BEFORE requestCameraPermission to ensure cameraExecutor exists
        initClassifier();
        initAudio();

        setupNavigation();
        setupSeekBar();
        setupDebugCropDrag();
        setupVoiceSwitch();
        setupClearHistoryButton();
        setupHelpButton();
        setupScoreVizOptions();

        btnStartStop.setOnClickListener(v -> toggleMonitoring());
        setMonitoringState(false);

        requestCameraPermission();

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sessionTimerHandler.removeCallbacks(sessionTimerRunnable);
        releaseResources();
    }

    // =========================================================================
    // Init
    // =========================================================================

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
            classifier     = new FatigueClassifier(this);
            cameraExecutor = Executors.newSingleThreadExecutor();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao inicializar classificador", e);
        }
    }

    private void requestCameraPermission() {
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    AppConstants.CAMERA_PERMISSION_CODE);
        }
    }

    // =========================================================================
    // Score visualization mode
    // =========================================================================

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
        if (isMonitoring) {
            showScoreWidgets(true);
        }
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

    // =========================================================================
    // Navigation
    // =========================================================================

    private void setupNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        bottomNav.setOnItemSelectedListener(item -> {
            hideAllLayouts();
            int id = item.getItemId();
            if (id == R.id.nav_monitoring) {
                layoutMonitoring.setVisibility(View.VISIBLE);
                if (isMonitoring) {
                    showScoreWidgets(true);
                }
            } else if (id == R.id.nav_history) {
                layoutHistory.setVisibility(View.VISIBLE);
                refreshHistoryTab();
            } else if (id == R.id.nav_settings) {
                layoutSettings.setVisibility(View.VISIBLE);
                updateLowLightSuggestion();
            }
            return true;
        });
    }

    private void hideAllLayouts() {
        layoutMonitoring.setVisibility(View.GONE);
        layoutSettings.setVisibility(View.GONE);
        layoutHistory.setVisibility(View.GONE);
    }

    // =========================================================================
    // Settings
    // =========================================================================

    private void setupSeekBar() {
        SeekBar  sb    = findViewById(R.id.sbSensitivity);
        TextView label = findViewById(R.id.tvSensitivityLabel);
        TextView mode  = findViewById(R.id.tvSensitivityMode);
        if (sb == null || label == null) return;

        SharedPreferences prefs = getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE);
        int saved = prefs.getInt(AppConstants.KEY_SENSITIVITY, AppConstants.DEFAULT_SENSITIVITY);
        sb.setProgress(saved);
        confidenceThreshold = saved / 100f;
        updateSensitivityLabel(label, mode, saved);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                confidenceThreshold = p / 100f;
                updateSensitivityLabel(label, mode, p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(AppConstants.KEY_SENSITIVITY, s.getProgress()).apply();
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
        btnClear.setOnClickListener(v -> {
            sessionRepository.clearAll();
            currentSession = null;
            refreshHistoryTab();
        });
    }

    // =========================================================================
    // Monitoring state machine
    // =========================================================================

    private void toggleMonitoring() {
        setMonitoringState(!isMonitoring);
    }

    private void setMonitoringState(boolean active) {
        isMonitoring = active;

        if (active) {
            currentSession = new DriveSession();
            fatigueStartTime = 0;
            resetScoreBuffer();

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
                android.widget.Toast.makeText(this,
                        currentSession.getEventCount() + " alerta(s) registado(s)",
                        android.widget.Toast.LENGTH_SHORT).show();
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

            sessionTimerHandler.post(sessionTimerRunnable);

            if (imageAnalysis != null) {
                imageAnalysis.setAnalyzer(cameraExecutor, image -> runOnUiThread(() -> {
                    if (!isMonitoring || classifier == null) { image.close(); return; }
                    Bitmap bitmap = viewFinder.getBitmap();
                    if (bitmap == null) { image.close(); return; }
                    Bitmap cropped = centerCrop(bitmap);
                    if (ivDebugCrop != null) ivDebugCrop.setImageBitmap(cropped);
                    image.close();
                    cameraExecutor.execute(() -> {
                        float score = classifier.analyzeImage(cropped);
                        runOnUiThread(() -> processFrame(score));
                    });
                }));
            }
        }, 3000);
    }

    // =========================================================================
    // Frame processing
    // =========================================================================

    private void processFrame(float score) {
        if (!isMonitoring) return;

        if (score == FatigueClassifier.NO_FACE) {
            resetScoreBuffer();
            tvStatus.setText("ROSTO NÃO DETETADO");
            tvStatus.setBackgroundResource(R.drawable.status_chip_warn);
            tvStatus.setTextColor(0xFFC8AB5A);
            updatePeakMeter(0f);
            if (pbLiveScore    != null) pbLiveScore.setProgress(0);
            if (tvScoreNumeric != null) tvScoreNumeric.setText("—");
            if (tvScoreInPanel != null) { tvScoreInPanel.setText("—"); tvScoreInPanel.setTextColor(0xFF3A3F50); }
            if (ivDebugCrop    != null) ivDebugCrop.setBackgroundColor(0xFF2A200A);
            stopAlarm();
            fatigueStartTime = 0;
            return;
        }

        if (classifier.getLastLuminance() < AppConstants.LOW_LIGHT_THRESHOLD) {
            tvStatus.setText("POUCA LUZ — ANÁLISE EM CURSO");
            tvStatus.setBackgroundResource(R.drawable.status_chip_idle);
            tvStatus.setTextColor(0xFF5A5F6E);
        }

        updateUI(smoothScore(score));
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

        // Score colour: green → amber → red
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
                ivDebugCrop.setBackgroundColor(score > (confidenceThreshold * 0.7f) ? 0xFF2A2000 : 0xFF0A2010);
            }
            stopAlarm();
        }
    }

    // =========================================================================
    // Alerts
    // =========================================================================

    private void triggerFatigueAlert(float score, long duration) {
        if (currentSession != null) currentSession.recordEvent(score, duration);
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

    // =========================================================================
    // History tab
    // =========================================================================

    private void refreshHistoryTab() {
        LinearLayout cardCurrent = findViewById(R.id.cardCurrentSession);
        LinearLayout llCards     = findViewById(R.id.llSessionCards);
        LinearLayout emptyState  = findViewById(R.id.layoutEmptyState);
        TextView     tvLabel     = findViewById(R.id.tvHistoryLabel);
        TextView     tvMeta      = findViewById(R.id.tvSessionMeta);

        if (llCards == null) return;

        List<DriveSession.SessionSnapshot> history = sessionRepository.loadSnapshots();
        boolean hasCurrentEvents = currentSession != null && currentSession.getEventCount() > 0;
        boolean hasHistory       = !history.isEmpty();

        if (hasCurrentEvents && cardCurrent != null) {
            cardCurrent.setVisibility(View.VISIBLE);
            TextView tvTime   = findViewById(R.id.tvCurrentTime);
            TextView tvAlerts = findViewById(R.id.tvCurrentAlerts);
            TextView tvAvg    = findViewById(R.id.tvCurrentAvgScore);
            TextView tvDur    = findViewById(R.id.tvCurrentDuration);

            long  elapsed = currentSession.getElapsedSeconds();
            int   alerts  = currentSession.getEventCount();
            float avg     = currentSession.getAverageScore();

            if (tvTime   != null) tvTime.setText(formatDuration(elapsed));
            if (tvAlerts != null) tvAlerts.setText(String.valueOf(alerts));
            if (tvAvg    != null) tvAvg.setText(avg > 0 ? String.format(Locale.getDefault(), "%.2f", avg) : "—");
            if (tvDur    != null) tvDur.setText(formatDuration(elapsed));
            if (tvMeta   != null) tvMeta.setText(String.format(Locale.getDefault(),
                    "%d alerta%s  ·  %s", alerts, alerts == 1 ? "" : "s", formatDuration(elapsed)));
        } else if (cardCurrent != null) {
            cardCurrent.setVisibility(View.GONE);
            if (tvMeta != null) tvMeta.setText("Sem sessão ativa");
        }

        if (!hasCurrentEvents && !hasHistory) {
            if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
            llCards.setVisibility(View.GONE);
            if (tvLabel != null) tvLabel.setVisibility(View.GONE);
            return;
        }

        if (emptyState != null) emptyState.setVisibility(View.GONE);
        llCards.setVisibility(View.VISIBLE);
        llCards.removeAllViews();

        if (hasHistory) {
            if (tvLabel != null) tvLabel.setVisibility(View.VISIBLE);
            for (DriveSession.SessionSnapshot snap : history) {
                llCards.addView(buildSessionCard(snap));
            }
        } else {
            if (tvLabel != null) tvLabel.setVisibility(View.GONE);
        }
    }

    private View buildSessionCard(DriveSession.SessionSnapshot snap) {
        int dp8  = dp(8);
        int dp12 = dp(12);
        int dp16 = dp(16);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.settings_card);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp8);
        card.setLayoutParams(cardParams);
        card.setPadding(dp16, dp12, dp16, dp12);

        // Date row
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dateRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvDate = new TextView(this);
        tvDate.setText(snap.date);
        tvDate.setTextColor(0xFF3A3F50);
        tvDate.setTextSize(11);
        LinearLayout.LayoutParams dateP = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvDate.setLayoutParams(dateP);
        dateRow.addView(tvDate);

        if (snap.endDate != null && !snap.endDate.isEmpty()) {
            String startTime = snap.date.length() > 11 ? snap.date.substring(11) : snap.date;
            TextView tvTime = new TextView(this);
            tvTime.setText(startTime + " → " + snap.endDate);
            tvTime.setTextColor(0xFF2A2F3E);
            tvTime.setTextSize(11);
            dateRow.addView(tvTime);
        }
        card.addView(dateRow);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(0xFF0E1018);
        LinearLayout.LayoutParams divP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divP.setMargins(0, dp8, 0, dp8);
        divider.setLayoutParams(divP);
        card.addView(divider);

        // Metrics row
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int alertColor = snap.alertCount > 3 ? 0xFFE53935
                : snap.alertCount > 0        ? 0xFFC8AB5A
                : 0xFF2ECC71;
        row.addView(buildMetric(String.valueOf(snap.alertCount), "alertas", alertColor));
        row.addView(buildDividerV());
        row.addView(buildMetric(formatDuration(snap.durationSeconds), "duração", 0xFF2A7AE4));
        row.addView(buildDividerV());

        float avg = 0f;
        if (snap.events != null && !snap.events.isEmpty()) {
            for (DriveSession.FatigueEvent e : snap.events) avg += e.score;
            avg /= snap.events.size();
        }
        row.addView(buildMetric(
                avg > 0 ? String.format(Locale.getDefault(), "%.2f", avg) : "—",
                "score médio", 0xFFC8AB5A));
        card.addView(row);
        return card;
    }

    private LinearLayout buildMetric(String value, String label, int valueColor) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(valueColor);
        tvValue.setTextSize(24);
        tvValue.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tvValue.setGravity(android.view.Gravity.CENTER);
        col.addView(tvValue);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(0xFF3A3F50);
        tvLabel.setTextSize(10);
        tvLabel.setGravity(android.view.Gravity.CENTER);
        col.addView(tvLabel);

        return col;
    }

    private View buildDividerV() {
        View v = new View(this);
        v.setBackgroundColor(0xFF0E1018);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(1), dp(36)));
        return v;
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        return String.format(Locale.getDefault(), "%dm%02ds", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // Low light suggestion
    // =========================================================================

    private void updateLowLightSuggestion() {
        View    tip = findViewById(R.id.tvLowLightTip);
        SeekBar sb  = findViewById(R.id.sbSensitivity);
        if (tip == null || sb == null) return;
        boolean isLowLight = classifier != null
                && classifier.getLastLuminance() < AppConstants.LOW_LIGHT_THRESHOLD;
        tip.setVisibility(isLowLight ? View.VISIBLE : View.GONE);
    }

    // =========================================================================
    // Help / Onboarding
    // =========================================================================

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
    }

    // =========================================================================
    // Camera
    // =========================================================================

    private void startCamera() {
        if (cameraLoadingOverlay != null) cameraLoadingOverlay.setVisibility(View.VISIBLE);

        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();

                viewFinder.getPreviewStreamState().observe(this, state -> {
                    if (state == PreviewView.StreamState.STREAMING && cameraLoadingOverlay != null) {
                        cameraLoadingOverlay.animate()
                                .alpha(0f).setDuration(400)
                                .withEndAction(() -> cameraLoadingOverlay.setVisibility(View.GONE))
                                .start();
                    }
                });

                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao iniciar câmara", e);
                if (cameraLoadingOverlay != null) cameraLoadingOverlay.setVisibility(View.GONE);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private Bitmap centerCrop(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight(), edge = Math.min(w, h);
        return Bitmap.createBitmap(src, (w - edge) / 2, (h - edge) / 2, edge, edge);
    }

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

    @Override
    public void onRequestPermissionsResult(int code,
                                           @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == AppConstants.CAMERA_PERMISSION_CODE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                // Small delay to ensure cameraExecutor is ready
                new Handler(Looper.getMainLooper()).postDelayed(
                        this::startCamera, AppConstants.CAMERA_INIT_DELAY_MS);
            } else {
                android.widget.Toast.makeText(this,
                        "A câmara é necessária para detectar fadiga.",
                        android.widget.Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}