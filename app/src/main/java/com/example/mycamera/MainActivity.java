package com.example.mycamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.widget.Button;

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

        if (!checkCameraPermission()) {
            requestCameraPermission();
        }
        initCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraManager.startBackgroundThread();
        if (textureView.isAvailable()) {
            cameraManager.openCamera();
        } else {
            textureView.setSurfaceTextureListener(cameraManager.getSurfaceTextureListener());
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