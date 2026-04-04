package com.example.mycamera.camera;

import android.Manifest;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.mycamera.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MyCameraManager {
    private static final String TAG = "CameraManager";

    private Context context;
    private TextureView textureView;
    private FrameLayout frameLayout;
    private ImageView imageFocus;
    private ImageView imageView;

    // Camera2相关
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest.Builder captureRequestBuilder;
    private ImageReader imageReader;
    // 用于人脸检测的预览ImageReader
    private ImageReader previewImageReader;
    private Size previewSizeForDetection;

    // 后台线程
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // 存储选择的摄像头ID
    private String cameraId;
    // 预览尺寸
    private Size previewSize;
    private boolean isRearCamera = true; // 默认后置摄像头
    private boolean isFlash = false;
    private boolean isMute = false;
    private boolean isAutoFocus = true;
    private int sensorArrayWidth;
    private int sensorArrayHeight;
    private int imageCount = 0;
    private MediaPlayer mediaPlayer;

    private FaceDetectorHelper faceDetector;
    private HandlerThread inferenceThread;
    private Handler inferenceHandler;
    private FaceOverlayView overlayView;
    private boolean faceDetectionEnabled = true; // 控制是否开启人脸检测

    // 美颜
    private BeautyProcessor beautyProcessor;
    private TextureView beautyTextureView;
    private SurfaceTexture beautySurfaceTexture;
    private Surface beautySurface;
    private HandlerThread beautyThread;
    private Handler beautyHandler;
    private List<RectF> lastDetectedFaces;  // 记录上一次检测到的人脸
    private boolean isPreviewBeautyEnabled = true;  // 控制美颜预览开关
    // 美颜队列控制
    private AtomicBoolean isBeautyProcessing = new AtomicBoolean(false);
    private int beautyPreviewFrameSkip = 1;  // 美颜帧跳帧设置
    private int frameCounter = 0;  // 帧计数器


    public MyCameraManager(Context context, TextureView textureView, FrameLayout frameLayout,
                           ImageView imageFocus, ImageView imageView, FaceOverlayView overlayView,
                           BeautyManager beautyManager, TextureView beautyTextureView) {
        this.context = context;
        this.textureView = textureView;
        this.frameLayout = frameLayout;
        this.imageFocus = imageFocus;
        this.imageView = imageView;
        this.overlayView = overlayView;
        this.beautyProcessor = beautyManager.getBeautyProcessor();
        this.beautyTextureView = beautyTextureView;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        startBackgroundThread();
        initFaceDetector();
        startBeautyThread();
        init();
    }

    private void init() {
        // 预览View的状态监听
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        // 对TextureView注册touch事件实现
        textureView.setOnTouchListener(touchListener);
        if (beautyTextureView != null) {
            initBeautyTextureView();
        }
    }

    private void initFaceDetector() {
        try {
            faceDetector = new FaceDetectorHelper(context);
            startInferenceThread();
        } catch (IOException e) {
            Log.e(TAG, "加载人脸检测模型失败", e);
            faceDetectionEnabled = false;
        }
    }

    /**
     * 初始化美颜TextureView
     */
    private void initBeautyTextureView() {
        if (beautyTextureView == null) return;

        Log.d(TAG, "初始化美颜TextureView");
        beautyTextureView.setVisibility(View.VISIBLE);
        beautyTextureView.requestLayout();

        // 强制立即创建SurfaceTexture（如果还没有）
        if (!beautyTextureView.isAvailable()) {
            // 手动创建一个SurfaceTexture并设置（不推荐，但可以触发回调）
            // 更好的办法：监听，但这里我们直接使用监听器，并在回调中立即创建Surface
        }

        beautyTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                Log.d(TAG, String.format("onSurfaceTextureAvailable: 尺寸%dx%d", width, height));
                beautySurfaceTexture = surface;
                if (beautySurface != null) {
                    beautySurface.release();
                }
                beautySurface = new Surface(beautySurfaceTexture);
                // 设置缓冲区大小（如果预览尺寸已知）
                if (previewSize != null) {
                    surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                }
                Log.d(TAG, "美颜Surface已创建");
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                beautySurfaceTexture = surface;
                if (beautySurface != null) {
                    beautySurface.release();
                }
                beautySurface = new Surface(beautySurfaceTexture);
                if (previewSize != null) {
                    surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (beautySurface != null) {
                    beautySurface.release();
                    beautySurface = null;
                }
                beautySurfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });

        // 如果TextureView已经可用，立即获取SurfaceTexture
        if (beautyTextureView.isAvailable()) {
            SurfaceTexture st = beautyTextureView.getSurfaceTexture();
            if (st != null) {
                beautySurfaceTexture = st;
                beautySurface = new Surface(st);
                Log.d(TAG, "立即创建美颜Surface成功");
            }
        }
    }

    private View.OnTouchListener touchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && !isAutoFocus) {
                float x = event.getX();
                float y = event.getY();

                // 获取TextureView相对于屏幕的绝对位置
                int[] location = new int[2];
                textureView.getLocationOnScreen(location);
                int textureViewX = location[0];
                int textureViewY = location[1];

                // 获取 imageFocus 父布局的屏幕位置
                View parent = (View) imageFocus.getParent();
                int[] parentLocation = new int[2];
                parent.getLocationOnScreen(parentLocation);

                // 显示并移动对焦框，减去父布局偏移，得到相对坐标
                imageFocus.setVisibility(View.VISIBLE);
                imageFocus.setX(textureViewX + x - imageFocus.getWidth() / 2 - parentLocation[0]);
                imageFocus.setY(textureViewY + y - imageFocus.getHeight() / 2 - parentLocation[1]);

                // 取消之前的动画，重置透明度
                imageFocus.animate().cancel();
                imageFocus.setAlpha(1f);
                // 使用动画渐隐后隐藏图片
                imageFocus.animate().alpha(0f).setDuration(500)
                        .withEndAction(() -> imageFocus.setVisibility(View.GONE))
                        .start();

                // 对焦声音
//                    mediaPlayer = MediaPlayer.create(context, R.raw.y15004);
//                    mediaPlayer.start();

                // 对焦区域的大小 (50x50)
                int focusSize = 50;

                // 根据屏幕坐标转换为传感器坐标
                int focusX = (int) (((textureView.getWidth() - x) / textureView.getWidth()) * sensorArrayWidth);
                int focusY = (int) ((y / textureView.getHeight()) * sensorArrayHeight);

                focusX = Math.max(focusSize / 2, Math.min(sensorArrayWidth - focusSize / 2, focusX));
                focusY = Math.max(focusSize / 2, Math.min(sensorArrayHeight - focusSize / 2, focusY));

                Log.i(TAG, "onTouch: focusX:" + focusX + ", focusY:" + focusY);
                // 创建MeteringRectangle
                MeteringRectangle focusArea = new MeteringRectangle(focusX - focusSize / 2, focusY - focusSize / 2, focusSize, focusSize, MeteringRectangle.METERING_WEIGHT_MAX);

                // 手动对焦
                if (previewRequestBuilder != null) {
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);

                    // 更新预览请求的对焦区域
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
                    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    try {
                        // 使用单次捕获而不是重复请求
                        cameraCaptureSession.capture(previewRequestBuilder.build(),
                                new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                        // 对焦完成后，自动重置对焦触发状态
                                        if (previewRequestBuilder != null && !isAutoFocus) {
                                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                                            try {
                                                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                                            } catch (CameraAccessException e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    }
                                }, backgroundHandler);
                    } catch (CameraAccessException e) {
                        throw new RuntimeException(e);
                    }

                    updatePreview();
                    Log.i(TAG, "onTouch: update focus");
                }

                if (captureRequestBuilder != null) {
                    // 更新拍摄请求的对焦区域，以便拍摄时使用
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                }
            }
            return true;
        }
    };

    public TextureView.SurfaceTextureListener getSurfaceTextureListener() {
        return surfaceTextureListener;
    }

    // TextureView的SurfaceTexture监听器
    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            // 纹理可用时，若相机已打开则打开
            if (cameraDevice != null) {
                openCamera();
            }
            // 重新应用矩阵
            updateTextureViewTransform();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            // 尺寸变化时重新应用矩阵，保证居中裁剪效果
            updateTextureViewTransform();
            updatePreview();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            // 无需处理
        }
    };

    private void updateTextureViewTransform() {
        if (textureView == null || previewSize == null) {
            Log.w(TAG, "updateTextureViewTransform: textureView或previewSize为null");
            return;
        }
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        if (surfaceTexture == null) {
            Log.w(TAG, "updateTextureViewTransform: SurfaceTexture为null");
            return;
        }

        // 1. 获取视图和预览的实际尺寸
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();

        if (viewWidth == 0 || viewHeight == 0) {
            Log.d(TAG, "updateTextureViewTransform: 视图尺寸为0，等待下一次回调");
            return;
        }

        // 2. 4:3 比例
        float targetAspectRatio = 4.0f / 3.0f;

        // 3. 计算缩放因子 (Scale)
        // 我们要让 4:3 的画面完整显示在屏幕内，所以需要计算是“宽度受限”还是“高度受限”
        float viewAspectRatio = (float) viewWidth / viewHeight;

        float scaleX, scaleY;

        if (viewAspectRatio > targetAspectRatio) {
            scaleX = (float) viewHeight / targetAspectRatio;
            scaleX = scaleX / viewWidth;
            scaleY = 1.0f;
        } else {
            scaleX = 1.0f;
            scaleY = (float) viewWidth * targetAspectRatio; // 4:3应有的高度
            scaleY = scaleY / viewHeight; // 这个高度占屏幕高度的比例
        }

        // 4. 构建 Matrix 矩阵
        Matrix matrix = new Matrix();

        float translateX = viewWidth * (1.0f - scaleX) / 2.0f;
        float translateY = viewHeight * (1.0f - scaleY) / 2.0f;

        // 设置矩阵：先缩放，再平移（实现居中）
        matrix.setScale(scaleX, scaleY);
        matrix.postTranslate(translateX, translateY);

        // 5. 应用变换
        textureView.setTransform(matrix);

        Log.d(TAG, String.format("强制4:3居中: 视图[%dx%d] 比例%.2f, 应用缩放[%.3f x %.3f] 平移[%.1f, %.1f]",
                viewWidth, viewHeight, viewAspectRatio, scaleX, scaleY, translateX, translateY));
    }

    // 预览帧的ImageReader回调
    private final ImageReader.OnImageAvailableListener previewImageAvailableListener = reader -> {
        long frameStartTime = System.currentTimeMillis();
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }

        try {
            // 检查图片格式
            int imageFormat = ImageFormat.UNKNOWN;
            try {
                imageFormat = image.getFormat();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Image already closed before processing", e);
                if (image != null) image.close();
                return;
            }

            if (imageFormat != ImageFormat.YUV_420_888) {
                if (image != null) image.close();
                return;
            }

            // 提取YUV数据
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] imageData = null;
            try {
                imageData = extractYUVDataFromImage(image);
            } catch (Exception e) {
                Log.e(TAG, "提取YUV数据异常", e);
            } finally {
                image.close();
            }

            if (imageData == null) {
                return;
            }

            // 统一转换
            int sensorOrientation = 0;
            try {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            } catch (CameraAccessException e) {
                Log.e(TAG, "获取Sensor方向失败", e);
            }

            // 将YUV转换为Bitmap
            Bitmap highQualityBitmap = convertYUVDataToBitmapOptimized(imageData, width, height, sensorOrientation, !isRearCamera);

            if (highQualityBitmap == null) {
                return; // 转换失败放弃此帧
            }

            // 人脸检测
            if (faceDetectionEnabled && faceDetector != null && highQualityBitmap != null) {
                Bitmap finalBitmapForDetection = highQualityBitmap;
                int finalSensorOrientation = sensorOrientation;
                backgroundHandler.post(() -> {
                    try {
                        // 调用新的高性能检测接口
                        List<RectF> faces = faceDetector.detectFacesFromBitmap(
                                finalBitmapForDetection, finalSensorOrientation, !isRearCamera);

                        // 更新人脸框显示
                        if (faces != null) {
                            if (!faces.isEmpty()) {
                                // detectFacesFromBitmap返回的坐标是基于传入Bitmap的尺寸
                                List<RectF> facesToDraw = mapFaceCoordinatesToTextureView(
                                        faces, finalBitmapForDetection.getWidth(), finalBitmapForDetection.getHeight());
                                new Handler(context.getMainLooper()).post(() -> {
                                    if (overlayView != null) {
                                        overlayView.setFaces(facesToDraw);
                                    }
                                });

                                // 保存检测到的人脸矩形，用于美颜
                                lastDetectedFaces = faces;
                            } else {
                                new Handler(context.getMainLooper()).post(() -> {
                                    if (overlayView != null) {
                                        overlayView.setFaces(new ArrayList<>());
                                    }
                                });
                                lastDetectedFaces = null;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "人脸检测异常", e);
                    }
                });
            }

            // 异步美颜处理
            if (isPreviewBeautyEnabled && beautyHandler != null && highQualityBitmap != null) {
                if (isBeautyProcessing.get()) {
                    // 如果还在处理，回收本帧的Bitmap，避免内存泄漏
                    highQualityBitmap.recycle();
                    return;
                }
                isBeautyProcessing.set(true);

                // 为美颜任务创建Bitmap的副本
                Bitmap bitmapCopy = highQualityBitmap.copy(highQualityBitmap.getConfig(), true);
                // 原图回收，副本用于美颜
                highQualityBitmap.recycle();

                RectF faceRectForBeauty = (lastDetectedFaces != null && !lastDetectedFaces.isEmpty()) ? lastDetectedFaces.get(0) : null;

                BeautyProcessTask task = new BeautyProcessTask(
                        bitmapCopy, // 传入Bitmap副本
                        sensorOrientation,
                        !isRearCamera,
                        faceRectForBeauty
                );
                beautyHandler.post(task);
            } else {
                // 如果不进行美颜，回收Bitmap
                if (highQualityBitmap != null && !highQualityBitmap.isRecycled()) {
                    highQualityBitmap.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "预览处理异常", e);
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Exception e) {
                }
            }
        }
    };

    // 从Image中提取YUV数据，输出NV21格式
    private byte[] extractYUVDataFromImage(Image image) {
        if (image == null || image.getFormat() != ImageFormat.YUV_420_888) {
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        int uRowStride = uPlane.getRowStride();
        int uPixelStride = uPlane.getPixelStride();
        int vRowStride = vPlane.getRowStride();
        int vPixelStride = vPlane.getPixelStride();

        // NV21 大小 = Y(宽×高) + VU(宽×高/2)
        int ySize = width * height;
        int uvSize = width * height / 2;
        byte[] nv21 = new byte[ySize + uvSize];

        try {
            // 提取Y平面
            extractYPlane(yBuffer, nv21, 0, width, height, yRowStride, yPixelStride);

            // 提取VU交错（V在前，U在后）
            int uvOffset = ySize;
            extractVUPlanesAsNV21(vBuffer, uBuffer, nv21, uvOffset, width, height,
                    vRowStride, uRowStride, vPixelStride, uPixelStride);

            return nv21;
        } catch (IllegalStateException e) {
            Log.e(TAG, "缓冲区不可访问，图像可能已关闭", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "提取YUV数据异常", e);
            return null;
        }
    }

    // 提取V和U平面并交错存储为NV21格式
    private void extractVUPlanesAsNV21(ByteBuffer vBuffer, ByteBuffer uBuffer, byte[] output, int offset,
                                       int width, int height, int vRowStride, int uRowStride,
                                       int vPixelStride, int uPixelStride) {
        vBuffer.rewind();
        uBuffer.rewind();

        int uvWidth = width / 2;
        int uvHeight = height / 2;

        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int outputIndex = offset + (row * width) + (col * 2);

                // V分量（在前）
                int vIndex = row * vRowStride + col * vPixelStride;
                // U分量（在后）
                int uIndex = row * uRowStride + col * uPixelStride;

                if (vIndex < vBuffer.remaining() && uIndex < uBuffer.remaining()) {
                    output[outputIndex] = vBuffer.get(vIndex);     // V
                    output[outputIndex + 1] = uBuffer.get(uIndex); // U
                } else {
                    Log.w(TAG, String.format("VU索引越界: vIndex=%d, uIndex=%d", vIndex, uIndex));
                }
            }
        }
    }

    // 提取Y平面
    private void extractYPlane(ByteBuffer buffer, byte[] output, int offset,
                               int width, int height, int rowStride, int pixelStride) {

        buffer.rewind();

        // 如果行步长等于宽度，直接复制
        if (rowStride == width && pixelStride == 1) {
            buffer.get(output, offset, width * height);
            return;
        }

        // 否则逐行复制
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                output[offset + row * width + col] = buffer.get(row * rowStride + col * pixelStride);
            }
        }
    }

    // YUV(NV21)转Bitmap
    private Bitmap convertYUVDataToBitmapOptimized(byte[] yuvData, int width, int height,
                                                   int sensorOrientation, boolean isFrontCamera) {
        if (yuvData == null || yuvData.length < width * height * 3 / 2) {
            Log.e(TAG, "convertYUVDataToBitmapOptimized: YUV数据无效或长度不足");
            return null;
        }
        Bitmap bitmap = null;

        try {
            // 创建ARGB_8888格式的Bitmap
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] argbArray = new int[width * height];

            // YUV(NV21) 转 ARGB
            convertNV21ToARGB(yuvData, argbArray, width, height);

            // 将转换后的像素数组设置到Bitmap
            bitmap.setPixels(argbArray, 0, width, 0, 0, width, height);

            // 应用传感器旋转和前置摄像头镜像
            bitmap = applyBitmapTransform(bitmap, sensorOrientation, isFrontCamera);

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "convertYUVDataToBitmapOptimized 转换异常", e);
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return null;
        }
    }

    //将 NV21 (YUV420SP) 格式的字节数组转换为 ARGB 像素数组。
    private void convertNV21ToARGB(byte[] nv21, int[] argb, int width, int height) {
        final int frameSize = width * height;
        final int uvStart = frameSize;

        int y, u, v;
        int r, g, b;
        int uvRowStart;

        for (int row = 0; row < height; row++) {
            int rowIndex = row * width;
            uvRowStart = uvStart + (row >> 1) * width;

            for (int col = 0; col < width; col++) {
                int pixelIndex = rowIndex + col;

                y = nv21[pixelIndex] & 0xFF;

                int uvIndex = uvRowStart + (col & ~1);

                if (uvIndex + 1 >= nv21.length) {
                    u = 128;
                    v = 128;
                } else {
                    v = nv21[uvIndex] & 0xFF;
                    u = nv21[uvIndex + 1] & 0xFF;
                }

                // 转换为YUV标准范围
                y = Math.max(0, y - 16);
                u = u - 128;
                v = v - 128;

                // 使用更准确的转换公式
                r = y + ((1436 * v) >> 10);
                g = y - ((354 * u + 732 * v) >> 10);
                b = y + ((1814 * u) >> 10);

                // 钳制RGB值
                r = clampRGB(r);
                g = clampRGB(g);
                b = clampRGB(b);

                argb[pixelIndex] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }


    // 将RGB值限制在0-255范围内
    private int clampRGB(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    // 应用旋转和镜像变换。
    private Bitmap applyBitmapTransform(Bitmap srcBitmap, int sensorOrientation, boolean isFrontCamera) {
        if (srcBitmap == null) return null;

        // 如果无需变换，直接返回原图
        if (sensorOrientation == 0 && !isFrontCamera) {
            return srcBitmap;
        }

        Matrix matrix = new Matrix();

        // 应用传感器旋转
        if (sensorOrientation != 0) {
            matrix.postRotate(sensorOrientation);
        }

        // 前置摄像头需要水平镜像
        if (isFrontCamera) {
            matrix.postScale(-1, 1, srcBitmap.getWidth() / 2f, srcBitmap.getHeight() / 2f);
        }

        try {
            Bitmap transformedBitmap = Bitmap.createBitmap(srcBitmap, 0, 0,
                    srcBitmap.getWidth(), srcBitmap.getHeight(), matrix, true);
            // 如果创建了新Bitmap，回收原Bitmap以释放内存
            if (transformedBitmap != srcBitmap) {
                srcBitmap.recycle();
            }
            return transformedBitmap;
        } catch (Exception e) {
            Log.e(TAG, "applyBitmapTransform 创建Bitmap失败", e);
            return srcBitmap; // 变换失败时返回原图
        }
    }

    // 将检测坐标映射到TextureView坐标系统
    private List<RectF> mapFaceCoordinatesToTextureView(List<RectF> detectedFaces, int detectionWidth, int detectionHeight) {
        List<RectF> mappedFaces = new ArrayList<>();

        if (detectedFaces == null || detectedFaces.isEmpty()) {
            return mappedFaces;
        }

        // TextureView的当前尺寸
        int textureWidth = textureView.getWidth();
        int textureHeight = textureView.getHeight();

        if (textureWidth == 0 || textureHeight == 0) {
            Log.w(TAG, String.format("TextureView尺寸为0: %dx%d", textureWidth, textureHeight));
            return detectedFaces;
        }

        // 计算缩放比例
        float scaleX = (float) textureWidth / detectionWidth;
        float scaleY = (float) textureHeight / detectionHeight;

        // 使用相同的缩放因子，避免拉伸
        float scale = Math.min(scaleX, scaleY);

        // 计算在TextureView中的实际显示区域
        float displayWidth = detectionWidth * scale;
        float displayHeight = detectionHeight * scale;
        float offsetX = (textureWidth - displayWidth) / 2;
        float offsetY = (textureHeight - displayHeight) / 2;

        for (RectF face : detectedFaces) {
            // 映射坐标
            float left = offsetX + face.left * scale;
            float top = offsetY + face.top * scale;
            float right = offsetX + face.right * scale;
            float bottom = offsetY + face.bottom * scale;

            mappedFaces.add(new RectF(left, top, right, bottom));
        }

        return mappedFaces;
    }

    // 打开相机
    public void openCamera() {
        // 确保美颜线程存活
        if (beautyThread == null || !beautyThread.isAlive()) {
            Log.w(TAG, "美颜线程未运行，重新启动");
            startBeautyThread();
        }

        startInferenceThread();
        try {
            setCamera();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 权限检查
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.d("TAG-T", "permission denied");
            return;
        }

        // 打开相机
        try {
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void setCamera() {
        try {
            // 获取cameraId
            String[] cameraIdList = cameraManager.getCameraIdList();
            for (String id : cameraIdList) {
                // 获取相机特性
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);

                // 获取传感器尺寸
                Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (sensorArraySize != null) {
                    sensorArrayWidth = sensorArraySize.width();
                    sensorArrayHeight = sensorArraySize.height();
                }

                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                // 根据传递参数选择摄像头
                if (isRearCamera && facing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue; // 使用后置，但当前摄像头不是后置，继续查找
                } else if (!isRearCamera && facing != CameraCharacteristics.LENS_FACING_FRONT) {
                    continue; // 使用前置，但当前摄像头不是前置，继续查找
                }

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);

                    cameraId = id;
                    previewSize = setPreviewSize(outputSizes);
                    // 设置预览ImageReader尺寸
                    previewSizeForDetection = chooseOptimalSize(map.getOutputSizes(ImageFormat.YUV_420_888), 320, 240);

                    if (textureView.getSurfaceTexture() != null) {
                        textureView.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

                        updateTextureViewTransform();
                    }

                    if (beautySurfaceTexture != null) {
                        beautySurfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                        Log.d(TAG, String.format("设置美颜SurfaceTexture默认缓冲区: %dx%d",
                                previewSize.getWidth(), previewSize.getHeight()));
                    } else {
                        Log.w(TAG, "美颜SurfaceTexture尚不存在，等待回调创建");
                    }
                }

                setImageReader();
                // 初始化用于人脸检测的预览ImageReader
                setPreviewImageReader();

                Log.i("TAG-T", "cameraId: " + cameraId);
                // 找到合适的摄像头设置后无需继续查找
                break;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 选择用于检测的预览尺寸
    private Size chooseOptimalSize(Size[] choices, int targetWidth, int targetHeight) {
        if (choices == null || choices.length == 0) {
            Log.e(TAG, "No available sizes to choose from");
            return new Size(640, 480); // 默认回退
        }

        // 按照像素数量从大到小排序
        List<Size> sortedSizes = new ArrayList<>(Arrays.asList(choices));
        sortedSizes.sort((a, b) -> {
            int areaA = a.getWidth() * a.getHeight();
            int areaB = b.getWidth() * b.getHeight();
            return Integer.compare(areaB, areaA); // 降序
        });

        // 寻找合适的分辨率
        Size optimalSize = null;
        float targetRatio = (float) targetWidth / targetHeight;
        float minDiff = Float.MAX_VALUE;

        Log.d(TAG, String.format("选择分辨率: 目标%d×%d (%.2f), 可用%d个尺寸",
                targetWidth, targetHeight, targetRatio, choices.length));

        for (Size size : sortedSizes) {
            int width = size.getWidth();
            int height = size.getHeight();
            float ratio = (float) width / height;

            float ratioDiff = Math.abs(ratio - targetRatio);
            int area = width * height;

            // 优先选择宽高比接近且分辨率合适的大小
            if (ratioDiff < 0.2 && area >= 640 * 480 && area <= 1920 * 1080) {
                float diff = ratioDiff + Math.abs(width - targetWidth)/1000f + Math.abs(height - targetHeight)/1000f;
                if (diff < minDiff) {
                    optimalSize = size;
                    minDiff = diff;
                }
            }
        }

        // 如果没找到合适的，使用第一个
        if (optimalSize == null && !sortedSizes.isEmpty()) {
            optimalSize = sortedSizes.get(0);
        }

        Log.d(TAG, "选择检测尺寸: " + optimalSize);
        return optimalSize;
    }

    // 设置预览ImageReader
    private void setPreviewImageReader() throws CameraAccessException {
        if (previewSizeForDetection == null) return;
        if (previewImageReader != null) {
            previewImageReader.close();
        }

        // 提高分辨率，使用640x480
        previewSizeForDetection = chooseOptimalSize(
                cameraManager.getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(ImageFormat.YUV_420_888),
                640, 480);

        previewImageReader = ImageReader.newInstance(
                previewSizeForDetection.getWidth(),
                previewSizeForDetection.getHeight(),
                ImageFormat.YUV_420_888, 2);
        previewImageReader.setOnImageAvailableListener(previewImageAvailableListener, backgroundHandler);
    }

    private Size setPreviewSize(Size[] sizes) {
        for (Size size : sizes) {
            // 4:3 比例 (0.75)，保证前后置相机的原始输出比例一致
            if (Math.abs((float) size.getHeight() / size.getWidth() - 0.75f) < 0.01) {
                return size;
            }
        }
        return sizes[0];
    }

    private void setImageReader() {
        // imageReader初始化，用于获取拍照信息
        if (previewSize == null) return;

        imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.JPEG, 10);
        imageReader.setOnImageAvailableListener(reader -> {
            // 拿到照片
            Image image = reader.acquireLatestImage();
            if (image == null) return;

            // 获取图像缓冲区数据
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            // 将字节数组解码为Bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            // 创建一个矩阵用于旋转
            Matrix matrix = new Matrix();
            if (isRearCamera) {
                // 后置顺时针旋转 90 度
                matrix.postRotate(90);
            } else {
                // 前置顺时针旋转 270 度
                matrix.postRotate(270);
                // 水平镜像
                matrix.postScale(-1, 1);
            }

            // 返回旋转后的Bitmap
            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

            // 保存字节数据到文件
            File photoFile = saveImage(rotatedBitmap);

            // 将文件添加到文件夹
            addImageToGallery(photoFile);

            // 缩小动画
            new Handler(context.getMainLooper()).post(() -> {
                imageView.setImageBitmap(rotatedBitmap);
                Animation animation = AnimationUtils.loadAnimation(context, R.anim.fade_out);
                animation.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {
                        // ...
                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        // 在动画结束时隐藏 ImageView
                        imageView.setVisibility(ImageView.INVISIBLE);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {
                        // ...
                    }
                });

                imageView.startAnimation(animation);
            });

            image.close();
        }, backgroundHandler);
    }

    private File saveImage(Bitmap bitmap) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Log.d("TAG-T", "permission---");
        }

        Log.d("TAG-T", getNowTime());
        // 应用美颜处理
        if (beautyProcessor != null) {
            Log.d(TAG, "BeautyProcessor 存在");

            if (faceDetector != null) {
                // 获取最后一次检测到的人脸矩形
                RectF faceRectForBeauty = faceDetector.getLastValidFaceRect();

                // 坐标映射
                if (!faceRectForBeauty.isEmpty()) {
                    // 先记录原始检测尺寸
                    int detectionWidth = 480;
                    int detectionHeight = 640;

                    // 获取拍照图片的尺寸
                    int photoWidth = bitmap.getWidth();
                    int photoHeight = bitmap.getHeight();

                    // 计算缩放比例
                    float scaleX = (float) photoWidth / detectionWidth;
                    float scaleY = (float) photoHeight / detectionHeight;

                    Log.d(TAG, "缩放比例: scaleX=" + scaleX + ", scaleY=" + scaleY);

                    // 应用缩放
                    float left = faceRectForBeauty.left * scaleX;
                    float top = faceRectForBeauty.top * scaleY;
                    float right = faceRectForBeauty.right * scaleX;
                    float bottom = faceRectForBeauty.bottom * scaleY;
                    faceRectForBeauty = new RectF(left, top, right, bottom);

                    Log.d(TAG, "映射后的人脸矩形: (" + left + ", " + top + ", " + right + ", " + bottom + ")");
                }

                // 美颜参数日志
                try {
                    // 通过反射获取BeautyProcessor的当前参数
                    java.lang.reflect.Field smoothField = beautyProcessor.getClass().getDeclaredField("smoothLevel");
                    smoothField.setAccessible(true);
                    float smoothLevel = (float) smoothField.get(beautyProcessor);

                    java.lang.reflect.Field whitenField = beautyProcessor.getClass().getDeclaredField("whitenLevel");
                    whitenField.setAccessible(true);
                    float whitenLevel = (float) whitenField.get(beautyProcessor);

                    Log.d(TAG, "当前美颜参数 - 磨皮: " + smoothLevel + ", 美白: " + whitenLevel);
                } catch (Exception e) {
                    Log.e(TAG, "获取美颜参数失败: " + e.getMessage());
                }

                // 使用映射后的坐标进行美颜
                bitmap = beautyProcessor.process(bitmap, faceRectForBeauty);

            } else {
                Log.d(TAG, "faceDetector 为 null，无法获取人脸矩形");
            }
        } else {
            Log.d(TAG, "beautyProcessor 为 null，跳过美颜处理");
        }
        File file = new File("/storage/emulated/0/DCIM/Camera/", getNowTime());
        try (FileOutputStream output = new FileOutputStream(file)) {
            // 把位图压缩为 JPEG 格式，并写入输出流
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output);
            output.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return file;
    }

    private String getNowTime() {
        Calendar calendar = Calendar.getInstance();
        int YY = calendar.get(Calendar.YEAR);
        int MM = calendar.get(Calendar.MONTH) + 1; // month 从0开始
        int DD = calendar.get(Calendar.DATE);
        int HH = calendar.get(Calendar.HOUR_OF_DAY);
        int NN = calendar.get(Calendar.MINUTE);
        int SS = calendar.get(Calendar.SECOND);
        int MI = calendar.get(Calendar.MILLISECOND);

        return String.format("IMG_%02d%02d%02d_%02d%02d%02d_%03d.jpg", YY, MM, DD, HH, NN, SS, MI);
    }

    // 将图片添加到文件夹
    private void addImageToGallery(File file) {
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, (path, uri) -> {
            // ...
        });
        Log.d("TAG-T", "save");
        imageCount++;
    }

    public void takePicture() {
        if (cameraDevice == null) {
            Log.d("TAG-T", "cameraDevice == null");
            return;
        }
        try {
            // 创建拍照需要的CaptureRequest.Builder
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            // 自动对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            // 自动曝光
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            // 自动白平衡
            captureRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            // 闪光灯
            if (isFlash) {
                // 持续亮
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            } else {
                // 关闭闪光灯
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }

            // 拍照
            CaptureRequest request = captureRequestBuilder.build();
            cameraCaptureSession.capture(request, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                    if (isMute) {
                        // 声音
                        if (mediaPlayer == null) {
                            mediaPlayer = MediaPlayer.create(context, R.raw.y1116);
                        }
                        if (mediaPlayer != null) {
                            mediaPlayer.start();
                        }
                    }
                    Log.d("TAG-T", "take a pic");
                }
            }, backgroundHandler);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void switchCamera() {
        isRearCamera = !isRearCamera;
        // 确定旋转的方向，后置到前置顺时针旋转，前置到后置逆时针旋转
        float startAngle = 0f;
        float midAngle = isRearCamera ? -90f : 90f;
        float endAngle = isRearCamera ? -360f : 360f;

        // 创建一个动画使视图中心绕y轴旋转90度
        ObjectAnimator flipOut = ObjectAnimator.ofFloat(textureView, "rotationY", startAngle, midAngle);
        flipOut.setDuration(200);
        flipOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                super.onAnimationStart(animation);
                closeCamera();
                openCamera();
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                super.onAnimationEnd(animation);
                // 创建另一个动画使视图中心绕y轴旋转90度
                ObjectAnimator flipIn = ObjectAnimator.ofFloat(textureView, "rotationY", midAngle > 0 ? midAngle + 180f : midAngle - 180f, endAngle);
                flipIn.setDuration(200);
                flipIn.start();
            }
        });

        flipOut.start();
    }

    private void updatePreview() {
        if (cameraCaptureSession != null && previewRequestBuilder != null) {
            try {
                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    // 相机状态回调
    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            try {
                // 创建预览请求
                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                Surface surface = new Surface(textureView.getSurfaceTexture());
                previewRequestBuilder.addTarget(surface);
                // 将预览ImageReader的表面也加入预览请求
                if (previewImageReader != null) {
                    previewRequestBuilder.addTarget(previewImageReader.getSurface());
                }

                // 创建相机会话
                // 需要将预览ImageReader的Surface也加入会话
                List<Surface> surfaces = new java.util.ArrayList<>();
                surfaces.add(surface);
                surfaces.add(imageReader.getSurface());
                if (previewImageReader != null) {
                    surfaces.add(previewImageReader.getSurface());
                }
                cameraDevice.createCaptureSession(surfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                cameraCaptureSession = session;
                                try {
                                    // 根据当前状态设置对焦模式
                                    if (isAutoFocus) {
                                        // 完全自动对焦模式
                                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                    } else {
                                        // 手动对焦模式，但仍保持AUTO模式能力
                                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                                    }
                                    // 设置自动曝光
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                    // 自动白平衡
                                    previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

                                    // 闪光灯
                                    if (isFlash) {
                                        // 持续亮
                                        previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                                    } else {
                                        // 关闭闪光灯
                                        previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                                    }

                                    cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);

                                    updateTextureViewTransform();
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                Log.e(TAG, "相机会话配置失败");
                            }
                        }, backgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            closeCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            closeCamera();
        }
    };

    // 关闭相机
    public void closeCamera() {
        stopInferenceThread();
        stopBeautyThread();
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        // 关闭预览ImageReader
        if (previewImageReader != null) {
            previewImageReader.close();
            previewImageReader = null;
        }
        if (beautySurface != null) {
            beautySurface.release();
            beautySurface = null;
        }
        isBeautyProcessing.set(false);
        beautySurfaceTexture = null;
    }

    // 启动后台线程
    public void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    // 停止后台线程
    public void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void startInferenceThread() {
        if (inferenceThread == null) {
            inferenceThread = new HandlerThread("InferenceThread");
            inferenceThread.start();
            inferenceHandler = new Handler(inferenceThread.getLooper());
        }
    }

    public void stopInferenceThread() {
        if (inferenceThread != null) {
            inferenceThread.quitSafely();
            try {
                inferenceThread.join();
                inferenceThread = null;
                inferenceHandler = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void startBeautyThread() {
        if (beautyThread == null) {
            beautyThread = new HandlerThread("BeautyProcessor");
            beautyThread.start();
            beautyHandler = new Handler(beautyThread.getLooper());
        }
    }

    public void stopBeautyThread() {
        if (beautyThread != null) {
            beautyThread.quitSafely();
            try {
                beautyThread.join();
                beautyThread = null;
                beautyHandler = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public boolean getAF() {
        return isAutoFocus;
    }
    public boolean getFlash() {
        return isFlash;
    }
    public boolean getMute() {
        return isMute;
    }
    public void setAF() {
        isAutoFocus = !isAutoFocus;
        if (isAutoFocus) {
            // 恢复完全自动对焦
            if (previewRequestBuilder != null) {
                // 清除手动对焦区域
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, null);
                // 恢复连续对焦模式
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                // 取消之前的对焦触发
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                // 更新预览
                updatePreview();
            }
        }
    }
    public void setFlash() {
        isFlash = !isFlash;
    }
    public void setMute() {
        isMute = !isMute;
    }

    public void setPreviewBeautyEnabled(boolean enabled) {
        this.isPreviewBeautyEnabled = enabled;
    }

    public boolean isPreviewBeautyEnabled() {
        return isPreviewBeautyEnabled;
    }

    // 美颜处理任务
    private class BeautyProcessTask implements Runnable {
        private Bitmap inputBitmap;
        private int sensorOrientation;
        private boolean isFrontCamera;
        private RectF faceRect;
        // 根据新的分辨率调整处理尺寸
        private int PROCESS_WIDTH = 640;  // 与预览分辨率匹配
        private int PROCESS_HEIGHT = 480;

        BeautyProcessTask(Bitmap inputBitmap, int sensorOrientation, boolean isFrontCamera, RectF faceRect) {
            this.inputBitmap = inputBitmap;
            this.sensorOrientation = sensorOrientation;
            this.isFrontCamera = isFrontCamera;
            this.faceRect = faceRect;

            // 根据输入Bitmap动态设置处理尺寸
            int width = inputBitmap.getWidth();
            int height = inputBitmap.getHeight();

            // 保持宽高比，设置合适的分辨率
            if (width > 0 && height > 0) {
                float aspect = (float) width / height;
                if (aspect > 1) { // 横屏
                    PROCESS_WIDTH = 640;
                    PROCESS_HEIGHT = (int)(640 / aspect);
                } else { // 竖屏
                    PROCESS_HEIGHT = 640;
                    PROCESS_WIDTH = (int)(640 * aspect);
                }

                // 确保是偶数
                PROCESS_WIDTH = PROCESS_WIDTH + (PROCESS_WIDTH % 2);
                PROCESS_HEIGHT = PROCESS_HEIGHT + (PROCESS_HEIGHT % 2);

                // 限制最大最小尺寸
                PROCESS_WIDTH = Math.min(Math.max(PROCESS_WIDTH, 320), 1280);
                PROCESS_HEIGHT = Math.min(Math.max(PROCESS_HEIGHT, 240), 960);
            }
        }

        @Override
        public void run() {
            if (beautyProcessor == null || beautyHandler == null ||
                    inputBitmap == null || inputBitmap.isRecycled()) {
                isBeautyProcessing.set(false);
                if (inputBitmap != null && !inputBitmap.isRecycled()) {
                    inputBitmap.recycle();
                }
                return;
            }

            Surface surface = getAvailableBeautySurface();
            if (surface == null) {
                Log.w(TAG, "无法获取美颜Surface，放弃本帧");
                inputBitmap.recycle();
                isBeautyProcessing.set(false);
                return;
            }

            try {
                long startTime = System.currentTimeMillis();
                Bitmap resultBitmap = null;

                // 直接使用传入的Bitmap
                Bitmap fullBitmap = inputBitmap;
                int width = fullBitmap.getWidth();
                int height = fullBitmap.getHeight();

                // 如果输入尺寸与处理尺寸相差不大，直接使用
                float sizeRatio = (float) Math.max(width, height) / Math.max(PROCESS_WIDTH, PROCESS_HEIGHT);

                if (sizeRatio < 1.5f && sizeRatio > 0.67f) {
                    // 尺寸接近，直接处理
                    Log.d(TAG, "尺寸接近，跳过缩放");
                    resultBitmap = processWithoutScaling(fullBitmap);
                } else {
                    // 需要缩放
                    resultBitmap = processWithScaling(fullBitmap);
                }

                if (resultBitmap == null) {
                    Log.e(TAG, "美颜处理返回null");
                    return;
                }

                // 渲染
                renderToTextureView(resultBitmap, surface);
                resultBitmap.recycle();

            } catch (Exception e) {
                Log.e(TAG, "美颜任务异常", e);
            } finally {
                isBeautyProcessing.set(false);
            }
        }

        private Bitmap processWithoutScaling(Bitmap inputBitmap) {
            Bitmap beautified = beautyProcessor.process(inputBitmap, faceRect);
            return beautified;
        }

        private Bitmap processWithScaling(Bitmap inputBitmap) {
            int width = inputBitmap.getWidth();
            int height = inputBitmap.getHeight();

            // 高质量缩放到处理尺寸
            long scaleStart = System.currentTimeMillis();
            Bitmap smallBitmap = Bitmap.createScaledBitmap(inputBitmap, PROCESS_WIDTH, PROCESS_HEIGHT, true);
            long scaleTime = System.currentTimeMillis() - scaleStart;

            // 坐标映射
            RectF smallFaceRect = null;
            if (faceRect != null && !faceRect.isEmpty()) {
                float scaleX = (float) PROCESS_WIDTH / width;
                float scaleY = (float) PROCESS_HEIGHT / height;
                smallFaceRect = new RectF(
                        faceRect.left * scaleX,
                        faceRect.top * scaleY,
                        faceRect.right * scaleX,
                        faceRect.bottom * scaleY
                );
            }

            // 美颜处理
            long beautyStart = System.currentTimeMillis();
            Bitmap beautifiedSmall = beautyProcessor.process(smallBitmap, smallFaceRect);
            smallBitmap.recycle();

            if (beautifiedSmall == null) {
                return null;
            }

            // 放大回原始尺寸
            Bitmap resultBitmap = Bitmap.createScaledBitmap(beautifiedSmall,
                    previewSize.getWidth(), previewSize.getHeight(), true);
            beautifiedSmall.recycle();

            return resultBitmap;
        }

        // 动态获取可用的美颜Surface（优先使用成员变量，若无效则从TextureView重新获取）
        private Surface getAvailableBeautySurface() {
            // 如果成员变量有效，直接使用
            if (beautySurface != null && beautySurface.isValid()) {
                return beautySurface;
            }
            // 否则尝试从TextureView重新获取
            if (beautyTextureView != null && beautyTextureView.isAvailable()) {
                SurfaceTexture st = beautyTextureView.getSurfaceTexture();
                if (st != null) {
                    // 重新创建Surface
                    if (beautySurface != null) {
                        beautySurface.release();
                    }
                    beautySurface = new Surface(st);
                    beautySurfaceTexture = st;
                    Log.d(TAG, "动态创建美颜Surface成功");
                    return beautySurface;
                }
            }
            return null;
        }

        // 渲染美颜后的图片到TextureView，保持Bitmap原始比例
        private void renderToTextureView(Bitmap bitmap, Surface surface) {
            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "美颜Surface无效或为空");
                return;
            }
            if (bitmap == null || bitmap.isRecycled()) {
                Log.e(TAG, "Bitmap无效或已回收");
                return;
            }
            if (beautyTextureView == null) {
                Log.e(TAG, "美颜TextureView为空");
                return;
            }

            // 确保 SurfaceTexture 缓冲区大小匹配视图尺寸
            int viewWidth = beautyTextureView.getWidth();
            int viewHeight = beautyTextureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                Log.w(TAG, "视图尺寸为0，跳过渲染");
                return;
            }

            if (beautySurfaceTexture != null) {
                beautySurfaceTexture.setDefaultBufferSize(viewWidth, viewHeight);
            }

            Canvas canvas = null;
            try {
                canvas = surface.lockCanvas(null);
                if (canvas == null) {
                    Log.e(TAG, "无法锁定美颜Surface的画布");
                    return;
                }

                // Bitmap的实际宽高
                int bitmapWidth = bitmap.getWidth();
                int bitmapHeight = bitmap.getHeight();

                // 计算Bitmap的原始宽高比
                float bitmapAspect = (float) bitmapWidth / bitmapHeight;

                // 计算在视图宽度下的原始比例高度
                float displayWidth = viewWidth;  // 宽度填满
                float displayHeight = viewWidth / bitmapAspect;  // 保持原始比例的高度

                // 计算上下留黑边的位置
                float topMargin = (viewHeight - displayHeight) / 2.0f;

                if (topMargin < 0) {
                    // 如果视图高度不够，按高度填满，宽度按比例缩小
                    displayHeight = viewHeight;
                    displayWidth = viewHeight * bitmapAspect;
                    topMargin = 0;

                    // 宽度方向居中
                    float leftMargin = (viewWidth - displayWidth) / 2.0f;

                    // 创建目标矩形
                    RectF dstRect = new RectF(leftMargin, 0, leftMargin + displayWidth, displayHeight);

                    // 创建源矩形（整个Bitmap）
                    Rect srcRect = new Rect(0, 0, bitmapWidth, bitmapHeight);

                    // 绘制Bitmap，自动缩放以适应目标区域
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
                } else {
                    // 创建目标矩形
                    RectF dstRect = new RectF(0, topMargin, displayWidth, topMargin + displayHeight);

                    // 创建源矩形（整个Bitmap）
                    Rect srcRect = new Rect(0, 0, bitmapWidth, bitmapHeight);

                    // 绘制Bitmap，自动缩放以适应目标区域
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
                }

                surface.unlockCanvasAndPost(canvas);

                // 切换视图可见性
                if (textureView.getVisibility() != View.GONE) {
                    new Handler(context.getMainLooper()).post(() -> {
                        textureView.setVisibility(View.GONE);
                        beautyTextureView.setVisibility(View.VISIBLE);
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "渲染到TextureView异常", e);
                if (canvas != null) {
                    try {
                        surface.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {}
                }
            }
        }
    }
}