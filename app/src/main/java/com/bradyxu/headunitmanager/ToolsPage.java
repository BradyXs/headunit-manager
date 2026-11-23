package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.SeekBar;
import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.net.InetSocketAddress;
import java.net.Socket;

final class ToolsPage extends ScrollView implements PageLifecycle {
    private final Activity activity;
    private final TextView diagnostics;
    private final TextView adbStatus;
    private final TextView bluetoothSdkStatus;
    private final TextView audioProbeStatus;
    private final TextView accessibilityStatus;
    private final MobileNetworkPanel mobileNetworkPanel;
    private final Ui.FlatSwitch accessibilityMaster;
    private final Ui.FlatSwitch wifiSwitch;
    private final Ui.FlatSwitch bluetoothSwitch;
    private final Ui.FlatSwitch adbSwitch;
    private final SeekBar volumeSlider;
    private final TextView volumeLabel;
    private final LinearLayout accessibilityList;
    private final Button[] accessibilityFilters = new Button[2];
    private final java.util.concurrent.atomic.AtomicBoolean systemLogExporting = new java.util.concurrent.atomic.AtomicBoolean(false);
    private boolean phoneCaptureStarting;
    private boolean phoneCaptureStopping;
    private java.util.List<AccessibilityTools.Service> accessibilityServices = java.util.Collections.emptyList();
    private java.util.Set<String> accessibilityEnabled = java.util.Collections.emptySet();
    private boolean showEnabledOnly;

