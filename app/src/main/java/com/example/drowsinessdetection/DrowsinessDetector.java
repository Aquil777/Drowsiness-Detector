package com.example.drowsinessdetection;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;

public class DrowsinessDetector {

    private static final String TAG = "DROWSY";

    private static final String PREF_NAME = "drowsy_prefs";
    private static final String KEY_BASE_EAR = "base_ear";
    private static final String KEY_BASE_MAR = "base_mar";
    private static final String KEY_EAR_DROP = "ear_drop";
    private static final String KEY_MAR_RATIO = "mar_ratio";

    // ===== BASELINES =====
    private float baselineEAR = -1f;
    private float baselineMAR = -1f;

    // ===== THRESHOLDS =====
    private float earDropRatio = 0.2f;   // será calibrado
    private float marThresholdRatio = 1.6f;

    // ===== CALIBRATION STATE =====
    private boolean openEyesDone = false;
    private boolean closedEyesDone = false;
    private boolean yawnDone = false;

    // ===== TEMP =====
    private static final int CALIB_FRAMES = 30;
    private int frames = 0;
    private float sumEAR = 0f;
    private float sumMAR = 0f;

    // ===== DROWSINESS =====
    private static final int DROWSY_FRAMES_REQUIRED = 15;
    private int drowsyFrames = 0;

    private final SharedPreferences prefs;

    public DrowsinessDetector(Context ctx) {
        prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        baselineEAR = prefs.getFloat(KEY_BASE_EAR, -1f);
        baselineMAR = prefs.getFloat(KEY_BASE_MAR, -1f);
        earDropRatio = prefs.getFloat(KEY_EAR_DROP, 0.2f);
        marThresholdRatio = prefs.getFloat(KEY_MAR_RATIO, 1.6f);

        openEyesDone = baselineEAR > 0 && baselineMAR > 0;
        closedEyesDone = prefs.contains(KEY_EAR_DROP);
        yawnDone = prefs.contains(KEY_MAR_RATIO);

        Log.d(TAG, "INIT | open=" + openEyesDone +
                " closed=" + closedEyesDone +
                " yawn=" + yawnDone);
    }

    // =====================================================
    // ================= CALIBRATION =======================
    // =====================================================

    public void calibrateOpenEyes(NormalizedLandmarkList lm) {
        if (openEyesDone) return;

        float ear = calculateEAR(lm);
        float mar = calculateMAR(lm);

        sumEAR += ear;
        sumMAR += mar;
        frames++;

        Log.d(TAG, "CALIB OPEN | EAR=" + ear + " MAR=" + mar + " f=" + frames);

        if (frames >= CALIB_FRAMES) {
            baselineEAR = sumEAR / CALIB_FRAMES;
            baselineMAR = sumMAR / CALIB_FRAMES;

            prefs.edit()
                    .putFloat(KEY_BASE_EAR, baselineEAR)
                    .putFloat(KEY_BASE_MAR, baselineMAR)
                    .apply();

            resetTemp();
            openEyesDone = true;

            Log.d(TAG, "OPEN DONE | baseEAR=" + baselineEAR + " baseMAR=" + baselineMAR);
        }
    }

    public void calibrateClosedEyes(NormalizedLandmarkList lm) {
        if (!openEyesDone || closedEyesDone) return;

        float ear = calculateEAR(lm);
        sumEAR += ear;
        frames++;

        Log.d(TAG, "CALIB CLOSED | EAR=" + ear + " f=" + frames);

        if (frames >= CALIB_FRAMES) {
            float closedEAR = sumEAR / CALIB_FRAMES;
            earDropRatio = (baselineEAR - closedEAR) / baselineEAR;

            prefs.edit()
                    .putFloat(KEY_EAR_DROP, earDropRatio)
                    .apply();

            resetTemp();
            closedEyesDone = true;

            Log.d(TAG, "CLOSED DONE | earDropRatio=" + earDropRatio +
                    " | thresholdEAR=" + (baselineEAR * (1 - earDropRatio)));
        }
    }

    public void calibrateYawn(NormalizedLandmarkList lm) {
        if (!openEyesDone || yawnDone) return;

        float mar = calculateMAR(lm);
        sumMAR += mar;
        frames++;

        Log.d(TAG, "CALIB YAWN | MAR=" + mar + " f=" + frames);

        if (frames >= CALIB_FRAMES) {
            float avgMAR = sumMAR / CALIB_FRAMES;
            marThresholdRatio = Math.max(1.6f, avgMAR / baselineMAR);

            prefs.edit()
                    .putFloat(KEY_MAR_RATIO, marThresholdRatio)
                    .apply();

            resetTemp();
            yawnDone = true;

            Log.d(TAG, "YAWN DONE | marRatio=" + marThresholdRatio +
                    " | thresholdMAR=" + (baselineMAR * marThresholdRatio));
        }
    }

    private void resetTemp() {
        frames = 0;
        sumEAR = 0f;
        sumMAR = 0f;
    }

    // =====================================================
    // ================= STATES ============================
    // =====================================================

    public boolean isOpenEyesCalibrated() { return openEyesDone; }
    public boolean isClosedEyesCalibrated() { return closedEyesDone; }
    public boolean isYawnCalibrated() { return yawnDone; }

    public boolean isFullyCalibrated() {
        return openEyesDone && closedEyesDone && yawnDone;
    }

    // =====================================================
    // ================= DROWSINESS ========================
    // =====================================================

    public boolean isDrowsy(NormalizedLandmarkList lm) {
        if (!isFullyCalibrated()) return false;

        float ear = calculateEAR(lm);
        float mar = calculateMAR(lm);

        boolean eyesClosed = ear < baselineEAR * (1 - earDropRatio);
        boolean yawning = mar > baselineMAR * marThresholdRatio;

        if (eyesClosed || yawning) drowsyFrames++;
        else drowsyFrames = 0;

        Log.d(TAG,
                "CHECK | EAR=" + ear +
                        " < " + (baselineEAR * (1 - earDropRatio)) +
                        " | MAR=" + mar +
                        " > " + (baselineMAR * marThresholdRatio) +
                        " | frames=" + drowsyFrames
        );

        return drowsyFrames >= DROWSY_FRAMES_REQUIRED;
    }

    // =====================================================
    // ================= MATH ==============================
    // =====================================================

    private float calculateEAR(NormalizedLandmarkList lm) {
        int[] r = {33, 160, 158, 133, 153, 144};
        int[] l = {362, 385, 387, 263, 373, 380};
        return (computeEAR(lm, r) + computeEAR(lm, l)) / 2f;
    }

    private float computeEAR(NormalizedLandmarkList lm, int[] i) {
        float v1 = distance(lm.getLandmark(i[1]), lm.getLandmark(i[4]));
        float v2 = distance(lm.getLandmark(i[2]), lm.getLandmark(i[5]));
        float h = distance(lm.getLandmark(i[0]), lm.getLandmark(i[3]));
        return (v1 + v2) / (2f * h);
    }

    private float calculateMAR(NormalizedLandmarkList lm) {
        return distance(lm.getLandmark(13), lm.getLandmark(14)) /
                distance(lm.getLandmark(61), lm.getLandmark(291));
    }

    private float distance(NormalizedLandmark a, NormalizedLandmark b) {
        float dx = a.getX() - b.getX();
        float dy = a.getY() - b.getY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
