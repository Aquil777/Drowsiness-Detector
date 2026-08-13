package com.example.drowsinessdetector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Ilustrações do onboarding desenhadas por código (Canvas).
 */
public class OnboardingIllustrations {

    public static View forStep(Context context, int step) {
        switch (step) {
            case 0:  return new Step1View(context);
            case 1:  return new Step2View(context);
            case 2:  return new Step3View(context);
            case 3:  return new Step4View(context);
            default: return new View(context);
        }
    }

    // cores partilhadas
    private static final int C_SURFACE  = 0xFF0D1118;
    private static final int C_AMBER    = 0xFFC8AB5A;
    private static final int C_BLUE     = 0xFF2A7AE4;
    private static final int C_GREEN    = 0xFF2ECC71;
    private static final int C_RED      = 0xFFE53935;
    private static final int C_BORDER   = 0xFF2A2E3D;
    private static final int C_MUTED    = 0xFF3A3F50;
    private static final int C_HINT     = 0xFF5A5F6E;
    private static final int C_DARK_RED = 0xFF3A0808;
    private static final int C_TRACK    = 0xFF1A1E2A;
    private static final int C_SCREEN   = 0xFF131A28;
    private static final int C_BG       = 0xFF080A0F;

    // PASSO 1 — Telemóvel no suporte, volante à direita
    static class Step1View extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Step1View(Context ctx) { super(ctx); }
        public Step1View(Context ctx, AttributeSet a) { super(ctx, a); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            // mantém proporção 160×140 do SVG
            int h = (int)(w * 140f / 160f);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            float sx = W / 160f, sy = H / 140f;

            // suporte (haste vertical)
            p.setColor(C_BORDER); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(32,28,37,103,sx,sy), 2*sx, 2*sy, p);
            // braço horizontal
            c.drawRoundRect(r(32,55,52,59,sx,sy), 2*sx, 2*sy, p);
            // clip
            p.setColor(C_MUTED);
            c.drawRoundRect(r(24,50,32,64,sx,sy), 2*sx, 2*sy, p);

