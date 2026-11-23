package com.bradyxu.headunitmanager;

import android.app.Service;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class LogService extends Service {
    private volatile boolean running;
    private static volatile boolean active;
    private static final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    static boolean active() { return active; }
    static synchronized String[] recent(String query) {
        java.util.ArrayList<String> matching = new java.util.ArrayList<>();
        for (String line : recent) if (query.isEmpty() || line.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) matching.add(line);
        java.util.Collections.reverse(matching);
        return matching.subList(0, Math.min(30, matching.size())).toArray(new String[0]);
    }
    static synchronized String[] allRecent() { return recent.toArray(new String[0]); }
    private static synchronized void remember(String line) {
        if (recent.size() >= 100) recent.removeFirst();
        recent.addLast(line.length() > 500 ? line.substring(0, 500) : line);
    }
    private Process logcat;
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (running || !Privilege.has(this, "android.permission.READ_LOGS")) return START_NOT_STICKY;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice = new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("车机管家自动化").setContentText("正在监听日志关键字")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(1, notice);
        running = true;
        active = true;
        final long started = System.currentTimeMillis();
        new Thread(() -> {
            try {
                logcat = new ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
                String line;
                java.text.SimpleDateFormat stamp = new java.text.SimpleDateFormat("yyyy MM-dd HH:mm:ss.SSS", java.util.Locale.US);
                while (running && (line = reader.readLine()) != null) {
                    if (line.length() < 19) continue;
                    try {
                        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
                        long when = stamp.parse(year + " " + line.substring(0, 18)).getTime();
                        if (when > System.currentTimeMillis() + 86400000L)
                            when = stamp.parse((year - 1) + " " + line.substring(0, 18)).getTime();
                        if (when < started) continue;
                    } catch (java.text.ParseException e) { continue; }
                    if (line.length() > 1000) line = line.substring(0, 1000);
                    remember(line);
                    Rules.dispatch(this, Rules.TRIGGER_LOG, line, null);
                }
            } catch (Exception ignored) { }
            finally { running = false; active = false; if (logcat != null) logcat.destroy(); stopSelf(); }
        }, "manager-log-listener").start();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        active = false;
        synchronized (LogService.class) { recent.clear(); }
        if (logcat != null) logcat.destroy();
        stopForeground(true);
        super.onDestroy();
    }
}
