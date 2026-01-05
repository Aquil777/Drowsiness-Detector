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
        long startTime = System.currentTimeMillis(); // Início

        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        ByteBuffer buffer = convertBitmapToByteBuffer(resized);

        float[][] output = new float[1][1];
        interpreter.run(buffer, output);

        long endTime = System.currentTimeMillis(); // Fim
        long inferenceTime = endTime - startTime;

        Log.d(TAG, "Inferência: " + output[0][0] + " | Tempo: " + inferenceTime + "ms");
        return output[0][0];
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            // Extrai os canais
            float r = ((pixel >> 16) & 0xFF);
            float g = ((pixel >> 8) & 0xFF);
            float b = (pixel & 0xFF);

            // MELHORIA DE LUMINOSIDADE: Normalização Simples (0-1)
            // Se a imagem estiver muito escura, você poderia aplicar um ganho aqui
            byteBuffer.putFloat(r / 255.0f);
            byteBuffer.putFloat(g / 255.0f);
            byteBuffer.putFloat(b / 255.0f);
        }
        return byteBuffer;
    }

    public void close() {
        if (interpreter != null) interpreter.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }
}
