package com.example.mycamera.camera;

import android.Manifest;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

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
    private long lastDetectionTime = 0; // 用于控制检测频率的时间戳

    // 美颜
    private BeautyProcessor beautyProcessor;

    public MyCameraManager(Context context, TextureView textureView, FrameLayout frameLayout,
                           ImageView imageFocus, ImageView imageView, FaceOverlayView overlayView) {
        this.context = context;
        this.textureView = textureView;
        this.frameLayout = frameLayout;
        this.imageFocus = imageFocus;
        this.imageView = imageView;
        this.overlayView = overlayView;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        startBackgroundThread();
        initFaceDetector();
        init();
    }

    public void setBeautyProcessor(BeautyProcessor beautyProcessor) {
        this.beautyProcessor = beautyProcessor;
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

    private void init() {
        // 预览View的状态监听
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        // 对TextureView注册touch事件实现
        textureView.setOnTouchListener(touchListener);
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
            // onSurfaceTextureAvailable()里面打开相机
            if (cameraDevice != null) {
                openCamera();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            updatePreview();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            // 如果距离上次检测的时间间隔小于设定值，则跳过本次检测
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastDetectionTime < 500) {
                return; // 跳过，不执行检测
            }
            // 更新最后一次检测的时间戳
            lastDetectionTime = currentTime;

            if (faceDetectionEnabled && faceDetector != null && textureView.isAvailable() && inferenceHandler != null) {
                inferenceHandler.post(() -> {
                    Bitmap bitmap = textureView.getBitmap();
                    if (bitmap != null) {
                        Log.d(TAG, "检测帧: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                        List<RectF> faces = faceDetector.detectFaces(bitmap);
                        Log.d(TAG, "检测到人脸数量: " + faces.size());
                        new Handler(context.getMainLooper()).post(() -> {
                            if (overlayView != null) {
                                overlayView.setFaces(faces);
                            }
                        });
                        bitmap.recycle();
                    }
                });
            }
        }
    };

    // 打开相机
    public void openCamera() {
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

                    if (textureView.getSurfaceTexture() != null) {
                        textureView.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

                        // 通过 Matrix 修复拉伸，实现居中裁剪
                        Matrix matrix = new Matrix();
                        int vWidth = textureView.getWidth();
                        int vHeight = textureView.getHeight();
                        float vRatio = (float) vWidth / vHeight;
                        float pRatio = (float) previewSize.getHeight() / previewSize.getWidth();

                        if (vRatio > pRatio) {
                            matrix.postScale(1f, vRatio / pRatio, vWidth / 2f, vHeight / 2f);
                        } else {
                            matrix.postScale(pRatio / vRatio, 1f, vWidth / 2f, vHeight / 2f);
                        }
                        textureView.setTransform(matrix);
                    }
                }

                setImageReader();

                Log.i("TAG-T", "cameraId: " + cameraId);
                // 找到合适的摄像头设置后无需继续查找
                break;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Size setPreviewSize(Size[] sizes) {
        for (Size size : sizes) {
            // 强制寻找 4:3 比例 (0.75)，保证前后置相机的原始输出比例一致
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
            bitmap = beautyProcessor.process(bitmap);
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
            // Scanned successfully
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

                // 创建相机会话
                cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
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
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {

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
}