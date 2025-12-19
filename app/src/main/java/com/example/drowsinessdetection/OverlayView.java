package com.example.drowsinessdetection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;

public class OverlayView extends View {

    private NormalizedLandmarkList landmarks;
    private Paint paintNormal;
    private Paint paintAlert;

    // Thresholds (mesmos do detector)
    private static final float EAR_THRESHOLD_RATIO = 0.7f;
    private static final float MAR_THRESHOLD_RATIO = 1.5f;
    private float baselineEAR = 0.4f; // valor default, pode atualizar pelo detector
    private float baselineMAR = 0.006f;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paintNormal = new Paint();
        paintNormal.setColor(Color.GREEN);
        paintNormal.setStyle(Paint.Style.STROKE);
        paintNormal.setStrokeWidth(5f);

        paintAlert = new Paint();
        paintAlert.setColor(Color.RED);
        paintAlert.setStyle(Paint.Style.STROKE);
        paintAlert.setStrokeWidth(5f);
    }

    public void setLandmarks(NormalizedLandmarkList landmarks) {
        this.landmarks = landmarks;
        invalidate();
    }

    public void setBaselines(float ear, float mar) {
        this.baselineEAR = ear;
        this.baselineMAR = mar;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (landmarks == null) return;

        Paint paint = paintNormal;

        // Calcula EAR e MAR atuais para colorir
        float ear = calculateEAR();
        float mar = calculateMAR();

        if (ear < baselineEAR * EAR_THRESHOLD_RATIO || mar > baselineMAR * MAR_THRESHOLD_RATIO) {
            paint = paintAlert;
        }

        // Desenha olhos
        drawEye(canvas, new int[]{33, 160, 158, 133, 153, 144}, paint); // direito
        drawEye(canvas, new int[]{362, 385, 387, 263, 373, 380}, paint); // esquerdo

        // Desenha boca
        drawMouth(canvas, new int[]{61, 291, 13, 14}, paint);
    }

    private void drawEye(Canvas canvas, int[] indices, Paint paint) {
        for (int i = 0; i < indices.length; i++) {
            int start = indices[i];
            int end = indices[(i + 1) % indices.length];
            float startX = landmarks.getLandmark(start).getX() * getWidth();
            float startY = landmarks.getLandmark(start).getY() * getHeight();
            float endX = landmarks.getLandmark(end).getX() * getWidth();
            float endY = landmarks.getLandmark(end).getY() * getHeight();
            canvas.drawLine(startX, startY, endX, endY, paint);
        }
    }

    private void drawMouth(Canvas canvas, int[] indices, Paint paint) {
        for (int i = 0; i < indices.length; i++) {
            int start = indices[i];
            int end = indices[(i + 1) % indices.length];
            float startX = landmarks.getLandmark(start).getX() * getWidth();
            float startY = landmarks.getLandmark(start).getY() * getHeight();
            float endX = landmarks.getLandmark(end).getX() * getWidth();
            float endY = landmarks.getLandmark(end).getY() * getHeight();
            canvas.drawLine(startX, startY, endX, endY, paint);
        }
    }

    private float calculateEAR() {
        int[] rightEye = {33, 160, 158, 133, 153, 144};
        int[] leftEye = {362, 385, 387, 263, 373, 380};
        return (computeEAR(rightEye) + computeEAR(leftEye)) / 2f;
    }

    private float computeEAR(int[] idx) {
        float v1 = distance(idx[1], idx[4]);
        float v2 = distance(idx[2], idx[5]);
        float h = distance(idx[0], idx[3]);
        return (v1 + v2) / (2f * h);
    }

    private float calculateMAR() {
        int top = 13;
        int bottom = 14;
        int left = 61;
        int right = 291;
        float v = distance(top, bottom);
        float h = distance(left, right);
        return v / h;
    }

    private float distance(int aIdx, int bIdx) {
        float dx = landmarks.getLandmark(aIdx).getX() - landmarks.getLandmark(bIdx).getX();
        float dy = landmarks.getLandmark(aIdx).getY() - landmarks.getLandmark(bIdx).getY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
