package com.bradyxu.headunitmanager;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/** Wired-session HFP request gate and lease; it never globally disables Bluetooth profiles. */
final class HfpSessionLease {
    private static final String KEY = "p350_hfp_gate_v1";
    private static final long HEARTBEAT_TIMEOUT_MS = 15000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context context;
    private static IBinder owner;
    private static IBinder.DeathRecipient death;
    private static String address;
    private static String lastWritten;
    private static long heartbeat;
    private static boolean holding;
    private static String expectedGateBeforeWrite;
    private static boolean verifyGateBeforeWrite;
    private static long authorizationEpoch;
    private static int writesSinceLog;
    private static boolean prearmed;
    private static boolean automaticInFlight;
    private static String recentReleasedAddress;
    private static long recentReleasedAt;
    private static boolean automaticAttempted;
    private static String reconnectGraceAddress;
    private static String reconnectGraceGateValue;
    private static final Runnable CLEAR_RECONNECT_GRACE = new Runnable() {
        @Override public void run() {
            synchronized (HfpSessionLease.class) {
                String expected = reconnectGraceGateValue;
                if (expected == null) return;
                reconnectGraceGateValue = null;
                reconnectGraceAddress = null;
                if (owner != null) return;
                try {
                    String current = Settings.Global.getString(context.getContentResolver(), KEY);
                    if (expected.equals(current) && expected.equals(lastWritten)) {
                        boolean cleared = Settings.Global.putString(context.getContentResolver(), KEY, null);
                        log("reconnect_gate_expired success=" + cleared);
                        if (cleared) lastWritten = null;
                    } else {
                        log("reconnect_gate_expired skipped=gate_changed");
                    }
                } catch (Exception error) {
                    log("reconnect_gate_expired_failed");
                }
            }
        }
    };
    private static final Runnable WATCH = new Runnable() {
        @Override public void run() {
            synchronized (HfpSessionLease.class) {
                if (owner == null) return;
                if (!fresh()) { clear("heartbeat_timeout"); return; }
                if (holding && !writeGate()) { clear("gate_write_failed"); return; }
                MAIN.postDelayed(this, 3000L);
            }
        }
    };

    private HfpSessionLease() { }

    static synchronized int pulse(Context app, IBinder token, String candidate, boolean wired) {
        if (token == null || !token.isBinderAlive() || !wired || candidate == null ||
                !candidate.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") ||
                candidate.equalsIgnoreCase("00:00:00:00:00:00")) return -1;
        if (owner != null && !fresh()) clear("stale_owner");
        if (owner != null && owner != token) return 0;
        if (owner == token && !candidate.equalsIgnoreCase(address)) {
            clear("session_identity_changed");
            return -1;
        }
        if (owner == null) {
            context = app.getApplicationContext();
            final String existingGate;
            try { existingGate = Settings.Global.getString(context.getContentResolver(), KEY); }
            catch (Exception error) { log("session_prearm_failed reason=gate_read"); return -1; }
            String gateState = HsaeBluetooth.classifyExistingHfpGate(existingGate, candidate);
            if ("active_other_phone".equals(gateState)) {
                log("session_prearm_skipped reason=active_gate_other_phone");
                return 0;
            }
            recentReleasedAddress = null;
            recentReleasedAt = 0;
            owner = token;
            address = candidate;
            automaticAttempted = false;
            automaticInFlight = false;
            if (reconnectGraceGateValue != null && reconnectGraceAddress != null &&
                    reconnectGraceAddress.equalsIgnoreCase(candidate)) {
                MAIN.removeCallbacks(CLEAR_RECONNECT_GRACE);
                reconnectGraceAddress = null;
                reconnectGraceGateValue = null;
                log("reconnect_gate_claimed_by_new_session");
            }
            death = () -> release(token, "client_died");
            try { token.linkToDeath(death, 0); }
            catch (Exception error) { clear("client_dead"); return -1; }
            heartbeat = SystemClock.elapsedRealtime();
            holding = true;
            prearmed = true;
            expectedGateBeforeWrite = existingGate;
            verifyGateBeforeWrite = true;
            boolean armed;
            try { armed = writeGate(); }
            finally {
                expectedGateBeforeWrite = null;
                verifyGateBeforeWrite = false;
            }
            if (!armed) { clear("session_prearm_write_failed"); return -1; }
            MAIN.removeCallbacks(WATCH);
            MAIN.postDelayed(WATCH, 3000L);
            log("wired_session_gate_prearmed identity=carplay_candidate_only profiles=not_checked");
            return 1;
        }
        heartbeat = SystemClock.elapsedRealtime();
        return holding ? 2 : 1;
    }

