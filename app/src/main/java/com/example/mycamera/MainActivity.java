package com.example.mycamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.mycamera.camera.BeautyManager;
import com.example.mycamera.camera.FaceOverlayView;
import com.example.mycamera.camera.MyCameraManager;
import com.example.mycamera.widget.BeautyControlView;
import com.example.mycamera.widget.FilterControlView;

import org.opencv.android.OpenCVLoader;

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
    private TextureView beautyTextureView;
    private Button btnCapture;
    private ImageButton btnSwitch;

    ImageButton btnFilter;
    FilterControlView filterLayout;
    ImageButton btnBeauty;
    BeautyControlView beautyLayout;
    LinearLayout seekbarContainer;
    private TextView seekbarLabel;
    private TextView valueDisplay;
    private SeekBar seekbarGeneral;

    private ImageButton btnAF;
    private ImageButton btnFlash;
    private ImageButton btnMute;

    private MyCameraManager cameraManager;
    private BeautyManager beautyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initOpenCV();

        textureView = findViewById(R.id.textureView);
        frameLayout = findViewById(R.id.frameLayout);
        imageFocus = findViewById(R.id.imageFocus);
        imageView = findViewById(R.id.imageView);
        beautyTextureView = findViewById(R.id.beautyTextureView);
        btnCapture = findViewById(R.id.btn_capture);
        btnSwitch = findViewById(R.id.btn_switch);

        btnFilter = findViewById(R.id.sidebar_filter);
        filterLayout = findViewById(R.id.filter_controls);
        btnBeauty = findViewById(R.id.sidebar_beauty);
        beautyLayout = findViewById(R.id.beauty_controls);
        seekbarContainer = findViewById(R.id.seekbar_container);
        seekbarLabel = findViewById(R.id.seekbar_label);
        valueDisplay = findViewById(R.id.value_display);
        seekbarGeneral = findViewById(R.id.seekbar_general);

        btnAF = findViewById(R.id.btn_af);
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
        if (btnFilter != null) {
            btnFilter.setOnClickListener(v -> {
                if (filterLayout.getVisibility() == View.VISIBLE) {
                    filterLayout.setVisibility(View.INVISIBLE);
                } else {
                    if (beautyLayout.getVisibility() == View.VISIBLE) {
                        beautyLayout.setVisibility(View.INVISIBLE);
                        hideSeekbar();
                    }
                    filterLayout.setVisibility(View.VISIBLE);
                }
            });
        }
        if (btnBeauty != null) {
            btnBeauty.setOnClickListener(v -> {
                if (beautyLayout.getVisibility() == View.VISIBLE) {
                    beautyLayout.setVisibility(View.INVISIBLE);
                    hideSeekbar();
                } else {
                    if (filterLayout.getVisibility() == View.VISIBLE) {
                        filterLayout.setVisibility(View.INVISIBLE);
                    }
                    beautyLayout.setVisibility(View.VISIBLE);
                }
            });
        }
        btnAF.setOnClickListener(v -> {
            if (cameraManager != null) {
                if (cameraManager.getAF()) {
//                    btnFlash.setImageResource(R.drawable.flash_off);
                } else {
//                    btnFlash.setImageResource(R.drawable.flash_on);
                }
                cameraManager.setAF();
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

    private void initOpenCV() {
        boolean success = OpenCVLoader.initDebug();
        if (success) {
            Log.d(TAG, "OpenCV 库加载成功！");
        } else {
            Log.e(TAG, "OpenCV 库加载失败！");
            // 处理加载失败的情况，例如显示错误提示
            // 注意：加载失败后，任何调用OpenCV函数的操作都会导致崩溃。
        }
    }

    BeautyControlView.OnBeautyControlListener beautyControlListener = new BeautyControlView.OnBeautyControlListener() {
        // 添加当前选中的美颜类型变量
        private String currentBeautyType = null;

        @Override
        public void onBeautyValueChanged(String type, int value) {
            // 不需要处理，BeautyManager 已处理
        }

        @Override
        public void onBeautyTypeSelected(String type, int currentValue) {
            // 保存当前选中的类型
            currentBeautyType = type;
            // 通知 BeautyManager 选择美颜类型
            beautyManager.selectBeautyType(type, seekbarGeneral, valueDisplay);
            // 显示调节杆容器
            showSidebarSeekbar(type);
        }

        @Override
        public void onBeautyToggleChanged(boolean isEnabled) {

        }

        @Override
        public void onBeautyReset() {
            // 重置参数
            beautyManager.resetToDefaults();
            if (currentBeautyType != null) {
                int value = beautyManager.getCurrentValue(currentBeautyType);
                seekbarGeneral.setProgress(value);

                // 更新数值显示文本
                if (valueDisplay != null) {
                    valueDisplay.setText(String.valueOf(value));
                }
            }
        }

        @Override
        public void onBackClicked() {
            // 隐藏美颜面板
            beautyLayout.setVisibility(View.INVISIBLE);
            hideSeekbar();
        }
    };

    private void showSidebarSeekbar(String type) {
        if (seekbarContainer != null) {
            // 根据美颜类型设置对应的标签文字
            String label = "";
            switch (type) {
                case "smooth": label = "磨皮"; break;
                case "eyes": label = "大眼"; break;
                case "face": label = "瘦脸"; break;
                case "whiten": label = "美白"; break;
            }

            // 更新标签文字
            if (seekbarLabel != null) {
                seekbarLabel.setText(label);
            }

            // 显示调节杆容器
            seekbarContainer.setVisibility(View.VISIBLE);
        }
    }

    private void hideSeekbar() {
        if (seekbarContainer != null) {
            // 隐藏调节杆容器
            seekbarContainer.setVisibility(View.INVISIBLE);
        }
    }

    private void initCamera() {
        beautyManager = new BeautyManager(this);
        beautyLayout.setOnBeautyControlListener(beautyControlListener);

        FaceOverlayView overlayView = findViewById(R.id.overlayView);
        cameraManager = new MyCameraManager(this, textureView, frameLayout, imageFocus, imageView, overlayView, beautyManager, beautyTextureView);
    }
}