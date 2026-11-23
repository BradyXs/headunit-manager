package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String[] TITLES = {"总览", "应用管理", "文件管理", "自动化任务", "工具箱"};
    private static final int[] ICONS = {
        R.drawable.ic_hm_overview, R.drawable.ic_hm_apps, R.drawable.ic_hm_files,
        R.drawable.ic_hm_automation, R.drawable.ic_hm_tools
    };
    private final View[] pages = new View[TITLES.length];
    private final LinearLayout[] navItems = new LinearLayout[TITLES.length];
    private final TextView[] navLabels = new TextView[TITLES.length];
    private final ImageView[] navIcons = new ImageView[TITLES.length];
    private LinearLayout content;
    private LinearLayout navigation;
    private boolean wide;
    private int page;
    private boolean resumedOnce;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        wide = Ui.expandedLayout(this);
        LinearLayout root = Ui.column(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);
        root.setBackgroundColor(Ui.BACK);

        navigation = wide ? Ui.column(this) : Ui.row(this);
        navigation.setBackgroundColor(Ui.RAIL);
        navigation.setPadding(Ui.dp(this, wide ? 10 : 8), Ui.dp(this, wide ? 16 : 5),
            Ui.dp(this, wide ? 10 : 8), Ui.dp(this, wide ? 10 : 5));
        if (wide) {
            LinearLayout brandRow = Ui.row(this);
            brandRow.setGravity(Gravity.CENTER_VERTICAL);
            brandRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 3), 0, Ui.dp(this, 18));
            ImageView mark = new ImageView(this);
            mark.setImageResource(R.drawable.ic_launcher_hires);
            mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            brandRow.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
            TextView brand = Ui.text(this, "车机管家", 20);
            brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
            brand.setPadding(Ui.dp(this, 9), 0, 0, 0);
            brandRow.addView(brand);
            navigation.addView(brandRow);
        }
        for (int i = 0; i < TITLES.length; i++) addDestination(i);
        root.addView(navigation, wide ? new LinearLayout.LayoutParams(Ui.dp(this, 210), -1) :
            new LinearLayout.LayoutParams(-1, Ui.dp(this, 64)));

        content = Ui.column(this);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 13), Ui.dp(this, 20), Ui.dp(this, 12));
        root.addView(content, wide ? new LinearLayout.LayoutParams(0, -1, 1) :
            new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        showPage(state == null ? 0 : state.getInt("page", 0));
        Rules.syncHmiService(this);
    }

    private void addDestination(int index) {
        LinearLayout tab = wide ? Ui.row(this) : Ui.column(this);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setPadding(Ui.dp(this, wide ? 13 : 2), 0, Ui.dp(this, wide ? 9 : 2), 0);
        tab.setBackground(Ui.touchBackground(this, Ui.RAIL, Ui.SURFACE_LIGHT, 12));
        ImageView icon = Ui.icon(this, ICONS[index], Ui.MUTED);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(Ui.dp(this, wide ? 24 : 21), Ui.dp(this, wide ? 24 : 21));
        if (!wide) iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        tab.addView(icon, iconParams);
        TextView label = Ui.text(this, TITLES[index], wide ? 16 : 12);
        label.setSingleLine(true);
        label.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER);
        label.setPadding(wide ? Ui.dp(this, 13) : 0, wide ? 0 : Ui.dp(this, 2), 0, 0);
        tab.addView(label, wide ? new LinearLayout.LayoutParams(0, -1, 1) :
            new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = wide ? new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)) :
            new LinearLayout.LayoutParams(0, -1, 1);
        if (wide) params.bottomMargin = Ui.dp(this, 4);
        else params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        navigation.addView(tab, params);
        tab.setContentDescription("打开" + TITLES[index]);
        tab.setFocusable(true);
        tab.setOnClickListener(v -> showPage(index));
        navItems[index] = tab;
        navLabels[index] = label;
        navIcons[index] = icon;
    }

    private void drawNavigation() {
        for (int i = 0; i < TITLES.length; i++) {
            boolean selected = i == page;
            navItems[i].setBackground(Ui.touchBackground(this,
                selected ? Ui.RAIL_SELECTED : Ui.RAIL, selected ? Ui.ACCENT : Ui.SURFACE_LIGHT, 12));
            navLabels[i].setTextColor(selected ? Ui.TEXT : Ui.MUTED);
            navIcons[i].setColorFilter(selected ? Ui.TEXT : Ui.MUTED);
            navItems[i].setSelected(selected);
        }
    }

    private void showPage(int index) {
        int target = index >= 0 && index < TITLES.length ? index : 0;
        if (target == page && content.getChildCount() > 0) return;
        if (content.getChildCount() > 0 && pages[page] instanceof PageLifecycle)
            ((PageLifecycle) pages[page]).onHostPause();
        page = target;
        drawNavigation();
        content.removeAllViews();
        View view = pages[page];
        boolean cached = view != null;
        if (view == null) {
            if (page == 0) view = new DashboardPage(this, this::showPage);
            else if (page == 1) view = new AppPage(this);
            else if (page == 2) view = new FilePage(this);
            else if (page == 3) view = new AutomationPage(this);
            else view = new ToolsPage(this);
            pages[page] = view;
        }
        content.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        if (cached && view instanceof PageLifecycle && resumedOnce) ((PageLifecycle) view).onHostResume();
    }

    void openTools() { showPage(4); }

    @Override public void onBackPressed() {
        if (page == 2 && pages[2] instanceof FilePage && ((FilePage) pages[2]).navigateBack()) return;
        super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
    }

    @Override protected void onResume() {
        super.onResume();
        if (resumedOnce && pages[page] instanceof PageLifecycle)
            ((PageLifecycle) pages[page]).onHostResume();
        resumedOnce = true;
    }

    @Override protected void onPause() {
        if (pages[page] instanceof PageLifecycle)
            ((PageLifecycle) pages[page]).onHostPause();
        super.onPause();
    }
}
