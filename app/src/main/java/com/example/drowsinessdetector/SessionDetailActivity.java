package com.example.drowsinessdetector;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;

import java.util.Locale;

/**
 * Mostra o detalhe completo de uma SessionSnapshot:
 */
public class SessionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SNAPSHOT_JSON = "snapshot_json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // deserializar snapshot
        String json = getIntent().getStringExtra(EXTRA_SNAPSHOT_JSON);
        if (json == null) { finish(); return; }

        DriveSession.SessionSnapshot snap =
                new Gson().fromJson(json, DriveSession.SessionSnapshot.class);
        if (snap == null) { finish(); return; }

        // build UI por código
        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(0xFF080A0F);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(32));
        root.addView(page);

        setContentView(root);

        // respeita a status bar — igual ao espaçamento das outras tabs
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            page.setPadding(dp(16), topInset + dp(16), dp(16), dp(32));
            return insets;
        });

        // cores
        final int C_AMBER   = 0xFFC8AB5A;
        final int C_GREEN   = 0xFF2ECC71;
        final int C_RED     = 0xFFE53935;
        final int C_TEXT    = 0xFFD8D8D8;
        final int C_MUTED   = 0xFF8A8F9E;
        final int C_HINT    = 0xFF5A5F6E;
        final int C_SURFACE = 0xFF0D1118;
        final int C_DIVIDER = 0xFF1A1E2A;

        // score médio
        float avg = 0f;
        if (snap.events != null && !snap.events.isEmpty()) {
            for (DriveSession.FatigueEvent e : snap.events) avg += e.score;
            avg /= snap.events.size();
        }

        int alertColor = snap.alertCount == 0 ? C_GREEN
                : snap.alertCount <= 2  ? C_AMBER
                : C_RED;
        int avgColor   = avg == 0f  ? C_HINT
                : avg < .45f ? C_GREEN
                : avg < .65f ? C_AMBER
                : C_RED;

        // título da página
        TextView tvTitle = new TextView(this);
        tvTitle.setText("DETALHE DA SESSÃO");
        tvTitle.setTextColor(C_TEXT);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tvTitle.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(8, 90, 0, dp(20));
        tvTitle.setLayoutParams(titleLp);
        page.addView(tvTitle);

        // resumo compacto
        LinearLayout summaryCard = new LinearLayout(this);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryLp.setMargins(0, 0, 0, dp(20));
        summaryCard.setLayoutParams(summaryLp);

        android.graphics.drawable.GradientDrawable summaryBg =
                new android.graphics.drawable.GradientDrawable();
        summaryBg.setColor(C_SURFACE);
        summaryBg.setCornerRadius(dp(6));
        summaryBg.setStroke(dp(1), C_AMBER);
        summaryCard.setBackground(summaryBg);

        // data/hora
        String dateLabel = snap.date
                + (snap.endDate != null && !snap.endDate.isEmpty()
                ? "  →  " + snap.endDate : "");
        summaryCard.addView(makeSummaryRow("data", dateLabel, C_MUTED, C_MUTED, C_DIVIDER));
        summaryCard.addView(makeSummaryRow("alertas",
                String.valueOf(snap.alertCount), C_MUTED, alertColor, C_DIVIDER));
        summaryCard.addView(makeSummaryRow("duração",
                formatDuration(snap.durationSeconds), C_MUTED, C_TEXT, C_DIVIDER));
        summaryCard.addView(makeSummaryRow("score médio",
                avg > 0 ? String.format(Locale.getDefault(), "%.2f", avg) : "—",
                C_MUTED, avgColor, 0));  // último sem divisor

        page.addView(summaryCard);

        // lista de eventos
        if (snap.events != null && !snap.events.isEmpty()) {

            TextView evTitle = new TextView(this);
            evTitle.setText("EVENTOS DE FADIGA");
            evTitle.setTextColor(C_HINT);
            evTitle.setTextSize(9);
            evTitle.setTypeface(android.graphics.Typeface.MONOSPACE);
            evTitle.setLetterSpacing(0.14f);
            LinearLayout.LayoutParams evTitleLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            evTitleLp.setMargins(dp(2), 0, 0, dp(10));
            evTitle.setLayoutParams(evTitleLp);
            page.addView(evTitle);

            for (DriveSession.FatigueEvent ev : snap.events) {
                page.addView(buildEventRow(ev, C_MUTED, C_SURFACE, C_DIVIDER));
            }

        } else {
            TextView noEv = new TextView(this);
            noEv.setText("SESSÃO SEM ALERTAS");
            noEv.setTextColor(C_HINT);
            noEv.setTextSize(13);
            noEv.setTypeface(android.graphics.Typeface.MONOSPACE);
            noEv.setGravity(android.view.Gravity.CENTER);
            noEv.setLetterSpacing(0.06f);
            noEv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            page.addView(noEv);
        }
    }

    // linha de evento (lista densa, sem card)
    private LinearLayout buildEventRow(DriveSession.FatigueEvent ev,
                                       int timeColor, int bgColor, int trackColor) {
        int evColor = ev.score < .45f ? 0xFF2ECC71
                : ev.score < .65f ? 0xFFC8AB5A
                : 0xFFE53935;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // hora
        TextView tvTime = new TextView(this);
        tvTime.setText(ev.timestamp != null ? ev.timestamp : "--:--");
        tvTime.setTextColor(timeColor);
        tvTime.setTextSize(11);
        tvTime.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                dp(62), LinearLayout.LayoutParams.WRAP_CONTENT);
        timeLp.setMargins(0, 0, dp(12), 0);
        tvTime.setLayoutParams(timeLp);
        row.addView(tvTime);

        // barra track + fill (2dp de altura, sem borda)
        FrameLayout track = new FrameLayout(this);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, dp(2), 1f);
        trackLp.setMargins(0, 0, dp(12), 0);
        track.setLayoutParams(trackLp);

        android.graphics.drawable.GradientDrawable trackBg =
                new android.graphics.drawable.GradientDrawable();
        trackBg.setColor(0xFF1A1E2A);
        trackBg.setCornerRadius(dp(1));
        View trackView = new View(this);
        trackView.setBackground(trackBg);
        trackView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        track.addView(trackView);

        android.graphics.drawable.GradientDrawable fillBg =
                new android.graphics.drawable.GradientDrawable();
        fillBg.setColor(evColor);
        fillBg.setCornerRadius(dp(1));
        View fill = new View(this);
        fill.setBackground(fillBg);
        final float sc = ev.score;
        track.post(() -> {
            int w = (int)(track.getWidth() * Math.min(1f, Math.max(0f, sc)));
            fill.setLayoutParams(new FrameLayout.LayoutParams(w,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        });
        track.addView(fill);
        row.addView(track);

        // score
        TextView tvScore = new TextView(this);
        tvScore.setText(String.format(Locale.getDefault(), "%.2f", ev.score));
        tvScore.setTextColor(evColor);
        tvScore.setTextSize(12);
        tvScore.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvScore.setGravity(android.view.Gravity.END);
        tvScore.setLayoutParams(new LinearLayout.LayoutParams(
                dp(34), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(tvScore);

        return row;
    }

    // linha de resumo: label à esquerda, valor à direita
    private LinearLayout makeSummaryRow(String label, String value,
                                        int lblColor, int valColor, int dividerColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        inner.setPadding(0, dp(7), 0, dp(7));
        inner.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextColor(lblColor);
        tvLabel.setTextSize(11);
        tvLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        inner.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextColor(valColor);
        tvValue.setTextSize(13);
        tvValue.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        tvValue.setGravity(android.view.Gravity.END);
        inner.addView(tvValue);

        row.addView(inner);

        if (dividerColor != 0) {
            View div = new View(this);
            div.setBackgroundColor(dividerColor);
            div.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            row.addView(div);
        }

        return row;
    }

    // utils
    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        return String.format(Locale.getDefault(), "%dm%02ds", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density);
    }
}