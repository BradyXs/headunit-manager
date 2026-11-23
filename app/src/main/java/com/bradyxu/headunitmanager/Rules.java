package com.bradyxu.headunitmanager;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class Rules {
    static final String PREF = "automation";
    static final String TRIGGER_BOOT = "BOOT";
    static final String TRIGGER_KEY = "KEY";
    static final String TRIGGER_LOG = "LOG";
    static final String TRIGGER_NISSAN_HMI = "NISSAN_HMI";
    private static final String HISTORY_KEY = "executionHistory";
    private static final int HISTORY_LIMIT = 20;
    private static final Map<String, Long> lastFire = new HashMap<>();
    static final String DEFAULT_PROFILE = "默认方案";

    private Rules() { }

    static String profile(Context context) {
        return context.getSharedPreferences(PREF, 0).getString("profile", DEFAULT_PROFILE);
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREF, 0).getBoolean("master", true);
    }

    static boolean active(Context context, JSONObject rule) {
        return enabled(context) && rule.optBoolean("enabled", true) &&
            profile(context).equals(rule.optString("profile", DEFAULT_PROFILE));
    }

    static boolean protectedPackage(Context context, String target) {
        return context.getPackageName().equals(target) || "com.nissan.appmanage".equals(target);
    }

    static JSONArray load(Context context) {
        try { return new JSONArray(context.getSharedPreferences(PREF, 0).getString("rules", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static void save(Context context, JSONArray rules) {
        context.getSharedPreferences(PREF, 0).edit().putString("rules", rules.toString()).apply();
        syncHmiService(context);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF, 0).edit().putBoolean("master", enabled).apply();
        syncHmiService(context);
    }

    static boolean hasAnyEnabledTrigger(Context context, String type) {
        JSONArray all = load(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.optJSONObject(i);
            if (rule != null && rule.optBoolean("enabled", true) &&
                type.equals(rule.optString("trigger"))) return true;
        }
        return false;
    }

    static void syncHmiService(Context context) {
        boolean hasRule = hasAnyEnabledTrigger(context, TRIGGER_NISSAN_HMI);
        if (enabled(context) && hasRule) NissanHmiAutomationService.startForRules(context);
        else NissanHmiAutomationService.sync(context);
    }

    static boolean hasTrigger(Context context, String type) {
        JSONArray all = load(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.optJSONObject(i);
            if (rule != null && active(context, rule) && type.equals(rule.optString("trigger"))) return true;
        }
        return false;
    }

    static void dispatch(Context context, String trigger, String match, AccessibilityService keyService) {
        JSONArray all = load(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.optJSONObject(i);
            if (rule == null || !active(context, rule) || !trigger.equals(rule.optString("trigger"))) continue;
            String wanted = rule.optString("match");
            boolean matches = trigger.equals(TRIGGER_BOOT) ||
                (trigger.equals(TRIGGER_KEY) && wanted.equals(match)) ||
                (trigger.equals(TRIGGER_NISSAN_HMI) && wanted.equals(match)) ||
                (trigger.equals(TRIGGER_LOG) && !wanted.isEmpty() && match.contains(wanted));
            if (matches && allow(trigger + ":" + i, trigger.equals(TRIGGER_LOG) ? 1200 : 0))
                execute(context, rule.optString("action"), rule.optString("target"), keyService,
                    trigger, wanted);
        }
    }

    static boolean hasKeyRule(Context context, String match) {
        JSONArray all = load(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.optJSONObject(i);
            if (rule != null && active(context, rule) && TRIGGER_KEY.equals(rule.optString("trigger")) &&
                match.equals(rule.optString("match"))) return true;
        }
        return false;
    }

    private static synchronized boolean allow(String key, long delay) {
        long now = System.currentTimeMillis();
        Long previous = lastFire.get(key);
        if (previous != null && now - previous < delay) return false;
        lastFire.put(key, now);
        return true;
    }

    static void execute(Context context, String action, String target, AccessibilityService keyService) {
        execute(context, action, target, keyService, "手动测试", "");
    }

    private static void execute(Context context, String action, String target,
                                AccessibilityService keyService, String source, String match) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        final Context appContext = app;
        final String safeAction = action == null ? "" : action;
        final String safeTarget = target == null ? "" : target;
        final String safeSource = source == null ? "" : source;
        final String safeMatch = match == null ? "" : match;
        try {
            if (safeAction.equals("LAUNCH")) {
                Intent intent = appContext.getPackageManager().getLaunchIntentForPackage(safeTarget);
                if (intent == null) {
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        "没有找到可打开的应用入口");
                    return;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
                recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                    "已向系统提交打开请求");
            } else if (safeAction.equals("STOP") || safeAction.equals("DISABLE") || safeAction.equals("ENABLE")) {
                if (protectedPackage(appContext, safeTarget)) {
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        "已阻止对车机管家或原版日产管家的操作");
                    return;
                }
                new Thread(() -> {
                    String result;
                    if (Privilege.managementAvailable()) {
                        String command = safeAction.equals("STOP") ? "force-stop" :
                            safeAction.equals("DISABLE") ? "disable" : "enable";
                        result = Privilege.managementCommand(safeAction.equals("STOP") ? "am" : "pm",
                            command, safeTarget);
                    } else result = "缺少系统 UID 或 root，未执行。";
                    boolean ok = result.startsWith("退出码 0\n");
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        ok ? "系统命令已完成" : "执行失败：" + concise(result));
                }, "manager-automation-app").start();
            } else if (safeAction.equals("PLAY_PAUSE") || safeAction.equals("PLAY") || safeAction.equals("PAUSE") ||
                safeAction.equals("NEXT") || safeAction.equals("PREVIOUS")) {
                int key = safeAction.equals("PLAY_PAUSE") ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE :
                    safeAction.equals("PLAY") ? KeyEvent.KEYCODE_MEDIA_PLAY :
                    safeAction.equals("PAUSE") ? KeyEvent.KEYCODE_MEDIA_PAUSE :
                    safeAction.equals("NEXT") ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                String result = MediaRouting.send(appContext, key);
                recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget, result);
            } else if (safeAction.equals("VOL_UP") || safeAction.equals("VOL_DOWN")) {
                AudioManager audio = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
                if (audio == null) {
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        "车机未提供音量服务");
                } else {
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        safeAction.equals("VOL_UP") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        "已提交媒体音量调整");
                }
            } else if (safeAction.equals("WIFI") || safeAction.equals("WIFI_ON") || safeAction.equals("WIFI_OFF")) {
                android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                    appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) {
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        "车机未提供 Wi-Fi 服务");
                } else {
                    boolean turnOn = safeAction.equals("WIFI_ON") ||
                        (safeAction.equals("WIFI") && !wifi.isWifiEnabled());
                    boolean accepted = wifi.setWifiEnabled(turnOn);
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        accepted ? "系统已接受" + (turnOn ? "开启" : "关闭") + "请求，状态正在更新" :
                            "系统未接受 Wi-Fi 开关请求");
                }
            } else if (safeAction.equals("BLUETOOTH") || safeAction.equals("BT_ON") || safeAction.equals("BT_OFF")) {
                HsaeBluetooth.Result report = message -> recordExecution(appContext, safeSource, safeMatch,
                    safeAction, safeTarget, message);
                if (safeAction.equals("BLUETOOTH")) HsaeBluetooth.toggle(appContext, report);
                else HsaeBluetooth.power(appContext, safeAction.equals("BT_ON"), report);
            } else if (safeAction.equals("KEYCODE") && safeTarget.matches("[0-9]{1,3}")) {
                int code = Integer.parseInt(safeTarget);
                if (code < 1 || code > 288) {
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        "Android 键值应在 1 到 288 之间");
                    return;
                }
                new Thread(() -> {
                    synchronized (KeyService.class) {
                        KeyService.simulatedKey(code);
                        String result;
                        try { result = Privilege.managementCommand("input", "keyevent", safeTarget); }
                        finally { KeyService.simulatedKey(-1); }
                        boolean ok = result.startsWith("退出码 0\n");
                        recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                            ok ? "已注入 Android 按键 " + code : "按键注入失败：" + concise(result));
                    }
                }, "manager-automation-key").start();
            } else if (safeAction.equals("HOME")) {
                boolean performed = keyService != null &&
                    keyService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                if (!performed) {
                    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(home);
                }
                recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                    performed ? "无障碍桌面动作已提交" : "已请求打开系统桌面");
            } else if (safeAction.equals("BACK")) {
                boolean performed = keyService != null &&
                    keyService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                if (performed) {
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        "无障碍返回动作已提交");
                } else {
                    String result = Privilege.managementCommand("input", "keyevent", "4");
                    boolean ok = result.startsWith("退出码 0\n");
                    recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                        ok ? "已注入 Android 返回键" : "返回动作失败：" + concise(result));
                }
            } else {
                recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                    "未知动作，未执行");
            }
        } catch (Exception error) {
            recordExecution(appContext, safeSource, safeMatch, safeAction, safeTarget,
                "执行异常：" + error.getClass().getSimpleName() + " " + String.valueOf(error.getMessage()));
            android.util.Log.e("HeadunitManager", "自动化动作执行异常：" + safeAction, error);
        }
    }

    static String latestExecution(Context context) {
        JSONArray all = executionHistory(context);
        JSONObject entry = all.optJSONObject(0);
        if (entry == null) return "尚无执行记录。保存规则不代表已触发；可先从规则菜单选择“测试动作”。";
        return formatExecution(entry, false);
    }

    static String executionHistoryText(Context context) {
        JSONArray all = executionHistory(context);
        if (all.length() == 0) return "尚无执行记录。";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry == null) continue;
            if (result.length() > 0) result.append("\n\n");
            result.append(formatExecution(entry, true));
        }
        return result.toString();
    }

    private static synchronized void recordExecution(Context context, String source, String match,
            String action, String target, String result) {
        JSONArray old = executionHistory(context);
        JSONArray updated = new JSONArray();
        try {
            JSONObject entry = new JSONObject().put("time", System.currentTimeMillis())
                .put("source", limit(source, 80)).put("match", limit(match, 100))
                .put("action", limit(action, 60)).put("target", limit(target, 160))
                .put("result", limit(result, 600));
            updated.put(entry);
            for (int i = 0; i < old.length() && updated.length() < HISTORY_LIMIT; i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous != null) updated.put(previous);
            }
            context.getSharedPreferences(PREF, 0).edit().putString(HISTORY_KEY, updated.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static JSONArray executionHistory(Context context) {
        try { return new JSONArray(context.getSharedPreferences(PREF, 0).getString(HISTORY_KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static String formatExecution(JSONObject entry, boolean includeSource) {
        long time = entry.optLong("time", 0L);
        String stamp = time <= 0 ? "—" : new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date(time));
        String source = entry.optString("source", "");
        String match = entry.optString("match", "");
        String action = entry.optString("action", "");
        String target = entry.optString("target", "");
        String result = entry.optString("result", "");
        StringBuilder value = new StringBuilder();
        if (includeSource) value.append(stamp).append(" · ").append(triggerName(source));
        else value.append("最近执行 · ").append(stamp).append(" · ").append(triggerName(source));
        if (!match.isEmpty()) value.append(" · ").append(match);
        value.append("\n").append(action);
        if (!target.isEmpty()) value.append(" · ").append(target);
        value.append("\n").append(result);
        return value.toString();
    }

    private static String triggerName(String trigger) {
        if (TRIGGER_BOOT.equals(trigger)) return "车机开机";
        if (TRIGGER_KEY.equals(trigger)) return "Android 按键";
        if (TRIGGER_NISSAN_HMI.equals(trigger)) return "日产 HMI 按键";
        if (TRIGGER_LOG.equals(trigger)) return "日志关键字";
        return trigger;
    }

    private static String concise(String value) {
        if (value == null || value.isEmpty()) return "无错误详情";
        String text = value.replaceFirst("^退出码 [-0-9]+\\s*", "").trim();
        return limit(text.isEmpty() ? value.trim() : text, 300);
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
