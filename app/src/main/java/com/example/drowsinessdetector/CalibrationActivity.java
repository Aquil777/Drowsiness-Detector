package com.example.drowsinessdetector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import com.example.drowsinessdetector.BiometricOverlayView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CalibrationActivity — v3
 *
 * Correções v3:
 *  - FIX PRINCIPAL: as amostras são capturadas e guardadas numa variável
 *    local atómica da fase, não comparando currentPhase depois de um delay.
 *    Isso resolve o bug de "0 amostras" quando a fase muda antes do post().
 *  - UI redesenhada: overlay biométrico com Path (oval + pontos de scan),
 *    sem emojis. Estética futurista consistente com o resto da app.
 *  - BiometricOverlayView: canvas animado com oval azul pulsante e scan line.
 */
public class CalibrationActivity extends AppCompatActivity {

    private static final String TAG = "CalibrationActivity";
    public  static final int    RESULT_CALIBRATED = 100;

    private static final long FRAME_INTERVAL_MS = 250L; // ligeiramente mais lento para estabilidade
    private static final int  PHASE_DURATION_S  = 5;

    // TTS utterance IDs
    private static final String TTS_INTRO        = "tts_intro";
    private static final String TTS_OPEN_START   = "tts_open_start";
    private static final String TTS_OPEN_DONE    = "tts_open_done";
    private static final String TTS_TRANSITION   = "tts_transition";
    private static final String TTS_PROCESSING = "tts_processing";
    private static final String TTS_CLOSED_START = "tts_closed_start";
    private static final String TTS_CLOSED_DONE  = "tts_closed_done";
    private static final String TTS_RESULT_OK    = "tts_result_ok";
    private static final String TTS_RESULT_FAIL  = "tts_result_fail";

    private enum Phase { INTRO, OPEN_EYES, TRANSITION, CLOSED_EYES, PROCESSING, RESULT }

    // ── UI ────────────────────────────────────────────────────────────────────
    private PreviewView         previewView;
    private BiometricOverlayView biometricOverlay;
    private TextView            tvBadge;
    private TextView            tvTitle;
    private TextView            tvDescription;
    private TextView            tvCountdown;
    private TextView            tvSampleCount;
    private MaterialButton      btnAction;
    private View                overlayDim;

    // ── Câmara ────────────────────────────────────────────────────────────────
    private ImageAnalysis   imageAnalysis;
    private ExecutorService cameraExecutor;

    // ── Classificador ─────────────────────────────────────────────────────────
    private FatigueClassifier classifier;

    // ── TTS ───────────────────────────────────────────────────────────────────
    private TextToSpeech tts;
    private boolean      ttsReady = false;

    // ── Estado ────────────────────────────────────────────────────────────────
    private Phase             currentPhase  = Phase.INTRO;
    private final List<Float> openScores    = new ArrayList<>();
    private final List<Float> closedScores  = new ArrayList<>();
    private final Handler     mainHandler   = new Handler(Looper.getMainLooper());
    private int               countdownSecs = PHASE_DURATION_S;

    // FIX: flag que identifica qual fase está activa no momento da captura,
    // evitando race condition quando currentPhase muda antes do post() executar.
    private volatile Phase captureTargetPhase = null;

