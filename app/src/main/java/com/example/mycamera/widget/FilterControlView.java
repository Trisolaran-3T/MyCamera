package com.example.mycamera.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.mycamera.R;

public class FilterControlView extends HorizontalScrollView {

    // 控制按钮
    private ImageButton btnFilterBack, btnFilterToggle;

    // 滤镜选项布局
    private LinearLayout[] filterLayouts;
    private ImageView[] filterIcons;
    private TextView[] filterLabels;

    // 当前选中的滤镜
    private String selectedFilter = "none";
    private View selectedFilterView = null;

    // 滤镜开关状态
    private boolean isFilterEnabled = true;

    // 监听器
    private OnFilterControlListener listener;

    // 滤镜定义
    private static final String[] FILTER_TYPES = {
            "fresh", "natural", "mono", "vintage", "vivid"
    };

    private static final String[] FILTER_NAMES = {
            "清新", "自然", "黑白", "复古", "鲜艳"
    };

    public FilterControlView(Context context) {
        super(context);
        init(context, null);
    }

    public FilterControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public FilterControlView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        LayoutInflater.from(context).inflate(R.layout.widget_filter_control, this, true);

        // 初始化视图
        initViews();

        // 设置点击监听
        setClickListeners();

        // 默认选中清新滤镜
        setSelectedFilter("fresh");
    }

    private void initViews() {
        // 控制按钮
        btnFilterBack = findViewById(R.id.btn_filter_back);
        btnFilterToggle = findViewById(R.id.btn_filter_toggle);

        // 初始化滤镜选项数组
        filterLayouts = new LinearLayout[] {
                findViewById(R.id.filter_none),
                findViewById(R.id.filter_natural),
                findViewById(R.id.filter_mono),
                findViewById(R.id.filter_vintage),
                findViewById(R.id.filter_vivid)
        };

        filterIcons = new ImageView[5];
        filterLabels = new TextView[5];

        // 获取滤镜图标和标签（假设布局中已有ImageView和TextView）
        for (int i = 0; i < 5; i++) {
            // 获取LinearLayout中的子视图
            filterIcons[i] = (ImageView) filterLayouts[i].getChildAt(0);
            filterLabels[i] = (TextView) filterLayouts[i].getChildAt(1);
        }
    }

    private void setClickListeners() {
        // 返回按钮
        btnFilterBack.setOnClickListener(v -> {
            if (listener != null) {
                listener.onBackClicked();
            }
        });

        // 滤镜开关按钮
        btnFilterToggle.setOnClickListener(v -> {
            isFilterEnabled = !isFilterEnabled;
            updateFilterToggleState();

            if (listener != null) {
                listener.onFilterToggleChanged(isFilterEnabled);
            }
        });

        // 滤镜选项点击监听
        for (int i = 0; i < FILTER_TYPES.length; i++) {
            final String filterType = FILTER_TYPES[i];
            filterLayouts[i].setOnClickListener(v -> {
                selectFilter(v, filterType);
            });
        }
    }

    private void selectFilter(View view, String filterType) {
        // 清除上次选中的样式
        if (selectedFilterView != null) {
            selectedFilterView.setSelected(false);
        }

        // 设置当前选中的样式
        view.setSelected(true);
        selectedFilterView = view;
        selectedFilter = filterType;

        // 应用滤镜效果
        if (listener != null && isFilterEnabled) {
            listener.onFilterSelected(filterType);
        }
    }

    private void updateFilterToggleState() {
        if (isFilterEnabled) {
//            btnFilterToggle.setImageResource(R.drawable.ic_filter_on);
            // 重新应用当前选中的滤镜
            if (listener != null) {
                listener.onFilterSelected(selectedFilter);
            }
        } else {
//            btnFilterToggle.setImageResource(R.drawable.ic_filter_off);
            // 应用无滤镜效果
            if (listener != null) {
                listener.onFilterSelected("none");
            }
        }
    }

    public void setSelectedFilter(String filterType) {
        for (int i = 0; i < FILTER_TYPES.length; i++) {
            if (FILTER_TYPES[i].equals(filterType)) {
                selectFilter(filterLayouts[i], filterType);
                break;
            }
        }
    }

    public String getSelectedFilter() {
        return selectedFilter;
    }

    public boolean isFilterEnabled() {
        return isFilterEnabled;
    }

    public void setFilterEnabled(boolean enabled) {
        isFilterEnabled = enabled;
        updateFilterToggleState();
    }

    public void setOnFilterControlListener(OnFilterControlListener listener) {
        this.listener = listener;
    }

    public interface OnFilterControlListener {
        void onFilterSelected(String filterType);
        void onFilterToggleChanged(boolean isEnabled);
        void onBackClicked();
    }
}