    ToolsPage(Activity a) {
        super(a);
        activity = a;
        LinearLayout body = Ui.column(a);
        body.setPadding(0, 0, 0, 0);
        addView(body);
        LinearLayout deviceCard = Ui.card(a);
        deviceCard.addView(Ui.sectionHeader(a, "设备与权限", R.drawable.ic_hm_device));
        deviceCard.addView(Ui.button(a, "刷新设备诊断", v -> refresh()));
        diagnostics = Ui.text(a, "", 15);
        diagnostics.setTextColor(Ui.MUTED);
        diagnostics.setTypeface(android.graphics.Typeface.create("monospace", 0));
        deviceCard.addView(diagnostics);
        body.addView(deviceCard, Ui.rowSpacing(a));
        mobileNetworkPanel = new MobileNetworkPanel(a);
        body.addView(mobileNetworkPanel, Ui.rowSpacing(a));
        LinearLayout logCard = Ui.card(a);
        logCard.addView(Ui.sectionHeader(a, "系统运行日志", R.drawable.ic_hm_logs));
        TextView logHint = Ui.text(a, "复现通话、蓝牙或设置问题后立即导出，再用文件管理器将 Download/HeadunitManager 中的文件复制到 U 盘。日志可能包含设备和应用信息。", 13);
        logHint.setTextColor(Ui.MUTED);
        logCard.addView(logHint);
        logCard.addView(Ui.button(a, "导出系统日志", v -> exportSystemLogs()));
        TextView phoneCaptureHint = Ui.text(a,
            "复现通话问题前开始定向采集，结束后停止。仅保存 P350 状态日志，最多 10 分钟或 1000 行。", 13);
        phoneCaptureHint.setTextColor(Ui.MUTED);
        logCard.addView(phoneCaptureHint);
        LinearLayout captureActions = Ui.row(a);
        captureActions.addView(Ui.button(a, "开始电话诊断", v -> startPhoneCapture()));
        captureActions.addView(Ui.button(a, "结束并保存", v -> stopPhoneCapture()));
        logCard.addView(Ui.actionGrid(a, captureActions));
        body.addView(logCard, Ui.rowSpacing(a));
        LinearLayout networkCard = Ui.card(a);
        networkCard.addView(Ui.sectionHeader(a, "网络与声音", R.drawable.ic_hm_network));
        wifiSwitch = new Ui.FlatSwitch(a);
        networkCard.addView(Ui.switchRow(a, "Wi-Fi", "读取系统当前连接状态", wifiSwitch,
            v -> wifi()), Ui.rowSpacing(a));
        LinearLayout volumeHeading = Ui.row(a);
        volumeHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        volumeHeading.addView(Ui.text(a, "媒体音量", 15), new LinearLayout.LayoutParams(0, -2, 1));
        volumeLabel = Ui.text(a, "", 14);
        volumeLabel.setTextColor(Ui.ACCENT);
        volumeHeading.addView(volumeLabel);
        networkCard.addView(volumeHeading);
        volumeSlider = new SeekBar(a);
        Ui.styleVolumeSlider(volumeSlider);
        AudioManager audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        volumeSlider.setMax(audio == null ? 100 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumeLabel.setText(progress + " / " + bar.getMax());
                if (fromUser && audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        networkCard.addView(volumeSlider, new LinearLayout.LayoutParams(-1, Ui.dp(a, 44)));
        refreshVolume();
        networkCard.addView(Ui.label(a, "日产车机蓝牙"));
        bluetoothSdkStatus = Ui.text(a, "正在读取日产 SDK 状态…", 15);
        bluetoothSdkStatus.setTextColor(Ui.ACCENT);
        networkCard.addView(bluetoothSdkStatus);
        android.widget.TextView bluetoothHint = Ui.text(a,
            "状态由 AnwPhoneLink 私有 SDK 读取；开启或关闭后会等待 SDK 状态同步。", 13);
        bluetoothHint.setTextColor(Ui.MUTED);
        networkCard.addView(bluetoothHint);
        bluetoothSwitch = new Ui.FlatSwitch(a);
        networkCard.addView(Ui.switchRow(a, "日产蓝牙", "通过 HSAE SDK 开启或关闭", bluetoothSwitch,
            v -> hsaeBluetooth(!bluetoothSwitch.isChecked())), Ui.rowSpacing(a));
        LinearLayout vendorBluetooth = Ui.row(a);
        vendorBluetooth.addView(Ui.button(a, "刷新蓝牙状态", v -> refreshBluetoothStatus()));
        networkCard.addView(Ui.actionGrid(a, vendorBluetooth));
        networkCard.addView(Ui.label(a, "原车电话与媒体音路状态"));
        audioProbeStatus = Ui.text(a, "未读取。只显示原车 SDK 的数字状态，不读取联系人或设备地址。", 13);
        audioProbeStatus.setTextColor(Ui.MUTED);
        networkCard.addView(audioProbeStatus);
        networkCard.addView(Ui.button(a, "读取 HFP/A2DP 状态", v -> readAudioProbe()));
        body.addView(networkCard, Ui.rowSpacing(a));
        LinearLayout accessibilityCard = Ui.card(a);
        accessibilityCard.addView(Ui.sectionHeader(a, "辅助功能服务", R.drawable.ic_hm_accessibility));
        TextView accessibilityHint = Ui.text(a,
            "在工具箱内管理车机管家按键监听、DiPlay 等服务。服务可能读取屏幕内容或执行操作，只启用你信任的服务。", 13);
        accessibilityHint.setTextColor(Ui.MUTED);
        accessibilityCard.addView(accessibilityHint);
        accessibilityStatus = Ui.text(a, "正在读取辅助功能状态…", 14);
        accessibilityStatus.setTextColor(Ui.ACCENT);
        accessibilityCard.addView(accessibilityStatus);
        accessibilityMaster = new Ui.FlatSwitch(a);
        accessibilityCard.addView(Ui.switchRow(a, "辅助功能总开关", "关闭时保留已选服务",
            accessibilityMaster, v -> setAccessibilityMaster(!accessibilityMaster.isChecked())));
        LinearLayout accessibilityActions = Ui.row(a);
        accessibilityFilters[0] = Ui.button(a, "全部", v -> { showEnabledOnly = false; renderAccessibilityServices(); });
        accessibilityFilters[1] = Ui.button(a, "已启用", v -> { showEnabledOnly = true; renderAccessibilityServices(); });
        accessibilityActions.addView(accessibilityFilters[0]);
        accessibilityActions.addView(accessibilityFilters[1]);
        accessibilityActions.addView(Ui.button(a, "刷新", v -> manageAccessibilityServices()));
        accessibilityActions.addView(Ui.button(a, "诊断", v -> diagnoseAccessibility()));
        accessibilityCard.addView(Ui.actionGrid(a, accessibilityActions));
        accessibilityList = Ui.column(a);
        accessibilityList.setPadding(0, Ui.dp(a, 4), 0, 0);
        accessibilityCard.addView(accessibilityList);
        body.addView(accessibilityCard, Ui.rowSpacing(a));
        LinearLayout adbCard = Ui.card(a);
        adbCard.addView(Ui.sectionHeader(a, "ADB 连接", R.drawable.ic_hm_adb));
        android.widget.TextView adbNote = Ui.text(a,
            "无线连接可单独关闭，USB 调试由系统设置控制。开启后需在电脑执行 adb connect。", 13);
        adbNote.setTextColor(Ui.MUTED);
        adbCard.addView(adbNote);
        adbStatus = Ui.text(a, "正在读取无线 ADB 状态…", 14);
        adbStatus.setTextColor(Ui.ACCENT);
        adbCard.addView(adbStatus);
        adbSwitch = new Ui.FlatSwitch(a);
        adbCard.addView(Ui.switchRow(a, "无线 ADB", "TCP 5555 端口", adbSwitch,
            v -> setWirelessAdb(!adbSwitch.isChecked())), Ui.rowSpacing(a));
        LinearLayout adb = Ui.row(a);
        adb.addView(Ui.button(a, "开发者设置", v -> a.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))));
        adbCard.addView(Ui.actionGrid(a, adb));
        body.addView(adbCard, Ui.rowSpacing(a));
        refresh();
        manageAccessibilityServices();
    }

