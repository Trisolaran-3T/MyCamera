package com.example.mycamera.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;

import com.example.mycamera.R;

public class BeautyControlView extends HorizontalScrollView {

    // 当前选中的美颜类型
    private String selectedBeautyType = null;

    // 当前进度值
    private int currentValue = 0;

    // 美颜参数存储
    private int smoothValue = 30;
    private int eyesValue = 20;
    private int faceValue = 25;
    private int whitenValue = 40;

    // 监听器接口
    public interface OnBeautyControlListener {
        void onBeautyValueChanged(String type, int value);
        void onBeautyTypeSelected(String type, int currentValue);
        void onBeautyToggleChanged(boolean isEnabled);
        void onBeautyReset();
        void onBackClicked();
    }

    private OnBeautyControlListener listener;

    public BeautyControlView(Context context) {
        super(context);
        init(context, null);
    }

    public BeautyControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public BeautyControlView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        LayoutInflater.from(context).inflate(R.layout.widget_beauty_control, this, true);

        // 设置点击监听
        findViewById(R.id.layout_smooth).setOnClickListener(v -> onBeautyTypeSelected("smooth", smoothValue));
        findViewById(R.id.layout_eyes).setOnClickListener(v -> onBeautyTypeSelected("eyes", eyesValue));
        findViewById(R.id.layout_face).setOnClickListener(v -> onBeautyTypeSelected("face", faceValue));
        findViewById(R.id.layout_whiten).setOnClickListener(v -> onBeautyTypeSelected("whiten", whitenValue));

        // 按钮监听
        findViewById(R.id.btn_beauty_back).setOnClickListener(v -> {
            if (listener != null) listener.onBackClicked();
        });

        findViewById(R.id.btn_beauty_toggle).setOnClickListener(v -> {
            // 开关功能
        });

        findViewById(R.id.btn_beauty_reset).setOnClickListener(v -> {
            resetToDefaults();
            if (listener != null) listener.onBeautyReset();
        });
    }

    private void onBeautyTypeSelected(String type, int value) {
        selectedBeautyType = type;
        currentValue = value;

        if (listener != null) {
            listener.onBeautyTypeSelected(type, value);
        }
    }

    public void onSeekBarValueChanged(int progress) {
        if (selectedBeautyType != null) {
            // 保存当前值
            switch (selectedBeautyType) {
                case "smooth":
                    smoothValue = progress;
                    break;
                case "eyes":
                    eyesValue = progress;
                    break;
                case "face":
                    faceValue = progress;
                    break;
                case "whiten":
                    whitenValue = progress;
                    break;
            }

            if (listener != null) {
                listener.onBeautyValueChanged(selectedBeautyType, progress);
            }
        }
    }

    public void resetToDefaults() {
        smoothValue = 30;
        eyesValue = 20;
        faceValue = 25;
        whitenValue = 40;
        currentValue = 0;
        selectedBeautyType = null;
    }

    public int getCurrentValue() {
        return currentValue;
    }

    public String getSelectedType() {
        return selectedBeautyType;
    }

    public int getValueByType(String type) {
        switch (type) {
            case "smooth": return smoothValue;
            case "eyes": return eyesValue;
            case "face": return faceValue;
            case "whiten": return whitenValue;
            default: return 0;
        }
    }

    public void setOnBeautyControlListener(OnBeautyControlListener listener) {
        this.listener = listener;
    }
}