            // telemóvel
            p.setColor(C_SURFACE);
            c.drawRoundRect(r(6,18,50,54,sx,sy), 4*sx, 4*sy, p);
            p.setColor(C_AMBER); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f*sx);
            c.drawRoundRect(r(6,18,50,54,sx,sy), 4*sx, 4*sy, p);
            // ecrã
            p.setColor(C_SCREEN); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(10,22,46,50,sx,sy), 2*sx, 2*sy, p);
            // câmara
            p.setColor(C_AMBER);
            c.drawCircle(28*sx, 26*sy, 2.5f*sx, p);
            // rosto (oval tracejado)
            p.setColor(C_BLUE); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1*sx); p.setPathEffect(dashEffect(2*sx, 2*sx));
            c.drawOval(oval(20,27,36,41,sx,sy), p);
            p.setPathEffect(null);

            // volante (direita)
            float cx = 118*sx, cy = 85*sy, r = 30*sx;
            // aro exterior
            p.setColor(C_BORDER); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5*sx); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawCircle(cx, cy, r, p);
            // raios
            p.setStrokeWidth(3.5f*sx);
            c.drawLine(cx, cy-r, cx, cy-r+14*sx, p);
            c.drawLine(cx, cy+r-14*sx, cx, cy+r, p);
            c.drawLine(cx-r, cy, cx-r+14*sx, cy, p);
            c.drawLine(cx+r-14*sx, cy, cx+r, cy, p);
            // cubo central
            p.setColor(C_TRACK); p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, 7*sx, p);
            p.setColor(C_BORDER); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f*sx);
            c.drawCircle(cx, cy, 7*sx, p);
            // coluna de direção
            p.setColor(C_BORDER); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(114,112,122,132,sx,sy), 2*sx, 2*sy, p);
        }
    }

    // PASSO 2 — Escuro (falsos) vs iluminado (preciso)
    static class Step2View extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Step2View(Context ctx) { super(ctx); }
        public Step2View(Context ctx, AttributeSet a) { super(ctx, a); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, (int)(w * 140f / 160f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            float sx = W / 160f, sy = H / 140f;

            // divisor central
            p.setColor(C_TRACK); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1*sx); p.setPathEffect(dashEffect(3*sx, 2*sx));
            c.drawLine(80*sx, 15*sy, 80*sx, 125*sy, p);
            p.setPathEffect(null);

            // lado escuro
            drawLabel(c, "ESCURO", 40*sx, 28*sy, C_MUTED, sx);
            // rosto escuro
            p.setColor(0xFF0D0F14); p.setStyle(Paint.Style.FILL);
            c.drawOval(oval(22,50,58,94,sx,sy), p);
            p.setColor(C_BORDER); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1*sx);
            c.drawOval(oval(22,50,58,94,sx,sy), p);
            // olhos escuros
            p.setColor(0xFF151820); p.setStyle(Paint.Style.FILL);
            c.drawOval(oval(29,62,37,70,sx,sy), p);
            c.drawOval(oval(43,62,51,70,sx,sy), p);
            // X vermelho
            p.setColor(C_RED); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f*sx); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(28*sx,100*sy, 36*sx,108*sy, p);
            c.drawLine(36*sx,100*sy, 28*sx,108*sy, p);
            drawLabel(c, "falsos", 40*sx, 122*sy, C_RED, sx);

            // lado iluminado
            drawLabel(c, "ILUMINADO", 120*sx, 28*sy, 0xFF8A8F9E, sx);
            // raios de luz
            p.setColor(C_AMBER); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f*sx); p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(110*sx,35*sy, 110*sx,44*sy, p);
            c.drawLine(100*sx,38*sy, 104*sx,45*sy, p);
            c.drawLine(120*sx,38*sy, 116*sx,45*sy, p);
            // rosto iluminado
            p.setColor(0xFF100F0A); p.setStyle(Paint.Style.FILL);
            c.drawOval(oval(102,54,138,98,sx,sy), p);
            p.setColor(C_AMBER); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1*sx);
            c.drawOval(oval(102,54,138,98,sx,sy), p);
            // olhos azuis
            p.setColor(C_BLUE); p.setStyle(Paint.Style.FILL);
            c.drawOval(oval(109,65,117,74,sx,sy), p);
            c.drawOval(oval(123,65,131,74,sx,sy), p);
            // check verde
            p.setColor(C_GREEN); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f*sx); p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path check = new Path();
            check.moveTo(108*sx,102*sy);
            check.lineTo(113*sx,108*sy);
            check.lineTo(133*sx,98*sy);
            c.drawPath(check, p);
            drawLabel(c, "preciso", 120*sx, 122*sy, C_GREEN, sx);
        }
    }

    // PASSO 3 — Telemóvel com contagem + botão iniciar
    static class Step3View extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Step3View(Context ctx) { super(ctx); }
        public Step3View(Context ctx, AttributeSet a) { super(ctx, a); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, (int)(w * 140f / 160f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            float sx = W / 160f, sy = H / 140f;

            // telemóvel
            p.setColor(C_SURFACE); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(40,15,120,125,sx,sy), 8*sx, 8*sy, p);
            p.setColor(C_BORDER); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1*sx);
            c.drawRoundRect(r(40,15,120,125,sx,sy), 8*sx, 8*sy, p);
            // ecrã
            p.setColor(C_BG); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(46,22,114,118,sx,sy), 4*sx, 4*sy, p);
            // chip ATENTO
            p.setColor(0xFF1A3A1A);
            c.drawRoundRect(r(56,30,104,44,sx,sy), 4*sx, 4*sy, p);
            drawLabelCenter(c, "ATENTO", 80*sx, 37*sy, C_GREEN, 7*sx);
            // countdown "3"
            drawLabelCenter(c, "3", 80*sx, 72*sy, C_AMBER, 28*sx, true);
            // botão INICIAR
            p.setColor(0xFF1A6B3A); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(52,90,108,108,sx,sy), 9*sx, 9*sy, p);
            drawLabelCenter(c, "INICIAR", 80*sx, 99*sy, 0xFFFFFFFF, 8*sx);
            // seta tap
            drawArrowRight(c, 112*sx, 99*sy, 116*sx, 99*sy, C_AMBER, sx);
            drawLabel(c, "tap", 132*sx, 102*sy, C_HINT, sx);
        }
    }

    // PASSO 4 — Alerta de fadiga com vibração
    static class Step4View extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Step4View(Context ctx) { super(ctx); }
        public Step4View(Context ctx, AttributeSet a) { super(ctx, a); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = View.MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, (int)(w * 140f / 160f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            float sx = W / 160f, sy = H / 140f;

            // telemóvel
            p.setColor(C_SURFACE); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(45,15,115,125,sx,sy), 8*sx, 8*sy, p);
            p.setColor(C_BORDER); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1*sx);
            c.drawRoundRect(r(45,15,115,125,sx,sy), 8*sx, 8*sy, p);
            // ecrã
            p.setColor(C_BG); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(51,22,109,118,sx,sy), 4*sx, 4*sy, p);
            // chip FADIGA DETETADA
            p.setColor(C_DARK_RED);
            c.drawRoundRect(r(55,32,105,48,sx,sy), 4*sx, 4*sy, p);
            drawLabelCenter(c, "FADIGA DETETADA", 80*sx, 40*sy, C_RED, 7*sx);
            // score
            drawLabelCenter(c, "0.87", 80*sx, 70*sy, C_RED, 16*sx, true);
            // barra track
            p.setColor(C_TRACK); p.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r(57,78,103,82,sx,sy), 2*sx, 2*sy, p);
            // barra fill
            p.setColor(C_RED);
            c.drawRoundRect(r(57,78,95,82,sx,sy), 2*sx, 2*sy, p);

            // vibrações
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            // esquerda — arco interno
            p.setColor(C_AMBER); p.setStrokeWidth(1.5f*sx);
            Path v1 = arcPath(28*sx,60*sy, 28*sx,76*sy, -8*sx);
            c.drawPath(v1, p);
            // esquerda — arco externo
            p.setStrokeWidth(1.2f*sx);
            Path v2 = arcPath(20*sx,54*sy, 20*sx,82*sy, -12*sx);
            c.drawPath(v2, p);
            // direita — arco interno
            p.setStrokeWidth(1.5f*sx);
            Path v3 = arcPath(132*sx,60*sy, 132*sx,76*sy, 8*sx);
            c.drawPath(v3, p);
            // direita — arco externo
            p.setStrokeWidth(1.2f*sx);
            Path v4 = arcPath(140*sx,54*sy, 140*sx,82*sy, 12*sx);
            c.drawPath(v4, p);
        }
    }

    // Helpers
    /** RectF a partir de coordenadas SVG (x1,y1,x2,y2) escaladas. */
    private static RectF r(float x1, float y1, float x2, float y2, float sx, float sy) {
        return new RectF(x1*sx, y1*sy, x2*sx, y2*sy);
    }

    /** RectF oval a partir de bounding box. */
    private static RectF oval(float x1, float y1, float x2, float y2, float sx, float sy) {
        return new RectF(x1*sx, y1*sy, x2*sx, y2*sy);
    }

    /** Texto centrado horizontalmente em x, baseline em y. */
    private static void drawLabel(Canvas c, String text, float x, float y,
                                  int color, float sx) {
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setColor(color);
        lp.setTextSize(8 * sx);
        lp.setTypeface(android.graphics.Typeface.MONOSPACE);
        lp.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, x, y, lp);
    }

    /** Texto centrado em (cx, cy) com tamanho arbitrário. */
    private static void drawLabelCenter(Canvas c, String text, float cx, float cy,
                                        int color, float textSize) {
        drawLabelCenter(c, text, cx, cy, color, textSize, false);
    }

    private static void drawLabelCenter(Canvas c, String text, float cx, float cy,
                                        int color, float textSize, boolean bold) {
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setColor(color);
        lp.setTextSize(textSize);
        lp.setTypeface(bold
                ? android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD)
                : android.graphics.Typeface.MONOSPACE);
        lp.setTextAlign(Paint.Align.CENTER);
        // centraliza verticalmente
        Paint.FontMetrics fm = lp.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        c.drawText(text, cx, baseline, lp);
    }

    /** Seta simples da esquerda para a direita. */
    private static void drawArrowRight(Canvas c, float x1, float y1,
                                       float x2, float y2, int color, float sx) {
        Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
        ap.setColor(color);
        ap.setStyle(Paint.Style.STROKE);
        ap.setStrokeWidth(1 * sx);
        ap.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x1, y1, x2, y2, ap);
        // ponta
        float hs = 4 * sx;
        Path head = new Path();
        head.moveTo(x2 - hs, y2 - hs * 0.6f);
        head.lineTo(x2, y2);
        head.lineTo(x2 - hs, y2 + hs * 0.6f);
        ap.setStyle(Paint.Style.STROKE);
        c.drawPath(head, ap);
    }

    /** Arco de vibração (curva cúbica simples). bulge < 0 = curva para a esquerda. */
    private static Path arcPath(float x1, float y1, float x2, float y2, float bulge) {
        Path path = new Path();
        path.moveTo(x1, y1);
        float midY = (y1 + y2) / 2f;
        path.cubicTo(x1 + bulge, y1, x2 + bulge, y2, x2, y2);
        return path;
    }

    /** DashPathEffect simples. */
    private static android.graphics.DashPathEffect dashEffect(float on, float off) {
        return new android.graphics.DashPathEffect(new float[]{on, off}, 0);
    }
}