    static synchronized IBinder currentToken() { return fresh() ? owner : null; }
    static synchronized long authorizationEpoch() { return authorizationEpoch; }
    static synchronized boolean claimAutomaticAttempt(IBinder token) {
        if (!fresh() || owner != token || automaticAttempted || (holding && !prearmed)) return false;
        automaticAttempted = true;
        prearmed = false;
        automaticInFlight = true;
        return true;
    }
    static synchronized void finishAutomaticAttempt(IBinder token) {
        if (owner == token) automaticInFlight = false;
    }
    static synchronized boolean isActiveHold(IBinder token, String candidate, long epoch) {
        return authorizationEpoch == epoch && holding && fresh() && owner == token &&
            address != null && address.equalsIgnoreCase(candidate);
    }
    static synchronized String restorableAddress() {
        long age = SystemClock.elapsedRealtime() - recentReleasedAt;
        return recentReleasedAddress != null && age >= 0 && age <= 600000L &&
            currentToken() == null ? recentReleasedAddress : null;
    }
    static synchronized boolean consumeRestorableAddress(String expected) {
        if (expected == null || !expected.equalsIgnoreCase(restorableAddress())) return false;
        recentReleasedAddress = null;
        recentReleasedAt = 0;
        return true;
    }

    static synchronized String beginAutomatic(IBinder expected, String matchedOemAddress, long epoch,
            String expectedExistingGate) {
        if (authorizationEpoch != epoch) return "未保持：核对期间会话状态已变化。";
        if (!fresh() || expected == null || owner != expected)
            return "未保持：没有当前有线 CarPlay 会话心跳或会话已切换，请安装配套 DiPlay 并连接。";
        if (!address.equalsIgnoreCase(matchedOemAddress))
            return "未保持：当前 CarPlay 设备候选与已授权的蓝牙媒体设备不一致。";
        expectedGateBeforeWrite = expectedExistingGate;
        verifyGateBeforeWrite = true;
        try {
            return activate(expected, matchedOemAddress, epoch);
        } finally {
            expectedGateBeforeWrite = null;
            verifyGateBeforeWrite = false;
        }
    }

    private static String activate(IBinder expected, String matchedOemAddress, long epoch) {
        if (authorizationEpoch != epoch) return "未保持：核对期间会话状态已变化。";
        if (!fresh() || expected == null || owner != expected)
            return "未保持：没有当前有线 CarPlay 会话心跳或会话已切换，请安装配套 DiPlay 并连接。";
        if (!address.equalsIgnoreCase(matchedOemAddress))
            return "未保持：当前 CarPlay 设备候选与已授权的蓝牙媒体设备不一致。";
        holding = true;
        writesSinceLog = 0;
        if (!writeGate()) {
            clear("gate_write_failed");
            return "未保持：写入固件门控参数失败。";
        }
        log("automatic_handoff_gate_started call=0");
        return "已按当前有线 CarPlay 会话保持 HFP 门控。";
    }

    static synchronized boolean release(IBinder token, String reason) {
        if (token == null || owner != token) return false;
        boolean wasHolding = holding && fresh();
        clear(reason);
        return wasHolding;
    }