    // ── Runnables ─────────────────────────────────────────────────────────────

    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (countdownSecs > 0) {
                tvCountdown.setText(String.valueOf(countdownSecs));
                tvCountdown.setVisibility(View.VISIBLE);
                speak(String.valueOf(countdownSecs),
                        "tts_count_" + currentPhase.name() + "_" + countdownSecs);
                countdownSecs--;
                mainHandler.postDelayed(this, 1000L);
            } else {
                tvCountdown.setVisibility(View.GONE);
                onPhaseComplete();
            }
        }
    };

    private final Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            // FIX: guarda a fase alvo localmente para evitar race condition
            final Phase target = captureTargetPhase;
            if (target == Phase.OPEN_EYES || target == Phase.CLOSED_EYES) {
                captureFrameForPhase(target);
                mainHandler.postDelayed(this, FRAME_INTERVAL_MS);
            }
        }
    };

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        bindViews();
        initClassifier();
        initTts();

        btnAction.setOnClickListener(v -> onActionButton());
        showPhase(Phase.INTRO);
        requestCameraPermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        captureTargetPhase = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (imageAnalysis  != null) imageAnalysis.clearAnalyzer();
        if (classifier     != null) classifier.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (tts            != null) { tts.stop(); tts.shutdown(); }
        if (biometricOverlay != null) biometricOverlay.stopAnimation();
    }

    // =========================================================================
    // Bind
    // =========================================================================

    private void bindViews() {
        previewView      = findViewById(R.id.calibPreviewView);
        biometricOverlay = findViewById(R.id.calibBiometricOverlay);
        tvBadge          = findViewById(R.id.calibBadge);
        tvTitle          = findViewById(R.id.calibTitle);
        tvDescription    = findViewById(R.id.calibDescription);
        tvCountdown      = findViewById(R.id.calibCountdown);
        tvSampleCount    = findViewById(R.id.calibSampleCount);
        btnAction        = findViewById(R.id.calibBtnAction);
        overlayDim       = findViewById(R.id.calibOverlayDim);
    }

    // =========================================================================
    // TTS
    // =========================================================================

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS não inicializou");
                return;
            }
            int result = tts.setLanguage(new Locale("pt", "PT"));
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts.setLanguage(new Locale("pt", "BR"));
            }
            ttsReady = (result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED);

            if (!ttsReady) return;

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onError(String id) {}
                @Override public void onDone(String id) {
                    mainHandler.post(() -> onTtsDone(id));
                }
            });

            mainHandler.post(() -> {
                if (currentPhase == Phase.INTRO) announcePhase(Phase.INTRO);
            });
        });
    }

    private void speak(String text, String id) { speak(text, id, false); }

    private void speak(String text, String id, boolean flush) {
        if (!ttsReady || tts == null) return;
        tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, id);
    }

    private void announcePhase(Phase phase) {
        switch (phase) {
            case INTRO:
                speak("Bem-vindo à calibração...", TTS_INTRO, false);   // não interrompe nada
                break;
            case OPEN_EYES:
                speak("Fase um de dois...", TTS_OPEN_START, false);
                break;
            case TRANSITION:
                speak("Muito bem, primeira fase concluída!...", TTS_TRANSITION, false);
                break;
            case CLOSED_EYES:
                speak("Fecha os olhos agora...", TTS_CLOSED_START, false);
                break;
            case PROCESSING:
                speak("A calcular o teu perfil pessoal...", "tts_processing", false);
                break;
            default:
                break;
        }
    }

    private void onTtsDone(String id) {
        switch (id) {
            case TTS_INTRO:
                // A introdução terminou — não fazemos nada, o utilizador pode carregar COMEÇAR.
                break;
            case TTS_OPEN_START:
                startCountdownAndCapture();
                break;
            case TTS_OPEN_DONE:
                // Só transita depois de a fala "Muito bem!" terminar (mais uma pequena pausa)
                mainHandler.postDelayed(() -> showPhase(Phase.TRANSITION), 800);
                break;
            case TTS_TRANSITION:
                // A fala de transição terminou — nada automático, espera pelo botão.
                break;
            case TTS_CLOSED_START:
                startCountdownAndCapture();
                break;
            case TTS_CLOSED_DONE:
                // Depois de "Podes abrir os olhos..." → PROCESSING
                mainHandler.postDelayed(() -> showPhase(Phase.PROCESSING), 800);
                break;
            case TTS_PROCESSING:
                // Já tratado internamente em calculateAndSave()
                break;
            case TTS_RESULT_OK:
            case TTS_RESULT_FAIL:
                // Nada extra.
                break;
        }
    }

    // =========================================================================
    // Classificador
    // =========================================================================

    private void initClassifier() {
        try {
            classifier = new FatigueClassifier(this);
            cameraExecutor = Executors.newSingleThreadExecutor();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao inicializar classificador: " + e.getMessage(), e);
            classifier = null;
            runOnUiThread(() -> android.widget.Toast.makeText(
                    this, "Erro ao carregar modelo de IA. A calibração não funcionará.",
                    android.widget.Toast.LENGTH_LONG).show());
        }
    }

    // =========================================================================
    // Câmara
    // =========================================================================

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    AppConstants.CAMERA_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == AppConstants.CAMERA_PERMISSION_CODE &&
                results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao iniciar câmara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // =========================================================================
    // Máquina de estados
    // =========================================================================

    private void showPhase(Phase phase) {
        currentPhase = phase;
        // Para capturas da fase anterior
        captureTargetPhase = null;
        mainHandler.removeCallbacks(countdownRunnable);
        mainHandler.removeCallbacks(captureRunnable);
        tvCountdown.setVisibility(View.GONE);
        if (tvSampleCount != null) tvSampleCount.setVisibility(View.GONE);

        switch (phase) {

            case INTRO:
                previewView.setVisibility(View.VISIBLE);
                overlayDim.setVisibility(View.GONE);
                biometricOverlay.setState(BiometricOverlayView.State.IDLE);
                tvBadge.setText("1 / 2");
                tvBadge.setTextColor(Color.parseColor("#C8AB5A"));
                tvTitle.setText("Calibração pessoal");
                tvDescription.setText(
                        "Vamos ajustar o detector ao teu rosto.\n\n" +
                                "Fase 1 — 5 s com olhos abertos\n" +
                                "Fase 2 — 5 s com olhos fechados\n\n" +
                                "Senta-te no carro (ou num local com iluminação semelhante)\n" +
                                "e posiciona o telemóvel como se fosses conduzir.\n\n" +
                                "O teu rosto deve ficar enquadrado no oval azul.\n\n" +
                                "Usa os mesmos óculos / lentes que usas ao volante.\n\n" +
                                "As instruções serão lidas em voz alta.");
                btnAction.setText("COMEÇAR");
                btnAction.setEnabled(true);
                setButtonColor(true);
                if (ttsReady) announcePhase(Phase.INTRO);
                break;

            case OPEN_EYES:
                previewView.setVisibility(View.VISIBLE);
                overlayDim.setVisibility(View.GONE);
                biometricOverlay.setState(BiometricOverlayView.State.SCANNING);
                tvBadge.setText("1 / 2");
                tvBadge.setTextColor(Color.parseColor("#2ECC71"));
                tvTitle.setText("Olhos abertos");
                tvDescription.setText(
                        "Olha directamente para a câmara\ncom os olhos bem abertos.");
                btnAction.setEnabled(false);
                setButtonColor(false);
                openScores.clear();
                announcePhase(Phase.OPEN_EYES);
                if (!ttsReady) startCountdownAndCapture();
                break;

            case TRANSITION:
                previewView.setVisibility(View.VISIBLE);
                overlayDim.setVisibility(View.GONE);
                biometricOverlay.setState(BiometricOverlayView.State.IDLE);
                tvBadge.setText("2 / 2");
                tvBadge.setTextColor(Color.parseColor("#C8AB5A"));
                tvTitle.setText("Fase 1 concluída");
                tvDescription.setText(
                        "Amostras capturadas: " + openScores.size() + "\n\n" +
                                "Quando premires CONTINUAR, fecha os olhos " +
                                "completamente e mantém-nos fechados.\n\n" +
                                "Ouvirás a contagem — quando terminar, podes abrir os olhos.");
                btnAction.setText("CONTINUAR");
                btnAction.setEnabled(true);
                setButtonColor(true);
                announcePhase(Phase.TRANSITION);
                break;

            case CLOSED_EYES:
                previewView.setVisibility(View.VISIBLE);
                overlayDim.setVisibility(View.VISIBLE);
                biometricOverlay.setState(BiometricOverlayView.State.CLOSED);
                tvBadge.setText("2 / 2");
                tvBadge.setTextColor(Color.parseColor("#E53935"));
                tvTitle.setText("Olhos fechados");
                tvDescription.setText(
                        "Mantém os olhos FECHADOS.\n\n" +
                                "Ouvirás cada segundo. Quando eu disser\n" +
                                "\"Podes abrir os olhos\", abre-os.");
                btnAction.setEnabled(false);
                setButtonColor(false);
                closedScores.clear();
                announcePhase(Phase.CLOSED_EYES);
                if (!ttsReady) startCountdownAndCapture();
                break;

            case PROCESSING:
                previewView.setVisibility(View.GONE);
                overlayDim.setVisibility(View.GONE);
                biometricOverlay.setState(BiometricOverlayView.State.PROCESSING);
                tvBadge.setText("…");
                tvBadge.setTextColor(Color.parseColor("#2A7AE4"));
                tvTitle.setText("A calcular…");
                tvDescription.setText(
                        "A analisar " + (openScores.size() + closedScores.size()) +
                                " amostras e a calcular o teu threshold pessoal.");
                btnAction.setEnabled(false);
                setButtonColor(false);
                announcePhase(Phase.PROCESSING);
                calculateAndSave();
                break;

            case RESULT:
                // tratado em showResult()
                break;
        }
    }

    private void onActionButton() {
        switch (currentPhase) {
            case INTRO:
                if (ttsReady) tts.stop();   // pára a fala atual (introdução)
                showPhase(Phase.OPEN_EYES);
                break;
            case TRANSITION:
                if (ttsReady) tts.stop();   // pára a fala de transição
                showPhase(Phase.CLOSED_EYES);
                break;
            case RESULT:
                finish();
                break;
        }
    }

    private void startCountdownAndCapture() {
        // FIX: define a fase alvo ANTES de iniciar os runnables
        captureTargetPhase = currentPhase;
        countdownSecs = PHASE_DURATION_S;
        mainHandler.post(countdownRunnable);
        // FIX: delay pequeno para garantir que o preview já tem frames
        mainHandler.postDelayed(captureRunnable, 300L);
        if (tvSampleCount != null) tvSampleCount.setVisibility(View.VISIBLE);
    }

    private void onPhaseComplete() {
        captureTargetPhase = null;

        switch (currentPhase) {
            case OPEN_EYES:
                // Fala de conclusão (sem interromper nada, apenas enfileira)
                speak("Muito bem! Descansa os olhos.", TTS_OPEN_DONE, false);
                // A transição para TRANSITION será feita em onTtsDone
                break;
            case CLOSED_EYES:
                speak("Podes abrir os olhos. Segunda fase concluída!", TTS_CLOSED_DONE, false);
                // A transição para PROCESSING será feita em onTtsDone
                break;
        }
    }

    // =========================================================================
    // Captura de frames — FIX PRINCIPAL
    // =========================================================================

    /**
     * FIX v3: recebe a fase como parâmetro em vez de ler currentPhase no callback.
     * Isso elimina a race condition onde currentPhase já mudou quando o
     * executor chama de volta na main thread.
     */
    private void captureFrameForPhase(final Phase targetPhase) {
        if (previewView == null) return;

        // getBitmap() tem de correr na main thread (já estamos nela)
        android.graphics.Bitmap bitmap = previewView.getBitmap();
        if (bitmap == null) {
            Log.d(TAG, "getBitmap() retornou null — preview ainda não activo");
            return;
        }

        // Inferência numa background thread
        cameraExecutor.execute(() -> {
            if (classifier == null) return;
            float score = classifier.analyzeImage(bitmap);

            // FIX: volta à main thread e usa targetPhase (imutável) em vez de currentPhase
            mainHandler.post(() -> {
                // Só adiciona se a fase alvo ainda estiver activa
                if (captureTargetPhase != targetPhase) return;

                if (score != FatigueClassifier.NO_FACE) {
                    if (targetPhase == Phase.OPEN_EYES) {
                        openScores.add(score);
                    } else if (targetPhase == Phase.CLOSED_EYES) {
                        closedScores.add(score);
                    }
                }

                // Atualiza contador de amostras no ecrã
                if (tvSampleCount != null && tvSampleCount.getVisibility() == View.VISIBLE) {
                    int count = (targetPhase == Phase.OPEN_EYES)
                            ? openScores.size() : closedScores.size();
                    tvSampleCount.setText(count + " amostras");
                }

                Log.d(TAG, "Fase=" + targetPhase + "  score=" + score +
                        "  abertos=" + openScores.size() +
                        "  fechados=" + closedScores.size());
            });
        });
    }

    // =========================================================================
    // Cálculo do threshold
    // =========================================================================

    private void calculateAndSave() {
        mainHandler.postDelayed(() -> {
            float mediaAbertos  = average(openScores,  0f);
            float mediaFechados = average(closedScores, 1f);

            Log.d(TAG, "RESULTADO — abertos=" + mediaAbertos +
                    " (" + openScores.size() + " amostras)" +
                    "  fechados=" + mediaFechados +
                    " (" + closedScores.size() + " amostras)");

            boolean erroAbertos    = mediaAbertos  > 0.55f;
            boolean erroFechados   = mediaFechados < 0.15f;
            boolean poucasAmostras = openScores.size() < 5 || closedScores.size() < 5;

            if (erroAbertos || erroFechados || poucasAmostras) {
                String msg;
                if (poucasAmostras) {
                    msg = "Amostras insuficientes\n\n" +
                            "Abertos: " + openScores.size() + " amostras\n" +
                            "Fechados: " + closedScores.size() + " amostras\n\n" +
                            "O mínimo é 5 por fase. Verifica se o rosto está\n" +
                            "enquadrado no oval e bem iluminado.";
                } else if (erroAbertos) {
                    msg = "Score com olhos abertos demasiado alto\n(" +
                            String.format(Locale.getDefault(), "%.2f", mediaAbertos) + ")\n\n" +
                            "Assegura-te de que os olhos estavam bem abertos\ne que há boa iluminação.";
                } else {
                    msg = "Score com olhos fechados demasiado baixo\n(" +
                            String.format(Locale.getDefault(), "%.2f", mediaFechados) + ")\n\n" +
                            "Fecha completamente os olhos na próxima tentativa.";
                }
                showResult(false, msg);
                return;
            }

            float threshold = (mediaAbertos + mediaFechados) / 2f;
            Log.d(TAG, "Threshold pessoal calculado: " + threshold);

            getSharedPreferences(AppConstants.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putFloat(AppConstants.KEY_FATIGUE_THRESHOLD, threshold)
                    .apply();

            String msg =
                    "Threshold pessoal: " +
                            String.format(Locale.getDefault(), "%.2f", threshold) + "\n\n" +
                            "Score médio olhos abertos:   " +
                            String.format(Locale.getDefault(), "%.3f", mediaAbertos) +
                            "  (" + openScores.size() + " amostras)\n" +
                            "Score médio olhos fechados:  " +
                            String.format(Locale.getDefault(), "%.3f", mediaFechados) +
                            "  (" + closedScores.size() + " amostras)\n\n" +
                            "O detector está agora optimizado para o teu rosto.";
            showResult(true, msg);

        }, 300L);
    }

    private void showResult(boolean success, String message) {
        currentPhase = Phase.RESULT;
        captureTargetPhase = null;
        previewView.setVisibility(View.GONE);
        overlayDim.setVisibility(View.GONE);

        biometricOverlay.setState(success
                ? BiometricOverlayView.State.SUCCESS
                : BiometricOverlayView.State.ERROR);

        tvBadge.setText(success ? "OK" : "ERRO");
        tvBadge.setTextColor(Color.parseColor(success ? "#2ECC71" : "#E53935"));
        tvTitle.setText(success ? "Calibração concluída" : "Calibração falhada");
        tvDescription.setText(message);

        btnAction.setText(success ? "FECHAR" : "REPETIR");
        btnAction.setEnabled(true);
        setButtonColor(success);

        if (success) {
            speak("Calibração concluída com sucesso! O detector está optimizado para o teu rosto.",
                    TTS_RESULT_OK, true);
            setResult(RESULT_CALIBRATED);
        } else {
            speak("A calibração falhou. Verifica as instruções e tenta novamente.",
                    TTS_RESULT_FAIL, true);
            btnAction.setOnClickListener(v -> {
                openScores.clear();
                closedScores.clear();
                showPhase(Phase.INTRO);
                btnAction.setOnClickListener(vv -> onActionButton());
            });
        }
    }

    // =========================================================================
    // Auxiliares
    // =========================================================================

    private float average(List<Float> list, float fallback) {
        if (list.isEmpty()) return fallback;
        float sum = 0f;
        for (float v : list) sum += v;
        return sum / list.size();
    }

    private void setButtonColor(boolean green) {
        btnAction.setBackgroundTintList(ColorStateList.valueOf(
                green ? 0xFF1A6B3A : 0xFF1E2230));
    }

}