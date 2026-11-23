package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class DashboardPage extends ScrollView {
    interface Navigate { void open(int page); }

    DashboardPage(Activity activity, Navigate navigate) {
        super(activity);
        setFillViewport(true);
        LinearLayout body = Ui.column(activity);
        body.setPadding(0, 0, 0, 0);
        addView(body);

        LinearLayout hero = Ui.row(activity);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(Ui.dp(activity, 22), Ui.dp(activity, 18), Ui.dp(activity, 22), Ui.dp(activity, 18));
        hero.setBackground(Ui.shape(activity, Ui.SURFACE, 20));
        LinearLayout words = Ui.column(activity);
        words.setPadding(0, 0, 0, 0);
        TextView eyebrow = Ui.label(activity, "车机管理");
        eyebrow.setTextColor(Ui.ACCENT);
        words.addView(eyebrow);
        TextView headline = Ui.text(activity, "管理这台车机", 24);
        headline.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        words.addView(headline);
        TextView detail = Ui.text(activity, "应用、文件、自动化与设备设置", 13);
        detail.setTextColor(Ui.MUTED);
        words.addView(detail);
        hero.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.ImageView vehicle = Ui.icon(activity, R.drawable.ic_hm_vehicle, Ui.ACCENT);
        vehicle.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12));
        vehicle.setBackground(Ui.shape(activity, Ui.BUTTON, 16));
        hero.addView(vehicle, new LinearLayout.LayoutParams(Ui.dp(activity, 64), Ui.dp(activity, 64)));
        body.addView(hero, new LinearLayout.LayoutParams(-1, Ui.dp(activity, 126)));

        TextView section = Ui.label(activity, "常用入口");
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2);
        sectionParams.topMargin = Ui.dp(activity, 14);
        body.addView(section, sectionParams);
        if (Ui.contentWidthDp(activity) < 620) {
            body.addView(feature(activity, R.drawable.ic_hm_apps, "应用管理", "查找、备份与启停应用", () -> navigate.open(1)), fullWidth(activity));
            body.addView(feature(activity, R.drawable.ic_hm_files, "文件管理", "浏览存储、安装 APK", () -> navigate.open(2)), fullWidth(activity));
            body.addView(feature(activity, R.drawable.ic_hm_automation, "自动化", "设置开机和按键规则", () -> navigate.open(3)), fullWidth(activity));
            body.addView(feature(activity, R.drawable.ic_hm_tools, "工具箱", "设备状态与连接控制", () -> navigate.open(4)), fullWidth(activity));
        } else {
            LinearLayout first = Ui.row(activity);
            first.addView(feature(activity, R.drawable.ic_hm_apps, "应用管理", "查找、备份与启停应用", () -> navigate.open(1)), weighted(activity));
            first.addView(feature(activity, R.drawable.ic_hm_files, "文件管理", "浏览存储、安装 APK", () -> navigate.open(2)), weighted(activity));
            body.addView(first);
            LinearLayout second = Ui.row(activity);
            second.addView(feature(activity, R.drawable.ic_hm_automation, "自动化", "设置开机和按键规则", () -> navigate.open(3)), weighted(activity));
            second.addView(feature(activity, R.drawable.ic_hm_tools, "工具箱", "设备状态与连接控制", () -> navigate.open(4)), weighted(activity));
            body.addView(second);
        }

        TextView hint = Ui.text(activity,
            "操作提示  ·  应用禁用、清除数据及无线 ADB 会影响车机运行，请在停车状态下使用。", 13);
        hint.setTextColor(Ui.MUTED);
        hint.setBackground(Ui.shape(activity, Ui.BUTTON, 12));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = Ui.dp(activity, 8);
        body.addView(hint, hintParams);
    }

    private static LinearLayout.LayoutParams weighted(Activity activity) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(activity, 104), 1);
        p.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4));
        return p;
    }

    private static LinearLayout.LayoutParams fullWidth(Activity activity) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 90));
        p.bottomMargin = Ui.dp(activity, 8);
        return p;
    }

    private static View feature(Activity activity, int icon, String title, String detail, Runnable open) {
        LinearLayout item = Ui.row(activity);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 12), Ui.dp(activity, 14), Ui.dp(activity, 12));
        item.setBackground(Ui.touchBackground(activity, Ui.SURFACE, Ui.SURFACE_LIGHT, 18));
        android.widget.ImageView symbol = Ui.icon(activity, icon, Ui.ACCENT);
        symbol.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 8), Ui.dp(activity, 8));
        symbol.setBackground(Ui.shape(activity, Ui.BUTTON, 12));
        item.addView(symbol, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        LinearLayout words = Ui.column(activity);
        words.setPadding(Ui.dp(activity, 8), 0, 0, 0);
        TextView name = Ui.text(activity, title, 18);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        words.addView(name);
        TextView description = Ui.text(activity, detail, 12);
        description.setTextColor(Ui.MUTED);
        words.addView(description);
        item.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        item.setFocusable(true);
        item.setOnClickListener(v -> open.run());
        return item;
    }
}
