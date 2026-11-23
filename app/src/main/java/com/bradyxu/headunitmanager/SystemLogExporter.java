package com.bradyxu.headunitmanager;

import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded Android 4.4 logcat snapshot; never leaves an empty published report. */
final class SystemLogExporter {
    private static final int MAX_LINES = 2000;
    private static final int MAX_PHONE_DIAGNOSTIC_LINES = 300;
    private static final int MAX_LINE_CHARS = 4096;
    private static final long BUFFER_TIMEOUT_MS = 8000L;

    static final class Result {
        final File file;
        final int lines;
        final String notes;
        Result(File file, int lines, String notes) {
            this.file = file;
            this.lines = lines;
            this.notes = notes;
        }
    }

    private static final class BufferResult {
        final ArrayDeque<String> lines = new ArrayDeque<>();
        final ArrayDeque<String> phoneDiagnostics = new ArrayDeque<>();
        volatile int exit = -1;
        volatile boolean timedOut;
        volatile String error;
    }

    private SystemLogExporter() { }

    static Result export() throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HeadunitManager");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("无法创建 Download/HeadunitManager 目录");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File output = new File(dir, "system-log-" + stamp + ".txt");
        File pending = new File(dir, ".system-log-" + stamp + ".part");
        BufferResult main = read("main");
        BufferResult system = read("system");
        String notes = note("main", main) + note("system", system);
        int total = main.lines.size() + system.lines.size() +
            main.phoneDiagnostics.size() + system.phoneDiagnostics.size();
        if (output.exists() || !pending.createNewFile()) throw new Exception("日志文件已存在，请重试");
        boolean published = false;
        try {
            try (FileOutputStream stream = new FileOutputStream(pending);
                 Writer writer = new OutputStreamWriter(stream, "UTF-8")) {
                writer.write("System log export: " + stamp + "\n");
                writeBuffer(writer, "main", main);
                writeBuffer(writer, "system", system);
                writer.write("\nResult: " + total + " lines. " + notes + "\n");
                writer.flush();
            }
            if (!pending.renameTo(output)) throw new Exception("日志保存失败，请检查存储空间");
            published = true;
            return new Result(output, total, notes);
        } finally {
            if (!published) pending.delete();
        }
    }

    private static void writeBuffer(Writer writer, String name, BufferResult result) throws Exception {
        writer.write("\n=== logcat " + name + " (latest " + MAX_LINES + " lines) ===\n");
        for (String line : result.lines) {
            writer.write(line);
            writer.write('\n');
        }
        writer.write("=== retained P350 phone/audio diagnostics (may repeat above) ===\n");
        for (String line : result.phoneDiagnostics) {
            writer.write(line);
            writer.write('\n');
        }
        writer.write("=== exit=" + result.exit + " timeout=" + result.timedOut +
            (result.error == null ? "" : " error=" + result.error) + " ===\n");
    }

    private static String note(String name, BufferResult result) {
        if (result.timedOut) return name + " 超时，已保留部分日志；";
        if (result.error != null || result.exit != 0) return name + " 读取异常，详见文件结尾；";
        if (result.lines.isEmpty()) return name + " 无日志；";
        return name + " 正常；";
    }

    private static BufferResult read(String buffer) {
        BufferResult result = new BufferResult();
        AtomicReference<Process> processRef = new AtomicReference<>();
        Thread readerThread = new Thread(() -> {
            Process process = null;
            try {
                // Some vendor logcat builds keep a pipe open after destroy(). Read on a bounded daemon.
                process = new ProcessBuilder("logcat", "-b", buffer, "-d", "-v", "threadtime")
                    .redirectErrorStream(true).start();
                processRef.set(process);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String line;
                while (!result.timedOut && (line = reader.readLine()) != null) {
                    if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS);
                    synchronized (result.lines) {
                        if (result.lines.size() == MAX_LINES) result.lines.removeFirst();
                        result.lines.addLast(line);
                        if (line.contains("P350PhoneDiag") || line.contains("P350AudioProbe") ||
                            line.contains("P350FirmwareBridge") || line.contains("P350ConnAuth") || line.contains("P350HfpGate") ||
                            line.contains("P350HfpNative") || line.contains("P350Capture") ||
                            line.contains("P350HfpRestore") ||
                            line.contains("P350Recents") || line.contains("P350VolumeSession") || line.contains("SCREENDOWNCATCH")) {
                            if (result.phoneDiagnostics.size() == MAX_PHONE_DIAGNOSTIC_LINES)
                                result.phoneDiagnostics.removeFirst();
                            result.phoneDiagnostics.addLast(line);
                        }
                    }
                }
                if (!result.timedOut) result.exit = process.waitFor();
            } catch (Exception error) {
                if (!result.timedOut) result.error = error.getClass().getSimpleName();
            } finally {
                if (process != null) process.destroy();
            }
        }, "system-log-" + buffer);
        readerThread.setDaemon(true);
        readerThread.start();
        try {
            readerThread.join(BUFFER_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.error = "InterruptedException";
        }
        if (readerThread.isAlive()) {
            result.timedOut = true;
            Process running = processRef.get();
            if (running != null) running.destroy();
            readerThread.interrupt();
        }
        BufferResult snapshot = new BufferResult();
        synchronized (result.lines) {
            snapshot.lines.addAll(result.lines);
            snapshot.phoneDiagnostics.addAll(result.phoneDiagnostics);
        }
        snapshot.exit = result.exit;
        snapshot.timedOut = result.timedOut;
        snapshot.error = result.error;
        return snapshot;
    }
}
