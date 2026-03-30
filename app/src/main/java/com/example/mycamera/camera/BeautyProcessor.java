package com.example.mycamera.camera;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

public class BeautyProcessor {
    private float smoothLevel = 0.3f;
    private float whitenLevel = 0.15f;
    private float eyeEnlargeLevel = 0.2f;
    private float faceShrinkLevel = 0.25f;

    public void setSmoothLevel(float level) { this.smoothLevel = Math.max(0f, Math.min(1f, level)); }
    public void setWhitenLevel(float level) { this.whitenLevel = Math.max(0f, Math.min(1f, level)); }
    public void setEyeEnlargeLevel(float level) { this.eyeEnlargeLevel = Math.max(0f, Math.min(1f, level)); }
    public void setFaceShrinkLevel(float level) { this.faceShrinkLevel = Math.max(0f, Math.min(1f, level)); }

    public Bitmap process(Bitmap src) {
        if (src == null) return null;
        Mat srcMat = new Mat();
        Utils.bitmapToMat(src, srcMat);
        Mat dstMat = srcMat.clone();

        // 磨皮：双边滤波 + 融合
        if (smoothLevel > 0) {
            Mat smoothed = new Mat();
            double sigmaColor = 30 + smoothLevel * 100;
            double sigmaSpace = 10 + smoothLevel * 30;
            Imgproc.bilateralFilter(dstMat, smoothed, 9, sigmaColor, sigmaSpace);
            double alpha = 0.3 + smoothLevel * 0.5;
            Core.addWeighted(dstMat, 1 - alpha, smoothed, alpha, 0, dstMat);
            smoothed.release();
        }

        // 大眼、瘦脸...

        // 美白：调整亮度和对比度
        if (whitenLevel > 0) {
            double alpha = 1.0 + whitenLevel * 0.5;
            double beta = 15 * whitenLevel;
            dstMat.convertTo(dstMat, -1, alpha, beta);
        }

        Bitmap outBitmap = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
        Utils.matToBitmap(dstMat, outBitmap);
        srcMat.release();
        dstMat.release();
        return outBitmap;
    }
}