package com.example.drowsinessdetector;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * CalibrationPanel
 *
 * Painel de calibração acessível diretamente na aba da câmara
 * (BottomSheet, aparece sobre o viewfinder sem mudar de aba).
 *
 * Contém:
 *   • Slider CLAHE Clip Limit  [1.0 – 3.0, padrão 2.0]
 *     → atualiza FatigueClassifier e ivDebugCrop em tempo real
 *
 * O threshold de fadiga (rigor do alarme) permanece nas Definições,
 * conforme pedido.
 *
 * Uso:
 *   CalibrationPanel panel = new CalibrationPanel(context, classifier, listener);
 *   panel.show();
 */
public class CalibrationPanel {

    public interface OnClipChangedListener {
        /** Chamado sempre que o utilizador mexe no slider do CLAHE. */
        void onClipChanged(float newClip);
    }

    private final BottomSheetDialog    dialog;
    private final FatigueClassifier    classifier;
    private final OnClipChangedListener listener;
    private final SharedPreferences    prefs;

    public CalibrationPanel(Context context,
                            FatigueClassifier classifier,
                            OnClipChangedListener listener) {
        this.classifier = classifier;
        this.listener   = listener;
        this.prefs      = context.getSharedPreferences(
                AppConstants.PREFS_NAME, Context.MODE_PRIVATE);
        this.dialog     = buildDialog(context);
    }

    public void show() {
        dialog.show();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private BottomSheetDialog buildDialog(Context context) {
        BottomSheetDialog d = new BottomSheetDialog(context);

        // Constrói a view do painel programaticamente para não depender de um
        // layout XML adicional. Usa apenas widgets do SDK.
        View root = buildContentView(context);
        d.setContentView(root);

        // Rounded corners + peek fixo
        d.getBehavior().setPeekHeight((int)(240 * context.getResources()
                .getDisplayMetrics().density));

        return d;
    }

    private View buildContentView(Context context) {
        android.widget.LinearLayout root = new android.widget.LinearLayout(context);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int dp16 = dp(context, 16);
        int dp8  = dp(context, 8);
        root.setPadding(dp16, dp16, dp16, dp16);
        root.setBackgroundColor(0xFF12161F); // mesma cor do card de settings

        // ── Título ────────────────────────────────────────────────────────────
        TextView title = new TextView(context);
        title.setText("Calibração da Câmara");
        title.setTextColor(0xFFE0E4F0);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.widget.LinearLayout.LayoutParams titleParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, 0, dp16);
        title.setLayoutParams(titleParams);
        root.addView(title);

        // ── Subtítulo CLAHE ───────────────────────────────────────────────────
        TextView labelClahe = new TextView(context);
        labelClahe.setText("Intensidade de Contraste (CLAHE)");
        labelClahe.setTextColor(0xFFB0B8CC);
        labelClahe.setTextSize(13);
        root.addView(labelClahe);

        // Valor atual
        float savedClip = prefs.getFloat(AppConstants.KEY_CLAHE_CLIP,
                AppConstants.DEFAULT_CLAHE_CLIP);
        final TextView tvClipValue = new TextView(context);
        tvClipValue.setText(String.format(java.util.Locale.US, "%.1f", savedClip));
        tvClipValue.setTextColor(0xFF2A7AE4);
        tvClipValue.setTextSize(13);
        android.widget.LinearLayout.LayoutParams valParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        valParams.setMargins(0, dp8 / 2, 0, 0);
        tvClipValue.setLayoutParams(valParams);
        root.addView(tvClipValue);

        // SeekBar CLAHE (0–20 → mapeia para 1.0–3.0 em passos de 0.1)
        SeekBar sbClip = new SeekBar(context);
        sbClip.setMax(20);
        sbClip.setProgress(clipToProgress(savedClip));
        android.widget.LinearLayout.LayoutParams sbParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        sbParams.setMargins(0, dp8, 0, dp8);
        sbClip.setLayoutParams(sbParams);
        root.addView(sbClip);

        // Labels min/max
        android.widget.LinearLayout rowLabels = new android.widget.LinearLayout(context);
        rowLabels.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        rowLabels.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvMin = new TextView(context);
        tvMin.setText("1.0 — Menos contraste");
        tvMin.setTextColor(0xFF5A5F6E);
        tvMin.setTextSize(11);
        tvMin.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rowLabels.addView(tvMin);

        TextView tvMax = new TextView(context);
        tvMax.setText("3.0 — Mais contraste");
        tvMax.setTextColor(0xFF5A5F6E);
        tvMax.setTextSize(11);
        tvMax.setGravity(android.view.Gravity.END);
        tvMax.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rowLabels.addView(tvMax);
        root.addView(rowLabels);

        // Dica contextual
        TextView tvHint = new TextView(context);
        tvHint.setText("Aumenta o contraste se os olhos não forem detetados corretamente "
                + "(ex: pele escura em ambiente com pouca luz).");
        tvHint.setTextColor(0xFF5A5F6E);
        tvHint.setTextSize(11);
        android.widget.LinearLayout.LayoutParams hintParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.setMargins(0, dp8, 0, 0);
        tvHint.setLayoutParams(hintParams);
        root.addView(tvHint);

        // ── Listener do SeekBar ───────────────────────────────────────────────
        sbClip.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float clip = progressToClip(progress);
                tvClipValue.setText(String.format(java.util.Locale.US, "%.1f", clip));
                if (classifier != null) classifier.setClaheClipLimit(clip);
                if (listener  != null) listener.onClipChanged(clip);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                float clip = progressToClip(seekBar.getProgress());
                prefs.edit()
                        .putFloat(AppConstants.KEY_CLAHE_CLIP, clip)
                        .apply();
            }
        });

        return root;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conversão SeekBar ↔ clip limit
    // ─────────────────────────────────────────────────────────────────────────

    /** Converte progress [0, 20] → clip [1.0, 3.0] */
    private float progressToClip(int progress) {
        return AppConstants.MIN_CLAHE_CLIP + progress * 0.1f;
    }

    /** Converte clip [1.0, 3.0] → progress [0, 20] */
    private int clipToProgress(float clip) {
        return Math.round((clip - AppConstants.MIN_CLAHE_CLIP) / 0.1f);
    }

    private int dp(Context ctx, int value) {
        return (int)(value * ctx.getResources().getDisplayMetrics().density);
    }
}