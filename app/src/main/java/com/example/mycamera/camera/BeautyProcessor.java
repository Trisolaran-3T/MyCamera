package com.example.mycamera.camera;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Point;
import java.util.ArrayList;
import java.util.List;

public class BeautyProcessor {
    private static final String TAG = "BeautyProcessor";

    private float smoothLevel = 0.3f; // 磨皮强度
    private float whitenLevel = 0.15f; // 美白强度
    private float eyeEnlargeLevel = 0.2f; // 大眼强度
    private float faceShrinkLevel = 0.25f; // 瘦脸强度

    // 设置美颜参数 (确保在0-1之间)
    public void setSmoothLevel(float level) { this.smoothLevel = Math.max(0f, Math.min(1f, level)); }
    public void setWhitenLevel(float level) { this.whitenLevel = Math.max(0f, Math.min(1f, level)); }
    public void setEyeEnlargeLevel(float level) { this.eyeEnlargeLevel = Math.max(0f, Math.min(1f, level)); }
    public void setFaceShrinkLevel(float level) { this.faceShrinkLevel = Math.max(0f, Math.min(1f, level)); }


    public Bitmap process(Bitmap src, RectF faceRect) {
        if (src == null) return null;
        Log.d(TAG, "美颜处理开始，尺寸: " + src.getWidth() + "x" + src.getHeight());

        // 1. Bitmap -> Mat (RGBA -> BGR)
        Mat srcMat = new Mat();
        Utils.bitmapToMat(src, srcMat);
        Mat workingMat = new Mat();
        if (srcMat.channels() == 4) {
            Imgproc.cvtColor(srcMat, workingMat, Imgproc.COLOR_RGBA2BGR);
        } else if (srcMat.channels() == 1) {
            Imgproc.cvtColor(srcMat, workingMat, Imgproc.COLOR_GRAY2BGR);
        } else {
            srcMat.copyTo(workingMat);
        }
        srcMat.release();

        // 2. 磨皮（强度越高，滤波半径越大）
        if (smoothLevel > 0.01f) {
            Mat smoothed = new Mat();
            int d = 5;                      // 滤波直径，固定为5可提速
            double sigmaColor = 30 + smoothLevel * 80;
            double sigmaSpace = 10 + smoothLevel * 20;
            Imgproc.bilateralFilter(workingMat, smoothed, d, sigmaColor, sigmaSpace);
            double alpha = 0.3f + smoothLevel * 0.5f;
            Core.addWeighted(workingMat, 1 - alpha, smoothed, alpha, 0, workingMat);
            smoothed.release();
        }

        // 3. 美白（亮度对比度调整，速度极快）
        if (whitenLevel > 0.01f) {
            double alpha = 1.0 + whitenLevel * 0.6;
            double beta = 12 * whitenLevel;
            workingMat.convertTo(workingMat, -1, alpha, beta);
        }

        // 4. Mat -> Bitmap (BGR -> RGBA)
        Mat rgbaMat = new Mat();
        Imgproc.cvtColor(workingMat, rgbaMat, Imgproc.COLOR_BGR2RGBA);
        Bitmap outBitmap = Bitmap.createBitmap(src.getWidth(), src.getHeight(), src.getConfig());
        Utils.matToBitmap(rgbaMat, outBitmap);

        workingMat.release();
        rgbaMat.release();
        return outBitmap;
    }

    public Mat processMat(Mat srcBGR, RectF faceRect) {
        if (srcBGR.empty()) return srcBGR.clone();
        Mat result = srcBGR.clone();
        if (smoothLevel > 0.01f) {
            Mat smoothed = new Mat();
            Imgproc.bilateralFilter(result, smoothed, 5, 30 + smoothLevel * 80, 10 + smoothLevel * 20);
            Core.addWeighted(result, 1 - (0.3 + smoothLevel * 0.5), smoothed, 0.3 + smoothLevel * 0.5, 0, result);
            smoothed.release();
        }
        if (whitenLevel > 0.01f) {
            result.convertTo(result, -1, 1.0 + whitenLevel * 0.6, 12 * whitenLevel);
        }
        return result;
    }

    // 磨皮：使用双边滤波保留边缘
    private Mat applySkinSmoothing(Mat src, float level) {
        // 确保输入是CV_8UC3格式
        if (src.type() != CvType.CV_8UC3) {
            Log.e(TAG, "磨皮输入格式错误: " + src.type() + ", 期望: " + CvType.CV_8UC3);
            return src.clone();
        }

        Mat smoothed = new Mat();
        // 滤波参数随强度调整
        double sigmaColor = 30 + level * 100; // 颜色空间标准差
        double sigmaSpace = 10 + level * 30;  // 坐标空间标准差

        // 确保src和smoothed不是同一个对象
        Imgproc.bilateralFilter(src, smoothed, 9, sigmaColor, sigmaSpace);

        // 将原图与滤波结果融合
        double alpha = 0.3 + level * 0.5; // 融合系数
        Mat result = new Mat();
        Core.addWeighted(src, 1 - alpha, smoothed, alpha, 0, result);
        smoothed.release();
        return result;
    }

    // 美白：调整亮度和对比度
    private Mat applySkinWhitening(Mat src, float level) {
        Mat result = new Mat();
        double alpha = 1.0 + level * 0.5; // 对比度增益 (>1)
        double beta = 15 * level;         // 亮度增量
        src.convertTo(result, -1, alpha, beta);
        return result;
    }

