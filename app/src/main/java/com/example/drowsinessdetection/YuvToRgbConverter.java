package com.example.drowsinessdetection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class YuvToRgbConverter {

    public YuvToRgbConverter(Context context) {
        // Não precisa de nada aqui
    }

    @androidx.camera.core.ExperimentalGetImage
    public void yuvToRgb(@NonNull ImageProxy image, @NonNull Bitmap output) {
        Image img = image.getImage();
        if (img == null) return;

        byte[] nv21 = yuv420ToNv21(img);

        YuvImage yuvImage = new YuvImage(
                nv21,
                ImageFormat.NV21,
                img.getWidth(),
                img.getHeight(),
                null
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(
                new Rect(0, 0, img.getWidth(), img.getHeight()),
                90,
                out
        );

        byte[] jpegBytes = out.toByteArray();
        Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(
                jpegBytes, 0, jpegBytes.length
        );

        new android.graphics.Canvas(output).drawBitmap(bitmap, 0, 0, null);
    }

    private static byte[] yuv420ToNv21(Image image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);

        byte[] uBytes = new byte[uSize];
        byte[] vBytes = new byte[vSize];
        uBuffer.get(uBytes);
        vBuffer.get(vBytes);

        for (int i = 0; i < uSize; i++) {
            nv21[ySize + i * 2] = vBytes[i];
            nv21[ySize + i * 2 + 1] = uBytes[i];
        }

        return nv21;
    }
}
