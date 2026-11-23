package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import java.io.File;

final class ApkInstaller {
    private ApkInstaller() { }

    static void choose(Activity activity, File apk) {
        if (!apk.isFile() || !apk.canRead()) { Ui.toast(activity, "无法读取 APK"); return; }
        PackageInfo info;
        try { info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0); }
        catch (Exception e) { info = null; }
        if (info == null || info.packageName == null) { Ui.toast(activity, "无法识别此 APK，请确认文件完整"); return; }
        if ("com.nissan.appmanage".equals(info.packageName)) {
            Ui.toast(activity, "为保护原车功能，不通过此入口替换日产原版管家"); return;
        }
        boolean selfUpdate = activity.getPackageName().equals(info.packageName);
        String label = info.packageName + "\n版本：" + info.versionName + " (" + info.versionCode + ")";
        if (selfUpdate) label += "\n这是车机管家更新包；安装后应用可能自动关闭并由系统重启。";
        Ui.dialog(activity).setTitle(selfUpdate ? "更新车机管家" : "安装 APK").setMessage(label)
            .setNegativeButton("取消", null)
            .setNeutralButton("系统安装器", (d, w) -> standard(activity, apk))
            .setPositiveButton("静默安装", (d, w) -> {
                String detail = selfUpdate
                    ? "将更新车机管家。系统只接受签名兼容且版本允许的安装包。"
                    : "将直接安装或更新 " + infoPackage(activity, apk) + "。签名不匹配时系统会拒绝更新。";
                Ui.confirm(activity, selfUpdate ? "更新车机管家" : "静默安装", detail, () -> {
                    Ui.toast(activity, "正在安装，请稍候");
                    new Thread(() -> {
                        String result = Privilege.managementCommand("pm", "install", "-r", apk.getAbsolutePath());
                        boolean success = result.startsWith("退出码 0\n") && result.contains("Success");
                        String outcome = installOutcome(success, result);
                        activity.runOnUiThread(() -> {
                            AlertDialog.Builder dialog = Ui.dialog(activity)
                                .setTitle(success ? "安装完成" : "静默安装未完成")
                                .setMessage(outcome).setPositiveButton("好", null);
                            if (!success) dialog.setNeutralButton("使用系统安装器", (d2, w2) -> standard(activity, apk));
                            dialog.show();
                        });
                    }, "apk-install").start();
                });
            }).show();
    }

    private static String installOutcome(boolean success, String raw) {
        if (success) return "应用已安装或更新完成。";
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("update_incompatible")) return "现有应用与安装包签名不一致，无法覆盖安装。请使用与当前版本签名一致的更新包。";
        if (lower.contains("version_downgrade")) return "安装包版本低于设备上的版本，系统拒绝降级安装。";
        if (lower.contains("insufficient_storage") || lower.contains("not enough space")) return "设备存储空间不足，请先清理空间后重试。";
        if (lower.contains("invalid_apk") || lower.contains("parse_failed") || lower.contains("parse error")) return "安装包损坏或格式不受支持，请重新获取 APK。";
        if (lower.contains("permission denied") || lower.contains("缺少系统 uid")) return "当前权限不足，无法静默安装。可以改用系统安装器。";
        return "系统未能完成安装。请检查安装包、应用签名和设备存储空间；也可以尝试系统安装器。";
    }

    private static String infoPackage(Activity activity, File apk) {
        PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return info == null ? apk.getName() : info.packageName;
    }

    private static void standard(Activity activity, File apk) {
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        try { activity.startActivity(install); }
        catch (Exception e) { Ui.toast(activity, "无法打开系统安装器：" + e.getMessage()); }
    }
}