    private void startPhoneCapture() {
        if (phoneCaptureStarting || phoneCaptureStopping) {
            Ui.toast(activity, "电话诊断正在处理，请稍候");
            return;
        }
        if (!Privilege.has(activity, "android.permission.READ_LOGS")) {
            Ui.toast(activity, "需要车机管家平台版的 READ_LOGS 权限");
            return;
        }
        if (PhoneDiagnosticCaptureService.active()) {
            Ui.toast(activity, "电话诊断正在采集");
            return;
        }
        phoneCaptureStarting = true;
        PhoneDiagnosticCaptureService.prepareStart();
        try {
            if (activity.startService(new Intent(activity, PhoneDiagnosticCaptureService.class)) == null) {
                phoneCaptureStarting = false;
                showPhoneCaptureResult("电话诊断未启动", "系统未启动采集服务，请导出系统日志检查原因。");
                return;
            }
        } catch (Exception error) {
            phoneCaptureStarting = false;
            showPhoneCaptureResult("电话诊断未启动", error.getClass().getSimpleName() + ": " + error.getMessage());
            return;
        }
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final long deadline = android.os.SystemClock.uptimeMillis() + 5000L;
        final Runnable[] check = new Runnable[1];
        check[0] = () -> {
            if (PhoneDiagnosticCaptureService.ready()) {
                phoneCaptureStarting = false;
                Ui.toast(activity, "电话诊断已开始；请在 10 分钟内复现通话");
            } else if (!PhoneDiagnosticCaptureService.latestError().isEmpty() ||
                    android.os.SystemClock.uptimeMillis() >= deadline) {
                phoneCaptureStarting = false;
                String error = PhoneDiagnosticCaptureService.latestError();
                showPhoneCaptureResult("电话诊断未启动", error.isEmpty() ?
                    "5 秒内未确认采集文件已打开，请导出系统日志检查 P350Capture。" : error);
            } else {
                main.postDelayed(check[0], 100L);
            }
        };
        main.postDelayed(check[0], 100L);
    }

