package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class AutomationPage extends LinearLayout implements PageLifecycle {
    private static final String[] TRIGGERS = {"车机开机", "Android 按键", "日志关键字", "日产 HMI 按键"};
    private static final String[] ACTIONS = {"打开应用", "停止运行应用（系统权限或 root）", "禁用应用（系统权限或 root）", "启用应用（系统权限或 root）",
        "播放/暂停", "下一曲", "上一曲", "音量+", "音量-", "返回", "桌面", "切换 Wi-Fi", "切换蓝牙（日产 HSAE）", "模拟按键", "播放", "暂停", "开启 Wi-Fi", "关闭 Wi-Fi", "开启日产蓝牙", "关闭日产蓝牙"};
    private static final String[] ACTION_CODES = {"LAUNCH", "STOP", "DISABLE", "ENABLE", "PLAY_PAUSE",
        "NEXT", "PREVIOUS", "VOL_UP", "VOL_DOWN", "BACK", "HOME", "WIFI", "BLUETOOTH", "KEYCODE", "PLAY", "PAUSE", "WIFI_ON", "WIFI_OFF", "BT_ON", "BT_OFF"};
    private final Activity activity;
    private final LinearLayout list;
    private final android.widget.TextView profileLabel;
    private final Ui.FlatSwitch master;
    private final Ui.FlatSwitch logSwitch;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private TextView latestRunText;
    private TextView androidStatusBadge;
    private TextView hmiStatusBadge;
    private TextView hmiStatusText;
    private boolean hostVisible;
    private int editing = -1;
    private final Runnable liveStatusRefresh = new Runnable() {
        @Override public void run() {
            if (!hostVisible) return;
            updateLiveStatus();
            stateHandler.postDelayed(this, 800L);
        }
    };

    AutomationPage(Activity a) {
        super(a);
        activity = a;
        setOrientation(VERTICAL);
        ScrollView scrolling = new ScrollView(a);
        scrolling.setFillViewport(false);
        scrolling.setVerticalScrollBarEnabled(true);
        LinearLayout body = Ui.column(a);
        body.setPadding(0, 0, 0, 0);
        scrolling.addView(body);
        addView(scrolling, new LayoutParams(-1, -1));
        LinearLayout profileCard = Ui.card(a);
        LinearLayout profiles = Ui.row(a);
        profiles.setGravity(android.view.Gravity.CENTER_VERTICAL);
        profileLabel = Ui.text(a, "", 17);
        profileLabel.setTextColor(Ui.ACCENT);
        profiles.addView(profileLabel, new LayoutParams(0, -2, 1));
        profiles.addView(Ui.text(a, "总开关", 14));
        master = new Ui.FlatSwitch(a);
        master.setContentDescription("自动化总开关");
        master.setChecked(Rules.enabled(a));
        master.setOnClickListener(v -> {
            boolean checked = !master.isChecked();
            Rules.setEnabled(a, checked);
            master.setChecked(checked);
            refresh();
            Ui.toast(a, checked ? "自动化已开启" : "自动化已暂停");
        });
        profiles.addView(master);
        profileCard.addView(profiles);
        LinearLayout profileActions = Ui.row(a);
        profileActions.addView(Ui.button(a, "切换方案", v -> selectProfile()));
        profileActions.addView(Ui.button(a, "新建方案", v -> newProfile()));
        profileActions.addView(Ui.button(a, "管理方案", v -> manageProfile()));
        profileCard.addView(Ui.actionGrid(a, profileActions));
        body.addView(profileCard, Ui.rowSpacing(a));
        LinearLayout actionCard = Ui.card(a);
        actionCard.addView(Ui.sectionHeader(a, "规则与监听", R.drawable.ic_hm_automation));
        logSwitch = new Ui.FlatSwitch(a);
        actionCard.addView(Ui.switchRow(a, "日志监听", "需要 READ_LOGS 权限", logSwitch,
            v -> toggleLogListener()), Ui.rowSpacing(a));
        LinearLayout toolbar = Ui.row(a);
        toolbar.addView(Ui.primaryButton(a, "添加规则", v -> { editing = -1; chooseTrigger(); }));
        toolbar.addView(Ui.button(a, "管理按键监听", v -> openAccessibilityTools()));
        actionCard.addView(Ui.actionGrid(a, toolbar));
        LinearLayout secondary = Ui.row(a);
        secondary.addView(Ui.button(a, "Android 按键诊断", v -> showLastKey()));
        secondary.addView(Ui.button(a, "日产按键诊断", v -> showHmiDiagnostics()));
        secondary.addView(Ui.button(a, "按键时间", v -> keyTiming()));
        secondary.addView(Ui.button(a, "媒体应用名单", v -> MediaRouting.settings(a)));
        secondary.addView(Ui.button(a, "导出最近日志", v -> exportRecentLogs()));
        secondary.addView(Ui.button(a, "导出配置", v -> exportRules()));
        secondary.addView(Ui.button(a, "导入配置", v -> importRules()));
        LayoutParams secondaryParams = new LayoutParams(-1, -2);
        secondaryParams.topMargin = Ui.dp(a, 8);
        actionCard.addView(Ui.actionGrid(a, secondary), secondaryParams);
        body.addView(actionCard, Ui.rowSpacing(a));
        list = Ui.column(a);
        list.setPadding(0, 0, 0, 0);
        body.addView(list, new LayoutParams(-1, -2));
        refresh();
    }

    private void refresh() {
        Rules.syncHmiService(activity);
        list.removeAllViews();
        profileLabel.setText("当前方案：" + Rules.profile(activity));
        master.setChecked(Rules.enabled(activity));
        logSwitch.setChecked(LogService.active());
        JSONArray all = Rules.load(activity);

        LinearLayout runCard = Ui.card(activity);
        LinearLayout runHeading = Ui.row(activity);
        runHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView runTitle = Ui.text(activity, "最近执行", 18);
        runTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        runHeading.addView(runTitle, new LayoutParams(0, -2, 1));
        runHeading.addView(Ui.button(activity, "查看记录", v -> showExecutionHistory()));
        runCard.addView(runHeading);
        latestRunText = Ui.text(activity, Rules.latestExecution(activity), 13);
        latestRunText.setTextColor(Ui.MUTED);
        runCard.addView(latestRunText, Ui.rowSpacing(activity));
        list.addView(runCard, Ui.rowSpacing(activity));

        LinearLayout keyCard = Ui.card(activity);
        LinearLayout keyHeading = Ui.row(activity);
        keyHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView keyTitle = Ui.text(activity, "按键映射", 18);
        keyTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        keyHeading.addView(keyTitle, new LayoutParams(0, -2, 1));
        boolean listening = KeyService.activeService() != null;
        androidStatusBadge = Ui.badge(activity, listening ? "监听已开启" : "监听未开启",
            listening ? 0xff254838 : 0xff4a3b2a, listening ? Ui.GREEN : Ui.AMBER);
        keyHeading.addView(androidStatusBadge);
        keyCard.addView(keyHeading);
        android.widget.TextView keyHint = Ui.text(activity,
            "Android 按键通过无障碍事件监听。日产方向盘与面板按键使用下方 HMI 固件回调；两类事件分开识别，不拦截原车动作。", 13);
        keyHint.setTextColor(Ui.MUTED);
        keyCard.addView(keyHint);
        LinearLayout mapActions = Ui.row(activity);
        mapActions.addView(Ui.button(activity, "添加按键映射", v -> { editing = -1; chooseKeyInput(); }));
        mapActions.addView(Ui.button(activity, "调整短按/长按", v -> keyTiming()));
        if (!listening) mapActions.addView(Ui.button(activity, "启用监听", v -> openAccessibilityTools()));
        keyCard.addView(Ui.actionGrid(activity, mapActions));

        int keyCount = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.optJSONObject(i);
            if (rule == null || !Rules.profile(activity).equals(rule.optString("profile", Rules.DEFAULT_PROFILE)) ||
                !Rules.TRIGGER_KEY.equals(rule.optString("trigger"))) continue;
            keyCount++;
            final int index = i;
            String key = keyLabel(rule.optString("match"));
            String target = rule.optString("target");
            String action = actionName(rule.optString("action")) + (target.isEmpty() ? "" : " · " + target);
            String state = rule.optBoolean("enabled", true) ? "已启用" : "已停用";
            keyCard.addView(ruleRow(key, action + "  ·  " + state,
                rule.optBoolean("enabled", true) ? "KEY" : "OFF", index, rule), Ui.rowSpacing(activity));
        }
        if (keyCount == 0) {
            android.widget.TextView empty = Ui.text(activity,
                listening ? "此方案还没有 Android 按键映射。" :
                    "Android 按键学习需要在工具箱开启车机管家按键监听服务。", 14);
            empty.setTextColor(Ui.MUTED);
            keyCard.addView(empty);
        }
        list.addView(keyCard, Ui.rowSpacing(activity));

        LinearLayout hmiCard = Ui.card(activity);
        LinearLayout hmiHeading = Ui.row(activity);
        hmiHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView hmiTitle = Ui.text(activity, "日产 HMI 按键映射", 18);
        hmiTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        hmiHeading.addView(hmiTitle, new LayoutParams(0, -2, 1));
        boolean hmiReady = NissanHmiAutomationService.isReady();
        hmiStatusBadge = Ui.badge(activity,
            hmiReady ? "固件已接通" : hmiBadgeLabel(activity),
            hmiReady ? 0xff254838 : 0xff4a3b2a, hmiReady ? Ui.GREEN : Ui.AMBER);
        hmiHeading.addView(hmiStatusBadge);
        hmiCard.addView(hmiHeading);
        hmiStatusText = Ui.text(activity,
            NissanHmiAutomationService.state(activity) +
                "。学习和执行时都保留 AutoCore 的原车处理；需要包含 HmiKeys 扩展并返回 0x62ff 能力确认的固件。", 13);
        hmiStatusText.setTextColor(Ui.MUTED);
        hmiCard.addView(hmiStatusText, Ui.rowSpacing(activity));
        LinearLayout hmiActions = Ui.row(activity);
        hmiActions.addView(Ui.button(activity, "添加日产按键映射", v -> {
            editing = -1;
            chooseNissanKeyInput();
        }));
        hmiActions.addView(Ui.button(activity, "诊断固件按键", v -> showHmiDiagnostics()));
        hmiCard.addView(Ui.actionGrid(activity, hmiActions), Ui.rowSpacing(activity));
        int hmiCount = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.optJSONObject(i);
            if (rule == null || !Rules.profile(activity).equals(rule.optString("profile", Rules.DEFAULT_PROFILE)) ||
                !Rules.TRIGGER_NISSAN_HMI.equals(rule.optString("trigger"))) continue;
            hmiCount++;
            final int index = i;
            int operation;
            try { operation = Integer.parseInt(rule.optString("match")); }
            catch (Exception ignored) { operation = -1; }
            String keyName = NissanHmiAutomationService.operationName(operation);
            String target = rule.optString("target");
            String detail = actionName(rule.optString("action")) +
                (target.isEmpty() ? "" : " · " + target) +
                (rule.optBoolean("enabled", true) ? " · 已启用" : " · 已停用");
            hmiCard.addView(ruleRow(keyName, detail,
                rule.optBoolean("enabled", true) ? "HMI" : "OFF", index, rule), Ui.rowSpacing(activity));
        }
        if (hmiCount == 0) {
            android.widget.TextView empty = Ui.text(activity,
                "此方案暂无日产 HMI 映射。按“添加日产按键映射”后学习实体按键，或从固件已知事件中选择。", 14);
            empty.setTextColor(Ui.MUTED);
            hmiCard.addView(empty);
        }
        list.addView(hmiCard, Ui.rowSpacing(activity));

        LinearLayout otherCard = Ui.card(activity);
        otherCard.addView(Ui.sectionHeader(activity, "开机与日志规则", R.drawable.ic_hm_logs));
        android.widget.TextView note = Ui.text(activity,
            "开机规则随系统启动触发；日志规则需要 READ_LOGS 权限。", 13);
        note.setTextColor(Ui.MUTED);
        otherCard.addView(note);
        int shown = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject r = all.optJSONObject(i);
            if (r == null || !Rules.profile(activity).equals(r.optString("profile", Rules.DEFAULT_PROFILE)) ||
                Rules.TRIGGER_KEY.equals(r.optString("trigger")) ||
                Rules.TRIGGER_NISSAN_HMI.equals(r.optString("trigger"))) continue;
            shown++;
            final int index = i;
            String trigger = triggerName(r.optString("trigger")) + " " + r.optString("match");
            String detail = (r.optBoolean("enabled", true) ? "已启用  ·  " : "已停用  ·  ") + actionName(r.optString("action")) + "  " + r.optString("target");
            otherCard.addView(ruleRow(trigger, detail, "AUTO", index, r),
                Ui.rowSpacing(activity));
        }
        if (shown == 0) {
            android.widget.TextView empty = Ui.text(activity, "此方案暂无开机或日志规则。", 14);
            empty.setTextColor(Ui.MUTED);
            otherCard.addView(empty);
        }
        list.addView(otherCard, Ui.rowSpacing(activity));
    }

    @Override public void onHostResume() {
        hostVisible = true;
        refresh();
        stateHandler.removeCallbacks(liveStatusRefresh);
        stateHandler.post(liveStatusRefresh);
    }

    @Override public void onHostPause() {
        hostVisible = false;
        stateHandler.removeCallbacks(liveStatusRefresh);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        hostVisible = true;
        stateHandler.removeCallbacks(liveStatusRefresh);
        stateHandler.post(liveStatusRefresh);
    }

    @Override protected void onDetachedFromWindow() {
        hostVisible = false;
        stateHandler.removeCallbacks(liveStatusRefresh);
        super.onDetachedFromWindow();
    }

    private void updateLiveStatus() {
        if (latestRunText != null) latestRunText.setText(Rules.latestExecution(activity));
        boolean keyReady = KeyService.activeService() != null;
        if (androidStatusBadge != null) {
            androidStatusBadge.setText(keyReady ? "监听已开启" : "监听未开启");
            androidStatusBadge.setTextColor(keyReady ? Ui.GREEN : Ui.AMBER);
            androidStatusBadge.setBackground(Ui.shape(activity,
                keyReady ? 0xff254838 : 0xff4a3b2a, 8));
        }
        boolean hmiReady = NissanHmiAutomationService.isReady();
        if (hmiStatusBadge != null) {
            hmiStatusBadge.setText(hmiReady ? "固件已接通" : hmiBadgeLabel(activity));
            hmiStatusBadge.setTextColor(hmiReady ? Ui.GREEN : Ui.AMBER);
            hmiStatusBadge.setBackground(Ui.shape(activity,
                hmiReady ? 0xff254838 : 0xff4a3b2a, 8));
        }
        if (hmiStatusText != null) hmiStatusText.setText(NissanHmiAutomationService.state(activity) +
            "。学习和执行时都保留 AutoCore 的原车处理；需要包含 HmiKeys 扩展并返回 0x62ff 能力确认的固件。");
    }

    private String hmiBadgeLabel(Activity context) {
        String current = NissanHmiAutomationService.state(context);
        if (current.contains("未启动")) return "未监听";
        if (current.contains("未找到")) return "服务未找到";
        if (current.contains("未提供")) return "固件不支持";
        if (current.contains("拒绝")) return "访问受限";
        if (current.contains("失败")) return "连接失败";
        return "连接中";
    }

    private void showExecutionHistory() {
        TextView details = Ui.text(activity, Rules.executionHistoryText(activity), 14);
        details.setTextIsSelectable(true);
        details.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8),
            Ui.dp(activity, 12), Ui.dp(activity, 8));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(details);
        Ui.dialog(activity).setTitle("自动化执行记录")
            .setView(scroll).setPositiveButton("完成", null).show();
    }

    private String keyLabel(String match) {
        String[] parts = match.split(":", 2);
        int code;
        try { code = Integer.parseInt(parts[0]); }
        catch (Exception ignored) { return "按键 " + match; }
        String name = keyName(code);
        String press = parts.length < 2 ? "按下" : parts[1].equals("SHORT") ? "短按" :
            parts[1].equals("DOUBLE") ? "双击" : parts[1].equals("LONG") ? "长按" : parts[1];
        return name + "  ·  " + press + "  ·  " + code;
    }

    private String keyName(int code) {
        String name;
        switch (code) {
            case 3: name = "主页键"; break;
            case 4: name = "返回键"; break;
            case 19: name = "方向上"; break;
            case 20: name = "方向下"; break;
            case 21: name = "方向左"; break;
            case 22: name = "方向右"; break;
            case 24: name = "音量加"; break;
            case 25: name = "音量减"; break;
            case 85: name = "播放/暂停"; break;
            case 87: name = "下一曲"; break;
            case 88: name = "上一曲"; break;
            case 164: name = "静音"; break;
            case 219: name = "语音助手"; break;
            default:
                String androidName = android.view.KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
                return "Android " + androidName;
        }
        return name;
    }

    private void showLastKey() {
        android.content.SharedPreferences prefs = activity.getSharedPreferences(Rules.PREF, 0);
        final int[] sequence = {prefs.getInt("lastKeySequence", 0)};
        final int[] received = {0};
        TextView status = Ui.text(activity, "", 14);
        status.setBackground(Ui.shape(activity, Ui.BUTTON, 12));
        TextView detail = Ui.text(activity, "", 15);
        detail.setBackground(Ui.shape(activity, Ui.SURFACE, 12));
        TextView hint = Ui.text(activity,
            "请按一下要检查的方向盘或面板按键。若“最近事件”没有更新时间，表示此按键没有作为 Android KeyEvent 送到车机管家；监听服务已连接也不代表车厂按键一定会转发。", 13);
        hint.setTextColor(Ui.MUTED);
        LinearLayout content = Ui.column(activity);
        content.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8), Ui.dp(activity, 12), Ui.dp(activity, 4));
        content.addView(status, Ui.rowSpacing(activity));
        content.addView(detail, Ui.rowSpacing(activity));
        content.addView(hint);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        AlertDialog dialog = Ui.dialog(activity).setTitle("按键事件诊断").setView(scroll)
            .setPositiveButton("完成", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                int currentSequence = prefs.getInt("lastKeySequence", 0);
                if (currentSequence != sequence[0]) {
                    int delta = currentSequence - sequence[0];
                    received[0] += delta > 0 ? delta : 1;
                    sequence[0] = currentSequence;
                }
                status.setText("监听服务：" + (KeyService.activeService() == null ? "未连接" : "已连接") +
                    "\n本窗口收到的事件更新：" + received[0] + " 次（按下和抬起分别计数）");
                long eventTime = prefs.getLong("lastKeyTime", 0L);
                if (eventTime <= 0L) {
                    detail.setText("最近事件：尚未捕获\n请按一下目标按键。\n\nAndroid 键值：—\n扫描码：—\n设备 ID：—");
                } else {
                    int code = prefs.getInt("lastKey", -1);
                    int action = prefs.getInt("lastKeyAction", -1);
                    int ageSeconds = (int) Math.max(0L, (System.currentTimeMillis() - eventTime) / 1000L);
                    String actionName = action == android.view.KeyEvent.ACTION_DOWN ? "按下" :
                        action == android.view.KeyEvent.ACTION_UP ? "抬起" : "动作 " + action;
                    detail.setText("最近事件：" + (code < 0 ? "未知按键" : keyName(code)) + " · " + actionName +
                        "\n事件时间：" + timeFormat.format(new java.util.Date(eventTime)) + "（" + ageSeconds + " 秒前）" +
                        "\n事件序号：" + prefs.getInt("lastKeySequence", 0) +
                        "\nAndroid 键值：" + code + (code < 0 ? "" : "  ·  " + android.view.KeyEvent.keyCodeToString(code)) +
                        "\n扫描码：" + prefs.getInt("lastScanCode", -1) +
                        "\n设备 ID：" + prefs.getInt("lastKeyDevice", -1) +
                        "\n重复次数：" + prefs.getInt("lastKeyRepeat", 0));
                }
                handler.postDelayed(this, 250L);
            }
        };
        dialog.setOnDismissListener(ignored -> handler.removeCallbacks(refresh));
        dialog.show();
        handler.post(refresh);
    }

    private void showHmiDiagnostics() {
        NissanHmiAutomationService.beginLearning(activity);
        TextView details = Ui.text(activity, NissanHmiAutomationService.diagnostics(activity), 14);
        details.setBackground(Ui.shape(activity, Ui.SURFACE, 12));
        details.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12),
            Ui.dp(activity, 14), Ui.dp(activity, 12));
        TextView hint = Ui.text(activity,
            "请在停车状态按方向盘或面板键。诊断期间自动化动作暂时暂停，但原车按键照常工作。若事件数不变，请核对固件是否含 AutoCore HmiKeys 扩展。", 13);
        hint.setTextColor(Ui.MUTED);
        LinearLayout content = Ui.column(activity);
        content.addView(details, Ui.rowSpacing(activity));
        content.addView(hint);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = Ui.dialog(activity).setTitle("日产固件按键诊断")
            .setView(scroll).setPositiveButton("完成", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable update = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                details.setText(NissanHmiAutomationService.diagnostics(activity));
                handler.postDelayed(this, 400L);
            }
        };
        dialog.setOnDismissListener(ignored -> {
            handler.removeCallbacks(update);
            NissanHmiAutomationService.finishLearning(activity);
            AutomationPage.this.refresh();
        });
        dialog.show();
        handler.post(update);
    }

    private String triggerName(String code) {
        if (code.equals(Rules.TRIGGER_BOOT)) return TRIGGERS[0];
        if (code.equals(Rules.TRIGGER_KEY)) return TRIGGERS[1];
        if (code.equals(Rules.TRIGGER_NISSAN_HMI)) return TRIGGERS[3];
        return TRIGGERS[2];
    }

    private String actionName(String code) {
        for (int i = 0; i < ACTION_CODES.length; i++) if (ACTION_CODES[i].equals(code)) return ACTIONS[i];
        return code;
    }

    private void ruleOptions(int index, JSONObject rule) {
        Ui.dialog(activity).setTitle("规则操作")
            .setItems(new String[]{"测试动作", "编辑规则", rule.optBoolean("enabled", true) ? "停用规则" : "启用规则", "删除规则"}, (d, which) -> {
                if (which == 0) {
                    new Thread(() -> Rules.execute(activity, rule.optString("action"), rule.optString("target"), KeyService.activeService())).start();
                    Ui.toast(activity, "已开始测试，执行结果会显示在“最近执行”中");
                } else if (which == 1) {
                    editing = index; chooseTrigger();
                } else if (which == 2) toggleRule(index, rule);
                else Ui.confirm(activity, "删除规则", "将删除这条自动化规则。", () -> {
                    JSONArray original = Rules.load(activity), updated = new JSONArray();
                    for (int i = 0; i < original.length(); i++) if (i != index) updated.put(original.optJSONObject(i));
                    Rules.save(activity, updated); refresh();
                });
            }).show();
    }

    private LinearLayout ruleRow(String title, String detail, String mark, int index, JSONObject rule) {
        LinearLayout item = Ui.listItem(activity, title, detail, mark, v -> ruleOptions(index, rule));
        item.removeViewAt(item.getChildCount() - 1);
        Ui.FlatSwitch toggle = new Ui.FlatSwitch(activity);
        toggle.setContentDescription(title + "规则开关");
        toggle.setChecked(rule.optBoolean("enabled", true));
        toggle.setOnClickListener(v -> toggleRule(index, rule));
        item.addView(toggle);
        return item;
    }

    private void toggleRule(int index, JSONObject rule) {
        boolean enabled = !rule.optBoolean("enabled", true);
        try {
            rule.put("enabled", enabled);
            JSONArray all = Rules.load(activity);
            all.put(index, rule);
            Rules.save(activity, all);
            refresh();
            Ui.toast(activity, enabled ? "规则已启用" : "规则已停用");
        } catch (Exception error) {
            Ui.toast(activity, "保存失败：" + error.getMessage());
        }
    }

    private void toggleLogListener() {
        if (LogService.active()) {
            activity.stopService(new Intent(activity, LogService.class));
            logSwitch.setChecked(false);
            Ui.toast(activity, "日志监听已停止");
        } else if (!Privilege.has(activity, "android.permission.READ_LOGS")) {
            Ui.toast(activity, "缺少 READ_LOGS 权限，无法启动日志监听");
        } else {
            activity.startService(new Intent(activity, LogService.class));
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                logSwitch.setChecked(LogService.active());
                Ui.toast(activity, LogService.active() ? "日志监听已开启" : "日志监听未能启动");
            }, 500);
        }
    }

    private java.util.ArrayList<String> profiles() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        names.add(Rules.DEFAULT_PROFILE);
        try {
            JSONArray saved = new JSONArray(activity.getSharedPreferences(Rules.PREF, 0).getString("profiles", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String name = saved.optString(i).trim();
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        } catch (Exception ignored) { }
        JSONArray rules = Rules.load(activity);
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            String name = rule.optString("profile", Rules.DEFAULT_PROFILE).trim();
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private void selectProfile() {
        java.util.ArrayList<String> names = profiles();
        Ui.dialog(activity).setTitle("选择方案")
            .setSingleChoiceItems(names.toArray(new String[0]), names.indexOf(Rules.profile(activity)), (d, which) -> {
                activity.getSharedPreferences(Rules.PREF, 0).edit().putString("profile", names.get(which)).apply();
                d.dismiss(); refresh();
            }).setNegativeButton("取消", null).show();
    }

    private void newProfile() {
        EditText input = Ui.input(activity);
        input.setSingleLine(true); input.setHint("方案名称");
        Ui.dialog(activity).setTitle("新建方案").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("创建", (d, w) -> {
                String name = input.getText().toString().trim();
                java.util.ArrayList<String> names = profiles();
                if (name.isEmpty() || name.length() > 30 || names.contains(name)) { Ui.toast(activity, "名称为空、过长或已存在"); return; }
                names.add(name);
                activity.getSharedPreferences(Rules.PREF, 0).edit().putString("profiles", new JSONArray(names).toString())
                    .putString("profile", name).apply();
                refresh();
            }).show();
    }

    private void manageProfile() {
        String current = Rules.profile(activity);
        Ui.dialog(activity).setTitle("管理方案：" + current)
            .setItems(new String[]{"复制方案", "重命名方案", "删除方案"}, (d, which) -> {
                if (which == 0) nameProfile(current, true);
                else if (which == 1) {
                    if (Rules.DEFAULT_PROFILE.equals(current)) Ui.toast(activity, "默认方案不可重命名");
                    else nameProfile(current, false);
                } else deleteProfile(current);
            }).show();
    }

    private void nameProfile(String current, boolean copy) {
        EditText input = Ui.input(activity);
        input.setText(copy ? current + " 副本" : current);
        Ui.dialog(activity).setTitle(copy ? "复制方案" : "重命名方案").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                String name = input.getText().toString().trim();
                java.util.ArrayList<String> names = profiles();
                if (name.isEmpty() || name.length() > 30 || names.contains(name)) { Ui.toast(activity, "名称为空、过长或已存在"); return; }
                JSONArray all = Rules.load(activity); int originalCount = all.length();
                try {
                    for (int i = 0; i < originalCount; i++) {
                        JSONObject rule = all.optJSONObject(i);
                        if (rule == null || !current.equals(rule.optString("profile", Rules.DEFAULT_PROFILE))) continue;
                        if (copy) { JSONObject duplicate = new JSONObject(rule.toString()); duplicate.put("profile", name); all.put(duplicate); }
                        else rule.put("profile", name);
                    }
                    if (!copy) names.remove(current);
                    names.add(name); Rules.save(activity, all);
                    activity.getSharedPreferences(Rules.PREF, 0).edit().putString("profiles", new JSONArray(names).toString())
                        .putString("profile", name).apply();
                    refresh();
                } catch (Exception e) { Ui.toast(activity, "保存失败：" + e.getMessage()); }
            }).show();
    }

    private void deleteProfile(String current) {
        if (Rules.DEFAULT_PROFILE.equals(current)) { Ui.toast(activity, "默认方案不可删除"); return; }
        Ui.confirm(activity, "删除方案", "删除“" + current + "”及其中所有规则？", () -> {
            JSONArray all = Rules.load(activity), remaining = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject rule = all.optJSONObject(i);
                if (rule != null && !current.equals(rule.optString("profile", Rules.DEFAULT_PROFILE))) remaining.put(rule);
            }
            java.util.ArrayList<String> names = profiles(); names.remove(current);
            Rules.save(activity, remaining);
            activity.getSharedPreferences(Rules.PREF, 0).edit().putString("profiles", new JSONArray(names).toString())
                .putString("profile", Rules.DEFAULT_PROFILE).apply();
            refresh();
        });
    }

    private void chooseTrigger() {
        Ui.dialog(activity).setTitle("触发条件").setItems(TRIGGERS, (d, which) -> {
            String trigger = which == 0 ? Rules.TRIGGER_BOOT : which == 1 ? Rules.TRIGGER_KEY :
                which == 2 ? Rules.TRIGGER_LOG : Rules.TRIGGER_NISSAN_HMI;
            if (which == 0) chooseAction(trigger, "");
            else if (which == 1) chooseKeyInput();
            else if (which == 2) chooseLogInput();
            else chooseNissanKeyInput();
        }).show();
    }

    private void chooseLogInput() {
        Ui.dialog(activity).setTitle("日志触发条件")
            .setItems(new String[]{"输入关键字", "从最近日志选择"}, (d, which) -> {
                if (which == 0) editLogMatch("");
                else {
                    if (!LogService.active()) { Ui.toast(activity, "请先启动日志监听，再操作车机后选择日志"); return; }
                    EditText filter = Ui.input(activity);
                    filter.setHint("输入筛选词，留空显示最近日志");
                    Ui.dialog(activity).setTitle("筛选最近日志").setView(filter)
                        .setNegativeButton("取消", null).setPositiveButton("查看", (dialog, button) -> {
                            String[] lines = LogService.recent(filter.getText().toString().trim());
                            if (lines.length == 0) { Ui.toast(activity, "没有匹配日志，请操作车机后重试"); return; }
                            Ui.dialog(activity).setTitle("选择日志并提取关键字")
                                .setItems(lines, (d2, index) -> editLogMatch(lines[index])).setNegativeButton("取消", null).show();
                        }).show();
                }
            }).show();
    }

    private void editLogMatch(String initial) {
        EditText input = Ui.input(activity);
        input.setText(initial); input.setHint("输入能稳定识别事件的关键字");
        Ui.dialog(activity).setTitle("日志关键字").setView(input)
            .setMessage("建议删除时间、进程号等每次会变化的内容。")
            .setNegativeButton("取消", null).setPositiveButton("下一步", (dialog, button) -> {
                String match = input.getText().toString().trim();
                if (match.isEmpty()) { Ui.toast(activity, "请输入日志关键字"); return; }
                chooseAction(Rules.TRIGGER_LOG, match);
            }).show();
    }

    private void chooseKeyInput() {
        Ui.dialog(activity).setTitle("获取按键")
            .setItems(new String[]{"按实际按键学习", "手动输入 Android 键值", "启用按键监听服务"}, (d, which) -> {
                if (which == 0) captureKey();
                else if (which == 2) openAccessibilityTools();
                else {
                    EditText input = Ui.input(activity);
                    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    input.setHint("例如 85");
                    Ui.dialog(activity).setTitle("Android 键值").setView(input)
                        .setNegativeButton("取消", null).setPositiveButton("下一步", (dialog, b) -> {
                            String code = input.getText().toString().trim();
                            if (!code.matches("[0-9]{1,3}") || Integer.parseInt(code) < 1 || Integer.parseInt(code) > 288) {
                                Ui.toast(activity, "Android 键值范围为 1 到 288"); return;
                            }
                            chooseKeyPress(code);
                        }).show();
                }
            }).show();
    }

    private void captureKey() {
        if (KeyService.activeService() == null) {
            Ui.dialog(activity).setTitle("先启用按键监听")
                .setMessage("在工具箱启用车机管家按键监听后再学习。部分车机专有按键可能不会发送 Android 键值。")
                .setNegativeButton("取消", null).setPositiveButton("打开工具箱", (d, w) -> openAccessibilityTools()).show();
            return;
        }
        AlertDialog dialog = Ui.dialog(activity).setTitle("学习按键")
            .setMessage("请在停车状态按一下要设置的方向盘或面板按键。车机原有按键动作仍会执行。")
            .setNegativeButton("取消", null).create();
        KeyService.capture((code, scan) -> activity.runOnUiThread(() -> {
            dialog.dismiss();
            Ui.toast(activity, "已捕获 Android 键值 " + code + "，扫描码 " + scan);
            chooseKeyPress(String.valueOf(code));
        }));
        dialog.setOnDismissListener(d -> KeyService.capture(null));
        dialog.show();
    }

    private void openAccessibilityTools() {
        ((MainActivity) activity).openTools();
    }

    private void keyTiming() {
        Ui.dialog(activity).setTitle("按键时间")
            .setItems(new String[]{"双击时间窗", "长按判定时间"}, (d, which) -> {
                final int[] values = {300, 400, 500, 600, 800, 1000};
                final String[] labels = {"300 毫秒", "400 毫秒", "500 毫秒", "600 毫秒", "800 毫秒", "1000 毫秒"};
                String key = which == 0 ? "doubleMs" : "longMs";
                int current = activity.getSharedPreferences(Rules.PREF, 0).getInt(key, which == 0 ? 400 : 600);
                int selected = 0; for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
                Ui.dialog(activity).setTitle(which == 0 ? "双击时间窗" : "长按判定时间")
                    .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                        activity.getSharedPreferences(Rules.PREF, 0).edit().putInt(key, values[index]).apply();
                        dialog.dismiss();
                    }).setNegativeButton("取消", null).show();
            }).show();
    }

    private void chooseKeyPress(String code) {
        String[] modes = {"按下时", "短按", "双击", "长按"};
        String[] suffix = {"", ":SHORT", ":DOUBLE", ":LONG"};
        Ui.dialog(activity).setTitle("按压方式").setItems(modes,
            (d, which) -> chooseAction(Rules.TRIGGER_KEY, code + suffix[which])).show();
    }

    private void chooseNissanKeyInput() {
        Ui.dialog(activity).setTitle("日产 HMI 按键")
            .setItems(new String[]{"学习实体按键", "从固件支持列表选择", "查看按键诊断"}, (d, which) -> {
                if (which == 0) captureNissanKey();
                else if (which == 1) chooseKnownNissanKey();
                else showHmiDiagnostics();
            }).show();
    }

    private void chooseKnownNissanKey() {
        int[] operations = NissanHmiAutomationService.supportedOperations();
        String[] labels = new String[operations.length];
        for (int i = 0; i < operations.length; i++)
            labels[i] = NissanHmiAutomationService.operationName(operations[i]) + " · 操作码 " + operations[i];
        Ui.dialog(activity).setTitle("选择固件按键")
            .setItems(labels, (d, which) -> chooseAction(Rules.TRIGGER_NISSAN_HMI,
                String.valueOf(operations[which])))
            .setNegativeButton("取消", null).show();
    }

    private void captureNissanKey() {
        NissanHmiAutomationService.beginLearning(activity);
        AlertDialog dialog = Ui.dialog(activity).setTitle("学习日产 HMI 按键")
            .setMessage("正在连接固件按键服务。\n\n" + NissanHmiAutomationService.state(activity) +
                "\n\n请按一下方向盘或面板按键。学习期间原车按键仍按固件逻辑执行。")
            .setNegativeButton("取消", null).create();
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                dialog.setMessage("正在连接固件按键服务。\n\n" + NissanHmiAutomationService.state(activity) +
                    "\n\n请按一下方向盘或面板按键。学习期间原车按键仍按固件逻辑执行。");
                handler.postDelayed(this, 500L);
            }
        };
        NissanHmiAutomationService.capture(operation -> activity.runOnUiThread(() -> {
            dialog.dismiss();
            Ui.toast(activity, "已学习：" + NissanHmiAutomationService.operationName(operation));
            chooseAction(Rules.TRIGGER_NISSAN_HMI, String.valueOf(operation));
        }));
        dialog.setOnDismissListener(ignored -> {
            handler.removeCallbacks(poll);
            NissanHmiAutomationService.capture(null);
            NissanHmiAutomationService.finishLearning(activity);
        });
        dialog.show();
        handler.post(poll);
    }

    private void chooseAction(String trigger, String match) {
        Ui.dialog(activity).setTitle("执行动作").setItems(ACTIONS, (d, which) -> {
            String action = ACTION_CODES[which];
            if (which <= 3) chooseApp(trigger, match, action);
            else if (action.equals("KEYCODE")) {
                EditText input = Ui.input(activity);
                input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setHint("Android 键值（1–288）");
                Ui.dialog(activity).setTitle("模拟按键").setView(input).setNegativeButton("取消", null)
                    .setPositiveButton("保存", (dialog, b) -> {
                        String code = input.getText().toString().trim();
                        if (!code.matches("[0-9]{1,3}") || Integer.parseInt(code) < 1 || Integer.parseInt(code) > 288) {
                            Ui.toast(activity, "请输入 1 到 288 的键值"); return;
                        }
                        add(trigger, match, action, code);
                    }).show();
            } else add(trigger, match, action, "");
        }).show();
    }

    private void chooseApp(String trigger, String match, String action) {
        List<ApplicationInfo> apps = new ArrayList<>(activity.getPackageManager().getInstalledApplications(0));
        Collections.sort(apps, (x, y) -> String.valueOf(activity.getPackageManager().getApplicationLabel(x))
            .compareToIgnoreCase(String.valueOf(activity.getPackageManager().getApplicationLabel(y))));
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++)
            names[i] = activity.getPackageManager().getApplicationLabel(apps.get(i)) + " · " + apps.get(i).packageName;
        Ui.dialog(activity).setTitle("选择应用").setItems(names, (d, which) -> {
            String pkg = apps.get(which).packageName;
            if (!action.equals("LAUNCH") && Rules.protectedPackage(activity, pkg)) {
                Ui.toast(activity, "不能对本应用或原版日产管家设置管理规则"); return;
            }
            if (action.equals("DISABLE") || action.equals("STOP"))
                Ui.confirm(activity, "确认自动化规则", "该规则将对 " + pkg + " 执行系统操作。", () -> add(trigger, match, action, pkg));
            else add(trigger, match, action, pkg);
        }).show();
    }

    private void add(String trigger, String match, String action, String target) {
        try {
            JSONObject rule = new JSONObject().put("trigger", trigger).put("match", match)
                .put("action", action).put("target", target).put("profile", Rules.profile(activity));
            JSONArray all = Rules.load(activity);
            if (editing >= 0 && editing < all.length()) {
                JSONObject old = all.optJSONObject(editing);
                rule.put("enabled", old == null || old.optBoolean("enabled", true));
                all.put(editing, rule);
            } else all.put(rule);
            editing = -1;
            Rules.save(activity, all);
            refresh();
        } catch (Exception e) { Ui.toast(activity, e.toString()); }
    }

    private void exportRecentLogs() {
        String[] lines = LogService.allRecent();
        if (lines.length == 0) { Ui.toast(activity, "没有最近日志，请先启动日志监听"); return; }
        File config = configFile();
        File file = new File(config.getParentFile(), "recent-log.txt");
        Ui.confirm(activity, "导出最近日志", "日志可能包含应用和设备信息，确认保存到 " + file + "？", () -> {
            try (FileOutputStream out = new FileOutputStream(file)) {
                for (String line : lines) out.write((line + "\n").getBytes("UTF-8"));
                Ui.toast(activity, "已导出 " + lines.length + " 条：" + file);
            } catch (Exception e) { Ui.toast(activity, "导出失败：" + e.getMessage()); }
        });
    }

    private File configFile() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HeadunitManager");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "automation.json");
    }

    private void exportRules() {
        File file = configFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            JSONObject data = new JSONObject().put("format", 1).put("rules", Rules.load(activity))
                .put("profiles", new JSONArray(profiles())).put("activeProfile", Rules.profile(activity))
                .put("master", Rules.enabled(activity)).put("media", MediaRouting.exportConfig(activity))
                .put("doubleMs", activity.getSharedPreferences(Rules.PREF, 0).getInt("doubleMs", 400))
                .put("longMs", activity.getSharedPreferences(Rules.PREF, 0).getInt("longMs", 600));
            out.write(data.toString(2).getBytes("UTF-8"));
            Ui.toast(activity, "已导出：" + file);
        } catch (Exception e) { Ui.toast(activity, "导出失败：" + e.getMessage()); }
    }

    private void importRules() {
        File file = configFile();
        Ui.confirm(activity, "导入配置", "从 " + file + " 导入，并覆盖当前规则？", () -> {
            try (FileInputStream in = new FileInputStream(file)) {
                if (file.length() > 128 * 1024) throw new Exception("配置文件过大");
                byte[] bytes = new byte[(int) file.length()];
                int read = 0, n;
                while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
                Object decoded = new org.json.JSONTokener(new String(bytes, 0, read, "UTF-8")).nextValue();
                JSONObject container = decoded instanceof JSONObject ? (JSONObject) decoded : null;
                JSONArray data = container == null && decoded instanceof JSONArray ? (JSONArray) decoded :
                    container == null ? null : container.optJSONArray("rules");
                if (data == null) throw new Exception("配置格式无效");
                JSONArray valid = new JSONArray();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject r = data.optJSONObject(i);
                    if (r == null) continue;
                    String trigger = r.optString("trigger"), action = r.optString("action");
                    if (!(trigger.equals(Rules.TRIGGER_BOOT) || trigger.equals(Rules.TRIGGER_KEY) ||
                        trigger.equals(Rules.TRIGGER_LOG) || trigger.equals(Rules.TRIGGER_NISSAN_HMI))) continue;
                    if (Rules.TRIGGER_NISSAN_HMI.equals(trigger)) {
                        boolean supported = false;
                        int operation;
                        try { operation = Integer.parseInt(r.optString("match")); }
                        catch (Exception ignored) { continue; }
                        for (int candidate : NissanHmiAutomationService.supportedOperations())
                            if (candidate == operation) supported = true;
                        if (!supported) continue;
                    }
                    boolean known = false;
                    for (String code : ACTION_CODES) if (code.equals(action)) known = true;
                    if (known) valid.put(r);
                }
                Rules.save(activity, valid);
                if (container != null) {
                    MediaRouting.importConfig(activity, container.optJSONObject("media"));
                    JSONArray names = container.optJSONArray("profiles");
                    String active = container.optString("activeProfile", Rules.DEFAULT_PROFILE);
                    activity.getSharedPreferences(Rules.PREF, 0).edit()
                        .putString("profiles", names == null ? "[]" : names.toString())
                        .putString("profile", active).putBoolean("master", container.optBoolean("master", true))
                        .putInt("doubleMs", Math.max(100, Math.min(2000, container.optInt("doubleMs", 400))))
                        .putInt("longMs", Math.max(100, Math.min(2000, container.optInt("longMs", 600)))).apply();
                }
                Rules.syncHmiService(activity);
                refresh(); Ui.toast(activity, "已导入 " + valid.length() + " 条规则");
            } catch (Exception e) { Ui.toast(activity, "导入失败：" + e.getMessage()); }
        });
    }
}
