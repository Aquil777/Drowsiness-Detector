package com.example.drowsinessdetector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * View customizada que desenha o overlay biométrico:
 */
public class BiometricOverlayView extends View {

    public enum State { IDLE, SCANNING, CLOSED, PROCESSING, SUCCESS, ERROR }

    private State state = State.IDLE;

    private final Paint ovalPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ovalRect     = new RectF();
    private final Path  clipPath     = new Path();

    private float scanY       = 0f;
    private float pulseAlpha  = 0.6f;
    private float pulseDir    = 0.02f;
    private int   dotAngle    = 0;

    // cores
    private static final int COLOR_BLUE    = 0xFF2A7AE4;
    private static final int COLOR_GREEN   = 0xFF2ECC71;
    private static final int COLOR_RED     = 0xFFE53935;
    private static final int COLOR_AMBER   = 0xFFC8AB5A;
    private static final int COLOR_DIM     = 0xFF1E2230;

    private final Handler animHandler = new Handler(Looper.getMainLooper());
    private final Runnable animRunnable = new Runnable() {
        @Override public void run() {
            // Pulse
            pulseAlpha += pulseDir;
            if (pulseAlpha > 0.9f) { pulseAlpha = 0.9f; pulseDir = -0.02f; }
            if (pulseAlpha < 0.3f) { pulseAlpha = 0.3f; pulseDir =  0.02f; }
            // Scan line
            scanY += getHeight() * 0.012f;
            if (scanY > ovalRect.bottom) scanY = ovalRect.top;
            // Spinner
            dotAngle = (dotAngle + 6) % 360;
            invalidate();
            animHandler.postDelayed(this, 16); // ~60 fps
        }
    };

    public BiometricOverlayView(Context context) {
        super(context);
        init();
    }

    public BiometricOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ovalPaint.setStyle(Paint.Style.STROKE);
        ovalPaint.setStrokeWidth(3f);

        scanPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(0xAACCCCCC);
        float textSizePx = 28f; // ~11sp approx
        labelPaint.setTextSize(textSizePx);
    }

    public void setState(State newState) {
        this.state = newState;
        animHandler.removeCallbacks(animRunnable);
        if (newState == State.SCANNING || newState == State.PROCESSING ||
                newState == State.IDLE) {
            animHandler.post(animRunnable);
        } else {
            invalidate();
        }
    }

    public void stopAnimation() {
        animHandler.removeCallbacks(animRunnable);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // Oval centrado — proporção 3:4 (rosto)
        float cx = w / 2f;
        float cy = h / 2f;
        float rx = w * 0.38f;
        float ry = h * 0.46f;
        ovalRect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        scanY = ovalRect.top;
        clipPath.reset();
        clipPath.addOval(ovalRect, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (ovalRect.isEmpty()) return;

        int ovalColor;
        String label;

        switch (state) {
            case SCANNING:
                ovalColor = COLOR_BLUE;
                label = "SCAN ACTIVO";
                drawScanEffect(canvas, ovalColor);
                break;
            case CLOSED:
                ovalColor = COLOR_RED;
                label = "OLHOS FECHADOS";
                break;
            case PROCESSING:
                ovalColor = COLOR_AMBER;
                label = "A PROCESSAR";
                drawSpinner(canvas);
                break;
            case SUCCESS:
                ovalColor = COLOR_GREEN;
                label = "CALIBRADO";
                drawCheck(canvas);
                break;
            case ERROR:
                ovalColor = COLOR_RED;
                label = "ERRO";
                break;
            default: // IDLE
                ovalColor = COLOR_DIM;
                label = "POSICIONA O ROSTO";
                drawIdleDots(canvas);
                break;
        }

        // Oval principal
        ovalPaint.setColor(ovalColor);
        ovalPaint.setAlpha((int)(pulseAlpha * 255));
        canvas.drawOval(ovalRect, ovalPaint);

        // Quatro cantos do oval
        drawCornerAccents(canvas, ovalColor);

        // Label em baixo do oval
        labelPaint.setColor(ovalColor);
        labelPaint.setAlpha(180);
        canvas.drawText(label,
                ovalRect.centerX(),
                ovalRect.bottom + labelPaint.getTextSize() * 1.6f,
                labelPaint);
    }

    private void drawScanEffect(Canvas canvas, int color) {
        // Scan line dentro do oval
        canvas.save();
        canvas.clipPath(clipPath);
        scanPaint.setColor(color);
        scanPaint.setAlpha(60);
        // Gradiente simulado: linha + faixa acima
        canvas.drawRect(ovalRect.left, scanY - getHeight() * 0.04f,
                ovalRect.right, scanY, scanPaint);
        scanPaint.setAlpha(120);
        canvas.drawRect(ovalRect.left, scanY,
                ovalRect.right, scanY + 3f, scanPaint);
        canvas.restore();
    }

    private void drawIdleDots(Canvas canvas) {
        // Pequenos pontos nos cantos do oval
        dotPaint.setColor(COLOR_DIM);
        dotPaint.setAlpha(160);
        float r = 5f;
        canvas.drawCircle(ovalRect.left  + 20, ovalRect.top    + 20, r, dotPaint);
        canvas.drawCircle(ovalRect.right - 20, ovalRect.top    + 20, r, dotPaint);
        canvas.drawCircle(ovalRect.left  + 20, ovalRect.bottom - 20, r, dotPaint);
        canvas.drawCircle(ovalRect.right - 20, ovalRect.bottom - 20, r, dotPaint);
    }

    private void drawSpinner(Canvas canvas) {
        float cx = ovalRect.centerX();
        float cy = ovalRect.centerY();
        int   n  = 8;
        float r  = Math.min(ovalRect.width(), ovalRect.height()) * 0.25f;
        for (int i = 0; i < n; i++) {
            double angle = Math.toRadians(dotAngle + i * (360.0 / n));
            float  x     = cx + (float)(r * Math.cos(angle));
            float  y     = cy + (float)(r * Math.sin(angle));
            int    alpha = 60 + (int)(195.0 * i / n);
            dotPaint.setColor(COLOR_AMBER);
            dotPaint.setAlpha(alpha);
            canvas.drawCircle(x, y, 6f, dotPaint);
        }
    }

    private void drawCheck(Canvas canvas) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(COLOR_GREEN);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float cx = ovalRect.centerX();
        float cy = ovalRect.centerY();
        float s  = Math.min(ovalRect.width(), ovalRect.height()) * 0.18f;
        Path check = new Path();
        check.moveTo(cx - s, cy);
        check.lineTo(cx - s * 0.2f, cy + s * 0.8f);
        check.lineTo(cx + s, cy - s * 0.6f);
        canvas.drawPath(check, p);
    }

    private void drawCornerAccents(Canvas canvas, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setAlpha(220);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f);
        p.setStrokeCap(Paint.Cap.ROUND);
        float len = 28f;
        float pad = 2f;
        float l = ovalRect.left  + pad;
        float r = ovalRect.right - pad;
        float t = ovalRect.top   + pad;
        float b = ovalRect.bottom - pad;
        // top-left
        canvas.drawLine(l, t + len, l, t, p);
        canvas.drawLine(l, t, l + len, t, p);
        // top-right
        canvas.drawLine(r - len, t, r, t, p);
        canvas.drawLine(r, t, r, t + len, p);
        // bottom-left
        canvas.drawLine(l, b - len, l, b, p);
        canvas.drawLine(l, b, l + len, b, p);
        // bottom-right
        canvas.drawLine(r - len, b, r, b, p);
        canvas.drawLine(r, b, r, b - len, p);
    }
}