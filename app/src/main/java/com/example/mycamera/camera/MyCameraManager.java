package com.example.mycamera.camera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.Toast;


import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MyCameraManager {
    private static final String TAG = "CameraManager";

    private Context context;
    private TextureView textureView;

    // Camera2相关
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private ImageReader imageReader;

    // 后台线程
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // 预览尺寸
    private Size previewSize;
    // 存储选择的摄像头ID
    private String cameraId;

    public MyCameraManager(Context context, TextureView textureView ) {
        this.context = context;
        this.textureView = textureView;
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        // 启动后台线程
        startBackgroundThread();

        // 设置预览View的状态监听
        textureView.setSurfaceTextureListener(surfaceTextureListener);
    }

    public TextureView.SurfaceTextureListener getSurfaceTextureListener() {
        return surfaceTextureListener;
    }

    // TextureView的SurfaceTexture监听器
    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            // onSurfaceTextureAvailable()里面打开相机
            if (cameraDevice == null) {
                openCamera();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };

    // 打开相机
    public void openCamera() {
        if (cameraManager == null) {
            return;
        }

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            // 取cameraId（前置/后置）
            cameraId = getBackCameraId();
            if (cameraId == null) {
                Toast.makeText(context, "没有找到后置摄像头", Toast.LENGTH_SHORT).show();
                return;
            }
            // 获取相机特性->选择合适的分辨率
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            // 设置预览尺寸
            setupPreviewSize(characteristics);

            // 创建ImageReader用于拍照
            imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(imageReaderListener, backgroundHandler);

            // 打开相机
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 取cameraId（前置/后置）
    private String getBackCameraId() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    // 获取相机特性->选择合适的分辨率
    private void setupPreviewSize(CameraCharacteristics characteristics) {
        // 获取支持的预览尺寸
        Size[] supportedSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(SurfaceTexture.class);

        // 选择最合适的预览尺寸
        previewSize = chooseOptimalSize(supportedSizes);
        Log.d(TAG, "选择的预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
    }

    // 选择最佳预览尺寸
    private Size chooseOptimalSize(Size[] choices) {
        if (choices == null || choices.length == 0) {
            return new Size(1920, 1080); // 默认尺寸
        }

        // 按面积从大到小排序
        List<Size> sizeList = Arrays.asList(choices);
        Collections.sort(sizeList, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Long.signum((long) b.getWidth() * b.getHeight() - (long) a.getWidth() * a.getHeight());
            }
        });

        // 返回最大尺寸
        return sizeList.isEmpty() ? choices[0] : sizeList.get(0);
    }

    // 相机状态回调
    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;

            // 创建Session
            createCameraPreviewSession();
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

    private void createCameraPreviewSession() {
        if (cameraDevice == null) {
            return;
        }

        if (textureView.getSurfaceTexture() == null) {
            return;
        }

        try {
            // 关闭旧的相机捕获会话
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }

            // 设置TextureView的缓冲区尺寸
            textureView.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // TextureView的surface传递给CameraDevice
            Surface previewSurface = new Surface(textureView.getSurfaceTexture());

            // 创建预览请求
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);

            // 设置自动对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // 设置自动曝光
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            // 准备Surface列表
            List<Surface> targetSurfaces = new ArrayList<>();
            targetSurfaces.add(previewSurface);

            // 添加ImageReader用于拍照
            if (imageReader != null && imageReader.getSurface() != null && imageReader.getSurface().isValid()) {
                targetSurfaces.add(imageReader.getSurface());
            }

            // 创建相机会话
            cameraDevice.createCaptureSession(targetSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        // onConfigured()创建预览请求
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                session.close();
                                return;
                            }
                            cameraCaptureSession = session;

                            // 开始连续预览
                            try {
                                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                        }
                    }, backgroundHandler);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ImageReader监听器
    private ImageReader.OnImageAvailableListener imageReaderListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            // 处理拍照得到的图片
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    // 保存图片
                    Log.d(TAG, "获取到图片: " + image.getWidth() + "x" + image.getHeight());
                }
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };

    // 关闭相机
    public void closeCamera() {
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

    // 获取预览尺寸
    public Size getPreviewSize() {
        return previewSize;
    }
}