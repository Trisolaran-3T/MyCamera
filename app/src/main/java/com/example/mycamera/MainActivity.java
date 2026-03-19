package com.example.mycamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
    private static final int WRITE_EXTERNAL_STORAGE_REQUEST = 101;

    private TextureView textureView;
    private FrameLayout frameLayout;
    private ImageView imageFocus;
    private ImageView imageView;
    private Button btnCapture;
    private ImageButton btnSwitch;
    private ImageButton btnFlash;
    private ImageButton btnMute;

    private MyCameraManager cameraManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        frameLayout = findViewById(R.id.frameLayout);
        imageFocus = findViewById(R.id.imageFocus);
        imageView = findViewById(R.id.imageView);
        btnCapture = findViewById(R.id.btn_capture);
        btnSwitch = findViewById(R.id.btn_switch);
        btnFlash = findViewById(R.id.btn_flash);
        btnMute = findViewById(R.id.btn_mute);

        if (checkPermissions()) {
            initCamera();
        } else {
            requestPermissions();
        }

        btnCapture.setOnClickListener(v -> {
            if (cameraManager != null) {
                cameraManager.takePicture();
            }
        });
        btnSwitch.setOnClickListener(v -> {
            if (cameraManager != null) {
                cameraManager.switchCamera();
            }
        });
        btnFlash.setOnClickListener(v -> {
            if (cameraManager != null) {
                if (cameraManager.getFlash()) {
                    btnFlash.setImageResource(R.drawable.flash_off);
                } else {
                    btnFlash.setImageResource(R.drawable.flash_on);
                }
                cameraManager.setFlash();
            }
        });
        btnMute.setOnClickListener(v -> {
            if (cameraManager != null) {
                if (cameraManager.getMute()) {
                    btnMute.setImageResource(R.drawable.mute_off);
                } else {
                    btnMute.setImageResource(R.drawable.mute_on);
                }
                cameraManager.setMute();
            }
        });
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

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

    private boolean checkPermissions() {
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
        // 存储权限
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        // 读取媒体库
        permissions.add(Manifest.permission.READ_MEDIA_IMAGES);

        return permissions;
    }

    private void requestPermissions() {
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
        cameraManager = new MyCameraManager(this, textureView, frameLayout, imageFocus, imageView);

    }
}