package com.example.drowsinessdetector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class FatigueClassifier {

    private static final String TAG        = "FatigueClassifier";
    private static final int    INPUT_SIZE = 168;
    private static final int    CHANNELS   = 3;
    private static final float  MAX_GAIN   = 3.0f;

    public static final float NO_FACE = -1f;

    private final Interpreter  interpreter;
    private final FaceDetector faceDetector;
    private final ByteBuffer   imgData;
    private final int[]        intValues;
    private GpuDelegate        gpuDelegate;
    private NnApiDelegate      nnApiDelegate;

    private float lastLuminance = 128f;

    public FatigueClassifier(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        // Tenta NNAPI, depois GPU, depois CPU
        boolean delegateAdded = false;
        try {
            nnApiDelegate = new NnApiDelegate();
            options.addDelegate(nnApiDelegate);
            delegateAdded = true;
            Log.d(TAG, "NNAPI ativo");
        } catch (Exception e) {
            Log.d(TAG, "NNAPI indisponível, a tentar GPU");
        }

        if (!delegateAdded) {
            try {
                gpuDelegate = new GpuDelegate();
                options.addDelegate(gpuDelegate);
                delegateAdded = true;
                Log.d(TAG, "GPU Delegate ativo");
            } catch (Exception e) {
                Log.d(TAG, "GPU indisponível, a usar CPU");
            }
        }

        if (!delegateAdded) {
            Log.d(TAG, "CPU ativa");
        }

        interpreter = new Interpreter(loadModelFile(context), options);
        imgData     = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * CHANNELS);
        imgData.order(ByteOrder.nativeOrder());
        intValues   = new int[INPUT_SIZE * INPUT_SIZE];

        // ML Kit — FAST_MODE para não atrasar a pipeline
        FaceDetectorOptions faceOptions = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build();
        faceDetector = FaceDetection.getClient(faceOptions);
    }

    private ByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd("modelo_fadiga.tflite");
        FileInputStream fis    = new FileInputStream(fd.getFileDescriptor());
        return fis.getChannel().map(
                FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    /**
     * Corre numa background thread (nunca chamar na main thread).
     * Devolve NO_FACE (-1f) se não houver rosto, ou score [0.0, 1.0].
     */
    public float analyzeImage(Bitmap bitmap) {
        // ML Kit é assíncrono — usamos CountDownLatch porque já estamos numa background thread
        AtomicBoolean faceFound = new AtomicBoolean(false);
        CountDownLatch latch    = new CountDownLatch(1);

        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    faceFound.set(!faces.isEmpty());
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "ML Kit erro: " + e.getMessage());
                    // Se falhar, assume que há rosto para não bloquear o sistema
                    faceFound.set(true);
                    latch.countDown();
                });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NO_FACE;
        }

        if (!faceFound.get()) {
            return NO_FACE;
        }

        // Rosto detetado — corre TFLite
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        convertBitmapToByteBuffer(resized);

        float[][] output = new float[1][1];
        long start = System.currentTimeMillis();
        interpreter.run(imgData, output);
        Log.d(TAG, String.format("Score: %.3f | %dms | Lum: %.1f",
                output[0][0], System.currentTimeMillis() - start, lastLuminance));

        return output[0][0];
    }

    private void convertBitmapToByteBuffer(Bitmap bitmap) {
        imgData.rewind();
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        float sum = 0f;
        for (int px : intValues) {
            float r = (px >> 16) & 0xFF;
            float g = (px >>  8) & 0xFF;
            float b =  px        & 0xFF;
            sum += (0.299f * r + 0.587f * g + 0.114f * b);
        }
        lastLuminance = sum / intValues.length;

        float gain = (lastLuminance < 100f)
                ? Math.min(128f / Math.max(lastLuminance, 1f), MAX_GAIN)
                : 1f;

        for (int px : intValues) {
            float r = Math.min(((px >> 16) & 0xFF) * gain, 255f);
            float g = Math.min(((px >>  8) & 0xFF) * gain, 255f);
            float b = Math.min(( px        & 0xFF) * gain, 255f);
            imgData.putFloat((r / 127.5f) - 1.0f);
            imgData.putFloat((g / 127.5f) - 1.0f);
            imgData.putFloat((b / 127.5f) - 1.0f);
        }
    }

    public float getLastLuminance() {
        return lastLuminance;
    }

    public void close() {
        if (interpreter  != null) interpreter.close();
        if (faceDetector != null) faceDetector.close();
        if (gpuDelegate  != null) gpuDelegate.close();
        if (nnApiDelegate != null) nnApiDelegate.close();
    }
}