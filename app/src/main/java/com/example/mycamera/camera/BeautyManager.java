package com.example.mycamera.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.SeekBar;
import android.widget.TextView;

public class BeautyManager {
    private static final String PREF_NAME = "beauty_settings";
    private static final String KEY_SMOOTH = "smooth_level";
    private static final String KEY_WHITEN = "whiten_level";
    private static final String KEY_EYES = "eyes_level";
    private static final String KEY_FACE = "face_level";

    private SharedPreferences preferences;
    private BeautyProcessor beautyProcessor;

    // 当前美颜参数
    private float smoothLevel = 0.3f;
    private float whitenLevel = 0.15f;
    private float eyeEnlargeLevel = 0.2f;
    private float faceShrinkLevel = 0.25f;

    // 当前选中的美颜类型
    private String currentBeautyType = null;
    private TextView valueTextView = null;

    public BeautyManager(Context context) {
        this.preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.beautyProcessor = new BeautyProcessor();
        loadSettings();
    }

    // 加载保存的设置
    private void loadSettings() {
        smoothLevel = preferences.getFloat(KEY_SMOOTH, 0.3f);
        whitenLevel = preferences.getFloat(KEY_WHITEN, 0.15f);
        eyeEnlargeLevel = preferences.getFloat(KEY_EYES, 0.2f);
        faceShrinkLevel = preferences.getFloat(KEY_FACE, 0.25f);

        // 同步到BeautyProcessor
        beautyProcessor.setSmoothLevel(smoothLevel);
        beautyProcessor.setWhitenLevel(whitenLevel);
        beautyProcessor.setEyeEnlargeLevel(eyeEnlargeLevel);
        beautyProcessor.setFaceShrinkLevel(faceShrinkLevel);
    }

    // 保存设置
    public void saveSettings() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat(KEY_SMOOTH, smoothLevel);
        editor.putFloat(KEY_WHITEN, whitenLevel);
        editor.putFloat(KEY_EYES, eyeEnlargeLevel);
        editor.putFloat(KEY_FACE, faceShrinkLevel);
        editor.apply();
    }

    // 选择美颜类型
    public void selectBeautyType(String type, SeekBar seekBar, TextView textView) {
        currentBeautyType = type;
        this.valueTextView = textView;

        // 获取当前值并设置到 SeekBar
        int progress = 0;
        String label = "";

        switch (type) {
            case "smooth":
                progress = (int) (smoothLevel * 100);
                label = "磨皮";
                break;
            case "whiten":
                progress = (int) (whitenLevel * 100);
                label = "美白";
                break;
            case "eyes":
                progress = (int) (eyeEnlargeLevel * 100);
                label = "大眼";
                break;
            case "face":
                progress = (int) (faceShrinkLevel * 100);
                label = "瘦脸";
                break;
        }

        // 设置SeekBar
        if (seekBar != null) {
            seekBar.setProgress(progress);
        }

        // 更新文本显示
        updateValueDisplay(progress);

        if (seekBar != null) {
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && currentBeautyType != null) {
                        float normalizedValue = progress / 100.0f;

                        // 更新参数
                        switch (currentBeautyType) {
                            case "smooth":
                                smoothLevel = normalizedValue;
                                beautyProcessor.setSmoothLevel(smoothLevel);
                                break;
                            case "whiten":
                                whitenLevel = normalizedValue;
                                beautyProcessor.setWhitenLevel(whitenLevel);
                                break;
                            case "eyes":
                                eyeEnlargeLevel = normalizedValue;
                                beautyProcessor.setEyeEnlargeLevel(eyeEnlargeLevel);
                                break;
                            case "face":
                                faceShrinkLevel = normalizedValue;
                                beautyProcessor.setFaceShrinkLevel(faceShrinkLevel);
                                break;
                        }

                        // 更新显示
                        updateValueDisplay(progress);

                        // 保存设置
                        saveSettings();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    // 更新数值显示
    private void updateValueDisplay(int value) {
        if (valueTextView != null) {
            valueTextView.setText(String.valueOf(value));
        }
    }

    // 重置到默认值
    public void resetToDefaults() {
        smoothLevel = 0.3f;
        whitenLevel = 0.15f;
        eyeEnlargeLevel = 0.2f;
        faceShrinkLevel = 0.25f;

        // 同步到BeautyProcessor
        beautyProcessor.setSmoothLevel(smoothLevel);
        beautyProcessor.setWhitenLevel(whitenLevel);
        beautyProcessor.setEyeEnlargeLevel(eyeEnlargeLevel);
        beautyProcessor.setFaceShrinkLevel(faceShrinkLevel);

        // 保存
        saveSettings();
    }

    // 获取BeautyProcessor
    public BeautyProcessor getBeautyProcessor() {
        return beautyProcessor;
    }

    // 获取当前参数值（0-100范围）
    public int getCurrentValue(String type) {
        switch (type) {
            case "smooth": return (int) (smoothLevel * 100);
            case "whiten": return (int) (whitenLevel * 100);
            case "eyes": return (int) (eyeEnlargeLevel * 100);
            case "face": return (int) (faceShrinkLevel * 100);
            default: return 0;
        }
    }
}
