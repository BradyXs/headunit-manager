package com.bradyxu.headunitmanager;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AccessibilityTools {
    private static final String WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String PREF_ACCESSIBILITY = "accessibility";
    private static final String PREF_RESTORE_IDS = "boot_restore_ids";

    static final class Service {
        final String id;
        final String label;
        final String packageName;

        Service(String id, String label, String packageName) {
            this.id = id;
            this.label = label;
            this.packageName = packageName;
        }

        String optionLabel() {
            return label + "  ·  " + packageName;
        }
    }

    static final class State {
        final boolean masterEnabled;
        final int enabledCount;
        final String rawEnabled;

        State(boolean masterEnabled, int enabledCount, String rawEnabled) {
            this.masterEnabled = masterEnabled;
            this.enabledCount = enabledCount;
            this.rawEnabled = rawEnabled;
        }

        String summary() {
            String state = masterEnabled ? "总开关已开启" : "总开关已关闭";
            String count = enabledCount == 1 ? "已选 1 项服务" : "已选 " + enabledCount + " 项服务";
            return state + "  ·  " + count;
        }
    }

    private AccessibilityTools() {}

    static List<Service> installedServices(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(AccessibilityService.SERVICE_INTERFACE);
        List<ResolveInfo> resolved = pm.queryIntentServices(query, PackageManager.GET_META_DATA);
        List<Service> services = new ArrayList<>();
        if (resolved == null) return services;
        for (ResolveInfo info : resolved) {
            ServiceInfo serviceInfo = info.serviceInfo;
            if (serviceInfo == null || !"android.permission.BIND_ACCESSIBILITY_SERVICE".equals(serviceInfo.permission))
                continue;
            ComponentName component = new ComponentName(serviceInfo.packageName, serviceInfo.name);
            CharSequence serviceLabel = info.loadLabel(pm);
            if (serviceLabel == null || serviceLabel.length() == 0) serviceLabel = serviceInfo.name;
            services.add(new Service(component.flattenToString(), serviceLabel.toString(), serviceInfo.packageName));
        }
        Collections.sort(services, new Comparator<Service>() {
            @Override public int compare(Service left, Service right) {
                int byLabel = left.label.compareToIgnoreCase(right.label);
                return byLabel != 0 ? byLabel : left.packageName.compareToIgnoreCase(right.packageName);
            }
        });
        return services;
    }

    static Set<String> enabledIds(Context context) {
        return parse(Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }

    static State readState(Context context) {
        String raw = Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int master = Settings.Secure.getInt(context.getContentResolver(),
            Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        return new State(master == 1, parse(raw).size(), raw == null ? "" : raw);
    }

    static String diagnostic(Context context) {
        State state;
        List<Service> services;
        Set<String> enabled;
        try {
            state = readState(context);
            services = installedServices(context);
            enabled = enabledIds(context);
        } catch (RuntimeException error) {
            return "读取无障碍配置失败：" + String.valueOf(error.getMessage());
        }

        StringBuilder report = new StringBuilder();
        int uid = android.os.Process.myUid();
        report.append("进程 UID：").append(uid)
            .append(uid == android.os.Process.SYSTEM_UID ? "（system）" : "（普通应用 UID）").append('\n');
        report.append("WRITE_SECURE_SETTINGS：")
            .append(Privilege.has(context, "android.permission.WRITE_SECURE_SETTINGS") ? "已授予" : "未授予")
            .append("（应用内写入所需权限）\n");
        report.append("无障碍总开关：").append(state.masterEnabled ? "开启" : "关闭").append('\n');
        report.append("已启用服务数量：").append(enabled.size()).append('\n');
        report.append("扫描到的服务数量：").append(services.size()).append("\n\n");
        Set<String> restoreIds = rememberedIds(context);
        report.append("已保存的开机恢复服务数量：").append(restoreIds.size()).append('\n');
        if (!restoreIds.isEmpty()) {
            for (Service service : services) {
                if (restoreIds.contains(service.id)) report.append("· ").append(service.label).append('\n');
            }
        }
        report.append('\n');
        report.append("enabled_accessibility_services：\n")
            .append(state.rawEnabled.isEmpty() ? "（空）" : state.rawEnabled).append("\n\n");
        for (Service service : services) {
            report.append(enabled.contains(service.id) ? "● " : "○ ")
                .append(service.label).append('\n').append(service.id).append('\n');
        }
        return report.toString();
    }

    static String statusSummary(Context context) {
        State state = readState(context);
        List<Service> services = installedServices(context);
        Set<String> enabled = enabledIds(context);
        int recognizedEnabled = 0;
        StringBuilder names = new StringBuilder();
        for (Service service : services) {
            if (!enabled.contains(service.id)) continue;
            recognizedEnabled++;
            if (names.length() > 0) names.append("、");
            names.append(service.label);
        }
        StringBuilder summary = new StringBuilder(state.summary());
        summary.append("\n已识别并启用：").append(recognizedEnabled).append(" / ").append(services.size());
        if (!state.masterEnabled && !enabled.isEmpty()) summary.append("\n服务已勾选，但总开关关闭");
        if (names.length() > 0) summary.append("\n").append(names);
        return summary.toString();
    }

    static String saveSelection(Context context, List<Service> services, boolean[] selected) {
        State old = readState(context);
        Set<String> knownIds = new LinkedHashSet<>();
        for (Service service : services) knownIds.add(service.id);

        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        boolean oldHasKnownService = false;
        for (String id : parseTokens(old.rawEnabled)) {
            if (knownIds.contains(canonical(id))) oldHasKnownService = true;
            else wanted.add(id);
        }

        int selectedCount = 0;
        for (int i = 0; i < services.size(); i++) {
            if (i < selected.length && selected[i]) {
                wanted.add(services.get(i).id);
                selectedCount++;
            }
        }
        String newList = join(wanted);
        int newMaster = old.masterEnabled ? 1 : 0;
        if (wanted.isEmpty()) newMaster = 0;
        else if (!old.masterEnabled && !oldHasKnownService && selectedCount > 0) newMaster = 1;

        String failure = writeSettings(context, newList, newMaster, old.rawEnabled,
            old.masterEnabled ? 1 : 0);
        if (failure != null) return failure;
        LinkedHashSet<String> restoreIds = new LinkedHashSet<>();
        for (int i = 0; i < services.size(); i++) {
            if (i < selected.length && selected[i]) restoreIds.add(services.get(i).id);
        }
        boolean remembered = rememberIds(context, restoreIds);
        return "辅助服务设置已保存。" + (newMaster == 1 ? "无障碍总开关已开启。" : "无障碍总开关保持关闭。")
            + (remembered ? "" : "但开机恢复记录未能保存。");
    }

    static String toggleService(Context context, Service service, boolean enabled) {
        State old = readState(context);
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String token : parseTokens(old.rawEnabled)) {
            if (!canonical(token).equals(service.id)) wanted.add(token);
        }
        if (enabled) wanted.add(service.id);
        int master = enabled ? 1 : old.masterEnabled && !wanted.isEmpty() ? 1 : 0;
        String failure = writeSettings(context, join(wanted), master, old.rawEnabled,
            old.masterEnabled ? 1 : 0);
        if (failure != null) return failure;
        LinkedHashSet<String> restoreIds = rememberedIds(context);
        if (enabled) restoreIds.add(service.id);
        else restoreIds.remove(service.id);
        boolean remembered = rememberIds(context, restoreIds);
        return service.label + (enabled ? " 已开启。" : " 已关闭。")
            + (remembered ? "" : "但开机恢复记录未能保存。");
    }

    static String restoreRememberedServices(Context context) {
        Set<String> remembered = rememberedIds(context);
        if (remembered.isEmpty()) return "没有已保存的辅助服务需要恢复。";

        String oldList = Settings.Secure.getString(context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        LinkedHashSet<String> merged = new LinkedHashSet<>(parseTokens(oldList));
        Set<String> current = parse(oldList);
        int added = 0;
        for (String id : remembered) {
            String canonicalId = canonical(id);
            if (!current.contains(canonicalId)) {
                merged.add(id);
                added++;
            }
        }
        if (added == 0) return "已保存的辅助服务仍在授权列表中。";

        String failure = writeOne(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            join(merged), false);
        if (failure != null) return "开机恢复辅助服务失败：" + failure;
        return "已将 " + added + " 项已保存的辅助服务合并回授权列表。";
    }

    private static LinkedHashSet<String> rememberedIds(Context context) {
        String value = context.getSharedPreferences(PREF_ACCESSIBILITY, Context.MODE_PRIVATE)
            .getString(PREF_RESTORE_IDS, "");
        return new LinkedHashSet<>(parseTokens(value));
    }

    static boolean hasRememberedServices(Context context) {
        return !rememberedIds(context).isEmpty();
    }

    private static boolean rememberIds(Context context, Set<String> ids) {
        return context.getSharedPreferences(PREF_ACCESSIBILITY, Context.MODE_PRIVATE).edit()
            .putString(PREF_RESTORE_IDS, join(ids)).commit();
    }

    static String setMaster(Context context, boolean enabled) {
        State state = readState(context);
        if (enabled && parse(state.rawEnabled).isEmpty())
            return "请先在“管理服务”中选择至少一项服务。";
        String failure = writeOne(context, Settings.Secure.ACCESSIBILITY_ENABLED,
            enabled ? "1" : "0", true);
        if (failure != null) return failure;
        return enabled ? "无障碍总开关已开启，已选服务现在可以运行。" :
            "无障碍总开关已关闭；服务勾选状态已保留。";
    }

    private static String writeSettings(Context context, String list, int master,
                                        String oldList, int oldMaster) {
        String listFailure = writeOne(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list, false);
        if (listFailure != null) {
            restore(context, oldList, oldMaster);
            return listFailure + "\n已尝试恢复原有辅助服务设置。";
        }
        String masterFailure = writeOne(context, Settings.Secure.ACCESSIBILITY_ENABLED,
            String.valueOf(master), true);
        if (masterFailure != null) {
            restore(context, oldList, oldMaster);
            return masterFailure + "\n已尝试恢复原有辅助服务设置。";
        }

        State actual = readState(context);
        if (!normalize(actual.rawEnabled).equals(normalize(list)) || actual.masterEnabled != (master == 1)) {
            restore(context, oldList, oldMaster);
            return "系统未确认设置已生效，已尝试恢复原有辅助服务设置。请检查平台授权或 root 状态。";
        }
        return null;
    }

    private static void restore(Context context, String oldList, int oldMaster) {
        writeOne(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            oldList == null ? "" : oldList, false);
        writeOne(context, Settings.Secure.ACCESSIBILITY_ENABLED, String.valueOf(oldMaster), true);
    }

    private static String writeOne(Context context, String key, String value, boolean integer) {
        if (Privilege.has(context, WRITE_SECURE_SETTINGS)) {
            try {
                boolean written = integer
                    ? Settings.Secure.putInt(context.getContentResolver(), key, Integer.parseInt(value))
                    : Settings.Secure.putString(context.getContentResolver(), key, value);
                if (written && valueMatches(context, key, value, integer)) return null;
            } catch (SecurityException ignored) {
                // Fall through to the system UID/root command path.
            } catch (RuntimeException ignored) {
                // Fall through to the system UID/root command path.
            }
        }

        if (Privilege.systemUid() || Privilege.rootAvailable()) {
            String result = Privilege.managementCommand("settings", "put", "secure", key, value);
            if (result.startsWith("退出码 0") && valueMatches(context, key, value, integer)) return null;
            return "系统没有接受辅助服务设置。" + (result.trim().isEmpty() ? "" : "\n" + result.trim());
        }
        return "当前安装包没有 WRITE_SECURE_SETTINGS 权限，也未检测到 root；没有修改服务授权。请安装平台签名版。";
    }

    private static boolean valueMatches(Context context, String key, String value, boolean integer) {
        if (integer) return Settings.Secure.getInt(context.getContentResolver(), key, 0) == Integer.parseInt(value);
        return normalize(Settings.Secure.getString(context.getContentResolver(), key)).equals(normalize(value));
    }

    private static Set<String> parse(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : parseTokens(value)) {
            String id = canonical(token);
            if (!id.isEmpty()) result.add(id);
        }
        return result;
    }

    private static List<String> parseTokens(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return result;
        String[] parts = value.split(":");
        for (String part : parts) if (!part.trim().isEmpty()) result.add(part.trim());
        return result;
    }

    private static String canonical(String id) {
        ComponentName component = ComponentName.unflattenFromString(id);
        return component == null ? id : component.flattenToString();
    }

    private static String join(Set<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(':');
            result.append(value);
        }
        return result.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

}
