package com.example.drowsinessdetection;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;

/**
 * Overlay vazio.
 * Mantido apenas para:
 *  - receber landmarks
 *  - permitir futuras extensões (texto, debug, gráficos)
 *
 * Atualmente NÃO desenha nada.
 */
public class OverlayView extends View {

    private NormalizedLandmarkList landmarks;

    private float baselineEAR = -1f;
    private float baselineMAR = -1f;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // Recebe landmarks (não desenha)
    public void setLandmarks(NormalizedLandmarkList landmarks) {
        this.landmarks = landmarks;
        invalidate();
    }

    // Recebe baselines (guardados apenas)
    public void setBaselines(float ear, float mar) {
        this.baselineEAR = ear;
        this.baselineMAR = mar;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // ❌ Nada é desenhado aqui de propósito
    }
}