    /** Keep only the same wired-phone's short HFP request gate while DiPlay rebuilds CarPlay. */
    static synchronized int preserveForReconnect(Context app, IBinder token, String candidate,
            long requestedMillis, int callState) {
        if (token == null || !token.isBinderAlive() || candidate == null ||
                !candidate.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return -1;
        if (owner != token || !fresh() || address == null || !address.equalsIgnoreCase(candidate) || !holding) {
            log("reconnect_gate_not_preserved reason=session_not_holding");
            return 0;
        }
        if (callState != 0) {
            log("reconnect_gate_not_preserved reason=call_active");
            return 0;
        }
        if (automaticInFlight) {
            log("reconnect_gate_not_preserved reason=handoff_in_flight");
            return 0;
        }
        final String expectedGate = lastWritten;
        if (expectedGate == null) {
            log("reconnect_gate_not_preserved reason=missing_gate");
            return 0;
        }
        try {
            String current = Settings.Global.getString(app.getContentResolver(), KEY);
            if (!expectedGate.equals(current)) {
                log("reconnect_gate_not_preserved reason=gate_changed");
                return 0;
            }
        } catch (Exception error) {
            log("reconnect_gate_not_preserved reason=gate_read_failed");
            return 0;
        }

        long duration = Math.max(1000L, Math.min(45000L, requestedMillis));
        MAIN.removeCallbacks(CLEAR_RECONNECT_GRACE);
        reconnectGraceAddress = candidate;
        reconnectGraceGateValue = expectedGate;
        MAIN.postDelayed(CLEAR_RECONNECT_GRACE, duration);
        authorizationEpoch++;
        MAIN.removeCallbacks(WATCH);
        expectedGateBeforeWrite = null;
        verifyGateBeforeWrite = false;
        if (death != null) try { token.unlinkToDeath(death, 0); }
        catch (Exception ignored) { }
        owner = null;
        death = null;
        address = null;
        holding = false;
        prearmed = false;
        automaticAttempted = false;
        automaticInFlight = false;
        heartbeat = 0;
        log("reconnect_gate_preserved duration_ms=" + duration + " identity=carplay_candidate_only call=0");
        return 1;
    }

    static synchronized void stopHold() {
        authorizationEpoch++;
        if (holding) {
            rememberForRestore();
            holding = false;
            prearmed = false;
            clearGate();
            log("session_hold_cancelled");
        }
    }

    static synchronized void shutdown() { clear("service_destroyed"); }

    private static boolean fresh() {
        long age = SystemClock.elapsedRealtime() - heartbeat;
        return owner != null && owner.isBinderAlive() && age >= 0 && age < HEARTBEAT_TIMEOUT_MS;
    }

    private static boolean writeGate() {
        String value = address + "|" + SystemClock.elapsedRealtime() + "|" + System.currentTimeMillis();
        try {
            if (verifyGateBeforeWrite) {
                String current = Settings.Global.getString(context.getContentResolver(), KEY);
                if (expectedGateBeforeWrite == null ? current != null : !expectedGateBeforeWrite.equals(current))
                    return false;
                verifyGateBeforeWrite = false;
                expectedGateBeforeWrite = null;
            }
            if (lastWritten != null && !lastWritten.equals(Settings.Global.getString(context.getContentResolver(), KEY)))
                return false;
            if (!Settings.Global.putString(context.getContentResolver(), KEY, value)) return false;
            lastWritten = value;
            if (++writesSinceLog >= 10) {
                writesSinceLog = 0;
                log("session_hold_refreshed heartbeat_age_ms=" + (SystemClock.elapsedRealtime() - heartbeat));
            }
            return true;
        } catch (Exception error) { return false; }
    }

    private static void clearGate() {
        if (context != null && lastWritten != null) try {
            if (lastWritten.equals(Settings.Global.getString(context.getContentResolver(), KEY))) {
                boolean cleared = Settings.Global.putString(context.getContentResolver(), KEY, null);
                log("session_gate_clear success=" + cleared);
            }
        } catch (Exception error) { log("session_gate_clear_failed fallback_expiry_90s"); }
        lastWritten = null;
    }

    private static void clear(String reason) {
        authorizationEpoch++;
        MAIN.removeCallbacks(WATCH);
        MAIN.removeCallbacks(CLEAR_RECONNECT_GRACE);
        reconnectGraceAddress = null;
        reconnectGraceGateValue = null;
        expectedGateBeforeWrite = null;
        verifyGateBeforeWrite = false;
        if (holding) rememberForRestore();
        clearGate();
        if (owner != null && death != null) try { owner.unlinkToDeath(death, 0); }
        catch (Exception ignored) { }
        owner = null;
        death = null;
        address = null;
        holding = false;
        prearmed = false;
        automaticAttempted = false;
        automaticInFlight = false;
        heartbeat = 0;
        log("session_released reason=" + reason);
    }

    private static void rememberForRestore() {
        if (address != null) {
            recentReleasedAddress = address;
            recentReleasedAt = SystemClock.elapsedRealtime();
        }
    }

    private static void log(String event) { Log.i("P350HfpGate", event); }
}
