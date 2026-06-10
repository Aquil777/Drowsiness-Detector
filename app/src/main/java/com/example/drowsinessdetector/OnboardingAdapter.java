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
        public final String badge;
        public final String title;
        public final String description;

        public OnboardingStep(String badge, String title, String description) {
            this.badge       = badge;
            this.title       = title;
            this.description = description;
        }
    }

    public static final OnboardingStep[] STEPS = {
            new OnboardingStep(
                    "1",
                    "Posiciona o telemóvel",
                    "Fixa o dispositivo num suporte de tablier ou viseira, com a câmara frontal apontada "
                            + "directamente ao teu rosto. Mantém uma distância de 40–80 cm. O rosto deve ficar "
                            + "dentro do campo visível da câmara do telemóvel."
            ),
            new OnboardingStep(
                    "2",
                    "Iluminação",
                    "O sistema funciona em condições de pouca luz, mas pode gerar mais avisos falsos. "
                            + "Para melhores resultados, garante boa iluminação natural ou interior. "
                            + "Se estiveres num ambiente muito escuro, considera aumentar o rigor da IA nas Definições."
            ),
            new OnboardingStep(
                    "3",
                    "Como iniciar",
                    "Prima o botão verde \"INICIAR MONITORIZAÇÃO\" no ecrã principal. "
                            + "O sistema dá-te 3 segundos para te posicionares antes de começar a análise. "
                            + "Durante a monitorização, o ecrã mostra o estado em tempo real."
            ),
            new OnboardingStep(
                    "4",
                    "Alertas e Relatórios",
                    "Ao detectar fadiga, o telemóvel vibra e dispara um alarme sonoro. "
                            + "No separador Relatórios consultas o historial de cada sessão — "
                            + "número de alertas, duração e score médio."
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

        holder.tvBadge.setText(step.badge);
        holder.tvTitle.setText(step.title);
        holder.tvDescription.setText(step.description);

        // substitui ilustração anterior pela do passo actual
        holder.illustration.removeAllViews();
        View illus = OnboardingIllustrations.forStep(context, position);
        // a ilustração ocupa 70% da largura do FrameLayout, centrada
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int)(holder.illustration.getResources()
                        .getDisplayMetrics().widthPixels * 0.70f),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        illus.setLayoutParams(lp);
        holder.illustration.addView(illus);
    }

    @Override
    public int getItemCount() { return STEPS.length; }

    static class StepViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout illustration;
        final TextView    tvBadge;
        final TextView    tvTitle;
        final TextView    tvDescription;

        StepViewHolder(@NonNull View itemView) {
            super(itemView);
            illustration  = itemView.findViewById(R.id.stepIllustration);
            tvBadge       = itemView.findViewById(R.id.stepBadge);
            tvTitle       = itemView.findViewById(R.id.stepTitle);
            tvDescription = itemView.findViewById(R.id.stepDescription);
        }
    }
}