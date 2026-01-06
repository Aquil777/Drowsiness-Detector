package com.example.drowsinessdetector;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private PreviewView viewFinder;
    private TextView tvStatus;
    private FatigueClassifier classifier;
    private ExecutorService cameraExecutor;
    private MediaPlayer mediaPlayer;
    private boolean isAlarmPlaying = false;
    private int fatigueCounter = 0; // Contador de frames de fadiga
    private final int FATIGUE_THRESHOLD_FRAMES = 3; // Precisamos de 4 frames seguidos
    private long fatigueStartTime = 0; // Marca quando a fadiga começou
    private final long FATIGUE_DURATION_THRESHOLD = 500; // 500ms (ajustável)
    private float confidenceThreshold = 0.60f; // Variável dinâmica
    private ImageView ivDebugCrop;
    private TextToSpeech tts;
    private boolean useVoiceAlerts = false;
    private long lastVoiceTime = 0; // Para não falar sem parar
    private androidx.constraintlayout.widget.ConstraintLayout layoutMonitoring;
    private android.widget.LinearLayout layoutSettings;
    private android.widget.LinearLayout layoutHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- INICIALIZAÇÃO DE COMPONENTES ---
        layoutMonitoring = findViewById(R.id.layoutMonitoring);
        layoutSettings = findViewById(R.id.layoutSettings);
        layoutHistory = findViewById(R.id.layoutHistory); // Adicionado
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);

        viewFinder = findViewById(R.id.viewFinder);
        tvStatus = findViewById(R.id.tvStatus);
        ivDebugCrop = findViewById(R.id.ivDebugCrop);
        SeekBar sbSensitivity = findViewById(R.id.sbSensitivity);
        TextView tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel);

        // --- LÓGICA DE NAVEGAÇÃO (As 3 Abas) ---
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            // Reset de visibilidade de todos os layouts
            layoutMonitoring.setVisibility(android.view.View.GONE);
            layoutSettings.setVisibility(android.view.View.GONE);
            layoutHistory.setVisibility(android.view.View.GONE);

            // O frame de debug por padrão fica escondido
            ivDebugCrop.setVisibility(android.view.View.GONE);

            if (id == R.id.nav_monitoring) {
                layoutMonitoring.setVisibility(android.view.View.VISIBLE);
                // Só aparece na aba de Condução
                ivDebugCrop.setVisibility(android.view.View.VISIBLE);
            } else if (id == R.id.nav_history) {
                layoutHistory.setVisibility(android.view.View.VISIBLE);
            } else if (id == R.id.nav_settings) {
                layoutSettings.setVisibility(android.view.View.VISIBLE);
            }
            return true;
        });

        // --- TUA LÓGICA DE VOZ E SOM ---
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR) {
                tts.setLanguage(new Locale("pt", "PT"));
            }
        });

        mediaPlayer = MediaPlayer.create(this, R.raw.alarm);
        mediaPlayer.setLooping(true);

        // --- TUA LÓGICA DE CÂMERA E PERMISSÕES ---
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        try {
            classifier = new FatigueClassifier(this);
        } catch (Exception e) { e.printStackTrace(); }

        cameraExecutor = Executors.newSingleThreadExecutor();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // --- TUA LÓGICA DA SEEKBAR ---
        sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                confidenceThreshold = progress / 100f;
                String categoria;

                // Se o valor é BAIXO (ex: 0.20), a IA dispara com pouca prova -> Sistema Nervoso/Rigoroso
                // Se o valor é ALTO (ex: 0.85), a IA só dispara com prova total -> Sistema Relaxado/Permissivo

                if (progress < 35) {
                    categoria = "RIGOROSO";   // Toca muito facilmente, quase sem tolerância.
                } else if (progress > 75) {
                    categoria = "RELAXADO";   // Só toca se tiver certeza absoluta (evita falsos alarmes).
                } else {
                    categoria = "EQUILIBRADO"; // O meio termo ideal.
                }

                tvSensitivityLabel.setText(String.format("Modo: %s (%.2f)", categoria, confidenceThreshold));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // --- TUA LÓGICA DO TOUCH NO DEBUG CROP ---
        ivDebugCrop.setOnTouchListener(new android.view.View.OnTouchListener() {
            float dX, dY;
            @Override
            public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        view.animate()
                                .x(event.getRawX() + dX)
                                .y(event.getRawY() + dY)
                                .setDuration(0)
                                .start();
                        break;
                    default:
                        return false;
                }
                return true;
            }
        });

        // --- LÓGICA DO SWITCH DE VOZ (Dentro do layoutSettings) ---
        android.widget.Switch swVoice = findViewById(R.id.switchVoiceMode);
        if (swVoice != null) {
            swVoice.setOnCheckedChangeListener((btn, isChecked) -> {
                useVoiceAlerts = isChecked;
                getSharedPreferences("Configs", MODE_PRIVATE).edit().putBoolean("useVoice", isChecked).apply();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences prefs = getSharedPreferences("Configs", MODE_PRIVATE);
        useVoiceAlerts = prefs.getBoolean("useVoice", false);
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    runOnUiThread(() -> {
                        Bitmap bitmap = viewFinder.getBitmap();
                        if (bitmap != null) {
                            // Este é o recorte que vai para o modelo
                            Bitmap croppedBitmap = centerCrop(bitmap);

                            // MOSTRA O RECORTE NO ECRÃ PARA TESTE
                            ivDebugCrop.setImageBitmap(croppedBitmap);

                            float result = classifier.analyzeImage(croppedBitmap);
                            updateUI(result);
                        }
                    });
                    image.close();
                });

                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis);
            } catch (Exception e) { e.printStackTrace(); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void triggerFatigueAlert() {
        if (useVoiceAlerts) {
            // Se estiver no modo voz, fala uma frase a cada 5 segundos
            if (System.currentTimeMillis() - lastVoiceTime > 5000) {
                String msg = getRandomMessage();
                tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "fatigue_id");
                lastVoiceTime = System.currentTimeMillis();
            }
        } else {
            // Se estiver no modo alarme, toca o áudio antigo
            if (!isAlarmPlaying) {
                mediaPlayer.start();
                isAlarmPlaying = true;
            }
        }
    }

    private String getRandomMessage() {
        String[] messages = {
                "Parece que está a ficar com sono. Se precisar, pare para descansar.",
                "Os seus olhos parecem pesados. Considere fazer uma pausa curta.",
                "Atenção à estrada. A sua segurança é o mais importante.",
                "Está a demonstrar sinais de cansaço. Mantenha-se alerta ou descanse."
        };
        return messages[new java.util.Random().nextInt(messages.length)];
    }

    private void updateUI(float result) {
        ProgressBar pbLiveScore = findViewById(R.id.pbLiveScore);
        pbLiveScore.setProgress((int)(result * 100));

        // Lógica de sensibilidade
        if (result > confidenceThreshold) {
            if (fatigueStartTime == 0) fatigueStartTime = System.currentTimeMillis();
        } else {
            fatigueStartTime = 0;
        }

        long duration = (fatigueStartTime == 0) ? 0 : (System.currentTimeMillis() - fatigueStartTime);

        // CRIAMOS UMA STRING COM O SCORE PARA REUTILIZAR
        String msgScore = String.format("Score: %.3f", result);

        if (duration >= FATIGUE_DURATION_THRESHOLD) {
            // ESTADO: FADIGA (Mostra a mensagem + o score + o tempo de olho fechado)
            tvStatus.setText("⚠️ FADIGA DETETADA!\n" + msgScore + "\nTempo: " + duration + "ms");
            tvStatus.setBackgroundColor(getColor(android.R.color.holo_red_dark));

            ivDebugCrop.setBackgroundColor(android.graphics.Color.RED);
            triggerFatigueAlert();
        } else {
            // ESTADO: ATENTO (Mostra a mensagem + o score)
            tvStatus.setText("✅ MOTORISTA ATENTO\n" + msgScore);
            tvStatus.setBackgroundColor(getColor(android.R.color.holo_green_dark));

            // Feedback visual no quadrado de debug
            if (result > (confidenceThreshold * 0.7)) {
                ivDebugCrop.setBackgroundColor(android.graphics.Color.YELLOW);
            } else {
                ivDebugCrop.setBackgroundColor(android.graphics.Color.GREEN);
            }

            stopAlarm();
        }
    }

    private Bitmap centerCrop(Bitmap srcBmp) {
        int width = srcBmp.getWidth();
        int height = srcBmp.getHeight();
        int newEdge = Math.min(width, height); // Cria um quadrado

        // Recorta o centro da imagem (onde o rosto do motorista deve estar)
        return Bitmap.createBitmap(
                srcBmp,
                (width - newEdge) / 2,
                (height - newEdge) / 2,
                newEdge,
                newEdge
        );
    }

    private void startAlarm() {
        if (!isAlarmPlaying) {
            mediaPlayer.start();
            isAlarmPlaying = true;
        }
    }

    private void stopAlarm() {
        if (isAlarmPlaying) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0); // Volta ao início para a próxima vez
            isAlarmPlaying = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        if (classifier != null) {
            classifier.close(); // Fecha o interpretador e a GPU
        }
        cameraExecutor.shutdown(); // Para a thread da câmera
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}