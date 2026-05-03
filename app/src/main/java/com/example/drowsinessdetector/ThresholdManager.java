package com.example.drowsinessdetector;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * ThresholdManager
 *
 * Combina o threshold pessoal guardado (calibração) com um offset
 * dinâmico baseado na luminosidade ambiente (sensor TYPE_LIGHT).
 *
 * Política de offsets:
 *   lux > 20          → +0.00  (dia / interior bem iluminado)
 *   5 ≤ lux ≤ 20      → +0.06  (penumbra)
 *   lux < 5           → +0.12  (escuridão)
 *
 * Chamar register() em onResume() e unregister() em onPause().
 */
public class ThresholdManager implements SensorEventListener {

    private static final String TAG = "ThresholdManager";

    // Offsets de luminosidade
    private static final float OFFSET_DARK   = 0.12f;   // lux < 5
    private static final float OFFSET_DIM    = 0.06f;   // 5 ≤ lux ≤ 20
    private static final float OFFSET_BRIGHT = 0.00f;   // lux > 20

    // Limiares de lux
    private static final float LUX_DARK_LIMIT = 5f;
    private static final float LUX_DIM_LIMIT  = 20f;

    // Threshold máximo permitido (evita falsos negativos em escuridão total)
    private static final float MAX_THRESHOLD  = 0.80f;

    private final Context       context;
    private final SensorManager sensorManager;
    private final Sensor        lightSensor;

    private volatile float currentLux = 100f;  // assume dia até o sensor responder

    public ThresholdManager(Context context) {
        this.context       = context.getApplicationContext();
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.lightSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        if (lightSensor == null) {
            Log.w(TAG, "Sensor de luz não disponível neste dispositivo");
        }
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Threshold efectivo = threshold pessoal + offset de luminosidade.
     * Usa DEFAULT_CONFIDENCE se não houver calibração guardada.
     */
    public float getEffectiveThreshold() {
        float base   = getPersonalThreshold();
        float offset = luxOffset(currentLux);
        return Math.min(base + offset, MAX_THRESHOLD);
    }

    /**
     * Threshold pessoal guardado (sem offset).
     * Devolve DEFAULT_CONFIDENCE se o utilizador nunca calibrou.
     */
    public float getPersonalThreshold() {
        return context
                .getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(AppConstants.KEY_FATIGUE_THRESHOLD, AppConstants.DEFAULT_CONFIDENCE);
    }

    /** True se já existe um threshold pessoal guardado. */
    public boolean isCalibrated() {
        return context
                .getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .contains(AppConstants.KEY_FATIGUE_THRESHOLD);
    }

    /** Offset actual de luminosidade (para debug / UI). */
    public float getCurrentLuxOffset() {
        return luxOffset(currentLux);
    }

    /** Último valor de lux recebido. */
    public float getCurrentLux() {
        return currentLux;
    }

    /** Regista o listener do sensor de luz. Chamar em onResume(). */
    public void register() {
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI);
            Log.d(TAG, "Sensor de luz registado");
        }
    }

    /** Remove o listener. Chamar em onPause(). */
    public void unregister() {
        sensorManager.unregisterListener(this);
    }

    // =========================================================================
    // SensorEventListener
    // =========================================================================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0];
            Log.d(TAG, "Lux atualizado: " + currentLux);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // não utilizado
    }

    // =========================================================================
    // Cálculo interno
    // =========================================================================

    private float luxOffset(float lux) {
        if (lux >= LUX_DIM_LIMIT) return OFFSET_BRIGHT;       // 0.00
        if (lux <= LUX_DARK_LIMIT) return OFFSET_DARK;        // 0.12
        // Interpolação linear entre 5 e 20 lux
        float proportion = (LUX_DIM_LIMIT - lux) / (LUX_DIM_LIMIT - LUX_DARK_LIMIT);
        return OFFSET_DARK * proportion;
    }
}