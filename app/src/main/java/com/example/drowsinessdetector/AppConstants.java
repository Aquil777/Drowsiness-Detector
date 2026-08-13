package com.example.drowsinessdetector;

public final class AppConstants {
    private AppConstants() {}

    // Preferências
    public static final String PREFS_NAME             = "DrowsinessPrefs"; //nome do ficheiro
    public static final String KEY_USE_VOICE          = "useVoice"; // chave para uso de voz
    public static final String KEY_SENSITIVITY        = "sensitivity"; //chave de sensibilidade
    public static final String KEY_ONBOARDING_DONE    = "onboardingDone"; //chave de onboarding

    // Calibração em tempo real
    public static final String KEY_FATIGUE_THRESHOLD  = "fatigueThreshold";

    // Monitorização
    public static final long   VOICE_COOLDOWN_MS      = 5000L;
    public static final float  DEFAULT_CONFIDENCE     = 0.45f;   // alinhado com o threshold do modelo v6
    public static final int    DEFAULT_SENSITIVITY    = 45;    // alinhado com DEFAULT_CONFIDENCE

    public static final int    SMOOTH_WINDOW          = 3;     // mais diluição, tolerância a ruído
    public static final long   FATIGUE_DURATION_MS    = 400L;  // mais conservador

    // CLAHE
    public static final float  DEFAULT_CLAHE_CLIP     = 2.0f;   // clipLimit padrão

    // Câmara
    public static final int    CAMERA_PERMISSION_CODE = 10;

    /**
     * Tamanho de entrada do modelo (treino a 224 × 224).
     */
    public static final int    MODEL_INPUT_SIZE       = 224;

    /**
     * Luminância abaixo deste valor → aviso visual de pouca luz.
     * Escala ITU-R BT.601: 0 (preto) a 255 (branco).
     */
    public static final float  LOW_LIGHT_THRESHOLD    = 40f;
}