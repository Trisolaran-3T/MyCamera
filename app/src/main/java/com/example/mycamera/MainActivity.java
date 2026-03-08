package com.example.mycamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import com.example.mycamera.camera.MyCameraManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private TextureView textureView;
    private Button btnCapture;
    private MyCameraManager cameraManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        btnCapture = findViewById(R.id.btn_capture);

        if (checkCameraPermission()) {
            initCamera();
        } else {
            requestCameraPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraManager != null) {
            cameraManager.startBackgroundThread();
            if (textureView.isAvailable()) {
                cameraManager.openCamera();
            } else {
                textureView.setSurfaceTextureListener(cameraManager.getSurfaceTextureListener());
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraManager != null) {
            cameraManager.closeCamera();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraManager != null) {
            cameraManager.closeCamera();
            cameraManager.stopBackgroundThread();
            cameraManager = null;
        }
    }

    // 重写权限请求结果回调方法
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults, int deviceId) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予，初始化相机
                Log.d(TAG, "相机权限已授予");

                // 延迟一小段时间确保UI已准备好
                textureView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cameraManager == null) {
                            initCamera();
                        }
                    }
                });
            } else {
                // 权限被拒绝
                Toast.makeText(this, "需要相机权限才能使用", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean checkCameraPermission() {
        List<String> permissions = getRequiredPermissions();

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private List<String> getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();

        // 相机权限
        permissions.add(Manifest.permission.CAMERA);

        return permissions;
    }

    private void requestCameraPermission() {
        List<String> permissionsNeeded = new ArrayList<>();
        List<String> requiredPermissions = getRequiredPermissions();

        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private void initCamera() {
        cameraManager = new MyCameraManager(this, textureView);
    }
}