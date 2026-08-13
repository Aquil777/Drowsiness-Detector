package com.example.drowsinessdetector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pipeline:
 *   1. ML Kit detecta o rosto.
 *   2. Frame completo → resize 224×224.
 *   3. Grayscale (BT.601) → CLAHE (clipLimit=2.0, grid 8×8) → RGB sintético.
 *   4. Normalização [-1, 1].
 */
public class FatigueClassifier {

    private static final String TAG        = "FatigueClassifier";
    private static final String MODEL_FILE = "modelo_fadiga.tflite";

    /** Grelha CLAHE — idêntica ao treino: tileGridSize=(8,8). */
    private static final int CLAHE_GRID = 8;

    public static final float NO_FACE = -1f;

    // TFLite
    private final Interpreter interpreter;
    private final ByteBuffer  imgData;

    // ML Kit
    private final FaceDetector faceDetector;

    // Executor para callbacks ML Kit — evita deadlock na cameraExecutor
    private final java.util.concurrent.ExecutorService mlKitExecutor =
            Executors.newSingleThreadExecutor();

    // Delegates
    private GpuDelegate   gpuDelegate;
    private NnApiDelegate nnApiDelegate;

    // Estado interno
    private float lastLuminance  = 128f;

    // Últimos resultados (thread-safe via volatile)
    private volatile Bitmap lastDebugBitmap = null;
    private volatile Rect   lastFaceRect    = null;

    // Construtor

