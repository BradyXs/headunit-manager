package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.view.View;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.BaseAdapter;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Arrays;
import java.util.ArrayList;

final class FilePage extends LinearLayout {
    private final Activity activity;
    private final GridView list;
    private final TextView fileStatus;
    private final LinearLayout pathBar;
    private final LinearLayout toolbar;
    private final LinearLayout firstLine;
    private final LinearLayout transferActions;
    private final android.widget.HorizontalScrollView firstScroll;
    private boolean transferOnSecondLine;
    private final LinearLayout normalActions;
    private final LinearLayout selectionActions;
    private final TextView selectAllButton;
    private final TextView copySelectedButton;
    private final TextView cutSelectedButton;
    private final TextView deleteSelectedButton;
    private final FileAdapter adapter = new FileAdapter();
    private FileEntry[] entries = new FileEntry[0];
    private int scanGeneration;
    private File current = Environment.getExternalStorageDirectory();
    private final java.util.ArrayList<File> clipboard = new java.util.ArrayList<>();
    private final java.util.LinkedHashSet<File> selected = new java.util.LinkedHashSet<>();
    private boolean selecting;
    private boolean move;
    private final TransferServer transfer = new TransferServer();

    FilePage(Activity a) {
        super(a);
        activity = a;
        setOrientation(VERTICAL);
        toolbar = Ui.column(a);
        toolbar.setPadding(Ui.dp(a, 5), Ui.dp(a, 3), Ui.dp(a, 5), Ui.dp(a, 3));
        toolbar.setBackground(Ui.shape(a, Ui.SURFACE, 12));
        firstLine = Ui.row(a);
        firstLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addToolbarButton(firstLine, fileButton("内部存储", false, v -> open(Environment.getExternalStorageDirectory())));
        addToolbarButton(firstLine, fileButton("下载", false, v -> open(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))));
        addToolbarButton(firstLine, fileButton("U 盘", false, v -> storageRoots()));
        addDivider(firstLine);
        normalActions = Ui.row(a);
        selectAllButton = fileButton("全选", false, v -> selectAll());
        addToolbarButton(normalActions, fileButton("新建", true, v -> create()));
        addToolbarButton(normalActions, fileButton("粘贴", false, v -> paste()));
        addToolbarButton(normalActions, selectAllButton);
        addToolbarButton(normalActions, fileButton("刷新", false, v -> open(current)));
        firstLine.addView(normalActions);
        selectionActions = Ui.row(a);
        copySelectedButton = fileButton("复制所选", true, v -> batchAction(0));
        cutSelectedButton = fileButton("剪切所选", false, v -> batchAction(1));
        deleteSelectedButton = fileButton("删除所选", false, v -> batchAction(2));
        deleteSelectedButton.setTextColor(0xffffb4aa);
        addToolbarButton(selectionActions, copySelectedButton);
        addToolbarButton(selectionActions, cutSelectedButton);
        addToolbarButton(selectionActions, deleteSelectedButton);
        addToolbarButton(selectionActions, fileButton("取消选择", false, v -> cancelSelection()));
        selectionActions.setVisibility(View.GONE);
        firstLine.addView(selectionActions);
        transferActions = Ui.row(a);
        addToolbarButton(transferActions, fileButton("快传", false, v -> transfer()));
        addToolbarButton(transferActions, fileButton("链接下载", false, v -> download()));
        addToolbarButton(transferActions, fileButton("飞机盘", false, v -> new Feijipan(activity,
            (link, name) -> startDownload(link, name)).openInput()));
        if (Ui.contentWidthDp(a) >= 650) {
            addDivider(firstLine);
            firstLine.addView(transferActions);
        } else {
            transferOnSecondLine = true;
        }
        firstScroll = toolbarScroll(firstLine);
        toolbar.addView(firstScroll);
        if (transferOnSecondLine) toolbar.addView(toolbarScroll(transferActions));
        addView(toolbar);
        firstScroll.post(this::ensureToolbarFits);
        pathBar = Ui.row(a);
        pathBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        pathBar.setPadding(Ui.dp(a, 4), Ui.dp(a, 3), Ui.dp(a, 4), Ui.dp(a, 3));
        LayoutParams pathParams = new LayoutParams(-1, -2);
        pathParams.topMargin = Ui.dp(a, 6);
        pathParams.bottomMargin = Ui.dp(a, 6);
        addView(pathBar, pathParams);
        fileStatus = Ui.text(a, "正在读取文件…", 14);
        fileStatus.setTextColor(Ui.MUTED);
        addView(fileStatus);
        list = new GridView(a);
        list.setNumColumns(Ui.contentWidthDp(a) >= 480 ? 2 : 1);
        list.setHorizontalSpacing(Ui.dp(a, 8));
        list.setVerticalSpacing(Ui.dp(a, 8));
        list.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 8));
        list.setClipToPadding(false);
        list.setBackgroundColor(Ui.BACK);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            File child = entries[position].file;
            if (selecting) toggleSelection(child);
            else if (entries[position].directory) open(child);
            else options(child);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            File child = entries[position].file;
            if (!selecting) { selecting = true; selected.clear(); }
            toggleSelection(child);
            return true;
        });
        addView(list, new LayoutParams(-1, 0, 1));
        open(current);
    }

    private TextView fileButton(String label, boolean primary, View.OnClickListener click) {
        TextView button = Ui.text(activity, label, 13);
        button.setTextSize(13);
        button.setTextColor(primary ? Ui.BACK : Ui.TEXT);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        button.setGravity(android.view.Gravity.CENTER);
        button.setSingleLine(true);
        button.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);
        button.setBackground(Ui.touchBackground(activity,
            primary ? Ui.ACCENT : Ui.BUTTON, primary ? Ui.RAIL_SELECTED : Ui.SURFACE_LIGHT, 12));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        return button;
    }

    private void addToolbarButton(LinearLayout row, TextView button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(activity, 40));
        params.setMargins(Ui.dp(activity, 3), Ui.dp(activity, 2), Ui.dp(activity, 3), Ui.dp(activity, 2));
        row.addView(button, params);
    }

    private void addDivider(LinearLayout row) {
        View divider = new View(activity);
        divider.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(activity, 1), Ui.dp(activity, 24));
        params.setMargins(Ui.dp(activity, 5), 0, Ui.dp(activity, 5), 0);
        row.addView(divider, params);
    }

    private android.widget.HorizontalScrollView toolbarScroll(LinearLayout row) {
        android.widget.HorizontalScrollView scroll = Ui.horizontalScroll(activity, row);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        return scroll;
    }

    private void ensureToolbarFits() {
        if (transferOnSecondLine || firstScroll.getWidth() == 0 ||
            firstLine.getWidth() <= firstScroll.getWidth()) return;
        firstLine.removeView(transferActions);
        firstLine.removeViewAt(firstLine.getChildCount() - 1);
        toolbar.addView(toolbarScroll(transferActions));
        transferOnSecondLine = true;
    }

    private void transfer() {
        Ui.confirm(activity, "局域网快传", "开启后，同一局域网内持有页面地址的人可以上传文件。请在传输结束后关闭。", () -> {
            try {
                String token = transfer.start();
                String address = lanAddress();
                Ui.dialog(activity).setTitle("文件快传已开启")
                    .setMessage("在手机浏览器打开：\nhttp://" + address + ":8088/?token=" + token + "\n\n文件保存到 Download/HeadunitManager/incoming")
                    .setPositiveButton("关闭快传", (d, w) -> transfer.stop())
                    .setOnCancelListener(d -> transfer.stop()).show();
            } catch (Exception e) { Ui.toast(activity, "无法开启快传：" + e.getMessage()); }
        });
    }

    private String lanAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "车机局域网IP";
    }

    private void download() {
        EditText input = Ui.input(activity);
        input.setSingleLine(true);
        input.setHint("https://.../文件.apk");
        Ui.dialog(activity).setTitle("下载直链文件").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("下载", (d, w) -> {
                String url = input.getText().toString().trim();
                if (!(url.startsWith("https://") || url.startsWith("http://"))) {
                    Ui.toast(activity, "请输入 http 或 https 链接"); return;
                }
                startDownload(url, null);
            }).show();
    }

    private void startDownload(String link, String suppliedName) {
        File destination = current;
        String name = suppliedName;
        try {
            if (name == null) name = URLDecoder.decode(new File(new URL(link).getPath()).getName(), "UTF-8");
            if (FileOps.safeChild(destination, name) == null) throw new IOException("无法确定有效文件名");
        } catch (Exception e) { Ui.toast(activity, "无法下载：" + e.getMessage()); return; }
        final String filename = name;
        final DownloadJob job = new DownloadJob();
        final Ui.TransferProgress progress = new Ui.TransferProgress(activity, filename, () -> {
            job.cancelled = true;
            new Thread(() -> { if (job.connection != null) job.connection.disconnect(); }).start();
        });
        new Thread(() -> {
            File partial = null;
            boolean partialOwned = false;
            try {
                URL url = new URL(link);
                if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https"))) throw new IOException("仅支持 HTTP(S) 链接");
                File target = FileOps.safeChild(destination, filename);
                if (target == null || target.exists()) throw new IOException("目标已存在或文件名无效：" + filename);
                partial = new File(destination, filename + ".part");
                if (!partial.createNewFile()) throw new IOException("存在同名下载任务或无法创建文件");
                partialOwned = true;
                if (job.cancelled) throw new IOException("已取消下载");
                job.connection = (HttpURLConnection) url.openConnection();
                TlsCompat.enable(job.connection);
                job.connection.setConnectTimeout(10000); job.connection.setReadTimeout(30000);
                job.connection.setInstanceFollowRedirects(true);
                int status = job.connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
                String type = job.connection.getContentType();
                if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".apk") && type != null && type.toLowerCase(java.util.Locale.ROOT).contains("text/html"))
                    throw new IOException("返回的是网页而非 APK，请使用真实下载地址");
                long length = job.connection.getContentLength();
                if (length > 512L * 1024 * 1024) throw new IOException("文件超过 512 MB");
                long total = 0;
                try (java.io.InputStream in = job.connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buf = new byte[65536]; int n; int previous = -1;
                    while (!job.cancelled && (n = in.read(buf)) != -1) {
                        total += n;
                        if (total > 512L * 1024 * 1024) throw new IOException("文件超过 512 MB");
                        out.write(buf, 0, n);
                        if (length > 0) {
                            int percent = (int) Math.min(100, total * 100 / length);
                            if (percent != previous) { previous = percent; activity.runOnUiThread(() -> progress.setProgress(percent)); }
                        }
                    }
                }
                if (job.cancelled) throw new IOException("已取消下载");
                if (length >= 0 && total != length) throw new IOException("下载未完成");
                if (target.exists() || !partial.renameTo(target)) throw new IOException("目标已存在或无法完成文件重命名");
                partial = null;
                activity.runOnUiThread(() -> { progress.dismiss(); Ui.toast(activity, "下载完成：" + target); open(current); });
            } catch (Exception e) {
                if (partialOwned && partial != null) partial.delete();
                String error = job.cancelled ? "已取消下载" : "下载失败：" + e.getMessage();
                activity.runOnUiThread(() -> { progress.dismiss(); Ui.toast(activity, error); });
            } finally { if (job.connection != null) job.connection.disconnect(); }
        }, "file-download").start();
    }

    private static final class DownloadJob {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
    }

    @Override protected void onDetachedFromWindow() {
        transfer.stop();
        super.onDetachedFromWindow();
    }

    private void storageRoots() {
        File[] roots = {new File("/storage"), new File("/mnt"), new File("/mnt/media_rw")};
        for (File root : roots) if (root.isDirectory()) { open(root); return; }
        Ui.toast(activity, "未找到 U 盘挂载目录");
    }

    private void open(File dir) {
        if (!dir.isDirectory() || !dir.canRead()) { Ui.toast(activity, "目录不可读取：" + dir); return; }
        if (!dir.equals(current)) { selected.clear(); selecting = false; }
        current = dir;
        showLocation();
        fileStatus.setText("正在读取文件…");
        fileStatus.setVisibility(View.VISIBLE);
        entries = new FileEntry[0];
        adapter.notifyDataSetChanged();
        int generation = ++scanGeneration;
        new Thread(() -> {
            File[] children = dir.listFiles();
            if (children != null) Arrays.sort(children, (x, y) -> {
                if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                return x.getName().compareToIgnoreCase(y.getName());
            });
            FileEntry[] loaded = children == null ? null : new FileEntry[children.length];
            if (loaded != null) {
                java.text.DateFormat date = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault());
                for (int i = 0; i < children.length; i++) {
                    File child = children[i];
                    boolean directory = child.isDirectory();
                    boolean apk = child.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk");
                    String detail = directory ? "文件夹 · " + date.format(new java.util.Date(child.lastModified())) :
                        (apk ? "安装包" : "文件") + " · " + (child.length() / 1024) + " KB";
                    loaded[i] = new FileEntry(child, directory, apk, detail);
                }
            }
            activity.runOnUiThread(() -> {
                if (generation != scanGeneration) return;
                entries = loaded == null ? new FileEntry[0] : loaded;
                showLocation();
                adapter.notifyDataSetChanged();
                if (loaded == null) fileStatus.setText("无法读取此目录");
                else if (loaded.length == 0) fileStatus.setText("空目录");
                else fileStatus.setVisibility(View.GONE);
            });
        }, "file-scan").start();
    }

    private void showLocation() {
        pathBar.removeAllViews();
        if (selecting) {
            TextView count = Ui.text(activity, "已选 " + selected.size() + " 项", 13);
            count.setTextColor(Ui.ACCENT);
            count.setSingleLine(true);
            pathBar.addView(count);
        }
        LinearLayout crumbs = Ui.row(activity);
        crumbs.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ArrayList<File> ancestors = new ArrayList<>();
        for (File folder = current.getAbsoluteFile(); folder != null; folder = folder.getParentFile())
            ancestors.add(folder);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            File folder = ancestors.get(i);
            boolean last = i == 0;
            String name = folder.getParentFile() == null ? "根目录" : folder.getName();
            TextView segment = Ui.text(activity, name, 13);
            segment.setSingleLine(true);
            segment.setGravity(android.view.Gravity.CENTER_VERTICAL);
            segment.setMinHeight(Ui.dp(activity, 40));
            segment.setPadding(Ui.dp(activity, 7), 0, Ui.dp(activity, 7), 0);
            segment.setTextColor(last ? Ui.TEXT : Ui.ACCENT);
            if (!last) {
                segment.setBackground(Ui.touchBackground(activity,
                    android.graphics.Color.TRANSPARENT, Ui.BUTTON, 8));
                segment.setContentDescription("进入" + folder.getAbsolutePath());
                segment.setOnClickListener(v -> open(folder));
            }
            crumbs.addView(segment);
            if (!last) {
                TextView separator = Ui.text(activity, "›", 17);
                separator.setTextColor(Ui.MUTED);
                separator.setPadding(0, 0, 0, 0);
                crumbs.addView(separator);
            }
        }
        android.widget.HorizontalScrollView pathScroll = new android.widget.HorizontalScrollView(activity);
        pathScroll.setHorizontalScrollBarEnabled(false);
        pathScroll.setFillViewport(false);
        pathScroll.addView(crumbs, new android.widget.FrameLayout.LayoutParams(-2, -1));
        pathBar.addView(pathScroll, new LayoutParams(0, Ui.dp(activity, 40), 1));
        pathScroll.post(() -> pathScroll.scrollTo(crumbs.getWidth(), 0));
        normalActions.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectionActions.setVisibility(selecting ? View.VISIBLE : View.GONE);
        firstScroll.post(this::ensureToolbarFits);
        boolean hasSelection = !selected.isEmpty();
        for (TextView button : new TextView[]{copySelectedButton, cutSelectedButton, deleteSelectedButton}) {
            button.setEnabled(hasSelection);
            button.setAlpha(hasSelection ? 1f : .45f);
        }
        if (selecting) pathBar.addView(pathAction(
            selected.size() == entries.length && entries.length > 0 ? "取消全选" : "全选", false,
            v -> selectAll()));
        else pathBar.addView(pathAction("← 上级", true, v -> {
            if (current.getParentFile() != null) open(current.getParentFile());
        }));
    }

    private TextView pathAction(String label, boolean back, View.OnClickListener click) {
        TextView action = Ui.text(activity, label, 14);
        action.setTextColor(Ui.ACCENT);
        action.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        action.setGravity(android.view.Gravity.CENTER);
        action.setSingleLine(true);
        action.setMinHeight(Ui.dp(activity, 40));
        action.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 8), 0);
        action.setBackground(Ui.touchBackground(activity,
            android.graphics.Color.TRANSPARENT, Ui.BUTTON, 8));
        action.setContentDescription(back ? "进入上级文件夹" : label);
        action.setFocusable(true);
        action.setOnClickListener(click);
        return action;
    }

    private void cancelSelection() {
        selecting = false;
        selected.clear();
        showLocation();
        adapter.notifyDataSetChanged();
    }

    private void toggleSelection(File child) {
        if (!selected.add(child)) selected.remove(child);
        showLocation();
        adapter.notifyDataSetChanged();
    }

    private static final class FileEntry {
        final File file;
        final boolean directory;
        final boolean apk;
        final String detail;
        FileEntry(File file, boolean directory, boolean apk, String detail) {
            this.file = file; this.directory = directory; this.apk = apk; this.detail = detail;
        }
    }

    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.length; }
        @Override public Object getItem(int position) { return entries[position]; }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout item = convertView instanceof LinearLayout ? (LinearLayout) convertView :
                Ui.iconItem(activity, "", "", Ui.fileIcon(true, false), null);
            item.setFocusable(false);
            item.setClickable(false);
            FileEntry entry = entries[position];
            ImageView icon = (ImageView) item.getChildAt(0);
            LinearLayout words = (LinearLayout) item.getChildAt(1);
            TextView arrow = (TextView) item.getChildAt(2);
            ((TextView) words.getChildAt(0)).setTextSize(15);
            ((TextView) words.getChildAt(0)).setText(entry.file.getName());
            ((TextView) words.getChildAt(1)).setText(entry.detail);
            icon.setImageDrawable(Ui.fileIcon(entry.directory, entry.apk));
            icon.setContentDescription(entry.directory ? "文件夹" : entry.apk ? "安装包" : "文件");
            arrow.setTextSize(18);
            item.setBackground(Ui.touchBackground(activity, selected.contains(entry.file) ? 0xff204b6a : Ui.SURFACE,
                selected.contains(entry.file) ? 0xff2c6288 : Ui.SURFACE_LIGHT, 12));
            return item;
        }
    }

    boolean navigateBack() {
        File root = Environment.getExternalStorageDirectory();
        if (current.equals(root) || current.getParentFile() == null) return false;
        open(current.getParentFile());
        return true;
    }

    private void options(File file) {
        String[] choices = file.getName().toLowerCase().endsWith(".apk") ?
            new String[]{"安装 APK", "复制", "剪切", "重命名", "删除", "属性"} :
            new String[]{"打开", "复制", "剪切", "重命名", "删除", "属性"};
        Ui.dialog(activity).setTitle(file.getName()).setItems(choices, (d, which) -> {
            if (which == 0) {
                if (file.isDirectory()) open(file);
                else if (file.getName().toLowerCase().endsWith(".apk")) {
                    ApkInstaller.choose(activity, file);
                } else {
                    String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getName());
                    String type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(Uri.fromFile(file), type == null ? "*/*" : type);
                    try { activity.startActivity(view); }
                    catch (android.content.ActivityNotFoundException e) { Ui.toast(activity, "车机没有可打开此文件的应用"); }
                }
            } else if (which == 1 || which == 2) {
                clipboard.clear(); clipboard.add(file); move = which == 2;
                Ui.toast(activity, "已" + (move ? "剪切" : "复制") + "：" + file.getName());
            } else if (which == 3) rename(file);
            else if (which == 4) Ui.confirm(activity, "删除文件", file.getAbsolutePath(), () -> new Thread(() -> {
                boolean deleted = FileOps.delete(file);
                activity.runOnUiThread(() -> {
                    Ui.toast(activity, deleted ? "已删除" : "删除失败");
                    open(current);
                });
            }).start());
            else Ui.dialog(activity).setTitle("属性")
                .setMessage(file.getAbsolutePath() + "\n大小：" + file.length() + " 字节\n可读：" + file.canRead() + "\n可写：" + file.canWrite())
                .setPositiveButton("确定", null).show();
        }).show();
    }

    private void create() {
        String[] types = {"文件夹", "空文件"};
        Ui.dialog(activity).setItems(types, (d, type) -> input("新建" + types[type], "", name -> {
            File file = FileOps.safeChild(current, name);
            if (file == null) { Ui.toast(activity, "名称不合法"); return; }
            try {
                boolean ok = type == 0 ? file.mkdir() : file.createNewFile();
                Ui.toast(activity, ok ? "已创建" : "创建失败"); open(current);
            } catch (IOException e) { Ui.toast(activity, e.toString()); }
        })).show();
    }

    private void rename(File file) {
        input("重命名", file.getName(), name -> {
            File dest = FileOps.safeChild(current, name);
            if (dest == null || dest.exists()) { Ui.toast(activity, "名称不合法或已存在"); return; }
            Ui.toast(activity, file.renameTo(dest) ? "已重命名" : "重命名失败");
            open(current);
        });
    }

    private interface NameAction { void apply(String name); }
    private void input(String title, String initial, NameAction action) {
        EditText text = Ui.input(activity); text.setText(initial);
        Ui.dialog(activity).setTitle(title).setView(text).setNegativeButton("取消", null)
            .setPositiveButton("确定", (d, w) -> action.apply(text.getText().toString().trim())).show();
    }

    private void selectAll() {
        if (entries.length == 0) { Ui.toast(activity, "当前目录没有可选文件"); return; }
        selecting = true;
        if (selected.size() == entries.length) selected.clear();
        else { selected.clear(); for (FileEntry entry : entries) selected.add(entry.file); }
        showLocation(); adapter.notifyDataSetChanged();
    }

    private void batchAction(int action) {
        if (!selecting || selected.isEmpty()) { Ui.toast(activity, "请长按文件进入多选，再选择文件"); return; }
        java.util.ArrayList<File> chosen = new java.util.ArrayList<>(selected);
        if (action < 2) {
            clipboard.clear(); clipboard.addAll(chosen); move = action == 1;
            selecting = false; selected.clear(); showLocation(); adapter.notifyDataSetChanged();
            Ui.toast(activity, "已" + (move ? "剪切 " : "复制 ") + clipboard.size() + " 项，请进入目标目录后粘贴");
            return;
        }
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < Math.min(chosen.size(), 8); i++) preview.append(chosen.get(i).getName()).append('\n');
        if (chosen.size() > 8) preview.append("等 ").append(chosen.size()).append(" 项");
        Ui.confirm(activity, "删除选中项", "将删除以下文件和文件夹及其内容：\n" + preview, () -> new Thread(() -> {
            java.util.ArrayList<String> failed = new java.util.ArrayList<>();
            for (File source : chosen) if (!FileOps.delete(source)) failed.add(source.getName());
            activity.runOnUiThread(() -> {
                selecting = false; selected.clear(); open(current);
                resultDialog("批量删除", chosen.size() - failed.size(), failed);
            });
        }).start());
    }

    private void resultDialog(String title, int done, java.util.List<String> failed) {
        StringBuilder message = new StringBuilder("完成 ").append(done).append(" 项");
        if (!failed.isEmpty()) {
            message.append("\n未完成 ").append(failed.size()).append(" 项：\n");
            for (String error : failed) message.append(error).append('\n');
        }
        Ui.dialog(activity).setTitle(title).setMessage(message.toString()).setPositiveButton("确定", null).show();
    }

    private void paste() {
        if (clipboard.isEmpty()) { Ui.toast(activity, "先复制或剪切文件"); return; }
        final java.util.ArrayList<File> sources = new java.util.ArrayList<>(clipboard);
        final File destination = current;
        final boolean cut = move;
        new Thread(() -> {
            java.util.ArrayList<String> failed = new java.util.ArrayList<>();
            java.util.ArrayList<File> completed = new java.util.ArrayList<>();
            for (File source : sources) {
                File target = FileOps.safeChild(destination, source.getName());
                try {
                    if (target == null) throw new IOException("目标名称无效");
                    FileOps.copy(source, target);
                    if (cut && !FileOps.delete(source)) throw new IOException("目标已复制，源文件删除不完整；请手动核对");
                    completed.add(source);
                } catch (IOException e) { failed.add(source.getName() + "：" + e.getMessage()); }
            }
            activity.runOnUiThread(() -> {
                if (cut) clipboard.removeAll(completed);
                open(current);
                resultDialog(cut ? "批量移动" : "批量复制", completed.size(), failed);
            });
        }).start();
    }
}
