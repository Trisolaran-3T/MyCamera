package com.example.mycamera.camera;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class FaceDetectorHelper {
    private static final String TAG = "FaceDetector";

    // 模型配置
    private static final String MODEL_FILE = "blazeface.tflite";
    private static final int INPUT_SIZE = 128;
    private static final int TOTAL_ANCHORS = 896;

    private Interpreter tflite;
    private final Context context;
    private List<Anchor> anchors;

    // 人脸框历史记录（用于平滑和跟踪）
    private RectF lastDetectedFace = new RectF();
    private float lastDetectionConfidence = 0f;
    private int consecutiveZeroFrames = 0;
    private static final int MAX_ZERO_FRAMES = 3;  // 允许连续3帧无检测
    // 平滑参数
    private float[] smoothedFaceRect = new float[4]; // left, top, right, bottom
    private float[][] smoothedLandmarks;             // 平滑后的关键点
    private static final float SMOOTH_ALPHA = 0.4f;  // 平滑因子

    // 美颜相关
    private RectF lastValidFaceRect = new RectF();
    private boolean hasValidFace = false;
    private float[][] lastFaceLandmarks = null;  // 存储关键点
    private int detectFrameSkip = 5;      // 每5帧检测一次
    private int detectFrameCounter = 0;
    private List<RectF> lastDetectedFaces = new ArrayList<>();

    public FaceDetectorHelper(Context context) throws IOException {
        this.context = context;
        initInterpreter();
        generateAnchors();
    }

    private void initInterpreter() {
        try {
            MappedByteBuffer modelBuffer = loadModelFile(context, MODEL_FILE);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            tflite = new Interpreter(modelBuffer, options);
            Log.i(TAG, "TFLite 模型加载成功");

        } catch (Exception e) {
            Log.e(TAG, "TFLite 模型加载失败", e);
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelFile) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelFile);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        fileChannel.close();
        inputStream.close();
        return buffer;
    }

    // 生成896个Anchor（与模型输出匹配）
    private void generateAnchors() {
        anchors = new ArrayList<>(TOTAL_ANCHORS);

        // BlazeFace Anchor配置
        // 特征图大小: 8x8, 16x16, 16x16
        // 每个位置的Anchor数: 2, 2, 1
        int[] featureMapSizes = {8, 16, 16};
        float[] strides = {16.0f, 8.0f, 8.0f};
        int[] anchorsPerCell = {2, 2, 1};
        float[][] scales = {
                {1.0f, 2.0f},  // 特征图1: 2个尺度
                {1.0f, 2.0f},  // 特征图2: 2个尺度
                {2.0f}         // 特征图3: 1个尺度
        };

        int anchorCount = 0;
        for (int i = 0; i < featureMapSizes.length; i++) {
            int gridSize = featureMapSizes[i];
            float stride = strides[i];
            int cells = anchorsPerCell[i];

            for (int y = 0; y < gridSize; y++) {
                for (int x = 0; x < gridSize; x++) {
                    float centerX = (x + 0.5f) * stride;
                    float centerY = (y + 0.5f) * stride;

                    for (int a = 0; a < cells; a++) {
                        float size = scales[i][a];
                        float width = stride * size;
                        float height = stride * size;

                        anchors.add(new Anchor(centerX, centerY, width, height));
                        anchorCount++;

                        if (anchorCount >= TOTAL_ANCHORS) {
                            Log.d(TAG, "生成 " + anchors.size() + " 个 Anchor（精确匹配896个）");
                            return;
                        }
                    }
                }
            }
        }

        Log.d(TAG, "生成 " + anchors.size() + " 个 Anchor（期望896个）");
    }

    // 直接从Bitmap进行人脸检测
    public List<RectF> detectFacesFromBitmap(Bitmap rgbBitmap, int sensorOrientation, boolean isFrontCamera) {
        // 帧计数，每隔 detectFrameSkip 帧才真正检测
        detectFrameCounter++;
        if (detectFrameCounter % detectFrameSkip != 0) {
            return lastDetectedFaces;
        }

        List<RectF> detectedFaces = new ArrayList<>();
        if (tflite == null || rgbBitmap == null || rgbBitmap.isRecycled()) {
            return detectedFaces;
        }

        int bitmapWidth = rgbBitmap.getWidth();
        int bitmapHeight = rgbBitmap.getHeight();

        try {
            // 预处理 (缩放到128x128，归一化)
            float[][][][] inputTensor = preprocessImage(rgbBitmap);
            if (inputTensor == null) {
                Log.e(TAG, "图像预处理失败");
                return detectedFaces;
            }

            // 运行推理
            float[][][] outputBoxes = new float[1][TOTAL_ANCHORS][16];
            float[][][] outputScores = new float[1][TOTAL_ANCHORS][1];

            Object[] inputs = { inputTensor };
            java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
            outputs.put(0, outputBoxes);
            outputs.put(1, outputScores);

            tflite.runForMultipleInputsOutputs(inputs, outputs);

            float[][] boxes2D = outputBoxes[0];
            float[] scores1D = new float[TOTAL_ANCHORS];
            for (int i = 0; i < TOTAL_ANCHORS; i++) {
                scores1D[i] = outputScores[0][i][0];
            }

            // 后处理
            detectedFaces = postprocessResultsOptimized(boxes2D, scores1D, bitmapWidth, bitmapHeight);
            detectedFaces = postProcessFinalResults(detectedFaces, bitmapWidth, bitmapHeight);

            // 更新历史记录
            updateFaceHistory(detectedFaces, bitmapWidth, bitmapHeight);

            Log.d(TAG, String.format("检测完成: 发现%d个人脸", detectedFaces.size()));

        } catch (Exception e) {
            Log.e(TAG, "人脸检测异常", e);
        }

        if (detectedFaces != null) {
            lastDetectedFaces = detectedFaces;
        }
        return detectedFaces;
    }

    private List<RectF> postProcessFinalResults(List<RectF> faces, int imageWidth, int imageHeight) {
        if (faces == null || faces.isEmpty()) {
            return faces;
        }

        // 如果有多个框，使用综合评分选择最佳框
        if (faces.size() > 1) {
            RectF bestFace = null;
            float bestScore = -1;

            for (RectF face : faces) {
                // 计算综合分数 = 置信度权重 + 中心位置权重 + 宽高比权重
                float centerX = face.centerX();
                float centerY = face.centerY();

                // 中心位置分数
                float centerScore = 1.0f - (Math.abs(centerX - imageWidth/2) / (imageWidth/2));
                centerScore += 1.0f - (Math.abs(centerY - imageHeight/2) / (imageHeight/2));
                centerScore /= 2.0f;

                // 宽高比分数
                float aspectRatio = face.width() / face.height();
                float aspectScore = 1.0f - Math.min(Math.abs(aspectRatio - 0.75f) / 0.5f, 1.0f);

                // 尺寸合理性分数
                float areaRatio = (face.width() * face.height()) / (imageWidth * imageHeight);
                float sizeScore = 0;
                if (areaRatio > 0.1f && areaRatio < 0.6f) { // 面积占10%-60%比较合理
                    sizeScore = 1.0f - Math.abs(areaRatio - 0.3f) / 0.3f;
                }

                // 综合分数
                float totalScore = centerScore * 0.4f + aspectScore * 0.3f + sizeScore * 0.3f;

                if (totalScore > bestScore) {
                    bestScore = totalScore;
                    bestFace = face;
                }
            }

            List<RectF> result = new ArrayList<>();
            if (bestFace != null && bestScore > 0.5f) { // 设置综合分数阈值
                result.add(bestFace);
            }
            return result;
        }

        return faces;
    }

    private List<RectF> postprocessResultsOptimized(float[][] boxes, float[] scores,
                                                    int imageWidth, int imageHeight) {
        List<FaceCandidate> candidates = new ArrayList<>();

        int highConfidenceCount = 0;
        int filteredBySize = 0;
        int filteredByAspect = 0;
        int filteredByDecode = 0;
        int filteredByScore = 0;

        // 适当降低阈值，提高小脸召回率
        float confidenceThreshold = 0.25f;

        for (int i = 0; i < TOTAL_ANCHORS && i < anchors.size(); i++) {
            float rawScore = scores[i];
            float confidence = sigmoid(rawScore);
            if (confidence < confidenceThreshold) {
                filteredByScore++;
                continue;
            }
            highConfidenceCount++;

            Anchor anchor = anchors.get(i);
            float[] box = boxes[i];

            float dx = box[0];
            float dy = box[1];
            float scale_w = box[2];
            float scale_h = box[3];

            // 中心点偏
            float cx_norm = (anchor.centerX + dx) / INPUT_SIZE;
            float cy_norm = (anchor.centerY + dy) / INPUT_SIZE;

            // 宽高缩放：使用 sigmoid 限制范围，并限制最大放大倍数为 3 倍
            float maxScale = 3.0f;
            float scale_w_sigmoid = sigmoid(scale_w);
            float scale_h_sigmoid = sigmoid(scale_h);
            float w_norm = (anchor.width * scale_w_sigmoid * maxScale) / INPUT_SIZE;
            float h_norm = (anchor.height * scale_h_sigmoid * maxScale) / INPUT_SIZE;

            // 尺寸下限
            float minSizeRatio = 0.02f;
            w_norm = clamp(w_norm, minSizeRatio, 1.0f);
            h_norm = clamp(h_norm, minSizeRatio, 1.0f);

            // 确保中心点范围
            cx_norm = clamp(cx_norm, 0.0f, 1.0f);
            cy_norm = clamp(cy_norm, 0.0f, 1.0f);

            // 计算边界框
            float xmin_norm = clamp(cx_norm - w_norm / 2, 0.0f, 1.0f);
            float ymin_norm = clamp(cy_norm - h_norm / 2, 0.0f, 1.0f);
            float xmax_norm = clamp(cx_norm + w_norm / 2, 0.0f, 1.0f);
            float ymax_norm = clamp(cy_norm + h_norm / 2, 0.0f, 1.0f);

            if (xmax_norm <= xmin_norm || ymax_norm <= ymin_norm) {
                filteredByDecode++;
                continue;
            }

            // 映射到实际图像像素坐标
            float left = xmin_norm * imageWidth;
            float top = ymin_norm * imageHeight;
            float right = xmax_norm * imageWidth;
            float bottom = ymax_norm * imageHeight;
            if (right <= left || bottom <= top) {
                filteredByDecode++;
                continue;
            }

            float boxWidth = right - left;
            float boxHeight = bottom - top;

            // 尺寸过滤：最小 2% 图像短边，最大 95%
            float minSize = Math.min(imageWidth, imageHeight) * minSizeRatio;
            float maxSize = Math.min(imageWidth, imageHeight) * 0.95f;
            if (boxWidth < minSize || boxHeight < minSize || boxWidth > maxSize || boxHeight > maxSize) {
                filteredBySize++;
                continue;
            }

            // 宽高比过滤
            float aspectRatio = boxWidth / boxHeight;
            if (aspectRatio < 0.3f || aspectRatio > 2.5f) {
                filteredByAspect++;
                continue;
            }

            RectF faceRect = new RectF(left, top, right, bottom);

            // 提取关键点
            float[][] landmarks = null;
            if (box.length >= 16) {
                landmarks = extractLandmarksFromBox(box, imageWidth, imageHeight);
            }

            candidates.add(new FaceCandidate(faceRect, confidence, i, landmarks));
        }

        // NMS 抑制
        float nmsThreshold = 0.5f;
        List<FaceCandidate> nmsResults = nonMaximumSuppression(candidates, nmsThreshold);

        List<RectF> results = new ArrayList<>();
        for (FaceCandidate candidate : nmsResults) {
            results.add(candidate.rect);
            if (candidate.confidence > lastDetectionConfidence) {
                lastDetectedFace = new RectF(candidate.rect);
                lastDetectionConfidence = candidate.confidence;
                if (candidate.landmarks != null) {
                    lastFaceLandmarks = candidate.landmarks;
                }
            }
        }

        return results;
    }

    // 更新人脸历史记录
    private void updateFaceHistory(List<RectF> detectedFaces, int imageWidth, int imageHeight) {
        if (detectedFaces != null && !detectedFaces.isEmpty()) {
            // 取第一个框
            RectF bestFace = detectedFaces.get(0);
            lastValidFaceRect = new RectF(bestFace);
            hasValidFace = true;
            consecutiveZeroFrames = 0;

            // 如果已经有真实关键点（从模型提取），则保留，否则估算
            if (lastFaceLandmarks == null) {
                lastFaceLandmarks = estimateFaceLandmarks(bestFace, imageWidth, imageHeight);
            }

            // 对矩形和关键点进行平滑
            smoothDetection(bestFace, lastFaceLandmarks);
        } else {
            consecutiveZeroFrames++;
            if (consecutiveZeroFrames > MAX_ZERO_FRAMES) {
                hasValidFace = false;
                lastFaceLandmarks = null;
            }
        }
    }

    // 估算人脸关键点，用于美颜
    private float[][] estimateFaceLandmarks(RectF faceRect, int imageWidth, int imageHeight) {
        if (faceRect == null || faceRect.isEmpty()) {
            return null;
        }

        float width = faceRect.width();
        float height = faceRect.height();
        float left = faceRect.left;
        float top = faceRect.top;

        // 5个关键点：左眼、右眼、鼻子、左嘴角、右嘴角
        float[][] landmarks = new float[5][2];

        // 左眼 (30%, 40%) - 更准确的位置
        landmarks[0][0] = left + width * 0.3f;
        landmarks[0][1] = top + height * 0.4f;

        // 右眼 (70%, 40%)
        landmarks[1][0] = left + width * 0.7f;
        landmarks[1][1] = top + height * 0.4f;

        // 鼻子 (50%, 60%)
        landmarks[2][0] = left + width * 0.5f;
        landmarks[2][1] = top + height * 0.6f;

        // 左嘴角 (35%, 85%)
        landmarks[3][0] = left + width * 0.35f;
        landmarks[3][1] = top + height * 0.85f;

        // 右嘴角 (65%, 85%)
        landmarks[4][0] = left + width * 0.65f;
        landmarks[4][1] = top + height * 0.85f;

        return landmarks;
    }

    // 从模型输出的 box 数组中提取 6 个关键点（取前 5 个用于美颜）左眼、右眼、鼻子、左嘴角、右嘴角、中心点
    private float[][] extractLandmarksFromBox(float[] box, int imageWidth, int imageHeight) {
        int numPoints = 5;   // 美颜只需要 5 个点
        float[][] landmarks = new float[numPoints][2];
        // 关键点从索引 4 开始，每 2 个值为一个点 (x, y)
        for (int i = 0; i < numPoints; i++) {
            float x = box[4 + i * 2];
            float y = box[4 + i * 2 + 1];
            // 根据模型实际情况，这里假设输出已经是归一化坐标 [0,1]
            // 如果不是，可能需要调整（例如先 clamp 到 [0,1]）
            x = clamp(x, 0.0f, 1.0f);
            y = clamp(y, 0.0f, 1.0f);
            landmarks[i][0] = x * imageWidth;
            landmarks[i][1] = y * imageHeight;
        }
        return landmarks;
    }

    // 获取人脸关键点
    public float[][] getFaceLandmarks() {
        return lastFaceLandmarks;
    }

    // 获取人脸矩形（包含平滑处理）
    public RectF getFaceRect() {
        if (!hasValidFace || lastValidFaceRect.isEmpty()) {
            return null;
        }

        // 应用简单平滑
        RectF smoothedRect = new RectF(lastValidFaceRect);

        // 可以在这里添加卡尔曼滤波等更高级的平滑算法
        if (!lastDetectedFace.isEmpty()) {
            // 简单加权平均
            float alpha = 0.3f; // 平滑因子
            smoothedRect.left = alpha * lastDetectedFace.left + (1 - alpha) * lastValidFaceRect.left;
            smoothedRect.top = alpha * lastDetectedFace.top + (1 - alpha) * lastValidFaceRect.top;
            smoothedRect.right = alpha * lastDetectedFace.right + (1 - alpha) * lastValidFaceRect.right;
            smoothedRect.bottom = alpha * lastDetectedFace.bottom + (1 - alpha) * lastValidFaceRect.bottom;
        }

        return smoothedRect;
    }

    // 获取最后一次有效的人脸框，用于OpenCV美颜
    public RectF getLastValidFaceRect() {
        return new RectF(lastValidFaceRect);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float sigmoid(float x) {
        // 数值稳定的sigmoid实现
        if (x >= 0) {
            return 1.0f / (1.0f + (float) Math.exp(-x));
        } else {
            float expX = (float) Math.exp(x);
            return expX / (1.0f + expX);
        }
    }

    private List<FaceCandidate> nonMaximumSuppression(List<FaceCandidate> candidates, float threshold) {
        if (candidates.isEmpty()) return candidates;

        candidates.sort((a, b) -> Float.compare(b.confidence, a.confidence));

        List<FaceCandidate> selected = new ArrayList<>();
        boolean[] suppressed = new boolean[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {
            if (suppressed[i]) continue;
            FaceCandidate current = candidates.get(i);
            selected.add(current);

            for (int j = i + 1; j < candidates.size(); j++) {
                if (suppressed[j]) continue;
                FaceCandidate other = candidates.get(j);
                float iou = calculateIoU(current.rect, other.rect);
                if (iou > threshold) {
                    suppressed[j] = true;
                }
            }
        }
        return selected;
    }

    private float calculateIoU(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        if (interRight <= interLeft || interBottom <= interTop) {
            return 0.0f;
        }

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);
        float unionArea = areaA + areaB - interArea;

        return interArea / unionArea;
    }

    // BlazeFace预处理图像：缩放到128x128，归一化像素值到[-1, 1]
    private float[][][][] preprocessImage(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "预处理输入Bitmap为null");
            return null;
        }

        // 使用TFLite Support Library进行标准化预处理
        try {
            TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
            tensorImage.load(bitmap);

            ImageProcessor imageProcessor = new ImageProcessor.Builder()
                    .add(new ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new NormalizeOp(127.5f, 127.5f)) // 将 [0,255] 归一化到 [-1, 1]
                    .build();

            tensorImage = imageProcessor.process(tensorImage);

            // 获取处理后的数据
            float[] floatArray = tensorImage.getTensorBuffer().getFloatArray();

            // 创建4D张量 [1, 128, 128, 3]
            float[][][][] inputArray = new float[1][INPUT_SIZE][INPUT_SIZE][3];

            // 手动填充数据到4D数组
            int index = 0;
            for (int y = 0; y < INPUT_SIZE; y++) {
                for (int x = 0; x < INPUT_SIZE; x++) {
                    for (int c = 0; c < 3; c++) {
                        if (index < floatArray.length) {
                            inputArray[0][y][x][c] = floatArray[index++];
                        } else {
                            Log.w(TAG, "预处理数据长度不足");
                            inputArray[0][y][x][c] = 0f;
                        }
                    }
                }
            }

            return inputArray;

        } catch (Exception e) {
            Log.e(TAG, "图像预处理异常", e);
            return null;
        }
    }

    // 对检测框和关键点进行指数移动平均平滑
    private void smoothDetection(RectF newRect, float[][] newLandmarks) {
        if (newRect == null) return;
        if (smoothedFaceRect[0] == 0 && smoothedFaceRect[1] == 0) {
            // 首次赋值
            smoothedFaceRect[0] = newRect.left;
            smoothedFaceRect[1] = newRect.top;
            smoothedFaceRect[2] = newRect.right;
            smoothedFaceRect[3] = newRect.bottom;
            if (newLandmarks != null) {
                smoothedLandmarks = new float[newLandmarks.length][2];
                for (int i = 0; i < newLandmarks.length; i++) {
                    smoothedLandmarks[i][0] = newLandmarks[i][0];
                    smoothedLandmarks[i][1] = newLandmarks[i][1];
                }
            }
        } else {
            smoothedFaceRect[0] = SMOOTH_ALPHA * newRect.left + (1 - SMOOTH_ALPHA) * smoothedFaceRect[0];
            smoothedFaceRect[1] = SMOOTH_ALPHA * newRect.top + (1 - SMOOTH_ALPHA) * smoothedFaceRect[1];
            smoothedFaceRect[2] = SMOOTH_ALPHA * newRect.right + (1 - SMOOTH_ALPHA) * smoothedFaceRect[2];
            smoothedFaceRect[3] = SMOOTH_ALPHA * newRect.bottom + (1 - SMOOTH_ALPHA) * smoothedFaceRect[3];
            if (newLandmarks != null && smoothedLandmarks != null && newLandmarks.length == smoothedLandmarks.length) {
                for (int i = 0; i < newLandmarks.length; i++) {
                    smoothedLandmarks[i][0] = SMOOTH_ALPHA * newLandmarks[i][0] + (1 - SMOOTH_ALPHA) * smoothedLandmarks[i][0];
                    smoothedLandmarks[i][1] = SMOOTH_ALPHA * newLandmarks[i][1] + (1 - SMOOTH_ALPHA) * smoothedLandmarks[i][1];
                }
            } else if (newLandmarks != null) {
                smoothedLandmarks = new float[newLandmarks.length][2];
                for (int i = 0; i < newLandmarks.length; i++) {
                    smoothedLandmarks[i][0] = newLandmarks[i][0];
                    smoothedLandmarks[i][1] = newLandmarks[i][1];
                }
            }
        }
    }

    // 获取平滑后的人脸矩形，供美颜使用
    public RectF getSmoothedFaceRect() {
        if (!hasValidFace || (smoothedFaceRect[0] == 0 && smoothedFaceRect[1] == 0)) {
            return null;
        }
        return new RectF(smoothedFaceRect[0], smoothedFaceRect[1], smoothedFaceRect[2], smoothedFaceRect[3]);
    }

    // 获取平滑后的关键点，供美颜使用
    public float[][] getSmoothedLandmarks() {
        return smoothedLandmarks;
    }

    // 美颜专用接口
    public RectF getFaceRectForBeauty() {
        return getSmoothedFaceRect();
    }

    public float[][] getFaceLandmarksForBeauty() {
        return getSmoothedLandmarks();
    }

    // 内部类
    private static class Anchor {
        float centerX, centerY, width, height;
        Anchor(float cx, float cy, float w, float h) {
            this.centerX = cx;
            this.centerY = cy;
            this.width = w;
            this.height = h;
        }
    }

    private static class FaceCandidate {
        RectF rect;
        float confidence;
        int index;
        float[][] landmarks;

        FaceCandidate(RectF rect, float confidence, int index, float[][] landmarks) {
            this.rect = rect;
            this.confidence = confidence;
            this.index = index;
            this.landmarks = landmarks;
        }
    }

    public void close() {
        if (tflite != null) {
            tflite.close();
            tflite = null;
        }
    }
}