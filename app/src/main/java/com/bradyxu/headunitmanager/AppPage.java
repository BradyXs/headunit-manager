package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AppPage extends LinearLayout implements PageLifecycle {
    private static final String[] FILTER_NAMES = {"全部", "个人", "系统", "已禁用"};
    private final Activity activity;
    private final PackageManager pm;
    private final Button[] sectionTabs = new Button[2];
    private final Button filterButton;
    private final EditText search;
    private final TextView countLabel;
    private final TextView resultMessage;
    private final GridView appGrid;
    private final ListView serviceList;
    private final AppAdapter appAdapter;
    private final ServiceAdapter serviceAdapter;
    private List<AppRecord> allApps = Collections.emptyList();
    private List<ServiceRecord> allServices = Collections.emptyList();
    private int section;
    private int filter;
    private int appLoadSequence;
    private int runtimeStatusSequence;
    private int serviceLoadSequence;
    private boolean appsLoaded;
    private boolean servicesLoaded;
    private boolean serviceScanning;
    private boolean serviceReloadPending;

    AppPage(Activity a) {
        super(a);
        activity = a;
        pm = a.getPackageManager();
        setOrientation(VERTICAL);

        LinearLayout sectionBar = Ui.row(a);
        sectionBar.setGravity(Gravity.CENTER_VERTICAL);
        sectionBar.setPadding(0, 0, 0, Ui.dp(a, 6));
        LinearLayout tabGroup = Ui.row(a);
        tabGroup.setPadding(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
        tabGroup.setBackground(Ui.shape(a, Ui.BUTTON, 20));
        String[] sectionNames = {"应用", "服务"};
        for (int i = 0; i < sectionTabs.length; i++) {
            final int index = i;
            Button tab = Ui.button(a, sectionNames[i], v -> selectSection(index));
            Ui.buttonIcon(tab, i == 0 ? R.drawable.ic_hm_apps : R.drawable.ic_hm_services);
            tab.setTextSize(14);
            tab.setMinHeight(0);
            tab.setMinWidth(0);
            tab.setPadding(Ui.dp(a, 9), 0, Ui.dp(a, 9), 0);
            tab.setCompoundDrawablePadding(Ui.dp(a, 5));
            tabGroup.addView(tab, new LinearLayout.LayoutParams(Ui.dp(a, 83), Ui.dp(a, 38)));
            sectionTabs[i] = tab;
        }
        sectionBar.addView(tabGroup);
        View spacer = new View(a);
        sectionBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        filterButton = Ui.button(a, "筛选：全部  ▾", v -> Ui.optionsDialog(activity,
            "筛选列表", "按来源或启用状态筛选", FILTER_NAMES, filter, choice -> {
                filter = choice;
                renderCurrentSection();
            }));
        filterButton.setTextSize(14);
        filterButton.setMinHeight(0);
        filterButton.setMinWidth(0);
        filterButton.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        sectionBar.addView(filterButton, new LinearLayout.LayoutParams(-2, Ui.dp(a, 42)));
        addView(sectionBar);

        LinearLayout find = Ui.row(a);
        find.setGravity(Gravity.CENTER_VERTICAL);
        search = Ui.input(a);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            searchNow();
            return true;
        });
        search.setHint("搜索名称或包名");
        Drawable searchIcon = a.getResources().getDrawable(android.R.drawable.ic_menu_search).mutate();
        searchIcon.setColorFilter(Ui.MUTED, android.graphics.PorterDuff.Mode.SRC_IN);
        searchIcon.setBounds(0, 0, Ui.dp(a, 20), Ui.dp(a, 20));
        search.setCompoundDrawables(searchIcon, null, null, null);
        search.setCompoundDrawablePadding(Ui.dp(a, 8));
        find.addView(search, new LayoutParams(0, Ui.dp(a, 46), 1));
        TextView searchAction = compactAction("搜索", v -> searchNow());
        TextView refreshAction = compactAction("刷新", v -> refresh());
        LayoutParams searchParams = new LayoutParams(Ui.dp(a, 64), Ui.dp(a, 42));
        searchParams.leftMargin = Ui.dp(a, 6);
        find.addView(searchAction, searchParams);
        LayoutParams refreshParams = new LayoutParams(Ui.dp(a, 64), Ui.dp(a, 42));
        refreshParams.leftMargin = Ui.dp(a, 6);
        find.addView(refreshAction, refreshParams);
        LayoutParams findParams = new LayoutParams(-1, -2);
        findParams.topMargin = Ui.dp(a, 8);
        findParams.bottomMargin = Ui.dp(a, 3);
        addView(find, findParams);

        countLabel = Ui.label(a, "");
        addView(countLabel);

        FrameLayout results = new FrameLayout(a);
        appGrid = new GridView(a);
        appGrid.setNumColumns(appColumns(a));
        appGrid.setHorizontalSpacing(Ui.dp(a, 6));
        appGrid.setVerticalSpacing(Ui.dp(a, 6));
        appGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        appGrid.setSelector(android.R.color.transparent);
        appGrid.setPadding(0, 0, 0, Ui.dp(a, 8));
        appAdapter = new AppAdapter(a);
        appGrid.setAdapter(appAdapter);
        results.addView(appGrid, new FrameLayout.LayoutParams(-1, -1));

        serviceList = new ListView(a);
        serviceList.setDivider(new ColorDrawable(0x00000000));
        serviceList.setDividerHeight(Ui.dp(a, 6));
        serviceList.setSelector(android.R.color.transparent);
        serviceList.setPadding(0, 0, 0, Ui.dp(a, 8));
        serviceAdapter = new ServiceAdapter(a);
        serviceList.setAdapter(serviceAdapter);
        results.addView(serviceList, new FrameLayout.LayoutParams(-1, -1));

        resultMessage = Ui.text(a, "正在读取应用列表…", 16);
        resultMessage.setTextColor(Ui.MUTED);
        resultMessage.setGravity(Gravity.CENTER);
        results.addView(resultMessage, new FrameLayout.LayoutParams(-1, -1));
        addView(results, new LayoutParams(-1, 0, 1));

        updateSectionStyle();
        updateFilterStyle();
        appGrid.setVisibility(VISIBLE);
        serviceList.setVisibility(GONE);
        showLoading("正在读取应用列表…");
        loadAppsAsync();
    }

    private static int appColumns(Context context) {
        float width = Ui.contentWidthDp(context);
        return width >= 1280 ? 4 : width >= 720 ? 3 : width >= 480 ? 2 : 1;
    }

    private TextView compactAction(String label, View.OnClickListener click) {
        TextView button = Ui.text(activity, label, 14);
        button.setPadding(0, 0, 0, 0);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        button.setBackground(Ui.touchBackground(activity, Ui.BUTTON, Ui.SURFACE_LIGHT, 12));
        button.setFocusable(true);
        button.setOnClickListener(click);
        return button;
    }

    private void selectSection(int next) {
        if (section == next) return;
        section = next;
        updateSectionStyle();
        appGrid.setVisibility(section == 0 ? VISIBLE : GONE);
        serviceList.setVisibility(section == 1 ? VISIBLE : GONE);
        renderCurrentSection();
        if (section == 0 && appsLoaded) refreshRuntimeStatuses();
        if (section == 1 && !servicesLoaded && !serviceScanning) loadServicesAsync();
    }

    private void updateSectionStyle() {
        for (int i = 0; i < sectionTabs.length; i++) {
            Ui.selectedButton(sectionTabs[i], i == section);
        }
    }

    private void updateFilterStyle() {
        filterButton.setText("筛选：" + FILTER_NAMES[filter] + "  ▾");
        filterButton.setTextColor(filter == 0 ? Ui.TEXT : Ui.ACCENT);
    }

    private void searchNow() {
        renderCurrentSection();
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private void refresh() {
        if (section == 0) loadAppsAsync();
        else loadServicesAsync();
    }

    @Override public void onHostResume() {
        if (section == 0 && !appsLoaded) loadAppsAsync();
        else if (section == 0) refreshRuntimeStatuses();
        if (section == 1 && !servicesLoaded && !serviceScanning) loadServicesAsync();
    }

    private void renderCurrentSection() {
        updateFilterStyle();
        if (section == 0) renderApps();
        else renderServices();
    }

    private void showLoading(String message) {
        resultMessage.setText(message);
        resultMessage.setVisibility(VISIBLE);
        if (section == 0) appGrid.setVisibility(GONE);
        else serviceList.setVisibility(GONE);
    }

    private void showEmpty(String message) {
        resultMessage.setText(message);
        resultMessage.setVisibility(VISIBLE);
        if (section == 0) appGrid.setVisibility(GONE);
        else serviceList.setVisibility(GONE);
    }

    private void loadAppsAsync() {
        final int request = ++appLoadSequence;
        ++runtimeStatusSequence;
        if (!appsLoaded) showLoading("正在读取应用列表…");
        new Thread(() -> {
            List<AppRecord> loaded = new ArrayList<>();
            String error = null;
            try {
                List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_DISABLED_COMPONENTS);
                Set<String> runningPackages = runningPackages();
                for (ApplicationInfo info : installed) {
                    String label = String.valueOf(pm.getApplicationLabel(info));
                    Drawable icon;
                    try { icon = pm.getApplicationIcon(info); }
                    catch (Exception e) { icon = pm.getDefaultActivityIcon(); }
                    loaded.add(new AppRecord(info, label, icon, appEnabled(info), isRunning(info, runningPackages)));
                }
                Collections.sort(loaded, (left, right) -> left.label.compareToIgnoreCase(right.label));
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (request != appLoadSequence) return;
                if (failure != null) {
                    if (section == 0) showEmpty("读取应用失败：" + failure);
                    return;
                }
                allApps = loaded;
                appsLoaded = true;
                if (section == 0) renderApps();
            });
        }, "app-list-load").start();
    }

    private Set<String> runningPackages() {
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return null;
        try {
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return null;
            Set<String> names = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pkgList != null) Collections.addAll(names, process.pkgList);
                if (process.processName != null) names.add(process.processName);
            }
            return names;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Boolean isRunning(ApplicationInfo app, Set<String> runningNames) {
        if (runningNames == null) return null;
        if (runningNames.contains(app.packageName) || runningNames.contains(app.processName)) return true;
        String processPrefix = app.packageName + ":";
        for (String processName : runningNames) {
            if (processName != null && processName.startsWith(processPrefix)) return true;
        }
        return false;
    }

    private void refreshRuntimeStatuses() {
        final int request = ++runtimeStatusSequence;
        final List<AppRecord> snapshot = new ArrayList<>(allApps);
        new Thread(() -> {
            Set<String> runningNames = runningPackages();
            List<AppRecord> updated = new ArrayList<>(snapshot.size());
            for (AppRecord record : snapshot) {
                updated.add(new AppRecord(record.info, record.label, record.icon, record.enabled,
                    isRunning(record.info, runningNames)));
            }
            activity.runOnUiThread(() -> {
                if (request != runtimeStatusSequence) return;
                allApps = updated;
                if (section == 0) renderApps();
            });
        }, "app-runtime-status").start();
    }

    private void renderApps() {
        if (!appsLoaded) {
            showLoading("正在读取应用列表…");
            return;
        }
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<AppRecord> shown = new ArrayList<>();
        for (AppRecord item : allApps) {
            boolean system = (item.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (filter == 1 && system || filter == 2 && !system || filter == 3 && item.enabled) continue;
            if (!matches(query, item.label, item.info.packageName)) continue;
            shown.add(item);
        }
        appAdapter.setItems(shown);
        countLabel.setText("显示 " + shown.size() + " 个应用  ·  启用状态与运行状态分开展示");
        if (shown.isEmpty()) showEmpty("没有符合条件的应用，请调整筛选或搜索词。");
        else {
            resultMessage.setVisibility(GONE);
            appGrid.setVisibility(VISIBLE);
        }
    }

    private void loadServicesAsync() {
        if (serviceScanning) return;
        final int request = ++serviceLoadSequence;
        serviceScanning = true;
        if (!servicesLoaded) showLoading("正在读取应用服务…");
        new Thread(() -> {
            List<ServiceRecord> loaded = new ArrayList<>();
            String error = null;
            try {
                List<PackageInfo> packages = pm.getInstalledPackages(
                    PackageManager.GET_SERVICES | PackageManager.GET_DISABLED_COMPONENTS);
                int scanned = 0;
                for (PackageInfo packageInfo : packages) {
                    scanned++;
                    ServiceInfo[] declared = packageInfo.services;
                    if (declared == null) continue;
                    String packageLabel = String.valueOf(pm.getApplicationLabel(packageInfo.applicationInfo));
                    boolean packageEnabled = appEnabled(packageInfo.applicationInfo);
                    boolean system = (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    for (ServiceInfo serviceInfo : declared) {
                        if (serviceInfo == null) continue;
                        ComponentName component = new ComponentName(serviceInfo.packageName, serviceInfo.name);
                        int state = pm.getComponentEnabledSetting(component);
                        boolean componentEnabled;
                        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) componentEnabled = true;
                        else if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) componentEnabled = false;
                        else componentEnabled = serviceInfo.enabled;
                        String label = serviceInfo.nonLocalizedLabel == null ?
                            serviceInfo.name.substring(serviceInfo.name.lastIndexOf('.') + 1) :
                            serviceInfo.nonLocalizedLabel.toString();
                        loaded.add(new ServiceRecord(component, label, packageLabel, system,
                            componentEnabled, packageEnabled, serviceInfo.permission));
                    }
                    if (scanned % 40 == 0 && !loaded.isEmpty()) {
                        final List<ServiceRecord> partial = new ArrayList<>(loaded);
                        activity.runOnUiThread(() -> {
                            if (request != serviceLoadSequence) return;
                            allServices = partial;
                            servicesLoaded = true;
                            if (section == 1) renderServices();
                        });
                    }
                }
                Collections.sort(loaded, (left, right) -> {
                    int byPackage = left.packageLabel.compareToIgnoreCase(right.packageLabel);
                    if (byPackage != 0) return byPackage;
                    return left.label.compareToIgnoreCase(right.label);
                });
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final String failure = error;
            activity.runOnUiThread(() -> {
                if (request != serviceLoadSequence) return;
                serviceScanning = false;
                if (failure != null) {
                    if (section == 1) showEmpty("读取服务失败：" + failure);
                    return;
                }
                allServices = loaded;
                servicesLoaded = true;
                if (section == 1) renderServices();
                if (serviceReloadPending) {
                    serviceReloadPending = false;
                    loadServicesAsync();
                }
            });
        }, "app-service-load").start();
    }

    private void renderServices() {
        if (!servicesLoaded) {
            showLoading("正在读取应用服务…");
            return;
        }
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<ServiceRecord> shown = new ArrayList<>();
        for (ServiceRecord item : allServices) {
            boolean system = item.system;
            boolean active = item.packageEnabled && item.componentEnabled;
            if (filter == 1 && system || filter == 2 && !system || filter == 3 && active) continue;
            if (!matches(query, item.label, item.packageLabel, item.component.getClassName())) continue;
            shown.add(item);
        }
        serviceAdapter.setItems(shown);
        countLabel.setText("显示 " + shown.size() + " 项服务组件" + (serviceScanning ? "  ·  正在继续扫描…" : "  ·  状态表示组件是否启用"));
        if (shown.isEmpty()) showEmpty("没有符合条件的服务组件，请调整筛选或搜索词。");
        else {
            resultMessage.setVisibility(GONE);
            serviceList.setVisibility(VISIBLE);
        }
    }

    private static boolean matches(String query, String... values) {
        if (query.isEmpty()) return true;
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private boolean appEnabled(ApplicationInfo app) {
        if (app == null) return false;
        try {
            int state = pm.getApplicationEnabledSetting(app.packageName);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) return false;
        } catch (Exception ignored) { }
        return app.enabled;
    }

    private void options(AppRecord item) {
        ApplicationInfo app = item.info;
        String[] actions = {"启动", "系统详情", "备份 APK", "卸载", item.enabled ? "禁用应用" : "启用应用", "清除数据（需系统权限或 root）"};
        String runtime = item.running == null ? "运行状态未知" : item.running ? "运行中" : "未运行";
        String summary = item.label + "\n" + app.packageName + "\n应用状态：" +
            (item.enabled ? "已启用" : "已禁用") + "  ·  " + runtime;
        try {
            PackageInfo packageInfo = pm.getPackageInfo(app.packageName, 0);
            summary += "\n版本 " + packageInfo.versionName + " (" + packageInfo.versionCode + ")  ·  UID " + app.uid;
        } catch (Exception ignored) { }
        Ui.dialog(activity).setTitle(summary)
            .setItems(actions, (d, which) -> {
                if (which == 0) {
                    Intent launch = pm.getLaunchIntentForPackage(app.packageName);
                    if (launch == null) Ui.toast(activity, "此应用没有可启动的界面");
                    else activity.startActivity(launch);
                } else if (which == 1) {
                    Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.packageName));
                    activity.startActivity(info);
                } else if (which == 2) backup(app);
                else if (which == 3) {
                    if (Rules.protectedPackage(activity, app.packageName)) {
                        Ui.toast(activity, "本工具不卸载自身或原版日产管家"); return;
                    }
                    Ui.confirm(activity, "卸载应用", item.label + " 的数据可能一并删除。", () ->
                        activity.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName))));
                } else privileged(app, item.label, item.enabled, which == 4);
            }).show();
    }

    private void serviceOptions(ServiceRecord service) {
        boolean active = service.packageEnabled && service.componentEnabled;
        String enableLabel = service.packageEnabled ? "启用服务组件" : "启用所属应用";
        String currentState = !service.packageEnabled ? "所属应用已禁用" :
            (service.componentEnabled ? "组件已启用" : "组件已禁用");
        String[] actions = {active ? "禁用服务组件" : enableLabel, "系统详情"};
        String title = service.label + "\n" + service.packageLabel + "\n" + currentState;
        Ui.dialog(activity).setTitle(title).setItems(actions, (d, which) -> {
            if (which == 1) {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + service.component.getPackageName())));
                return;
            }
            confirmServiceToggle(service);
        }).show();
    }

    private void confirmServiceToggle(ServiceRecord service) {
        boolean enable = !(service.packageEnabled && service.componentEnabled);
        if (Rules.protectedPackage(activity, service.component.getPackageName()) && !enable) {
            Ui.toast(activity, "本工具不禁用自身或原版日产管家服务");
            return;
        }
        String detail = enable
            ? "将启用 " + (service.packageEnabled ? "此服务组件。" : "所属应用；该应用的其他功能也会恢复。")
            : "将禁用此服务组件，可能影响所属应用的部分功能。";
        Ui.confirm(activity, enable ? "启用服务" : "禁用服务", detail,
            () -> setServiceEnabled(service, enable));
    }

    private void setServiceEnabled(ServiceRecord service, boolean enable) {
        final android.app.Dialog progress = Ui.progress(activity, enable ? "正在启用服务…" : "正在禁用服务…");
        new Thread(() -> {
            String argument;
            String command;
            if (enable && !service.packageEnabled) {
                argument = service.component.getPackageName();
                command = "enable";
            } else {
                argument = service.component.flattenToShortString();
                command = enable ? "enable" : "disable";
            }
            String result = Privilege.managementCommand("pm", command, argument);
            boolean commandOk = result.startsWith("退出码 0\n");
            boolean actual = enable && !service.packageEnabled
                ? appEnabledByPackage(service.component.getPackageName())
                : componentEnabled(service.component, service.componentEnabled);
            boolean success = commandOk && actual == enable;
            String message = success
                ? (enable ? "服务已启用。" : "服务已禁用。")
                : commandOk ? "系统已接受命令，但回读状态尚未匹配，请刷新列表确认。" :
                    "未能更改服务状态。请确认使用平台签名版或设备已授予 root 权限。";
            if (!success) {
                String detail = result.replaceFirst("^退出码 [-0-9]+\\s*", "").trim();
                if (!detail.isEmpty()) message += "\n\n系统信息：" + detail;
            }
            String finalMessage = message;
            activity.runOnUiThread(() -> {
                progress.dismiss();
                Ui.dialog(activity).setTitle(success ? "服务设置已更新" : "服务设置未完成")
                    .setMessage(finalMessage).setPositiveButton("好", null).show();
                if (success) {
                    if (serviceScanning) serviceReloadPending = true;
                    else loadServicesAsync();
                }
            });
        }, "service-component-change").start();
    }

    private boolean appEnabledByPackage(String packageName) {
        try { return appEnabled(pm.getApplicationInfo(packageName, PackageManager.GET_DISABLED_COMPONENTS)); }
        catch (Exception ignored) { return false; }
    }

    private boolean componentEnabled(ComponentName component, boolean manifestDefault) {
        try {
            int state = pm.getComponentEnabledSetting(component);
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return manifestDefault;
        } catch (Exception ignored) { }
        return false;
    }

    private void backup(ApplicationInfo app) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HeadunitManager/backup");
        if (!dir.exists() && !dir.mkdirs()) { Ui.toast(activity, "无法创建备份目录"); return; }
        File to = new File(dir, app.packageName + ".apk");
        new Thread(() -> {
            try { FileOps.copy(new File(app.sourceDir), to); activity.runOnUiThread(() -> Ui.toast(activity, "已备份：" + to)); }
            catch (Exception e) { activity.runOnUiThread(() -> Ui.toast(activity, "备份失败：" + e.getMessage())); }
        }).start();
    }

    private void privileged(ApplicationInfo app, String label, boolean wasEnabled, boolean enableToggle) {
        if (Rules.protectedPackage(activity, app.packageName)) {
            Ui.toast(activity, "本工具不修改自身或原版日产管家"); return;
        }
        String operationTitle = enableToggle ? (wasEnabled ? "禁用应用" : "启用应用") : "清除应用数据";
        String operationDetail = enableToggle
            ? (wasEnabled
                ? "禁用后，该应用及其组件在重新启用前不能启动；系统也可能结束它当前运行的进程。"
                : "重新启用后，该应用可以再次启动。运行状态会在刷新后重新读取。")
            : "将以当前系统 UID 或 root 权限清除 " + app.packageName + " 的数据。此操作不可撤销。";
        Ui.confirm(activity, operationTitle, operationDetail,
            () -> {
                final android.app.Dialog progress = Ui.progress(activity,
                    enableToggle ? (wasEnabled ? "正在禁用应用…" : "正在启用应用…") : "正在清除应用数据…");
                new Thread(() -> {
                String raw;
                if (enableToggle) raw = Privilege.managementCommand("pm", wasEnabled ? "disable" : "enable", app.packageName);
                else raw = Privilege.managementCommand("pm", "clear", app.packageName);
                boolean commandOk = raw.startsWith("退出码 0\n");
                boolean success = commandOk && (!enableToggle || appEnabledByPackage(app.packageName) != wasEnabled);
                String title = success ? "操作完成" : "操作未完成";
                String message;
                if (raw.startsWith("缺少系统 UID")) {
                    message = "未执行此操作。请使用平台签名版，或确认设备已授予 root 权限。";
                } else if (success && enableToggle) {
                    message = wasEnabled ? label + " 已禁用。" : label + " 已启用。";
                } else if (success) {
                    message = app.packageName + " 的应用数据已清除。";
                } else {
                    message = (enableToggle ? (wasEnabled ? "无法禁用 " : "无法启用 ") : "无法清除应用数据：") +
                        (enableToggle ? label : app.packageName) + "。请检查应用是否为系统必需组件及权限状态。";
                    String detail = raw.replaceFirst("^退出码 [-0-9]+\\s*", "").trim();
                    if (!detail.isEmpty()) message += "\n\n系统信息：" + detail;
                }
                String resultMessage = message;
                activity.runOnUiThread(() -> {
                    progress.dismiss();
                    Ui.dialog(activity).setTitle(title).setMessage(resultMessage).setPositiveButton("好", null).show();
                    refresh();
                });
                }).start();
            });
    }

    private final class AppAdapter extends BaseAdapter {
        private final Activity context;
        private List<AppRecord> items = Collections.emptyList();

        AppAdapter(Activity context) { this.context = context; }
        void setItems(List<AppRecord> next) { items = next; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public AppRecord getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return getItem(position).info.packageName.hashCode(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            AppCell cell;
            if (convertView == null) {
                LinearLayout card = Ui.column(context);
                card.setPadding(0, 0, 0, 0);
                card.setBackground(Ui.touchBackground(context, Ui.SURFACE, Ui.SURFACE_LIGHT, 12));

                LinearLayout header = Ui.row(context);
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 8), 0);
                GradientDrawable headerBackground = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xff353f4c, 0xff2b3d50});
                float radius = Ui.dp(context, 11);
                headerBackground.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
                header.setBackground(headerBackground);
                ImageView icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(context, 54), Ui.dp(context, 54)));
                LinearLayout labels = Ui.column(context);
                labels.setPadding(Ui.dp(context, 11), 0, Ui.dp(context, 4), 0);
                TextView name = Ui.text(context, "", 17);
                name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
                name.setPadding(0, 0, 0, 0);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(name);
                TextView packageName = Ui.text(context, "", 11);
                packageName.setTextColor(Ui.MUTED);
                packageName.setPadding(0, Ui.dp(context, 5), 0, 0);
                packageName.setSingleLine(true);
                packageName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                labels.addView(packageName);
                TextView running = Ui.text(context, "", 11);
                running.setPadding(0, Ui.dp(context, 3), 0, 0);
                running.setSingleLine(true);
                labels.addView(running);
                header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                TextView detail = Ui.text(context, "详情", 12);
                detail.setTextColor(Ui.MUTED);
                detail.setGravity(Gravity.CENTER);
                detail.setPadding(0, 0, 0, 0);
                detail.setBackground(Ui.touchBackground(context, android.graphics.Color.TRANSPARENT, Ui.BUTTON, 8));
                detail.setFocusable(true);
                header.addView(detail, new LinearLayout.LayoutParams(Ui.dp(context, 40), Ui.dp(context, 40)));
                LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, Ui.dp(context, 83));
                headerParams.setMargins(Ui.dp(context, 1), Ui.dp(context, 1), Ui.dp(context, 1), 0);
                card.addView(header, headerParams);

                View divider = new View(context);
                divider.setBackgroundColor(Ui.BORDER);
                card.addView(divider, new LinearLayout.LayoutParams(-1, Ui.dp(context, 1)));
                LinearLayout footer = Ui.row(context);
                footer.setGravity(Gravity.CENTER_VERTICAL);
                footer.setPadding(Ui.dp(context, 11), 0, Ui.dp(context, 5), 0);
                LinearLayout badges = Ui.row(context);
                badges.setGravity(Gravity.CENTER_VERTICAL);
                TextView state = Ui.badge(context, "", 0xff254838, Ui.GREEN);
                TextView kind = Ui.badge(context, "", 0xff203c54, Ui.ACCENT);
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
                badgeParams.setMargins(0, 0, Ui.dp(context, 4), 0);
                badges.addView(state, badgeParams);
                badges.addView(kind);
                footer.addView(badges);
                footer.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
                Ui.FlatSwitch toggle = new Ui.FlatSwitch(context);
                footer.addView(toggle, new LinearLayout.LayoutParams(Ui.dp(context, 52), Ui.dp(context, 32)));
                TextView more = Ui.text(context, "···", 17);
                more.setGravity(Gravity.CENTER);
                more.setTextColor(Ui.MUTED);
                more.setPadding(0, 0, 0, 0);
                more.setBackground(Ui.touchBackground(context, android.graphics.Color.TRANSPARENT, Ui.BUTTON, 8));
                more.setFocusable(true);
                footer.addView(more, new LinearLayout.LayoutParams(Ui.dp(context, 32), Ui.dp(context, 40)));
                card.addView(footer, new LinearLayout.LayoutParams(-1, Ui.dp(context, 46)));
                cell = new AppCell(icon, name, packageName, detail, state, running, kind, toggle, more);
                card.setTag(cell);
                convertView = card;
            } else cell = (AppCell) convertView.getTag();

            AppRecord record = getItem(position);
            boolean system = (record.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            cell.icon.setImageDrawable(record.icon);
            cell.icon.setAlpha(record.enabled ? 1f : .65f);
            cell.name.setText(record.label);
            cell.packageName.setText("包名：" + record.info.packageName);
            cell.state.setText(record.enabled ? "已启用" : "已禁用");
            cell.state.setTextColor(record.enabled ? Ui.GREEN : Ui.MUTED);
            cell.state.setBackground(Ui.shape(context, record.enabled ? 0xff254838 : Ui.BUTTON, 8));
            cell.running.setText(record.running == null ? "运行未知" : record.running ? "运行中" : "未运行");
            cell.running.setTextColor(record.running == null ? Ui.AMBER : record.running ? Ui.GREEN : Ui.MUTED);
            cell.kind.setText(system ? "系统" : "用户");
            cell.kind.setTextColor(system ? Ui.ACCENT : 0xffc8abff);
            cell.kind.setBackground(Ui.shape(context, system ? 0xff203c54 : 0xff3d3451, 8));
            cell.toggle.setChecked(record.enabled);
            cell.toggle.setContentDescription(record.label + (record.enabled ? "已启用，点击禁用" : "已禁用，点击启用"));
            cell.toggle.setOnClickListener(v -> privileged(record.info, record.label, record.enabled, true));
            cell.detail.setContentDescription("查看" + record.label + "详情与操作");
            cell.detail.setOnClickListener(v -> options(record));
            cell.more.setContentDescription("更多" + record.label + "操作");
            cell.more.setOnClickListener(v -> options(record));
            convertView.setOnClickListener(v -> options(record));
            return convertView;
        }
    }

    private static final class AppCell {
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView detail;
        final TextView state;
        final TextView running;
        final TextView kind;
        final Ui.FlatSwitch toggle;
        final TextView more;
        AppCell(ImageView icon, TextView name, TextView packageName, TextView detail,
                TextView state, TextView running, TextView kind, Ui.FlatSwitch toggle, TextView more) {
            this.icon = icon; this.name = name; this.packageName = packageName; this.detail = detail;
            this.state = state; this.running = running; this.kind = kind; this.toggle = toggle; this.more = more;
        }
    }

    private final class ServiceAdapter extends BaseAdapter {
        private final Activity context;
        private List<ServiceRecord> items = Collections.emptyList();

        ServiceAdapter(Activity context) { this.context = context; }
        void setItems(List<ServiceRecord> next) { items = next; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public ServiceRecord getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return getItem(position).component.flattenToString().hashCode(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ServiceRecord service = getItem(position);
            boolean active = service.packageEnabled && service.componentEnabled;
            String type = "android.permission.BIND_ACCESSIBILITY_SERVICE".equals(service.permission) ? "无障碍服务" : "Android 服务";
            String detail = service.packageLabel + "  ·  " + service.component.getShortClassName() + "  ·  " + type + "  ·  " +
                (!service.packageEnabled ? "所属应用已禁用" : active ? "组件已启用" : "组件已禁用");
            LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView :
                Ui.listItem(context, "", "", "", null);
            TextView badge = (TextView) row.getChildAt(0);
            LinearLayout words = (LinearLayout) row.getChildAt(1);
            ((TextView) words.getChildAt(0)).setText(service.label);
            ((TextView) words.getChildAt(1)).setText(detail);
            badge.setText(active ? "启" : "禁");
            badge.setTextColor(active ? Ui.GREEN : Ui.MUTED);
            badge.setBackground(Ui.shape(context, active ? 0xff254838 : Ui.BUTTON, 10));
            View trailing = row.getChildAt(2);
            Ui.FlatSwitch toggle;
            if (trailing instanceof Ui.FlatSwitch) toggle = (Ui.FlatSwitch) trailing;
            else {
                row.removeView(trailing);
                toggle = new Ui.FlatSwitch(context);
                row.addView(toggle);
            }
            toggle.setChecked(active);
            toggle.setContentDescription(service.label + (active ? "已启用，点击禁用" : "已禁用，点击启用"));
            toggle.setOnClickListener(v -> confirmServiceToggle(service));
            row.setOnClickListener(v -> serviceOptions(service));
            return row;
        }
    }

    private static final class AppRecord {
        final ApplicationInfo info;
        final String label;
        final Drawable icon;
        final boolean enabled;
        final Boolean running;
        AppRecord(ApplicationInfo info, String label, Drawable icon, boolean enabled, Boolean running) {
            this.info = info; this.label = label; this.icon = icon; this.enabled = enabled;
            this.running = running;
        }
    }

    private static final class ServiceRecord {
        final ComponentName component;
        final String label;
        final String packageLabel;
        final boolean system;
        final boolean componentEnabled;
        final boolean packageEnabled;
        final String permission;
        ServiceRecord(ComponentName component, String label, String packageLabel, boolean system,
                      boolean componentEnabled, boolean packageEnabled, String permission) {
            this.component = component; this.label = label; this.packageLabel = packageLabel;
            this.system = system; this.componentEnabled = componentEnabled;
            this.packageEnabled = packageEnabled; this.permission = permission;
        }
    }
}