    public FatigueClassifier(Context context) throws IOException {
        Interpreter interpreter_temp = null;

        // Tenta NNAPI
        try {
            nnApiDelegate = new NnApiDelegate();
            Interpreter.Options nnOpts = new Interpreter.Options();
            nnOpts.setNumThreads(4);
            nnOpts.addDelegate(nnApiDelegate);
            interpreter_temp = new Interpreter(loadModelFile(context), nnOpts);
            Log.d(TAG, "NNAPI ativo");
        } catch (Exception e) {
            Log.d(TAG, "NNAPI falhou: " + e.getMessage());
            if (nnApiDelegate != null) { nnApiDelegate.close(); nnApiDelegate = null; }
            interpreter_temp = null;
        }

        // Tenta GPU
        if (interpreter_temp == null) {
            try {
                gpuDelegate = new GpuDelegate();
                Interpreter.Options gpuOpts = new Interpreter.Options();
                gpuOpts.setNumThreads(4);
                gpuOpts.addDelegate(gpuDelegate);
                interpreter_temp = new Interpreter(loadModelFile(context), gpuOpts);
                Log.d(TAG, "GPU Delegate ativo");
            } catch (Exception e) {
                Log.d(TAG, "GPU falhou: " + e.getMessage());
                if (gpuDelegate != null) { gpuDelegate.close(); gpuDelegate = null; }
                interpreter_temp = null;
            }
        }

        // Fallback CPU
        if (interpreter_temp == null) {
            Interpreter.Options cpuOpts = new Interpreter.Options();
            cpuOpts.setNumThreads(4);
            interpreter_temp = new Interpreter(loadModelFile(context), cpuOpts);
            Log.d(TAG, "CPU puro ativo");
        }

        interpreter = interpreter_temp;

        // Buffer dimensionado para 224×224×3
        final int sz = AppConstants.MODEL_INPUT_SIZE;
        imgData = ByteBuffer.allocateDirect(4 * sz * sz * 3);
        imgData.order(ByteOrder.nativeOrder());

        // ML Kit
        FaceDetectorOptions faceOptions = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.10f)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build();
        faceDetector = FaceDetection.getClient(faceOptions);
    }

    public Rect   getLastFaceRect()    { return lastFaceRect;    }
    public float  getLastLuminance()   { return lastLuminance;   }
    public Bitmap getLastDebugBitmap() { return lastDebugBitmap; }

    /**
     * Análise de um frame completo da câmara.
     *
     * Pipeline idêntico ao treino:
     *   frame completo → resize 224×224 → grayscale → CLAHE → RGB → norm [-1,1]
     *
     * O ML Kit corre de forma assíncrona APENAS para atualizar o overlay de UI.
     * A inferência NÃO depende da deteção de rosto – devolve sempre um score.
     */
    public float analyzeImage(Bitmap fullFrame) {

        // ── Deteção de rosto
        InputImage inputImage = InputImage.fromBitmap(fullFrame, 0);
        faceDetector.process(inputImage)
                .addOnSuccessListener(mlKitExecutor, (List<Face> faces) -> {
                    if (!faces.isEmpty()) {
                        Face best = faces.get(0);
                        for (Face f : faces) {
                            if (rectArea(f.getBoundingBox()) > rectArea(best.getBoundingBox()))
                                best = f;
                        }
                        lastFaceRect = best.getBoundingBox();
                    } else {
                        lastFaceRect = null;
                    }
                })
                .addOnFailureListener(mlKitExecutor, e -> {
                    Log.e(TAG, "ML Kit falhou: " + e.getMessage());
                    lastFaceRect = null;
                });

        // Pipeline de inferência
        final int sz = AppConstants.MODEL_INPUT_SIZE; // 224
        Bitmap resized = Bitmap.createScaledBitmap(fullFrame, sz, sz, true);

        // Grayscale → CLAHE → RGB sintético
        int[] grayPixels = toGrayscalePixels(resized, sz);
        lastLuminance = computeMeanLuminance(grayPixels);

        int[] clahePixels = applyClahe(grayPixels, sz, sz, CLAHE_GRID, AppConstants.DEFAULT_CLAHE_CLIP);
        lastDebugBitmap = grayscalePixelsToBitmap(clahePixels, sz);

        // Normalização [-1, 1] e preenchimento do buffer
        fillInputBuffer(clahePixels);

        // Inferência
        float[][] output = new float[1][1];
        try {
            interpreter.run(imgData, output);
        } catch (Exception e) {
            Log.e(TAG, "TFLite erro: " + e.getMessage(), e);
            return NO_FACE; // só aqui devolve erro se o modelo falhar
        }
        return output[0][0];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline de imagem
    /**
     * Grayscale BT.601 — cv2.cvtColor(img, CV_RGB2GRAY).
     */
    private int[] toGrayscalePixels(Bitmap bmp, int sz) {
        int[] rgba   = new int[sz * sz];
        int[] result = new int[sz * sz];
        bmp.getPixels(rgba, 0, sz, 0, 0, sz, sz);
        for (int i = 0; i < rgba.length; i++) {
            int px = rgba[i];
            float r = (px >> 16) & 0xFF;
            float g = (px >>  8) & 0xFF;
            float b =  px        & 0xFF;
            result[i] = (int)(0.299f * r + 0.587f * g + 0.114f * b);
        }
        return result;
    }

    private float computeMeanLuminance(int[] pixels) {
        long sum = 0;
        for (int v : pixels) sum += v;
        return (float) sum / pixels.length;
    }

    /**
     * CLAHE manual — equivalente a cv2.createCLAHE(clipLimit, tileGridSize).apply().
     *
     * Idêntico ao treino: clipLimit=2.0, tileGridSize=(8,8).
     * A interpolação bilinear entre tiles elimina fronteiras visíveis.
     */
    private int[] applyClahe(int[] pixels, int width, int height,
                             int gridSize, float clipLimit) {

        int tileW    = width  / gridSize;
        int tileH    = height / gridSize;
        int tileArea = tileW  * tileH;
        int clipCount = Math.max(1, (int)(clipLimit * tileArea / 256f));

        int[][][] luts = new int[gridSize][gridSize][256];

        for (int ty = 0; ty < gridSize; ty++) {
            for (int tx = 0; tx < gridSize; tx++) {

                int[] hist  = new int[256];
                int startX  = tx * tileW;
                int startY  = ty * tileH;
                int endX    = (tx == gridSize - 1) ? width  : startX + tileW;
                int endY    = (ty == gridSize - 1) ? height : startY + tileH;
                int count   = 0;

                for (int y = startY; y < endY; y++)
                    for (int x = startX; x < endX; x++) {
                        hist[pixels[y * width + x]]++;
                        count++;
                    }

                // Clip e redistribuição
                int excess = 0;
                for (int i = 0; i < 256; i++) {
                    if (hist[i] > clipCount) { excess += hist[i] - clipCount; hist[i] = clipCount; }
                }
                int redistPerBin = excess / 256;
                int residual     = excess - redistPerBin * 256;
                for (int i = 0; i < 256; i++) {
                    hist[i] += redistPerBin;
                    if (i < residual) hist[i]++;
                }

                // CDF → LUT
                int cdf = 0, cdfMin = -1;
                for (int i = 0; i < 256; i++) {
                    cdf += hist[i];
                    if (cdfMin < 0 && cdf > 0) cdfMin = cdf;
                    luts[ty][tx][i] = (count > cdfMin)
                            ? Math.min(255, (int)((float)(cdf - cdfMin) / (count - cdfMin) * 255f))
                            : 0;
                }
            }
        }

        // Interpolação bilinear entre tiles vizinhos
        int[] output = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                float fy = ((float) y / tileH) - 0.5f;
                float fx = ((float) x / tileW) - 0.5f;

                int ty0 = Math.max(0,             (int) fy);
                int tx0 = Math.max(0,             (int) fx);
                int ty1 = Math.min(gridSize - 1,  ty0 + 1);
                int tx1 = Math.min(gridSize - 1,  tx0 + 1);

                float wy = Math.max(0f, Math.min(1f, fy - ty0));
                float wx = Math.max(0f, Math.min(1f, fx - tx0));

                int v  = pixels[y * width + x];
                float tl = luts[ty0][tx0][v];
                float tr = luts[ty0][tx1][v];
                float bl = luts[ty1][tx0][v];
                float br = luts[ty1][tx1][v];

                float interp = (1 - wy) * ((1 - wx) * tl + wx * tr)
                        +      wy  * ((1 - wx) * bl + wx * br);

                output[y * width + x] = Math.min(255, Math.max(0, (int) interp));
            }
        }
        return output;
    }

    /**
     * Normalização [-1, 1] — idêntica ao treino:
     *   (rgb.astype(np.float32) / 127.5) - 1.0
     * Canal sintético: R=G=B=gray (cv2.merge([eq, eq, eq]))
     */
    private void fillInputBuffer(int[] clahePixels) {
        imgData.rewind();
        for (int v : clahePixels) {
            float norm = (v / 127.5f) - 1.0f;
            imgData.putFloat(norm); // R
            imgData.putFloat(norm); // G
            imgData.putFloat(norm); // B
        }
    }

    private Bitmap grayscalePixelsToBitmap(int[] pixels, int sz) {
        int[] argb = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int v = pixels[i];
            argb[i] = Color.rgb(v, v, v);
        }
        Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, sz, 0, 0, sz, sz);
        return bmp;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auxiliares
    // ─────────────────────────────────────────────────────────────────────────

    private int rectArea(Rect r) { return r.width() * r.height(); }

    private ByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd  = context.getAssets().openFd(MODEL_FILE);
        FileInputStream     fis = new FileInputStream(fd.getFileDescriptor());
        return fis.getChannel().map(
                FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    public void close() {
        if (interpreter   != null) interpreter.close();
        if (faceDetector  != null) faceDetector.close();
        if (gpuDelegate   != null) gpuDelegate.close();
        if (nnApiDelegate != null) nnApiDelegate.close();
        mlKitExecutor.shutdown();
    }
}