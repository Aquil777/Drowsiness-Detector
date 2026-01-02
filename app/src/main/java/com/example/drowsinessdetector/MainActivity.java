package com.example.drowsinessdetector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
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
    private float sensitivityThreshold = 0.60f; // Variável dinâmica
    private ImageView ivDebugCrop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inicializa o som (alarm.wav está em res/raw)
        mediaPlayer = MediaPlayer.create(this, R.raw.alarm);
        mediaPlayer.setLooping(true); // Toca sem parar se a fadiga continuar

        viewFinder = findViewById(R.id.viewFinder);
        tvStatus = findViewById(R.id.tvStatus);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        try {
            classifier = new FatigueClassifier(this);
        } catch (Exception e) { e.printStackTrace(); }

        cameraExecutor = Executors.newSingleThreadExecutor();

        SeekBar sbSensitivity = findViewById(R.id.sbSensitivity);
        TextView tvSensitivityLabel = findViewById(R.id.tvSensitivityLabel);

        sbSensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sensitivityThreshold = progress / 100f;

                String label = "PADRÃO";
                if (progress < 40) label = "RELAXADO (Menos alarmes)";
                else if (progress > 75) label = "RIGOROSO (Uso noturno)";

                tvSensitivityLabel.setText("Ajuste de Rigor: " + label);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Dentro do onCreate
        ivDebugCrop = findViewById(R.id.ivDebugCrop);

        ivDebugCrop.setOnTouchListener(new android.view.View.OnTouchListener() {
            float dX, dY;

            @Override
            public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        // Guarda a distância entre o toque e a borda da imagem
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        break;

                    case android.view.MotionEvent.ACTION_MOVE:
                        // Move a imagem mantendo a posição relativa ao dedo
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

    private void updateUI(float result) {
        ProgressBar pbLiveScore = findViewById(R.id.pbLiveScore);
        pbLiveScore.setProgress((int)(result * 100));

        // Lógica de sensibilidade
        if (result > sensitivityThreshold) {
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
            startAlarm();
        } else {
            // ESTADO: ATENTO (Mostra a mensagem + o score)
            tvStatus.setText("✅ MOTORISTA ATENTO\n" + msgScore);
            tvStatus.setBackgroundColor(getColor(android.R.color.holo_green_dark));

            // Feedback visual no quadrado de debug
            if (result > (sensitivityThreshold * 0.7)) {
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