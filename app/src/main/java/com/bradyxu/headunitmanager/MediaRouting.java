package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class MediaRouting {
    private static final String MODE = "mediaMode", APPS = "mediaApps", SKIP_SYSTEM = "mediaSkipSystem";
    private MediaRouting() { }

    static void settings(Activity a) {
        android.content.SharedPreferences pref = a.getSharedPreferences(Rules.PREF, 0);
        String mode = pref.getString(MODE, "system");
        int choice = mode.equals("white") ? 1 : mode.equals("black") ? 2 : 0;
        String[] options = {"系统默认路由", "白名单：只发送给选中应用", "黑名单：发送给未排除的应用"};
        android.widget.LinearLayout content = Ui.column(a);
        content.addView(Ui.label(a, "仅影响本工具发出的媒体动作"));
        android.widget.Button modeButton = Ui.button(a, "路由模式  ·  " + options[choice], null);
        modeButton.setOnClickListener(v -> Ui.optionsDialog(a, "选择路由模式", null, options,
            pref.getString(MODE, "system").equals("white") ? 1 : pref.getString(MODE, "system").equals("black") ? 2 : 0,
            selected -> {
                pref.edit().putString(MODE, selected == 0 ? "system" : selected == 1 ? "white" : "black").apply();
                modeButton.setText("路由模式  ·  " + options[selected]);
                if (selected == 1 && selected(a).isEmpty()) Ui.toast(a, "请继续选择白名单应用");
            }));
        content.addView(modeButton, Ui.rowSpacing(a));
        content.addView(Ui.button(a, "选择媒体应用", v -> chooseApps(a)), Ui.rowSpacing(a));
        Ui.FlatSwitch skipSwitch = new Ui.FlatSwitch(a);
        skipSwitch.setChecked(pref.getBoolean(SKIP_SYSTEM, false));
        content.addView(Ui.switchRow(a, "排除系统应用", "仅发送给用户应用", skipSwitch, v -> {
            boolean enabled = !skipSwitch.isChecked();
            pref.edit().putBoolean(SKIP_SYSTEM, enabled).apply();
            skipSwitch.setChecked(enabled);
        }));
        Ui.dialog(a).setTitle("媒体应用路由").setView(content)
            .setPositiveButton("完成", null).show();
    }

    private static Set<String> selected(Context c) {
        return new HashSet<>(c.getSharedPreferences(Rules.PREF, 0).getStringSet(APPS, Collections.<String>emptySet()));
    }

    private static void chooseApps(Activity a) {
        PackageManager pm = a.getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>(pm.getInstalledApplications(0));
        Collections.sort(apps, (x, y) -> String.valueOf(pm.getApplicationLabel(x)).compareToIgnoreCase(String.valueOf(pm.getApplicationLabel(y))));
        String[] labels = new String[apps.size()]; boolean[] checked = new boolean[apps.size()];
        Set<String> saved = selected(a);
        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo app = apps.get(i);
            labels[i] = pm.getApplicationLabel(app) + "  ·  " + app.packageName;
            checked[i] = saved.contains(app.packageName);
        }
        Ui.multiChoiceDialog(a, "选择媒体应用", "选择可接收本工具媒体动作的应用。", labels, checked,
            "取消", "保存", () -> {
                Set<String> chosen = new HashSet<>();
                for (int i = 0; i < apps.size(); i++) if (checked[i]) chosen.add(apps.get(i).packageName);
                a.getSharedPreferences(Rules.PREF, 0).edit().putStringSet(APPS, chosen).apply();
                Ui.toast(a, "已选择 " + chosen.size() + " 个应用");
            });
    }

    static String send(Context context, int key) {
        Context c = context.getApplicationContext();
        android.content.SharedPreferences pref = c.getSharedPreferences(Rules.PREF, 0);
        String mode = pref.getString(MODE, "system");
        if (mode.equals("system")) {
            sendPair(c, key, null);
            return "已向系统默认路由发送媒体按键；播放器是否响应取决于系统路由。";
        }
        final Set<String> chosen = selected(c);
        final boolean skipSystem = pref.getBoolean(SKIP_SYSTEM, false);
        if (mode.equals("white") && chosen.isEmpty()) return "白名单没有选择媒体应用，未发送媒体按键。";
        new Thread(() -> {
            Set<String> targets = new LinkedHashSet<>();
            if (mode.equals("white")) targets.addAll(chosen);
            else {
                PackageManager pm = c.getPackageManager();
                Intent media = new Intent(Intent.ACTION_MEDIA_BUTTON);
                for (ResolveInfo receiver : pm.queryBroadcastReceivers(media, 0))
                    if (receiver.activityInfo != null) targets.add(receiver.activityInfo.packageName);
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> running = am == null ? null : am.getRunningAppProcesses();
                if (running != null) for (ActivityManager.RunningAppProcessInfo process : running)
                    if (process.pkgList != null) Collections.addAll(targets, process.pkgList);
                targets.removeAll(chosen);
            }
            targets.remove(c.getPackageName());
            PackageManager pm = c.getPackageManager();
            for (String pkg : targets) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    if (!info.enabled || (skipSystem && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0)) continue;
                    sendPair(c, key, pkg);
                } catch (Exception ignored) { }
            }
        }, "media-routing").start();
        return mode.equals("white") ? "已向白名单应用提交媒体广播（" + chosen.size() + " 个目标）。" :
            "已提交黑名单路由媒体广播；目标应用由系统接收器和运行状态决定。";
    }

    private static void sendPair(Context c, int key, String pkg) {
        long now = android.os.SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON)
                .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, action, key, 0));
            if (pkg != null) intent.setPackage(pkg);
            c.sendOrderedBroadcast(intent, null);
        }
    }

    static JSONObject exportConfig(Context c) throws Exception {
        android.content.SharedPreferences p = c.getSharedPreferences(Rules.PREF, 0);
        return new JSONObject().put("mode", p.getString(MODE, "system"))
            .put("apps", new JSONArray(selected(c))).put("skipSystem", p.getBoolean(SKIP_SYSTEM, false));
    }

    static void importConfig(Context c, JSONObject json) {
        if (json == null) return;
        String mode = json.optString("mode", "system");
        if (!mode.equals("system") && !mode.equals("white") && !mode.equals("black")) mode = "system";
        Set<String> apps = new HashSet<>(); JSONArray list = json.optJSONArray("apps");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            String name = list.optString(i);
            if (name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) apps.add(name);
        }
        c.getSharedPreferences(Rules.PREF, 0).edit().putString(MODE, mode).putStringSet(APPS, apps)
            .putBoolean(SKIP_SYSTEM, json.optBoolean("skipSystem", false)).apply();
    }
}
