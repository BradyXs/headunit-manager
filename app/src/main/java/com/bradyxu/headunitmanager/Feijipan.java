package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads public share data from the site's published web endpoints. */
final class Feijipan {
    interface Download { void start(String url, String name); }
    private final Activity activity;
    private final Download download;
    private final String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 21);
    private final ArrayList<Folder> history = new ArrayList<>();
    private String shareId, code, shareUrl;
    private String viewerId = "";
    private int offset = 1;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private boolean more;

    private static final class Folder {
        final String id, name;
        Folder(String id, String name) { this.id = id; this.name = name; }
    }
    private static final class Entry {
        final String id, name;
        final boolean folder;
        Entry(String id, String name, boolean folder) { this.id = id; this.name = name; this.folder = folder; }
    }

    Feijipan(Activity a, Download download) { this.activity = a; this.download = download; }

    void openInput() {
        LinearLayout content = Ui.column(activity);
        EditText link = Ui.input(activity); link.setHint("飞机盘分享链接");
        EditText password = Ui.input(activity); password.setHint("提取码（如有）");
        content.addView(link); content.addView(password);
        Ui.dialog(activity).setTitle("飞机盘分享")
            .setMessage("输入公开分享链接及提取码；访问受限时可尝试浏览器。")
            .setView(content).setNegativeButton("取消", null).setPositiveButton("浏览", (d, w) -> {
                try {
                    String raw = link.getText().toString().trim();
                    java.util.regex.Matcher match = java.util.regex.Pattern.compile("https?://[^\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(raw);
                    if (!match.find()) throw new IOException("请输入完整分享链接");
                    String value = match.group().replaceAll("[，。；）)]+$", "");
                    URL parsed = new URL(value);
                    String host = parsed.getHost().toLowerCase(java.util.Locale.ROOT);
                    if (!(host.equals("feijipan.com") || host.endsWith(".feijipan.com") || host.equals("feijix.com") || host.endsWith(".feijix.com")))
                        throw new IOException("目前只支持飞机盘分享链接");
                    java.util.regex.Matcher id = java.util.regex.Pattern.compile("^/s/([A-Za-z0-9_-]+)(?:/.*)?$").matcher(parsed.getPath());
                    if (!id.matches()) throw new IOException("无法识别分享地址，请使用 /s/ 开头的分享链接");
                    shareId = id.group(1); shareUrl = value;
                    code = password.getText().toString().trim();
                    if (code.isEmpty()) { String queryCode = Uri.parse(value).getQueryParameter("code"); if (queryCode != null) code = queryCode; }
                    history.clear(); history.add(new Folder("", "分享根目录"));
                    load(false);
                } catch (Exception e) { Ui.toast(activity, "无法打开：" + e.getMessage()); }
            }).show();
    }

    private Map<String, String> common() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("devType", "6"); p.put("devModel", "Chrome"); p.put("uuid", uuid); p.put("extra", "2");
        p.put("timestamp", encrypted(String.valueOf(System.currentTimeMillis())));
        p.put("shareId", shareId);
        if (code != null && !code.isEmpty()) p.put("code", code);
        return p;
    }

    private static String encrypted(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("dingHao-disk-app".getBytes("UTF-8"), "AES"));
        StringBuilder out = new StringBuilder();
        for (byte b : cipher.doFinal(value.getBytes("UTF-8"))) out.append(String.format(java.util.Locale.US, "%02X", b & 255));
        return out.toString();
    }

    private static String query(Map<String, String> values) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(entry.getKey(), "UTF-8")).append('=')
                .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return b.toString();
    }

    private HttpURLConnection connect(String endpoint, Map<String, String> params, String method, boolean redirects) throws Exception {
        URL url = new URL("https://api.feijipan.com/ws/" + endpoint + "?" + query(params));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        TlsCompat.enable(c);
        c.setConnectTimeout(12000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(redirects);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Origin", "https://share.feijipan.com");
        c.setRequestProperty("Referer", "https://share.feijipan.com/");
        c.setRequestMethod(method);
        if (method.equals("POST")) { c.setDoOutput(true); c.getOutputStream().close(); }
        return c;
    }

    private static String body(HttpURLConnection c) throws Exception {
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new IOException("HTTP " + status);
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n, total = 0;
            while ((n = input.read(buf)) != -1) {
                total += n; if (total > 1024 * 1024) throw new IOException("返回内容过大");
                out.write(buf, 0, n);
            }
            if (status >= 400) throw new IOException("HTTP " + status);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private void load(boolean next) {
        if (!next) { offset = 1; entries.clear(); }
        Folder folder = history.get(history.size() - 1);
        android.app.Dialog progress = Ui.progress(activity, "正在读取飞机盘目录…");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                Map<String, String> p = common(); p.put("offset", String.valueOf(offset)); p.put("limit", "80");
                if (!folder.id.isEmpty()) p.put("folderId", folder.id); else p.put("type", "0");
                c = connect(folder.id.isEmpty() ? "recommend/list" : "share/list", p, "POST", true);
                JSONObject data = new JSONObject(body(c));
                if (data.optInt("code", -1) != 200) throw new IOException(data.optString("msg", "无法读取目录"));
                if (data.optInt("status", 0) == 2) throw new IOException("提取码错误或分享需要授权");
                JSONArray list = data.optJSONArray("list");
                if (list == null) throw new IOException("服务未返回目录列表");
                ArrayList<Entry> page = new ArrayList<>();
                if (folder.id.isEmpty()) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject root = list.optJSONObject(i); if (root == null) continue;
                        JSONArray files = root.optJSONArray("fileList");
                        if (files != null) for (int j = 0; j < files.length(); j++) addEntry(page, files.optJSONObject(j));
                    }
                } else for (int i = 0; i < list.length(); i++) addEntry(page, list.optJSONObject(i));
                int total = data.optInt("total", -1);
                int responseCount = list.length();
                int currentPage = offset;
                activity.runOnUiThread(() -> {
                    progress.dismiss(); entries.addAll(page);
                    // Root results contain groups of files; total counts groups, not flattened entries.
                    more = responseCount > 0 && (total >= 0
                        ? (currentPage - 1) * 80 + responseCount < total : responseCount >= 80);
                    showDirectory();
                });
            } catch (Exception e) {
                String message = e.getMessage();
                activity.runOnUiThread(() -> { progress.dismiss(); showError(message); });
            } finally { if (c != null) c.disconnect(); }
        }, "feijipan-list").start();
    }

    private static void addEntry(ArrayList<Entry> entries, JSONObject item) {
        if (item == null) return;
        boolean folder = item.optInt("fileType", 0) == 2;
        String id = item.optString(folder ? "folderId" : "fileId", "");
        String name = item.optString(folder ? "folderName" : "fileName", "未命名");
        if (!id.isEmpty() && !id.equals("null")) entries.add(new Entry(id, name, folder));
    }

    private void showDirectory() {
        Folder folder = history.get(history.size() - 1);
        ArrayList<String> labels = new ArrayList<>();
        for (Entry e : entries) labels.add((e.folder ? "📁  " : "▤  ") + e.name);
        if (more) labels.add("加载下一页");
        if (labels.isEmpty()) labels.add("此目录为空");
        Ui.dialog(activity).setTitle(folder.name).setItems(labels.toArray(new String[0]), (d, which) -> {
            if (which < entries.size()) {
                Entry entry = entries.get(which);
                if (entry.folder) { history.add(new Folder(entry.id, entry.name)); load(false); }
                else file(entry);
            } else if (more) { offset++; load(true); }
            else showDirectory();
        }).setNeutralButton("浏览器打开", (d, w) -> browser())
          .setNegativeButton(history.size() > 1 ? "上级" : "关闭", (d, w) -> {
              if (history.size() > 1) { history.remove(history.size() - 1); load(false); }
          }).show();
    }

    private void file(Entry entry) {
        Ui.dialog(activity).setTitle(entry.name).setItems(new String[]{"下载到当前目录", "返回文件列表"}, (d, which) -> {
            if (which == 1) { showDirectory(); return; }
            android.app.Dialog progress = Ui.progress(activity, "正在获取下载地址…");
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    long now = System.currentTimeMillis();
                    Map<String, String> p = common(); p.put("timestamp", encrypted(String.valueOf(now)));
                    p.put("downloadId", encrypted(entry.id + "|" + viewerId));
                    p.put("auth", encrypted(entry.id + "|" + now)); p.put("enable", "1");
                    c = connect("file/redirect", p, "GET", false);
                    int status = c.getResponseCode();
                    String location = c.getHeaderField("Location");
                    if (status < 300 || status >= 400 || location == null) {
                        String reason;
                        try { reason = new JSONObject(body(c)).optString("msg", "服务未返回下载地址"); }
                        catch (Exception ignored) { reason = "服务未返回下载地址（HTTP " + status + "）"; }
                        throw new IOException(reason);
                    }
                    String resolved = new URL(new URL("https://api.feijipan.com/"), location).toString();
                    if (!(resolved.startsWith("https://") || resolved.startsWith("http://"))) throw new IOException("下载地址无效");
                    activity.runOnUiThread(() -> { progress.dismiss(); download.start(resolved, entry.name); });
                } catch (Exception e) {
                    String message = e.getMessage(); activity.runOnUiThread(() -> { progress.dismiss(); showError(message); });
                } finally { if (c != null) c.disconnect(); }
            }, "feijipan-link").start();
        }).setNegativeButton("取消", (d, w) -> showDirectory()).show();
    }

    private void browser() {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(shareUrl))); }
        catch (Exception e) { Ui.toast(activity, "无法打开浏览器：" + e.getMessage()); }
    }
    private void showError(String message) {
        Ui.dialog(activity).setTitle("飞机盘访问失败")
            .setMessage((message == null ? "服务没有返回可用结果" : message) + "\n若分享需要登录、权限或会员，请在浏览器查看。")
            .setNegativeButton("返回", (d, w) -> { if (!history.isEmpty()) { if (!history.get(history.size()-1).id.isEmpty()) history.remove(history.size()-1); if (!history.isEmpty()) load(false); } })
            .setPositiveButton("浏览器打开", (d, w) -> browser()).show();
    }
}
