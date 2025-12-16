package com.example.drowsinessdetection;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.solutioncore.ResultListener;
import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;
import com.google.mediapipe.solutions.facemesh.FaceMeshResult;

public class FaceMeshProcessor {

    private FaceMesh faceMesh;
    private LandmarksListener onLandmarksReceived;

    public FaceMeshProcessor(Context context) {

        FaceMeshOptions options = FaceMeshOptions.builder()
                .setStaticImageMode(false)   // stream
                .setRefineLandmarks(true)
                .setMaxNumFaces(1)
                .build();

        faceMesh = new FaceMesh(context, options);

        faceMesh.setResultListener(new ResultListener<FaceMeshResult>() {
            @Override
            public void run(FaceMeshResult result) {
                if (result.multiFaceLandmarks().isEmpty()) return;

                NormalizedLandmarkList landmarks =
                        result.multiFaceLandmarks().get(0);

                if (onLandmarksReceived != null) {
                    onLandmarksReceived.onLandmarks(landmarks);
                }
            }
        });
    }

    public interface LandmarksListener {
        void onLandmarks(NormalizedLandmarkList landmarks);
    }

    public void setLandmarksListener(LandmarksListener listener) {
        this.onLandmarksReceived = listener;
    }

    // 🔴 MÉTODO CORRETO PARA STREAM
    public void process(Bitmap bitmap, long timestampMs) {
        faceMesh.send(bitmap, timestampMs);
    }

    public void close() {
        faceMesh.close();
    }
}
