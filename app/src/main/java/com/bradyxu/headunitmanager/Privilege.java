package com.bradyxu.headunitmanager;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;

final class Privilege {
    private Privilege() {}

    static boolean has(Context c, String permission) {
        return c.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean systemUid() {
        return android.os.Process.myUid() == android.os.Process.SYSTEM_UID;
    }

    static boolean managementAvailable() {
        return systemUid() || rootAvailable();
    }

    static boolean rootAvailable() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end) {
                try { p.exitValue(); break; }
                catch (IllegalThreadStateException e) { Thread.sleep(50); }
            }
            try { p.exitValue(); }
            catch (IllegalThreadStateException e) { p.destroy(); return false; }
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            return p.exitValue() == 0 && line != null && line.contains("uid=0");
        } catch (Exception e) { return false; }
    }

    static String run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() < 12000) {
                    int remaining = 12000 - out.length();
                    out.append(line, 0, Math.min(line.length(), remaining));
                    if (out.length() < 12000) out.append('\n');
                }
            }
            int code = p.waitFor();
            return "退出码 " + code + "\n" + out;
        } catch (Exception e) { return e.toString(); }
    }

    static String rootCommand(String... args) {
        StringBuilder command = new StringBuilder();
        for (String arg : args) {
            if (command.length() > 0) command.append(' ');
            command.append('\'').append(arg.replace("'", "'\\''")).append('\'');
        }
        return run("su", "-c", command.toString());
    }

    static String managementCommand(String... args) {
        if (systemUid()) return run(args);
        if (rootAvailable()) return rootCommand(args);
        return "缺少系统 UID 或 root，未执行。";
    }

    static String propertyValue(String name) {
        try {
            Process p = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String value = reader.readLine();
            if (p.waitFor() != 0) return "";
            return value == null ? "" : value.trim();
        } catch (Exception e) { return ""; }
    }
}