    private void stopPhoneCapture() {
        if (phoneCaptureStarting || phoneCaptureStopping) {
            Ui.toast(activity, "电话诊断正在处理，请稍候");
            return;
        }
        if (!PhoneDiagnosticCaptureService.active()) {
            Ui.toast(activity, "电话诊断未在采集");
            return;
        }
        phoneCaptureStopping = true;
        activity.stopService(new Intent(activity, PhoneDiagnosticCaptureService.class));
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final long deadline = android.os.SystemClock.uptimeMillis() + 10000L;
        final Runnable[] check = new Runnable[1];
        check[0] = () -> {
            String path = PhoneDiagnosticCaptureService.latestPath();
            String error = PhoneDiagnosticCaptureService.latestError();
            if (!path.isEmpty() && !PhoneDiagnosticCaptureService.active()) {
                phoneCaptureStopping = false;
                File file = new File(path);
                if (file.isFile() && file.length() > 0L) {
                    showPhoneCaptureResult("电话诊断已保存", "文件位置：\n" + path +
                        "\n大小：" + file.length() + " 字节。请用文件管理器复制到 U 盘。");
                } else {
                    showPhoneCaptureResult("电话诊断保存失败", "采集服务报告的文件不存在或为空：\n" + path);
                }
            } else if (!error.isEmpty() || !PhoneDiagnosticCaptureService.active()) {
                phoneCaptureStopping = false;
                showPhoneCaptureResult("电话诊断保存失败", error.isEmpty() ?
                    "采集服务已退出，但没有生成文件。请导出系统日志检查 P350Capture。" : error);
            } else if (android.os.SystemClock.uptimeMillis() >= deadline) {
                phoneCaptureStopping = false;
                showPhoneCaptureResult("电话诊断仍在保存", "10 秒内未完成。稍后检查 Download/HeadunitManager；若仍无文件，请导出系统日志检查 P350Capture。");
            } else {
                main.postDelayed(check[0], 100L);
            }
        };
        main.postDelayed(check[0], 100L);
    }

    private void showPhoneCaptureResult(String title, String message) {
        Ui.dialog(activity).setTitle(title).setMessage(message).setPositiveButton("确定", null).show();
    }

    private void exportSystemLogs() {
        if (!systemLogExporting.compareAndSet(false, true)) {
            Ui.toast(activity, "系统日志仍在导出，请稍候");
            return;
        }
        if (!Privilege.has(activity, "android.permission.READ_LOGS")) {
            systemLogExporting.set(false);
            Ui.dialog(activity).setTitle("无法导出系统日志")
                .setMessage("当前应用没有 READ_LOGS 权限，请检查是否安装了车机管家平台版。")
                .setPositiveButton("确定", null).show();
            return;
        }
        final android.app.Dialog progress = Ui.progress(activity, "正在导出系统日志…");
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean timeoutShown = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable timeout = () -> {
            if (finished.get() || !timeoutShown.compareAndSet(false, true)) return;
            progress.dismiss();
            Ui.dialog(activity).setTitle("系统日志仍在保存")
                .setMessage("读取已按时间限制结束，后台正在保存已取得的日志；完成后会显示文件位置。")
                .setPositiveButton("确定", null).show();
        };
        main.postDelayed(timeout, 45000L);
        new Thread(() -> {
            String result;
            try {
                SystemLogExporter.Result saved = SystemLogExporter.export();
                result = "已保存 " + saved.lines + " 行到：\n" + saved.file.getAbsolutePath() +
                    "\n" + saved.notes + "\n请使用文件管理器复制到 U 盘。日志可能含设备和应用信息。";
            } catch (Exception error) {
                result = "导出失败：" + error.getClass().getSimpleName() + " " + String.valueOf(error.getMessage());
            }
            final String message = result;
            activity.runOnUiThread(() -> {
                systemLogExporting.set(false);
                finished.set(true);
                main.removeCallbacks(timeout);
                if (!timeoutShown.get()) progress.dismiss();
                Ui.dialog(activity).setTitle("系统日志导出结果")
                    .setMessage(message).setPositiveButton("确定", null).show();
            });
        }, "system-log-export").start();
    }

