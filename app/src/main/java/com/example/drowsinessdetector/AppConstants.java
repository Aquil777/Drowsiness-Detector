package com.example.drowsinessdetector;

public final class AppConstants {
    private AppConstants() {}

    // Preferências
    public static final String PREFS_NAME             = "DrowsinessPrefs";
    public static final String KEY_USE_VOICE          = "useVoice";
    public static final String KEY_SENSITIVITY        = "sensitivity";
    public static final String KEY_ONBOARDING_DONE    = "onboardingDone";

    // Monitorização
    public static final long   FATIGUE_DURATION_MS    = 350L;
    public static final long   VOICE_COOLDOWN_MS      = 5000L;
    public static final float  DEFAULT_CONFIDENCE     = 0.60f;
    public static final int    DEFAULT_SENSITIVITY    = 60;
    public static final int    SMOOTH_WINDOW          = 5;

    // Câmara
    public static final int    CAMERA_PERMISSION_CODE = 10;
    public static final long   CAMERA_INIT_DELAY_MS   = 500L;

    /**
     * Luminância abaixo deste valor → aviso visual de pouca luz.
     * A inferência continua — o modelo v3 foi treinado com augmentation de brilho.
     * Escala ITU-R BT.601: 0 (preto) a 255 (branco).
     */
    public static final float  LOW_LIGHT_THRESHOLD    = 40f;
}