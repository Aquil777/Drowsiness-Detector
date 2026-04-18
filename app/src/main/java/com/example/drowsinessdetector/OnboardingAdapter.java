package com.example.drowsinessdetector;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.StepViewHolder> {

    public static class OnboardingStep {
        public final String emoji;
        public final String badge;
        public final String title;
        public final String description;
        public final int    illustrationColor;

        public OnboardingStep(String emoji, String badge, String title,
                              String description, int illustrationColor) {
            this.emoji             = emoji;
            this.badge             = badge;
            this.title             = title;
            this.description       = description;
            this.illustrationColor = illustrationColor;
        }
    }

    public static final OnboardingStep[] STEPS = {
            new OnboardingStep(
                    "📱",
                    "1",
                    "Posiciona o telemóvel",
                    "Fixa o dispositivo num suporte de tablier ou viseira, com a câmara frontal apontada "
                            + "directamente ao teu rosto. Mantém uma distância de 40–80 cm. O rosto deve ficar "
                            + "dentro do rectângulo guia que aparece no ecrã — a IA só analisa o que está dentro desse rectângulo.",
                    0xFF0D1A14
            ),
            new OnboardingStep(
                    "💡",
                    "2",
                    "Iluminação",
                    "O sistema funciona em condições de pouca luz, mas pode gerar mais avisos falsos. "
                            + "Para melhores resultados, garante boa iluminação natural ou interior. "
                            + "Se estiveres num ambiente muito escuro, considera reduzir o rigor da IA nas Definições.",
                    0xFF1A1400
            ),
            new OnboardingStep(
                    "▶",
                    "3",
                    "Como iniciar",
                    "Prima o botão verde \"INICIAR MONITORIZAÇÃO\" no ecrã principal. "
                            + "O sistema dá-te 3 segundos para te posicionares antes de começar a análise. "
                            + "Durante a monitorização, o ecrã mostra o estado em tempo real.",
                    0xFF00101A
            ),
            new OnboardingStep(
                    "🔔",
                    "4",
                    "Alertas e Relatórios",
                    "Ao detectar fadiga, o telemóvel vibra e dispara um alarme sonoro. "
                            + "No separador Relatórios consultas o historial de cada sessão — "
                            + "número de alertas, duração e score médio.",
                    0xFF1A0A00
            )
    };

    private final Context context;

    public OnboardingAdapter(Context context) {
        this.context = context;
    }

    @NonNull
    @Override
    public StepViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.onboarding_step, parent, false);
        return new StepViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StepViewHolder holder, int position) {
        OnboardingStep step = STEPS[position];
        holder.tvEmoji.setText(step.emoji);
        holder.tvBadge.setText(step.badge);
        holder.tvTitle.setText(step.title);
        holder.tvDescription.setText(step.description);
        holder.illustration.setBackgroundColor(step.illustrationColor);
    }

    @Override
    public int getItemCount() { return STEPS.length; }

    static class StepViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout illustration;
        final TextView    tvEmoji;
        final TextView    tvBadge;
        final TextView    tvTitle;
        final TextView    tvDescription;

        StepViewHolder(@NonNull View itemView) {
            super(itemView);
            illustration  = itemView.findViewById(R.id.stepIllustration);
            tvEmoji       = itemView.findViewById(R.id.stepEmoji);
            tvBadge       = itemView.findViewById(R.id.stepBadge);
            tvTitle       = itemView.findViewById(R.id.stepTitle);
            tvDescription = itemView.findViewById(R.id.stepDescription);
        }
    }
}