    private void refresh() {
        mobileNetworkPanel.refresh();
        refreshWifiStatus();
        refreshVolume();
        refreshBluetoothStatus();
        refreshAccessibilityStatus();
        new Thread(() -> {
            StringBuilder s = new StringBuilder();
            s.append("Android：").append(android.os.Build.VERSION.RELEASE).append(" / API ")
                .append(android.os.Build.VERSION.SDK_INT).append('\n');
            s.append("设备：").append(android.os.Build.DEVICE).append(" · ").append(android.os.Build.MODEL).append('\n');
            s.append("构建：").append(android.os.Build.DISPLAY).append('\n');
            s.append("进程身份：").append(Privilege.run("id").trim()).append('\n');
            s.append("系统 UID：").append(Privilege.systemUid() ? "是" : "否").append('\n');
            s.append("root：").append(Privilege.rootAvailable() ? "可用" : "不可用").append('\n');
            s.append("READ_LOGS：").append(Privilege.has(activity, "android.permission.READ_LOGS") ? "已授权" : "未授权").append('\n');
            s.append("HSAE AnwSdkService：").append(HsaeBluetooth.available(activity) ? "已安装" : "未安装").append('\n');
            s.append("AnwSdkService 文件：").append(HsaeBluetooth.installedAnwApk(activity)).append('\n');
            s.append("adbd 状态：").append(Privilege.propertyValue("init.svc.adbd")).append('\n');
            s.append("无线 ADB 端口：").append(Privilege.propertyValue("service.adb.tcp.port")).append('\n');
            s.append("USB 配置：").append(Privilege.propertyValue("sys.usb.config"));
            boolean listening = localAdbPortOpen();
            String port = Privilege.propertyValue("service.adb.tcp.port");
            activity.runOnUiThread(() -> {
                diagnostics.setText(s.toString());
                adbStatus.setText("本机 5555 端口：" + (listening ? "正在监听" : "未检测到监听") + "  ·  端口配置：" + port);
                adbSwitch.setChecked(listening && "5555".equals(port));
            });
        }).start();
    }

    @Override public void onHostResume() { refresh(); manageAccessibilityServices(); }

    @Override public void onHostPause() { mobileNetworkPanel.pause(); }

    private void refreshAccessibilityStatus() {
        new Thread(() -> {
            AccessibilityTools.State state;
            String summary;
            try {
                state = AccessibilityTools.readState(activity);
                summary = state.summary();
            } catch (RuntimeException error) {
                activity.runOnUiThread(() -> accessibilityStatus.setText("读取辅助功能状态失败，请重试。"));
                return;
            }
            activity.runOnUiThread(() -> {
                accessibilityStatus.setText(summary);
                accessibilityStatus.setTextColor(state.masterEnabled ? Ui.GREEN : Ui.AMBER);
                accessibilityMaster.setChecked(state.masterEnabled);
            });
        }, "accessibility-status").start();
    }

    private void manageAccessibilityServices() {
        accessibilityStatus.setText("正在读取辅助服务…");
        new Thread(() -> {
            java.util.List<AccessibilityTools.Service> services;
            java.util.Set<String> enabled;
            try {
                services = AccessibilityTools.installedServices(activity);
                enabled = AccessibilityTools.enabledIds(activity);
            } catch (RuntimeException error) {
                String message = "读取辅助服务失败：" + String.valueOf(error.getMessage());
                activity.runOnUiThread(() -> showAccessibilityResult("辅助服务", message));
                return;
            }
            activity.runOnUiThread(() -> {
                accessibilityServices = services;
                accessibilityEnabled = enabled;
                renderAccessibilityServices();
                refreshAccessibilityStatus();
            });
        }, "accessibility-list").start();
    }

