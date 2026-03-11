package com.example.drowsinessdetector;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // --- Constantes ---
    private static final long  FATIGUE_DURATION_THRESHOLD = 500L;
    private static final long  VOICE_COOLDOWN_MS          = 5000L;
    private static final float DEFAULT_CONFIDENCE         = 0.60f;
    private static final int   DEFAULT_SENSITIVITY        = 60;
    private static final int   CAMERA_PERMISSION_CODE     = 10;
    private static final long  CAMERA_INIT_DELAY_MS       = 500L;

    // --- Suavização do score ---
    private static final int   SMOOTH_WINDOW = 5;
    private final float[]      scoreBuffer   = new float[SMOOTH_WINDOW];
    private int                scoreIndex    = 0;
    private int                scoreCount    = 0;

    private static final String PREFS_NAME      = "DrowsinessPrefs";
    private static final String KEY_USE_VOICE   = "useVoice";
    private static final String KEY_SENSITIVITY = "sensitivity";

    private static final String[] ALERT_MESSAGES = {
            "Parece que está a ficar com sono. Se precisar, pare para descansar.",
            "Os seus olhos parecem pesados. Considere fazer uma pausa curta.",
            "Atenção à estrada. A sua segurança é o mais importante.",
            "Está a demonstrar sinais de cansaço. Mantenha-se alerta ou descanse."
    };

    // --- UI ---
    private PreviewView    viewFinder;
    private TextView       tvStatus;
    private ProgressBar    pbLiveScore;
    private ImageView      ivDebugCrop;
    private MaterialButton btnStartStop;
    private ConstraintLayout          layoutMonitoring;
    private android.widget.LinearLayout layoutSettings;
    private android.widget.LinearLayout layoutHistory;

    // --- Câmara ---
    private ImageAnalysis   imageAnalysis;
    private ExecutorService cameraExecutor;

    // --- IA ---
    private FatigueClassifier classifier;

    // --- Áudio ---
    private MediaPlayer  mediaPlayer;
    private TextToSpeech tts;
    private boolean      isAlarmPlaying = false;
    private long         lastVoiceTime  = 0;

    // --- Estado ---
    private boolean isMonitoring        = false;
    private long    fatigueStartTime    = 0;
    private float   confidenceThreshold = DEFAULT_CONFIDENCE;
    private boolean useVoiceAlerts      = false;

    // --- Sessão ---
    private DriveSession      currentSession;
    private SessionRepository sessionRepository;

    // =========================================================================
    // Ciclo de vida
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bindViews();
        sessionRepository = new SessionRepository(this);
        initAudio();
        initClassifier();
        setupNavigation();
        setupSeekBar();
        setupDebugCropDrag();
        setupVoiceSwitch();
        setupClearHistoryButton();

        btnStartStop.setOnClickListener(v -> toggleMonitoring());
        setMonitoringState(false);

        requestCameraPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        useVoiceAlerts = prefs.getBoolean(KEY_USE_VOICE, false);
        Switch swVoice = findViewById(R.id.switchVoiceMode);
        if (swVoice != null) swVoice.setChecked(useVoiceAlerts);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseResources();
    }

    // =========================================================================
    // Inicialização
    // =========================================================================

    private void bindViews() {
        layoutMonitoring = findViewById(R.id.layoutMonitoring);
        layoutSettings   = findViewById(R.id.layoutSettings);
        layoutHistory    = findViewById(R.id.layoutHistory);
        viewFinder       = findViewById(R.id.viewFinder);
        tvStatus         = findViewById(R.id.tvStatus);
        pbLiveScore      = findViewById(R.id.pbLiveScore);
        ivDebugCrop      = findViewById(R.id.ivDebugCrop);
        btnStartStop     = findViewById(R.id.btnStartStop);
    }

    private void initAudio() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR) tts.setLanguage(new Locale("pt", "PT"));
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
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        }
    }

    // =========================================================================
    // Configuração de UI
    // =========================================================================

    private void setupNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        bottomNav.setOnItemSelectedListener(item -> {
            hideAllLayouts();
            int id = item.getItemId();
            if (id == R.id.nav_monitoring) {
                layoutMonitoring.setVisibility(View.VISIBLE);
                ivDebugCrop.setVisibility(isMonitoring ? View.VISIBLE : View.GONE);
            } else if (id == R.id.nav_history) {
                layoutHistory.setVisibility(View.VISIBLE);
                refreshHistoryTab();
            } else if (id == R.id.nav_settings) {
                layoutSettings.setVisibility(View.VISIBLE);
            }
            return true;
        });
    }

    private void hideAllLayouts() {
        layoutMonitoring.setVisibility(View.GONE);
        layoutSettings.setVisibility(View.GONE);
        layoutHistory.setVisibility(View.GONE);
        ivDebugCrop.setVisibility(View.GONE);
    }

    private void setupSeekBar() {
        SeekBar  sb    = findViewById(R.id.sbSensitivity);
        TextView label = findViewById(R.id.tvSensitivityLabel);
        if (sb == null || label == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int saved = prefs.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY);
        sb.setProgress(saved);
        confidenceThreshold = saved / 100f;
        updateSensitivityLabel(label, saved);

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                confidenceThreshold = progress / 100f;
                updateSensitivityLabel(label, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(KEY_SENSITIVITY, seekBar.getProgress()).apply();
            }
        });
    }

    private void updateSensitivityLabel(TextView label, int progress) {
        String cat;
        if (progress < 35)      cat = "RIGOROSO";
        else if (progress > 75) cat = "RELAXADO";
        else                    cat = "EQUILIBRADO";
        label.setText(String.format("Modo: %s (%.2f)", cat, progress / 100f));
    }

    private void setupDebugCropDrag() {
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
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(KEY_USE_VOICE, isChecked).apply();
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
    // Lógica de monitorização
    // =========================================================================

    private void toggleMonitoring() {
        setMonitoringState(!isMonitoring);
    }

    private void setMonitoringState(boolean active) {
        isMonitoring = active;

        if (active) {
            currentSession   = new DriveSession();
            fatigueStartTime = 0;
            resetScoreBuffer();
            btnStartStop.setEnabled(false);
            btnStartStop.setBackgroundTintList(ColorStateList.valueOf(0xFFE53935));
            startCountdown();
        } else {
            btnStartStop.setText("INICIAR");
            btnStartStop.setEnabled(true);
            btnStartStop.setBackgroundTintList(ColorStateList.valueOf(0xFF4CAF50));
            ivDebugCrop.setVisibility(View.GONE);
            if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
            stopAlarm();
            stopVibration();
            fatigueStartTime = 0;
            resetScoreBuffer();
            if (currentSession != null && currentSession.getEventCount() > 0) {
                sessionRepository.saveSession(currentSession);
            }
            tvStatus.setText("⏸ SISTEMA PAUSADO");
            tvStatus.setBackgroundColor(0xFF444444);
            pbLiveScore.setProgress(0);
            Log.d(TAG, "Monitorização pausada");
        }
    }

    private void resetScoreBuffer() {
        scoreIndex = 0;
        scoreCount = 0;
        for (int i = 0; i < SMOOTH_WINDOW; i++) scoreBuffer[i] = 0f;
    }

    private float smoothScore(float raw) {
        scoreBuffer[scoreIndex] = raw;
        scoreIndex = (scoreIndex + 1) % SMOOTH_WINDOW;
        if (scoreCount < SMOOTH_WINDOW) scoreCount++;
        float sum = 0f;
        for (int i = 0; i < scoreCount; i++) sum += scoreBuffer[i];
        return sum / scoreCount;
    }

    private void startCountdown() {
        final Handler handler = new Handler(Looper.getMainLooper());
        tvStatus.setText("Prepare-se...");
        tvStatus.setBackgroundColor(0xFF444444);
        btnStartStop.setText("3");
        handler.postDelayed(() -> btnStartStop.setText("2"), 1000);
        handler.postDelayed(() -> btnStartStop.setText("1"), 2000);
        handler.postDelayed(() -> {
            btnStartStop.setText("PARAR");
            btnStartStop.setEnabled(true);
            ivDebugCrop.setVisibility(View.VISIBLE);
            if (imageAnalysis != null) {
                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    runOnUiThread(() -> {
                        if (!isMonitoring || classifier == null) { image.close(); return; }
                        Bitmap bitmap = viewFinder.getBitmap();
                        if (bitmap == null) { image.close(); return; }
                        Bitmap cropped = centerCrop(bitmap);
                        ivDebugCrop.setImageBitmap(cropped);
                        image.close();
                        cameraExecutor.execute(() -> {
                            float score = classifier.analyzeImage(cropped);
                            runOnUiThread(() -> processFrame(score));
                        });
                    });
                });
            }
            Log.d(TAG, "Monitorização iniciada após countdown");
        }, 3000);
    }

    private void processFrame(float score) {
        if (!isMonitoring) return;

        if (score == FatigueClassifier.NO_FACE) {
            resetScoreBuffer();
            tvStatus.setText("👤 Rosto não detetado\nAjuste a posição do dispositivo");
            tvStatus.setBackgroundColor(0xFFE65100);
            pbLiveScore.setProgress(0);
            ivDebugCrop.setBackgroundColor(0xFFE65100);
            stopAlarm();
            fatigueStartTime = 0;
            return;
        }

        if (classifier.getLastLuminance() < AppConstants.LOW_LIGHT_THRESHOLD) {
            // Aviso visual mas não bloqueia — modelo v3 foi treinado com augmentation de brilho
            tvStatus.setText("🔦 Pouca luz — análise em curso");
            tvStatus.setBackgroundColor(0xFF37474F);
        }

        updateUI(smoothScore(score));
    }

    private void updateUI(float score) {
        pbLiveScore.setProgress((int)(score * 100));
        if (score > confidenceThreshold) {
            if (fatigueStartTime == 0) fatigueStartTime = System.currentTimeMillis();
        } else {
            fatigueStartTime = 0;
        }
        long   duration = (fatigueStartTime == 0) ? 0 : (System.currentTimeMillis() - fatigueStartTime);
        String msgScore = String.format(Locale.getDefault(), "Score: %.3f", score);
        if (duration >= FATIGUE_DURATION_THRESHOLD) {
            tvStatus.setText("⚠️ FADIGA DETETADA!\n" + msgScore + "\nTempo: " + duration + "ms");
            tvStatus.setBackgroundColor(getColor(android.R.color.holo_red_dark));
            ivDebugCrop.setBackgroundColor(Color.RED);
            triggerFatigueAlert(score, duration);
        } else {
            tvStatus.setText("✅ MOTORISTA ATENTO\n" + msgScore);
            tvStatus.setBackgroundColor(getColor(android.R.color.holo_green_dark));
            ivDebugCrop.setBackgroundColor(score > (confidenceThreshold * 0.7f) ? Color.YELLOW : Color.GREEN);
            stopAlarm();
        }
    }

    // =========================================================================
    // Alertas
    // =========================================================================

    private void triggerFatigueAlert(float score, long duration) {
        if (currentSession != null) currentSession.recordEvent(score, duration);
        triggerVibration();
        if (useVoiceAlerts) {
            long now = System.currentTimeMillis();
            if (now - lastVoiceTime > VOICE_COOLDOWN_MS) {
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
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) vibrator.cancel();
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
    // Relatórios — novo layout com cards
    // =========================================================================

    private void refreshHistoryTab() {
        LinearLayout cardCurrent  = findViewById(R.id.cardCurrentSession);
        LinearLayout llCards      = findViewById(R.id.llSessionCards);
        LinearLayout emptyState   = findViewById(R.id.layoutEmptyState);
        TextView     tvLabel      = findViewById(R.id.tvHistoryLabel);
        TextView     tvMeta       = findViewById(R.id.tvSessionMeta);

        if (llCards == null) return;

        List<DriveSession.SessionSnapshot> history = sessionRepository.loadSnapshots();
        boolean hasCurrentEvents = currentSession != null && currentSession.getEventCount() > 0;
        boolean hasHistory       = !history.isEmpty();

        // --- Sessão atual ---
        if (hasCurrentEvents && cardCurrent != null) {
            cardCurrent.setVisibility(View.VISIBLE);

            TextView tvTime    = findViewById(R.id.tvCurrentTime);
            TextView tvAlerts  = findViewById(R.id.tvCurrentAlerts);
            TextView tvAvg     = findViewById(R.id.tvCurrentAvgScore);
            TextView tvDur     = findViewById(R.id.tvCurrentDuration);

            long elapsed = currentSession.getElapsedSeconds();
            int  alerts  = currentSession.getEventCount();
            float avg    = currentSession.getAverageScore();

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

        // --- Estado vazio ---
        if (!hasCurrentEvents && !hasHistory) {
            if (emptyState != null) emptyState.setVisibility(View.VISIBLE);
            llCards.setVisibility(View.GONE);
            if (tvLabel != null) tvLabel.setVisibility(View.GONE);
            return;
        }

        if (emptyState != null) emptyState.setVisibility(View.GONE);
        llCards.setVisibility(View.VISIBLE);

        // --- Cards de sessões anteriores ---
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

        // Card container
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1E1E1E);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp8);
        card.setLayoutParams(cardParams);
        card.setPadding(dp16, dp12, dp16, dp12);

        // Data / hora
        // Data e intervalo de horas
        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dateRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvDate = new TextView(this);
        tvDate.setText(snap.date);
        tvDate.setTextColor(0xFF888888);
        tvDate.setTextSize(11);
        LinearLayout.LayoutParams dateP = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvDate.setLayoutParams(dateP);
        dateRow.addView(tvDate);

        if (snap.endDate != null && !snap.endDate.isEmpty()) {
            // Extrai só a hora do startDate (formato "dd/MM/yyyy HH:mm")
            String startTime = snap.date.length() > 11 ? snap.date.substring(11) : snap.date;
            TextView tvTime = new TextView(this);
            tvTime.setText(startTime + " → " + snap.endDate);
            tvTime.setTextColor(0xFF555555);
            tvTime.setTextSize(11);
            dateRow.addView(tvTime);
        }

        card.addView(dateRow);

        // Linha de separação
        View divider = new View(this);
        divider.setBackgroundColor(0xFF2A2A2A);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divParams.setMargins(0, dp8, 0, dp8);
        divider.setLayoutParams(divParams);
        card.addView(divider);

        // Linha de métricas
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(buildMetric(
                String.valueOf(snap.alertCount),
                "Alertas",
                snap.alertCount > 3 ? 0xFFEF5350 : snap.alertCount > 0 ? 0xFFFFEB3B : 0xFF4CAF50));
        row.addView(buildDividerV());
        row.addView(buildMetric(formatDuration(snap.durationSeconds), "Duração", 0xFF2196F3));
        row.addView(buildDividerV());

        // Score médio dos eventos
        float avg = 0f;
        if (snap.events != null && !snap.events.isEmpty()) {
            for (DriveSession.FatigueEvent e : snap.events) avg += e.score;
            avg /= snap.events.size();
        }
        row.addView(buildMetric(
                avg > 0 ? String.format(Locale.getDefault(), "%.2f", avg) : "—",
                "Score Médio",
                0xFFFFEB3B));

        card.addView(row);
        return card;
    }

    private LinearLayout buildMetric(String value, String label, int valueColor) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(p);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(valueColor);
        tvValue.setTextSize(22);
        tvValue.setTypeface(null, android.graphics.Typeface.BOLD);
        tvValue.setGravity(Gravity.CENTER);
        col.addView(tvValue);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(0xFF666666);
        tvLabel.setTextSize(10);
        tvLabel.setGravity(Gravity.CENTER);
        col.addView(tvLabel);

        return col;
    }

    private View buildDividerV() {
        View v = new View(this);
        v.setBackgroundColor(0xFF2A2A2A);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(1), dp(36)));
        return v;
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        return String.format(Locale.getDefault(), "%dm%02ds", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // Câmara
    // =========================================================================

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao iniciar câmara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // =========================================================================
    // Utilitários
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
        if (mediaPlayer    != null) { mediaPlayer.release();      mediaPlayer    = null; }
        if (classifier     != null) { classifier.close();         classifier     = null; }
        if (tts            != null) { tts.stop(); tts.shutdown(); tts            = null; }
        if (cameraExecutor != null) cameraExecutor.shutdown();
        stopVibration();
    }

    @Override
    public void onRequestPermissionsResult(int code,
                                           @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == CAMERA_PERMISSION_CODE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                new Handler(Looper.getMainLooper()).postDelayed(this::startCamera, CAMERA_INIT_DELAY_MS);
            } else {
                android.widget.Toast.makeText(this,
                        "A câmara é necessária para detetar fadiga.",
                        android.widget.Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}