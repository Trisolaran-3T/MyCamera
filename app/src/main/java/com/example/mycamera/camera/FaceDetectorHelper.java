package com.example.mycamera.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FaceDetectorHelper {
    private static final String TAG = "FaceDetector";

    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private int inputSize = 128;  // BlazeFace模型输入尺寸为 128x128

    public FaceDetectorHelper(Context context) throws IOException {
        // 加载模型文件
        ByteBuffer modelBuffer = FileUtil.loadMappedFile(context, "blazeface.tflite");

        // 配置解释器选项
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        // 创建解释器
        interpreter = new Interpreter(modelBuffer, options);

        // 获取模型输入张量信息
        int[] inputShape = interpreter.getInputTensor(0).shape();
        DataType dataType = interpreter.getInputTensor(0).dataType();
        Log.d(TAG, "模型输入形状: " + java.util.Arrays.toString(inputShape));
        Log.d(TAG, "模型输入数据类型: " + dataType);

        // BlazeFace模型输入 [1, 128, 128, 3]
        if (inputShape.length >= 2) {
            inputSize = inputShape[1]; // 获取高度/宽度
        }
    }

    private List<RectF> previousRects = new ArrayList<>();

    private RectF applyKalmanFilter(RectF rect) {
        // 平滑系数，值越小越平滑
        final float ALPHA = 0.2f;

        if (previousRects.isEmpty()) {
            previousRects.add(rect);
            return rect;
        }

        RectF prev = previousRects.get(previousRects.size() - 1);

        float smoothedLeft   = ALPHA * rect.left   + (1 - ALPHA) * prev.left;
        float smoothedTop    = ALPHA * rect.top    + (1 - ALPHA) * prev.top;
        float smoothedRight  = ALPHA * rect.right  + (1 - ALPHA) * prev.right;
        float smoothedBottom = ALPHA * rect.bottom + (1 - ALPHA) * prev.bottom;

        RectF smoothedRect = new RectF(smoothedLeft, smoothedTop,
                smoothedRight, smoothedBottom);

        previousRects.add(rect);
        if (previousRects.size() > 2) previousRects.remove(0);

        return smoothedRect;
    }

    // 对Bitmap进行人脸检测
    public List<RectF> detectFaces(Bitmap bitmap) {
        int targetSize = inputSize;

        // 1. 缩放图像
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true);

        // 2. 创建输入缓冲区（float32类型）
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(targetSize * targetSize * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[targetSize * targetSize];
        resizedBitmap.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize);

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;

            inputBuffer.putFloat(r);
            inputBuffer.putFloat(g);
            inputBuffer.putFloat(b);
        }

        inputBuffer.rewind();

        // 3. 使用正确的输出类型（BlazeFace模型输出是float32）
        // 输出1: 边界框 [1, 896, 16]
        // 输出2: 置信度 [1, 896, 1]
        float[][][] boxes = new float[1][896][16];
        float[][][] scores = new float[1][896][1];

        // 4. 执行推理
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, boxes);
        outputs.put(1, scores);
        interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

        // 5. 解析结果
        List<RectF> faces = new ArrayList<>();
        float confidenceThreshold = 0.8f;
        int validBoxCount = 0;

        float bestScore = 0f;
        RectF bestRawRect = null;
        for (int i = 0; i < 896; i++) {
            float score = scores[0][i][0];
            if (score > confidenceThreshold) {
                float xmin = Math.abs(boxes[0][i][0]) % 1.0f;
                float ymin = Math.abs(boxes[0][i][1]) % 1.0f;
                float xmax = Math.abs(boxes[0][i][2]) % 1.0f;
                float ymax = Math.abs(boxes[0][i][3]) % 1.0f;

                if (score > confidenceThreshold) {
                    Log.d(TAG, "原始坐标[" + i + "]: xmin=" + xmin + ", ymin=" + ymin +
                            ", xmax=" + xmax + ", ymax=" + ymax + ", score=" + score);
                }

                if (xmin > xmax) { float temp = xmin; xmin = xmax; xmax = temp; }
                if (ymin > ymax) { float temp = ymin; ymin = ymax; ymax = temp; }

                int originalWidth = bitmap.getWidth();
                int originalHeight = bitmap.getHeight();

                float left = xmin * originalWidth;
                float top = ymin * originalHeight;
                float right = xmax * originalWidth;
                float bottom = ymax * originalHeight;

                if (left >= right || top >= bottom) continue;

                float boxWidth = right - left;
                float boxHeight = bottom - top;
                float widthRatio = boxWidth / originalWidth;
                float heightRatio = boxHeight / originalHeight;

                if (widthRatio >= 0.1f && widthRatio <= 0.6f &&
                        heightRatio >= 0.1f && heightRatio <= 0.8f) {

                    left = Math.max(0, Math.min(originalWidth, left));
                    top = Math.max(0, Math.min(originalHeight, top));
                    right = Math.max(0, Math.min(originalWidth, right));
                    bottom = Math.max(0, Math.min(originalHeight, bottom));

                    // 记录最高置信度的原始框
                    if (score > bestScore) {
                        bestScore = score;
                        bestRawRect = new RectF(left, top, right, bottom);
                    }

                    if (validBoxCount < 3) {
                        Log.d(TAG, "候选框" + validBoxCount + ": 尺寸=" + widthRatio + "x" + heightRatio +
                                ", 位置=(" + left + "," + top + ")-(" + right + "," + bottom + "), 置信度=" + score);
                    }
                    validBoxCount++;
                }
            }
        }
        // 对最佳框应用平滑
        if (bestRawRect != null) {
            RectF bestRect = applyKalmanFilter(bestRawRect);
            // 调整为正方形框
            float cx = (bestRect.left + bestRect.right) / 2;
            float cy = (bestRect.top + bestRect.bottom) / 2;
            float w = bestRect.width();
            float h = bestRect.height();
            float size = (w + h) / 2;
            float halfSize = size / 2;
            float newLeft = cx - halfSize;
            float newTop = cy - halfSize;
            float newRight = cx + halfSize;
            float newBottom = cy + halfSize;

            // 扩大框比例到 0.2
            float expand = 0.20f;
            float width = newRight - newLeft;
            float height = newBottom - newTop;
            newLeft -= width * expand;
            newRight += width * expand;
            newTop -= height * expand;
            newBottom += height * expand;

            // 确保不超出图像边界
            int bitmapWidth = bitmap.getWidth();
            int bitmapHeight = bitmap.getHeight();
            newLeft = Math.max(0, Math.min(bitmapWidth, newLeft));
            newTop = Math.max(0, Math.min(bitmapHeight, newTop));
            newRight = Math.max(0, Math.min(bitmapWidth, newRight));
            newBottom = Math.max(0, Math.min(bitmapHeight, newBottom));
            bestRect = new RectF(newLeft, newTop, newRight, newBottom);

            // 置信度阈值 0.8
            if (bestScore > 0.8f) {
                faces.add(bestRect);
                Log.d(TAG, "最终选择最佳框: 置信度=" + bestScore + ", 位置=" + bestRect);
            } else {
                Log.d(TAG, "置信度过低，丢弃框: " + bestScore);
            }
        }
        Log.d(TAG, "最终有效人脸数量: " + faces.size());

        return faces;
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
        }
//        if (gpuDelegate != null) {
//            gpuDelegate.close();
//        }
    }
}
