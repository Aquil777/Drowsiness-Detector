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
import java.util.concurrent.atomic.AtomicReference;

/**
 * FatigueClassifier — v6
 *
 * Pipeline de inferência idêntico ao treino:
 *   1. ML Kit detecta o rosto e devolve a Bounding Box.
 *   2. Crop dinâmico com margem de 15 % (elimina center crop fixo).
 *   3. Grayscale → CLAHE manual → imagem sintética RGB (3× cinza).
 *   4. Resize 224×224 + normalização [-1, 1].
 *   5. TFLite (modelo_fadiga_v6_int8.tflite) devolve score [0, 1].
 *
 * Parâmetros ajustáveis em tempo real (sem reiniciar):
 *   - claheClipLimit  (1.0 – 3.0, padrão 2.0)
 */
public class FatigueClassifier {

    private static final String TAG       = "FatigueClassifier";
    private static final String MODEL_FILE = "modelo_fadiga.tflite";

    /** Tamanho da grelha do CLAHE (mesmo valor do treino em Python). */
    private static final int CLAHE_GRID = 8;

    public static final float NO_FACE = -1f;

    // TFLite
    private final Interpreter  interpreter;
    private final ByteBuffer   imgData;
    private final int[]        intValues;

    // ML Kit
    private final FaceDetector faceDetector;

    // Delegates (fechados em close())
    private GpuDelegate   gpuDelegate;
    private NnApiDelegate nnApiDelegate;

    // Estado interno
    private float lastLuminance  = 128f;
    private float claheClipLimit = AppConstants.DEFAULT_CLAHE_CLIP;

    /**
     * A última imagem processada pelo CLAHE (grayscale → RGB sintético).
     * Actualizada em cada chamada a analyzeImage(); usada pelo ivDebugCrop.
     * Pode ser null se ainda não houve inferência.
     */
    private volatile Bitmap lastDebugBitmap = null;

    // ─────────────────────────────────────────────────────────────────────────
    // Construtor
    // ─────────────────────────────────────────────────────────────────────────

