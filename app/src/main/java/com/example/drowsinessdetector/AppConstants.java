package com.example.drowsinessdetector;

public final class AppConstants {
    private AppConstants() {}

    // Preferências
    public static final String PREFS_NAME             = "DrowsinessPrefs";
    public static final String KEY_USE_VOICE          = "useVoice";
    public static final String KEY_SENSITIVITY        = "sensitivity";
    public static final String KEY_ONBOARDING_DONE    = "onboardingDone";

    // Calibração em tempo real (novos — v6)
    public static final String KEY_CLAHE_CLIP         = "claheClip";
    public static final String KEY_FATIGUE_THRESHOLD  = "fatigueThreshold";

    // Monitorização
    public static final long   VOICE_COOLDOWN_MS      = 5000L;
    public static final float  DEFAULT_CONFIDENCE     = 0.45f;   // alinhado com o threshold do modelo v6
    public static final int    DEFAULT_SENSITIVITY    = 45;    // alinhado com DEFAULT_CONFIDENCE

    public static final int    SMOOTH_WINDOW          = 2;     // menos diluição
    public static final long   FATIGUE_DURATION_MS    = 300L;  // mais reativo

    // CLAHE
    public static final float  DEFAULT_CLAHE_CLIP     = 2.0f;   // clipLimit padrão igual ao treino
    public static final float  MIN_CLAHE_CLIP         = 1.0f;
    public static final float  MAX_CLAHE_CLIP         = 3.0f;

    // Câmara
    public static final int    CAMERA_PERMISSION_CODE = 10;
    public static final long   CAMERA_INIT_DELAY_MS   = 500L;

    /**
     * Margem adicionada à bounding box do rosto antes do crop dinâmico.
     * 15 % garante que sobrancelhas e queixo não sejam cortados.
     */
    public static final float  FACE_CROP_MARGIN       = 0.15f;

    /**
     * Tamanho de entrada do modelo v6 (treino a 224 × 224).
     */
    public static final int    MODEL_INPUT_SIZE       = 224;

    /**
     * Luminância abaixo deste valor → aviso visual de pouca luz.
     * Escala ITU-R BT.601: 0 (preto) a 255 (branco).
     */
    public static final float  LOW_LIGHT_THRESHOLD    = 40f;
}