    private void renderAccessibilityServices() {
        Ui.selectedButton(accessibilityFilters[0], !showEnabledOnly);
        Ui.selectedButton(accessibilityFilters[1], showEnabledOnly);
        accessibilityList.removeAllViews();
        int shown = 0;
        for (AccessibilityTools.Service service : accessibilityServices) {
            boolean active = accessibilityEnabled.contains(service.id);
            if (showEnabledOnly && !active) continue;
            shown++;
            LinearLayout row = Ui.row(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8), Ui.dp(activity, 12), Ui.dp(activity, 8));
            row.setBackground(Ui.touchBackground(activity, Ui.SURFACE, Ui.SURFACE_LIGHT, 12));
            row.setFocusable(true);
            LinearLayout words = Ui.column(activity);
            words.setPadding(0, 0, Ui.dp(activity, 8), 0);
            TextView name = Ui.text(activity, service.label, 15);
            name.setPadding(0, 0, 0, 0);
            words.addView(name);
            TextView detail = Ui.text(activity, service.packageName + (active ? "  ·  已启用" : "  ·  已关闭"), 12);
            detail.setTextColor(Ui.MUTED);
            detail.setPadding(0, 0, 0, 0);
            words.addView(detail);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            Ui.FlatSwitch toggle = new Ui.FlatSwitch(activity);
            toggle.setFocusable(false);
            toggle.setContentDescription(service.label);
            toggle.setChecked(active);
            row.addView(toggle);
            View.OnClickListener click = v -> toggleAccessibility(service, !active);
            toggle.setOnClickListener(click);
            row.setOnClickListener(click);
            accessibilityList.addView(row, Ui.rowSpacing(activity));
        }
        if (shown == 0) accessibilityList.addView(Ui.text(activity,
            accessibilityServices.isEmpty() ? "没有发现已安装的辅助服务。" : "暂无已启用的辅助服务。", 14));
    }

    private void toggleAccessibility(AccessibilityTools.Service service, boolean enabled) {
        final android.app.Dialog progress = Ui.progress(activity, enabled ? "正在开启辅助服务…" : "正在关闭辅助服务…");
        new Thread(() -> {
            String result;
            java.util.Set<String> actual;
            try {
                result = AccessibilityTools.toggleService(activity, service, enabled);
                actual = AccessibilityTools.enabledIds(activity);
            } catch (RuntimeException error) {
                result = "辅助服务设置失败：" + String.valueOf(error.getMessage());
                actual = accessibilityEnabled;
            }
            final String message = result;
            final java.util.Set<String> current = actual;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                accessibilityEnabled = current;
                renderAccessibilityServices();
                refreshAccessibilityStatus();
                Ui.toast(activity, message);
            });
        }, "accessibility-toggle").start();
    }

    private void setAccessibilityMaster(boolean enabled) {
        String title = enabled ? "开启辅助功能总开关" : "关闭辅助功能总开关";
        String detail = enabled
            ? "开启后，当前已勾选的辅助服务都可以运行。只启用你信任的服务。"
            : "关闭后，所有辅助服务会暂停；服务勾选状态会保留。";
        Ui.confirm(activity, title, detail, () -> {
            final android.app.Dialog progress = Ui.progress(activity, "正在更新辅助功能…");
            new Thread(() -> {
                String result = AccessibilityTools.setMaster(activity, enabled);
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    Ui.toast(activity, result);
                    refreshAccessibilityStatus();
                });
            }, "accessibility-master").start();
        });
    }

    private void diagnoseAccessibility() {
        Ui.toast(activity, "正在读取无障碍授权信息…");
        new Thread(() -> {
            String report = AccessibilityTools.diagnostic(activity);
            activity.runOnUiThread(() -> Ui.dialog(activity).setTitle("无障碍授权诊断")
                .setMessage(report).setPositiveButton("关闭", null).show());
        }, "accessibility-diagnostic").start();
    }

    private void showAccessibilityResult(String title, String message) {
        Ui.dialog(activity).setTitle(title).setMessage(message)
            .setPositiveButton("确定", null).show();
    }

    private void refreshBluetoothStatus() {
        bluetoothSdkStatus.setText("正在读取日产 SDK 状态…");
        bluetoothSdkStatus.setTextColor(Ui.ACCENT);
        HsaeBluetooth.readStatus(activity, (status, error) -> {
            if (status == null) {
                bluetoothSdkStatus.setText("暂时无法读取，请检查日产蓝牙服务或稍后重试。");
                bluetoothSdkStatus.setTextColor(Ui.AMBER);
            } else if (status == 1) {
                bluetoothSdkStatus.setText("蓝牙已开启");
                bluetoothSdkStatus.setTextColor(Ui.GREEN);
                bluetoothSwitch.setChecked(true);
            } else if (status == 0) {
                bluetoothSdkStatus.setText("蓝牙已关闭");
                bluetoothSdkStatus.setTextColor(Ui.MUTED);
                bluetoothSwitch.setChecked(false);
            } else {
                bluetoothSdkStatus.setText("状态无法识别，请重新读取。");
                bluetoothSdkStatus.setTextColor(Ui.AMBER);
            }
        });
    }

    private void readAudioProbe() {
        audioProbeStatus.setText("正在读取原车音路状态…");
        HsaeBluetooth.readAudioProbe(activity, result -> audioProbeStatus.setText(result));
    }

    private void wifi() {
        WifiManager manager = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (manager == null) { Ui.toast(activity, "没有 Wi-Fi 服务"); return; }
        boolean enable = !manager.isWifiEnabled();
        Ui.confirm(activity, "切换 Wi-Fi", enable ? "开启 Wi-Fi？" : "关闭 Wi-Fi 会断开热点或手机连接。", () -> {
            boolean accepted = manager.setWifiEnabled(enable);
            Ui.toast(activity, accepted ? "已请求" + (enable ? "开启" : "关闭") + " Wi-Fi，系统正在切换。" :
                "系统未接受 Wi-Fi 切换请求。");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshWifiStatus, 1200);
        });
    }

    private void refreshWifiStatus() {
        WifiManager manager = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiSwitch.setChecked(manager != null && manager.isWifiEnabled());
    }

    private void refreshVolume() {
        AudioManager manager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) { volumeSlider.setEnabled(false); volumeLabel.setText("不可用"); return; }
        volumeSlider.setMax(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeSlider.setProgress(manager.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    private void hsaeBluetooth(boolean enable) {
        Ui.confirm(activity, enable ? "开启日产蓝牙" : "关闭日产蓝牙",
            "通过车机 AnwPhoneLink SDK " + (enable ? "开启" : "关闭") + "蓝牙，并读取操作后的实际状态。", () -> {
                final android.app.Dialog progress = Ui.progress(activity, "正在切换日产蓝牙…");
                HsaeBluetooth.power(activity, enable, result -> {
                    progress.dismiss();
                    refreshBluetoothStatus();
                    Ui.dialog(activity).setTitle("日产蓝牙操作结果")
                        .setMessage(result).setPositiveButton("确定", null).show();
                });
            });
    }

    private void setWirelessAdb(boolean enable) {
        Ui.confirm(activity, enable ? "开启无线 ADB" : "关闭无线 ADB",
            enable ? "将把 adbd 切换到 TCP 5555，可能中断当前调试连接。" :
                "将关闭 TCP 5555 并切回 USB 模式，当前无线连接会断开。USB 调试状态由系统设置控制。", () -> {
                final android.app.Dialog progress = Ui.progress(activity, "正在切换 ADB 连接…");
                new Thread(() -> {
                String result = changeAdbTransport(enable);
                String display = result;
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    Ui.dialog(activity).setTitle("ADB 操作结果")
                        .setMessage(display).setPositiveButton("确定", null).show();
                    refresh();
                });
                }).start();
            });
    }

    private String changeAdbTransport(boolean enable) {
        if (!Privilege.managementAvailable()) return "需要系统 UID 或 root；本次未执行。";
        String requested = enable ? "5555" : "0";
        String identity = Privilege.systemUid() ? "系统 UID" : "root";
        String set = Privilege.managementCommand("setprop", "service.adb.tcp.port", requested);
        String actual = Privilege.propertyValue("service.adb.tcp.port");
        if (!requested.equals(actual))
            return identity + " 写入端口属性失败。\n" + set + "当前值：" + actual;
        Privilege.managementCommand("setprop", "ctl.restart", "adbd");
        boolean matched = false;
        int consecutive = 0;
        for (int i = 0; i < 12; i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (localAdbPortOpen() == enable) consecutive++; else consecutive = 0;
            if (consecutive >= 3) { matched = true; break; }
        }
        String state = Privilege.propertyValue("init.svc.adbd");
        String observed = Privilege.propertyValue("service.adb.tcp.port");
        if (matched)
            return (enable ? "已确认本机 TCP 5555 正在监听。" : "已连续检测到本机 TCP 5555 未监听；请在电脑端确认无线连接已断开。USB 调试状态请在开发者设置中查看。") +
                "\nadbd：" + state + "，端口属性：" + observed;
        return "端口配置已写入，但尚未确认本机 5555 端口状态完成切换。\n" +
            "adbd：" + state + "，端口属性：" + observed +
            "\n请在电脑端验证连接，或查看车机日志中的 SELinux 拒绝记录。";
    }

    private static boolean localAdbPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 800);
            return true;
        } catch (Exception e) { return false; }
    }
}
