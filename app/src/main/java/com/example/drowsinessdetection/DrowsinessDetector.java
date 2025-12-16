package com.example.drowsinessdetection;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;

public class DrowsinessDetector {

    /* ============================
       PARÂMETROS DE CONTROLO
       ============================ */

    // Limite do Eye Aspect Ratio:
    private static final double EAR_THRESHOLD = 0.22;

    // Limite do Mouth Aspect Ratio:
    private static final double MAR_THRESHOLD = 0.60;

    // Limite de inclinação da cabeça (pitch):
    private static final double PITCH_THRESHOLD = 0.15;

    // Contador de frames consecutivos com olhos fechados
    private static int closedEyeFrames = 0;

    // Número mínimo de frames seguidos para considerar sonolência - ~15 frames ≈ 0.5s
    private static final int EYE_FRAMES_LIMIT = 15;

    /* ============================
       MÉTODO PRINCIPAL
       ============================ */

    /**
     * Decide se o utilizador está sonolento com base nos landmarks
     */
    public static boolean isDrowsy(NormalizedLandmarkList lm) {

        // Calcula EAR médio (olho esquerdo + direito)
        double earLeft = computeEAR(lm, true);
        double earRight = computeEAR(lm, false);
        double ear = (earLeft + earRight) / 2.0;

        // Calcula abertura da boca
        double mar = computeMAR(lm);

        // Calcula inclinação da cabeça
        double pitch = computePitch(lm);

        /* ----------------------------
           OLHOS FECHADOS
           ---------------------------- */

        // Se EAR abaixo do limite, conta frame
        if (ear < EAR_THRESHOLD) {
            closedEyeFrames++;
        } else {
            // Se abriu os olhos, reset
            closedEyeFrames = 0;
        }

        // Considera olhos fechados por tempo prolongado
        boolean eyesClosedTooLong = closedEyeFrames > EYE_FRAMES_LIMIT;

        /* ----------------------------
           BOCEJO E CABEÇA CAÍDA
           ---------------------------- */

        boolean yawning = mar > MAR_THRESHOLD;
        boolean headDown = pitch > PITCH_THRESHOLD;

        /* ----------------------------
           DECISÃO FINAL
           ---------------------------- */

        // Sonolento se QUALQUER condição for verdadeira
        return eyesClosedTooLong || yawning || headDown;
    }

    /* ============================
       EAR – Eye Aspect Ratio
       ============================ */

    /**
     * Calcula EAR para um olho específico
     * @param left true = olho esquerdo, false = direito
     */
    private static double computeEAR(NormalizedLandmarkList lm, boolean left) {

        // Índices oficiais do MediaPipe FaceMesh
        int[] idx = left
                ? new int[]{33, 160, 158, 133, 153, 144}   // olho esquerdo
                : new int[]{362, 385, 387, 263, 373, 380}; // olho direito

        // Pontos do olho
        NormalizedLandmark p1 = lm.getLandmark(idx[0]); // canto esquerdo
        NormalizedLandmark p2 = lm.getLandmark(idx[1]); // topo interno
        NormalizedLandmark p3 = lm.getLandmark(idx[2]); // topo externo
        NormalizedLandmark p4 = lm.getLandmark(idx[3]); // canto direito
        NormalizedLandmark p5 = lm.getLandmark(idx[4]); // baixo externo
        NormalizedLandmark p6 = lm.getLandmark(idx[5]); // baixo interno

        // Distâncias verticais
        double vertical1 = dist(p2, p6);
        double vertical2 = dist(p3, p5);

        // Distância horizontal
        double horizontal = dist(p1, p4);

        // Fórmula do EAR
        return (vertical1 + vertical2) / (2.0 * horizontal);
    }

    /* ============================
       MAR – Mouth Aspect Ratio
       ============================ */

    /**
     * Calcula abertura da boca (bocejo)
     */
    private static double computeMAR(NormalizedLandmarkList lm) {

        NormalizedLandmark top = lm.getLandmark(13);    // lábio superior
        NormalizedLandmark bottom = lm.getLandmark(14); // lábio inferior
        NormalizedLandmark left = lm.getLandmark(78);   // canto esquerdo
        NormalizedLandmark right = lm.getLandmark(308); // canto direito

        double vertical = dist(top, bottom);
        double horizontal = dist(left, right);

        return vertical / horizontal;
    }

    /* ============================
       HEAD PITCH
       ============================ */

    /**
     * Mede inclinação vertical da cabeça
     */
    private static double computePitch(NormalizedLandmarkList lm) {

        NormalizedLandmark nose = lm.getLandmark(1);   // nariz
        NormalizedLandmark chin = lm.getLandmark(152); // queixo

        // Quanto maior a diferença, mais a cabeça está caída
        return chin.getY() - nose.getY();
    }

    /* ============================
       DISTÂNCIA ENTRE DOIS PONTOS
       ============================ */

    /**
     * Distância euclidiana entre dois landmarks normalizados
     */
    private static double dist(NormalizedLandmark a, NormalizedLandmark b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
