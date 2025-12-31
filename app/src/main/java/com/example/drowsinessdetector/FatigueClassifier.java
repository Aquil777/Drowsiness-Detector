package com.example.drowsinessdetector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class FatigueClassifier {

    private static final String TAG = "TFLITE";
    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;

    private static final int INPUT_SIZE = 168;

    public FatigueClassifier(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        boolean delegateAdded = false;

        // 1️⃣ NNAPI (mais estável)
        try {
            options.setUseNNAPI(true);
            delegateAdded = true;
            Log.d(TAG, "NNAPI ativo");
        } catch (Exception ignored) {}

        // 2️⃣ GPU (fallback)
        if (!delegateAdded) {
            try {
                gpuDelegate = new GpuDelegate();
                options.addDelegate(gpuDelegate);
                delegateAdded = true;
                Log.d(TAG, "GPU Delegate ativo");
            } catch (Exception ignored) {}
        }

        // 3️⃣ CPU (sempre funciona)
        if (!delegateAdded) {
            Log.d(TAG, "CPU ativa");
        }

        interpreter = new Interpreter(loadModelFile(context), options);
    }

    private ByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd("modelo_fadiga.tflite");
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    public float analyzeImage(Bitmap bitmap) {
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer buffer = convertBitmapToByteBuffer(resized);

        float[][] output = new float[1][1];
        interpreter.run(buffer, output);

        Log.d(TAG, "Resultado da Inferência: " + output[0][0]);
        return output[0][0];
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            byteBuffer.putFloat(((pixel >> 16) & 0xFF) / 255f);
            byteBuffer.putFloat(((pixel >> 8) & 0xFF) / 255f);
            byteBuffer.putFloat((pixel & 0xFF) / 255f);
        }

        return byteBuffer;
    }

    public void close() {
        if (interpreter != null) interpreter.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }
}
