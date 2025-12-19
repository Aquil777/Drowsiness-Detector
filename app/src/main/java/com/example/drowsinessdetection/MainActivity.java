package com.example.drowsinessdetection;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private FaceMeshProcessor faceMeshProcessor;
    private MediaPlayer mediaPlayer;
    private ExecutorService cameraExecutor;
    private YuvToRgbConverter yuvConverter;

    private DrowsinessDetector drowsinessDetector;

    @androidx.camera.core.ExperimentalGetImage
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);

        drowsinessDetector = new DrowsinessDetector(this);

        OverlayView overlayView = findViewById(R.id.overlayView);
        overlayView.setBaselines(drowsinessDetector.getBaselineEAR(), drowsinessDetector.getBaselineMAR());

        yuvConverter = new YuvToRgbConverter(this);

        // Inicializa FaceMeshProcessor
        faceMeshProcessor = new FaceMeshProcessor(this);
        faceMeshProcessor.setLandmarksListener(new FaceMeshProcessor.LandmarksListener() {
            private boolean calibrationFinishedShown = false; // <<< declarada aqui

            @Override
            public void onLandmarks(NormalizedLandmarkList landmarks) {
                runOnUiThread(() -> overlayView.setLandmarks(landmarks));
                if (!drowsinessDetector.isCalibrated()) {
                    drowsinessDetector.calibrate(landmarks);

                    runOnUiThread(() -> Toast.makeText(
                            MainActivity.this,
                            "Calibrando... Mantenha o rosto visível.",
                            Toast.LENGTH_SHORT
                    ).show());

                    if (drowsinessDetector.isCalibrated() && !calibrationFinishedShown) {
                        calibrationFinishedShown = true;
                        runOnUiThread(() -> Toast.makeText(
                                MainActivity.this,
                                "Calibração concluída! Detector pronto.",
                                Toast.LENGTH_SHORT
                        ).show());
                    }
                    return; // sai do listener até a calibração acabar
                }

                // Código de verificação de sonolência
                boolean drowsy = drowsinessDetector.isDrowsy(landmarks);
                if (drowsy) {
                    if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.start();
                } else {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        mediaPlayer.seekTo(0);
                    }
                }
            }
        });


        mediaPlayer = MediaPlayer.create(this, R.raw.alarm);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Permissão da câmera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 101);
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {

                    Bitmap bitmap = Bitmap.createBitmap(
                            image.getWidth(),
                            image.getHeight(),
                            Bitmap.Config.ARGB_8888
                    );

                    yuvConverter.yuvToRgb(image, bitmap);
                    long timestamp = System.currentTimeMillis();
                    faceMeshProcessor.process(bitmap, timestamp);
                    image.close();
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        faceMeshProcessor.close();

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        cameraExecutor.shutdown();
    }

    @androidx.camera.core.ExperimentalGetImage
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == 101 &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }
}