    public FatigueClassifier(Context context) throws IOException {
        // ── TFLite ──────────────────────────────────────────────────────────
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

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
                Log.d(TAG, "GPU Delegate ativo");
            } catch (Exception e) {
                Log.d(TAG, "GPU indisponível, a usar CPU");
            }
        }

        interpreter = new Interpreter(loadModelFile(context), options);

        final int sz = AppConstants.MODEL_INPUT_SIZE;
        imgData   = ByteBuffer.allocateDirect(4 * sz * sz * 3);
        imgData.order(ByteOrder.nativeOrder());
        intValues = new int[sz * sz];

        // ── ML Kit ──────────────────────────────────────────────────────────
        FaceDetectorOptions faceOptions = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.10f)  // detecta rostos mais pequenos (camera à distância)
                .build();
        faceDetector = FaceDetection.getClient(faceOptions);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Atualiza o clipLimit do CLAHE em tempo real.
     * Thread-safe: pode ser chamado da UI thread enquanto a inferência corre.
     */
    public synchronized void setClaheClipLimit(float clip) {
        this.claheClipLimit = Math.max(AppConstants.MIN_CLAHE_CLIP,
                Math.min(AppConstants.MAX_CLAHE_CLIP, clip));
    }

    public synchronized float getClaheClipLimit() {
        return claheClipLimit;
    }

    // Após "private volatile Bitmap lastDebugBitmap = null;"
    private volatile Rect lastFaceRect = null;

    // Após getLastDebugBitmap():
    public Rect getLastFaceRect() {
        return lastFaceRect;
    }

    public float getLastLuminance() {
        return lastLuminance;
    }

    /**
     * Devolve a última imagem que foi enviada para o modelo (pós-CLAHE).
     * Pode ser null antes da primeira inferência.
     */
    public Bitmap getLastDebugBitmap() {
        return lastDebugBitmap;
    }

    /**
     * Análise de um frame completo da câmara.
     *
     * Deve ser chamado numa background thread.
     * Devolve {@link #NO_FACE} se não for detetado rosto.
     * Devolve um score [0.0, 1.0] — quanto mais alto, mais fadiga.
     */
    public float analyzeImage(Bitmap fullFrame) {

        // ── 1. Detetar rosto ─────────────────────────────────────────────────
        final AtomicReference<Rect> faceRect = new AtomicReference<>(null);
        lastFaceRect = null; // reset antes de cada frame
        final CountDownLatch latch = new CountDownLatch(1);

        InputImage inputImage = InputImage.fromBitmap(fullFrame, 0);
        faceDetector.process(inputImage)
                .addOnSuccessListener((List<Face> faces) -> {
                    if (!faces.isEmpty()) {
                        // Rosto com maior bounding box (mais próximo da câmara)
                        Face best = faces.get(0);
                        for (Face f : faces) {
                            if (rectArea(f.getBoundingBox()) > rectArea(best.getBoundingBox())) {
                                best = f;
                            }
                        }
                        faceRect.set(best.getBoundingBox());
                    }
                    latch.countDown();
                })
                .addOnFailureListener(e -> latch.countDown());

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return NO_FACE;
        }

        lastFaceRect = faceRect.get();
        if (lastFaceRect == null) {
            lastDebugBitmap = null;
            return NO_FACE;
        }

        // ── 2. Crop dinâmico com margem ──────────────────────────────────────
        Bitmap faceCrop = cropWithMargin(fullFrame, faceRect.get(),
                AppConstants.FACE_CROP_MARGIN);

        // ── 3. Pipeline Grayscale → CLAHE → RGB sintético ────────────────────
        final int sz = AppConstants.MODEL_INPUT_SIZE;
        Bitmap resized = Bitmap.createScaledBitmap(faceCrop, sz, sz, true);

        int[] grayPixels = toGrayscalePixels(resized, sz);
        lastLuminance = computeMeanLuminance(grayPixels);

        float currentClip;
        synchronized (this) { currentClip = claheClipLimit; }

        int[] clahePixels = applyClahe(grayPixels, sz, sz, CLAHE_GRID, currentClip);

        // Guardar bitmap de debug (o que a IA vê)
        lastDebugBitmap = grayscalePixelsToBitmap(clahePixels, sz);

        // ── 4. Normalização e inferência ─────────────────────────────────────
        fillInputBuffer(clahePixels);

        float[][] output = new float[1][1];
        try {
            interpreter.run(imgData, output);
        } catch (Exception e) {
            Log.e(TAG, "TFLite inference error: " + e.getMessage(), e);
            return NO_FACE;
        }
        return output[0][0];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline de imagem
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recorta a bounding box do rosto do frame completo, adicionando uma
     * margem percentual em todos os lados para não cortar o rosto.
     */
    private Bitmap cropWithMargin(Bitmap src, Rect box, float margin) {
        int w = src.getWidth();
        int h = src.getHeight();

        int bw = box.width();
        int bh = box.height();
        int mx = (int)(bw * margin);
        int my = (int)(bh * margin);

        int x1 = Math.max(0, box.left   - mx);
        int y1 = Math.max(0, box.top    - my);
        int x2 = Math.min(w, box.right  + mx);
        int y2 = Math.min(h, box.bottom + my);

        return Bitmap.createBitmap(src, x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Converte bitmap para array de luminâncias [0, 255] usando BT.601.
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
     * CLAHE manual (Contrast Limited Adaptive Histogram Equalization).
     *
     * Divide a imagem em tiles de (gridSize × gridSize) células.
     * Em cada célula: limita o histograma ao clipLimit × área_célula /256,
     * redistribui o excesso uniformemente e equaliza.
     *
     * @param pixels     array de luminâncias [0, 255]
     * @param width      largura da imagem
     * @param height     altura da imagem
     * @param gridSize   número de tiles por eixo (ex: 8 → grelha 8×8)
     * @param clipLimit  fator de limitação (ex: 2.0)
     * @return array equalizado [0, 255]
     */
    private int[] applyClahe(int[] pixels, int width, int height,
                             int gridSize, float clipLimit) {

        int tileW = width  / gridSize;
        int tileH = height / gridSize;
        int tileArea = tileW * tileH;

        // clipCount = clipLimit * (tileArea / 256)
        int clipCount = Math.max(1, (int)(clipLimit * tileArea / 256f));

        // 1. Calcula o LUT (lookup table) de cada tile
        int[][][] luts = new int[gridSize][gridSize][256];

        for (int ty = 0; ty < gridSize; ty++) {
            for (int tx = 0; tx < gridSize; tx++) {

                int[] hist = new int[256];
                int startX = tx * tileW;
                int startY = ty * tileH;
                int endX   = (tx == gridSize - 1) ? width  : startX + tileW;
                int endY   = (ty == gridSize - 1) ? height : startY + tileH;
                int count  = 0;

                for (int y = startY; y < endY; y++) {
                    for (int x = startX; x < endX; x++) {
                        hist[pixels[y * width + x]]++;
                        count++;
                    }
                }

                // Clip e redistribuição
                int excess = 0;
                for (int i = 0; i < 256; i++) {
                    if (hist[i] > clipCount) {
                        excess += hist[i] - clipCount;
                        hist[i] = clipCount;
                    }
                }
                int redistPerBin = excess / 256;
                int residual     = excess - redistPerBin * 256;
                for (int i = 0; i < 256; i++) {
                    hist[i] += redistPerBin;
                    if (i < residual) hist[i]++;
                }

                // CDF → LUT
                int cdf = 0;
                int cdfMin = -1;
                for (int i = 0; i < 256; i++) {
                    cdf += hist[i];
                    if (cdfMin < 0 && cdf > 0) cdfMin = cdf;
                    luts[ty][tx][i] = (count > cdfMin)
                            ? Math.min(255, (int)((float)(cdf - cdfMin) / (count - cdfMin) * 255f))
                            : 0;
                }
            }
        }

        // 2. Interpolação bilinear entre tiles vizinhos
        int[] output = new int[pixels.length];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                // Coordenadas no espaço dos tiles
                float fy = ((float) y / tileH) - 0.5f;
                float fx = ((float) x / tileW) - 0.5f;

                int ty0 = Math.max(0, (int) fy);
                int tx0 = Math.max(0, (int) fx);
                int ty1 = Math.min(gridSize - 1, ty0 + 1);
                int tx1 = Math.min(gridSize - 1, tx0 + 1);

                float wy = fy - ty0;
                float wx = fx - tx0;
                wy = Math.max(0f, Math.min(1f, wy));
                wx = Math.max(0f, Math.min(1f, wx));

                int v = pixels[y * width + x];

                float tl = luts[ty0][tx0][v];
                float tr = luts[ty0][tx1][v];
                float bl = luts[ty1][tx0][v];
                float br = luts[ty1][tx1][v];

                float interp = (1 - wy) * ((1 - wx) * tl + wx * tr)
                        + wy  * ((1 - wx) * bl + wx * br);

                output[y * width + x] = Math.min(255, Math.max(0, (int) interp));
            }
        }

        return output;
    }

    /**
     * Preenche o ByteBuffer para o TFLite.
     * Canal sintético RGB = (gray, gray, gray), normalizado para [-1, 1].
     */
    private void fillInputBuffer(int[] grayPixels) {
        imgData.rewind();
        for (int v : grayPixels) {
            float norm = (v / 127.5f) - 1.0f;
            imgData.putFloat(norm); // R
            imgData.putFloat(norm); // G
            imgData.putFloat(norm); // B
        }
    }

    /**
     * Constrói um Bitmap ARGB_8888 a partir de pixels de luminância [0, 255].
     * Usado exclusivamente para o debug visual (ivDebugCrop).
     */
    private Bitmap grayscalePixelsToBitmap(int[] grayPixels, int sz) {
        int[] argb = new int[grayPixels.length];
        for (int i = 0; i < grayPixels.length; i++) {
            int v = grayPixels[i];
            argb[i] = Color.rgb(v, v, v);
        }
        Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, sz, 0, 0, sz, sz);
        return bmp;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auxiliares
    // ─────────────────────────────────────────────────────────────────────────

    private int rectArea(Rect r) {
        return r.width() * r.height();
    }

    private ByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
        FileInputStream fis    = new FileInputStream(fd.getFileDescriptor());
        return fis.getChannel().map(
                FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    public void close() {
        if (interpreter   != null) interpreter.close();
        if (faceDetector  != null) faceDetector.close();
        if (gpuDelegate   != null) gpuDelegate.close();
        if (nnApiDelegate != null) nnApiDelegate.close();
    }
}