    // 大眼：基于人眼关键点进行局部缩放
    private Mat applyEyeEnlarge(Mat src, List<Point> landmarks, float level) {
        Mat result = src.clone();
        // 估算左右眼中心 (landmarks[0], landmarks[1] 在 FaceDetectorHelper 中已估算)
        // 这里使用简化的圆心和半径
        int eyeRadius = (int) (Math.min(src.width(), src.height()) * 0.02 * (1 + level)); // 放大半径

        if (landmarks.size() >= 2) {
            Point leftEye = landmarks.get(0);
            Point rightEye = landmarks.get(1);
            // 左眼区域放大
            org.opencv.core.Rect leftEyeROI = new org.opencv.core.Rect(
                    (int)(leftEye.x - eyeRadius), (int)(leftEye.y - eyeRadius),
                    2*eyeRadius, 2*eyeRadius);
            // 右眼区域放大
            org.opencv.core.Rect rightEyeROI = new org.opencv.core.Rect(
                    (int)(rightEye.x - eyeRadius), (int)(rightEye.y - eyeRadius),
                    2*eyeRadius, 2*eyeRadius);

            // 确保 ROI 在图像范围内
            leftEyeROI = clampRect(leftEyeROI, src.size());
            rightEyeROI = clampRect(rightEyeROI, src.size());

            // 使用缩放模拟放大效果 (简化版，可使用更精细的液化滤镜)
            if (leftEyeROI.area() > 0) {
                Mat leftEyeMat = new Mat(src, leftEyeROI);
                Mat enlargedLeftEye = new Mat();
                Imgproc.resize(leftEyeMat, enlargedLeftEye, new Size(leftEyeROI.width*1.2, leftEyeROI.height*1.2));
                Imgproc.resize(enlargedLeftEye, leftEyeMat, leftEyeMat.size());
                leftEyeMat.copyTo(new Mat(result, leftEyeROI));
                leftEyeMat.release();
                enlargedLeftEye.release();
            }
            if (rightEyeROI.area() > 0) {
                Mat rightEyeMat = new Mat(src, rightEyeROI);
                Mat enlargedRightEye = new Mat();
                Imgproc.resize(rightEyeMat, enlargedRightEye, new Size(rightEyeROI.width*1.2, rightEyeROI.height*1.2));
                Imgproc.resize(enlargedRightEye, rightEyeMat, rightEyeMat.size());
                rightEyeMat.copyTo(new Mat(result, rightEyeROI));
                rightEyeMat.release();
                enlargedRightEye.release();
            }
        }
        return result;
    }

    // 瘦脸：通过局部缩放模拟脸颊收缩
    private Mat applyFaceShrink(Mat src, List<Point> landmarks, float level) {
        // 简化实现：对脸颊区域进行向内收缩的仿射变换
        // 更优方案是使用 OpenCV 的薄板样条（TPS）或网格变形，但代码较复杂
        // 此处提供一种基于局部缩放的简化效果
        Mat result = src.clone();
        if (landmarks.size() >= 5) {
            Point leftCheek = landmarks.get(3); // 左脸颊估算点
            Point rightCheek = landmarks.get(4); // 右脸颊估算点
            int cheekRadius = (int) (Math.min(src.width(), src.height()) * 0.04);
            float shrinkFactor = 1.0f - 0.15f * level; // 缩放因子

            // 左脸颊区域向内收缩
            org.opencv.core.Rect leftCheekROI = clampRect(new org.opencv.core.Rect(
                    (int)(leftCheek.x - cheekRadius), (int)(leftCheek.y - cheekRadius),
                    2*cheekRadius, 2*cheekRadius), src.size());
            if (leftCheekROI.area() > 0) {
                Mat leftCheekMat = new Mat(src, leftCheekROI);
                Mat shrunken = new Mat();
                Size newSize = new Size(leftCheekROI.width * shrinkFactor, leftCheekROI.height * shrinkFactor);
                Imgproc.resize(leftCheekMat, shrunken, newSize);
                // 将缩小后的区域放回，置于ROI中心
                int startX = leftCheekROI.x + (leftCheekROI.width - (int)newSize.width)/2;
                int startY = leftCheekROI.y + (leftCheekROI.height - (int)newSize.height)/2;
                org.opencv.core.Rect insertROI = clampRect(new org.opencv.core.Rect(startX, startY, (int)newSize.width, (int)newSize.height), src.size());
                shrunken.copyTo(new Mat(result, insertROI));
                leftCheekMat.release();
                shrunken.release();
            }
            // 同理处理右脸颊...
        }
        return result;
    }

    // 根据人脸矩形估算关键点
    private List<Point> estimateFacialLandmarks(RectF faceRect) {
        List<Point> points = new ArrayList<>();
        float centerX = faceRect.centerX();
        float centerY = faceRect.centerY();
        float width = faceRect.width();
        float height = faceRect.height();

        // 左眼 (30%, 30%)
        points.add(new Point(faceRect.left + width * 0.3f, faceRect.top + height * 0.3f));
        // 右眼 (70%, 30%)
        points.add(new Point(faceRect.left + width * 0.7f, faceRect.top + height * 0.3f));
        // 鼻子 (50%, 50%)
        points.add(new Point(centerX, centerY));
        // 左嘴角 (35%, 70%)
        points.add(new Point(faceRect.left + width * 0.35f, faceRect.top + height * 0.7f));
        // 右嘴角 (65%, 70%)
        points.add(new Point(faceRect.left + width * 0.65f, faceRect.top + height * 0.7f));
        return points;
    }

    // 确保矩形区域在图像范围内
    private org.opencv.core.Rect clampRect(org.opencv.core.Rect rect, Size imgSize) {
        int x = Math.max(0, Math.min(rect.x, (int)imgSize.width - 1));
        int y = Math.max(0, Math.min(rect.y, (int)imgSize.height - 1));
        int width = Math.max(1, Math.min(rect.width, (int)imgSize.width - x));
        int height = Math.max(1, Math.min(rect.height, (int)imgSize.height - y));
        return new org.opencv.core.Rect(x, y, width, height);
    }
}