package com.example.drowsinessdetection;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;

public class DrowsinessDetector {

    private static final String TAG = "DROWSY";
    private static final String PREF_NAME = "drowsy_prefs";

    private static final String KEY_EAR = "baselineEAR";
    private static final String KEY_MAR = "baselineMAR";
    private static final String KEY_EAR_THRESHOLD = "earThreshold";

    private float baselineEAR = -1f;
    private float baselineMAR = -1f;
    private float earThreshold = 0.7f; // default inicial
    private static final float MAR_THRESHOLD_RATIO = 1.5f;

    private static final int CALIBRATION_FRAMES = 30;
    private int framesCounted = 0;
    private float sumEAR = 0f;
    private float sumMAR = 0f;

    private boolean calibrated = false;

    private final SharedPreferences prefs;

    private static final int DROWSY_FRAMES_REQUIRED = 15; // ~0.5s a 30fps
    private int drowsyFrameCount = 0;

    public DrowsinessDetector(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        baselineEAR = prefs.getFloat(KEY_EAR, -1f);
        baselineMAR = prefs.getFloat(KEY_MAR, -1f);
        earThreshold = prefs.getFloat(KEY_EAR_THRESHOLD, 0.7f);

        calibrated = baselineEAR > 0 && baselineMAR > 0;

        Log.d(TAG, calibrated
                ? "Valores carregados: EAR=" + baselineEAR + " MAR=" + baselineMAR + " EAR Threshold=" + earThreshold
                : "Nenhuma calibração encontrada");
    }

    public void calibrate(NormalizedLandmarkList landmarks) {
        float ear = calculateEAR(landmarks);
        float mar = calculateMAR(landmarks);

        sumEAR += ear;
        sumMAR += mar;
        framesCounted++;

        Log.d(TAG, "Calibrando... EAR=" + ear + " MAR=" + mar);

        if (framesCounted >= CALIBRATION_FRAMES) {
            baselineEAR = sumEAR / CALIBRATION_FRAMES;
            baselineMAR = sumMAR / CALIBRATION_FRAMES;

            // Ajuste automático do threshold EAR
            earThreshold = Math.max(0.75f, Math.min(0.85f, baselineEAR * 0.8f / baselineEAR)); // ajusta entre 0.75-0.85

            calibrated = true;

            prefs.edit()
                    .putFloat(KEY_EAR, baselineEAR)
                    .putFloat(KEY_MAR, baselineMAR)
                    .putFloat(KEY_EAR_THRESHOLD, earThreshold)
                    .apply();

            Log.d(TAG, "Calibração concluída! EAR base=" + baselineEAR + " MAR base=" + baselineMAR + " EAR threshold=" + earThreshold);
        }
    }

    public boolean isCalibrated() {
        return calibrated;
    }

    public float getBaselineEAR() {
        return baselineEAR;
    }

    public float getBaselineMAR() {
        return baselineMAR;
    }


    public boolean isDrowsy(NormalizedLandmarkList landmarks) {
        if (!calibrated) return false;

        float ear = calculateEAR(landmarks);
        float mar = calculateMAR(landmarks);

        boolean eyesClosed = ear < baselineEAR * earThreshold;
        boolean yawning = mar > baselineMAR * MAR_THRESHOLD_RATIO;

        if (eyesClosed || yawning) {
            drowsyFrameCount++;
        } else {
            drowsyFrameCount = 0;
        }

        Log.d(TAG,
                "EAR=" + ear +
                        " | MAR=" + mar +
                        " | frames=" + drowsyFrameCount
        );

        Log.d(TAG, "EAR atual=" + ear + " | EAR base=" +
                baselineEAR + " | EAR threshold=" + (baselineEAR * earThreshold) +
                " | MAR atual=" + mar + " | MAR base=" + baselineMAR +
                " | frames=" + drowsyFrameCount);

        return drowsyFrameCount >= DROWSY_FRAMES_REQUIRED;
    }


    private float calculateEAR(NormalizedLandmarkList landmarks) {
        int[] rightEye = {33, 160, 158, 133, 153, 144};
        int[] leftEye = {362, 385, 387, 263, 373, 380};

        return (computeEAR(landmarks, rightEye) + computeEAR(landmarks, leftEye)) / 2f;
    }

    private float computeEAR(NormalizedLandmarkList lm, int[] idx) {
        NormalizedLandmark p1 = lm.getLandmark(idx[0]);
        NormalizedLandmark p2 = lm.getLandmark(idx[1]);
        NormalizedLandmark p3 = lm.getLandmark(idx[2]);
        NormalizedLandmark p4 = lm.getLandmark(idx[3]);
        NormalizedLandmark p5 = lm.getLandmark(idx[4]);
        NormalizedLandmark p6 = lm.getLandmark(idx[5]);

        float v1 = distance(p2, p5);
        float v2 = distance(p3, p6);
        float h = distance(p1, p4);

        return (v1 + v2) / (2f * h);
    }

    private float calculateMAR(NormalizedLandmarkList landmarks) {
        NormalizedLandmark top = landmarks.getLandmark(13);
        NormalizedLandmark bottom = landmarks.getLandmark(14);
        NormalizedLandmark left = landmarks.getLandmark(61);
        NormalizedLandmark right = landmarks.getLandmark(291);

        return distance(top, bottom) / distance(left, right);
    }

    private float distance(NormalizedLandmark a, NormalizedLandmark b) {
        float dx = a.getX() - b.getX();
        float dy = a.getY() - b.getY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
