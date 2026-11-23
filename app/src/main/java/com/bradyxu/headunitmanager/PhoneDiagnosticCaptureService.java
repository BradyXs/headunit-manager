package com.bradyxu.headunitmanager;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Explicit, bounded capture of state-only diagnostics before logcat wraps. */
public final class PhoneDiagnosticCaptureService extends Service {
    private static final long MAX_DURATION_MS = 10 * 60 * 1000L;
    private static final int MAX_LINES = 1000;
    private static volatile boolean active;
    private static volatile boolean ready;
    private static volatile String latestPath = "";
    private static volatile String latestError = "";
    private volatile boolean running;
    private Process logcat;

    static boolean active() { return active; }
    static boolean ready() { return ready; }
    static String latestPath() { return latestPath; }
    static String latestError() { return latestError; }
    static void prepareStart() {
        latestPath = "";
        latestError = "";
        ready = false;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (running) return START_NOT_STICKY;
        if (!Privilege.has(this, "android.permission.READ_LOGS")) {
            latestError = "缺少 READ_LOGS 权限";
            android.util.Log.w("P350Capture", "start_failed permission");
            stopSelf();
            return START_NOT_STICKY;
        }
        prepareStart();
        running = true;
        active = true;
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice = new Notification.Builder(this)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("车机电话诊断采集中")
            .setContentText("最多 10 分钟；返回工具箱结束")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(2, notice);
        new Thread(() -> capture(), "p350-phone-capture").start();
        return START_NOT_STICKY;
    }

    private void capture() {
        long started = System.currentTimeMillis();
        File dir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), "HeadunitManager");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date(started));
        File file = new File(dir, "phone-diagnostic-" + stamp + ".txt");
        File pending = new File(dir, ".phone-diagnostic-" + stamp + ".part");
        int lines = 0;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("output directory unavailable");
            if (file.exists() || !pending.createNewFile()) throw new Exception("capture file already exists");
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(pending), "UTF-8")) {
                writer.write("P350 phone diagnostics started " + stamp + "; state-only tags; max 10 minutes / 1000 lines\n");
                writer.flush();
                // Filter in logcat itself: high-volume USB errors cannot displace these lines here.
                logcat = new ProcessBuilder("logcat", "-b", "main", "-v", "threadtime",
                    "P350PhoneDiag:I", "P350AudioProbe:I", "P350FirmwareBridge:I",
                    "P350HfpGate:I", "P350HfpNative:I", "P350HfpRestore:I", "P350Recents:I",
                    "SCREENDOWNCATCH:I", "*:S")
                    .redirectErrorStream(true).start();
                ready = true;
                android.util.Log.i("P350Capture", "capture_file_opened");
                BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream(), "UTF-8"));
                SimpleDateFormat time = new SimpleDateFormat("yyyy MM-dd HH:mm:ss.SSS", Locale.US);
                int year = Calendar.getInstance().get(Calendar.YEAR);
                while (running && lines < MAX_LINES && System.currentTimeMillis() - started < MAX_DURATION_MS) {
                    if (!reader.ready()) {
                        try { throw new Exception("logcat exited before capture ended: " + logcat.exitValue()); }
                        catch (IllegalThreadStateException stillRunning) { }
                        Thread.sleep(100L);
                        continue;
                    }
                    String line = reader.readLine();
                    if (line == null) break;
                    if (!line.contains("P350PhoneDiag") && !line.contains("P350AudioProbe") &&
                        !line.contains("P350FirmwareBridge") && !line.contains("P350HfpGate") &&
                        !line.contains("P350HfpNative") &&
                        !line.contains("P350HfpRestore") && !line.contains("P350Recents") &&
                        !line.contains("SCREENDOWNCATCH")) continue;
                    if (line.length() < 18) continue;
                    long when;
                    try { when = time.parse(year + " " + line.substring(0, 18)).getTime(); }
                    catch (Exception ignored) { continue; }
                    if (when < started - 1000L) continue;
                    writer.write(line.length() > 500 ? line.substring(0, 500) : line);
                    writer.write('\n');
                    writer.flush();
                    lines++;
                }
                writer.write("Capture ended; diagnostic lines=" + lines + "\n");
                writer.flush();
            }
            if (!pending.renameTo(file) || !file.isFile() || file.length() == 0L)
                throw new Exception("capture file publish failed");
            latestPath = file.getAbsolutePath();
            android.util.Log.i("P350Capture", "capture_saved lines=" + lines + " bytes=" + file.length());
        } catch (Exception error) {
            pending.delete();
            latestError = error.getClass().getSimpleName() + ": " +
                (error.getMessage() == null ? "unknown" : error.getMessage());
            android.util.Log.w("P350Capture", "capture_failed " + latestError);
        } finally {
            running = false;
            ready = false;
            active = false;
            if (logcat != null) logcat.destroy();
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        running = false;
        if (logcat != null) logcat.destroy();
        stopForeground(true);
        super.onDestroy();
    }
}
