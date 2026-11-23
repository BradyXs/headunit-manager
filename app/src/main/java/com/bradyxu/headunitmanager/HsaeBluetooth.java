package com.bradyxu.headunitmanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bluetooth power, status, and profile operations through the device integration service. */
final class HsaeBluetooth {
    interface Result { void done(String message); }
    interface StatusResult { void done(Integer status, String error); }
    private interface BinderTask<T> { T run(IBinder binder) throws Exception; }
    private interface Completion<T> { void done(T value, Exception error); }

    private static final String PKG = "com.anwsdk.service";
    private static final String CLS = "com.anwsdk.service.AnwSdkService";
    private static final String ACTION = "com.anwsdk.service.AnwPhoneLink";
    private static final String DESCRIPTOR = "com.anwsdk.service.IAnwPhoneLink";
    private static final int TX_POWER_ON = 1;
    private static final int TX_POWER_OFF = 2;
    private static final int TX_POWER_STATUS = 3;
    private static final int TX_CONNECT_STATUS = 0x17;
    private static final int TX_CONNECTED_DEVICE_INFO = 0x18;
    private static final int TX_CONNECT_MOBILE = 0x1b;
    private static final int TX_RELEASE_HFP = 0x100;
    private static final int TX_A2DP_CONNECT = 0x31;
    private static final int TX_CALL_STATUS = 0x2a;
    private static final int TX_AUDIO_PLAY_STATUS = 0x36;
    private static final int TX_A2DP_PLAYING = 0x3d;
    private static final int SUCCESS = 1;
    private static final int REQUEST_TIMEOUT_MS = 12000;
    private static final String HFP_GATE_KEY = "p350_hfp_gate_v1";
    private static final long HFP_GATE_WINDOW_MS = 90_000L;

    private HsaeBluetooth() {}

    static boolean available(Context context) {
        try {
            context.getPackageManager().getServiceInfo(new ComponentName(PKG, CLS), 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Reads the actual Bluetooth power state from Nissan's SDK (transaction 3). */
    static void readStatus(Context context, StatusResult callback) {
        request(context, binder -> callInt(binder, TX_POWER_STATUS, null), (status, error) ->
            callback.done(status, error == null ? null : describe(error)));
    }

    /** Requests a power change, then polls Nissan's SDK until it reports the requested state. */
    static void power(Context context, boolean enable, Result callback) {
        request(context, binder -> {
            int transaction = enable ? TX_POWER_ON : TX_POWER_OFF;
            int result = callInt(binder, transaction, null);
            if (result != SUCCESS) {
                return "日产蓝牙服务没有接受" + (enable ? "开启" : "关闭") + "请求，请稍后刷新状态或重试。";
            }

            int expected = enable ? 1 : 0;
            long deadline = System.currentTimeMillis() + 5000L;
            int status = Integer.MIN_VALUE;
            do {
                status = callInt(binder, TX_POWER_STATUS, null);
                if (status == expected) {
                    return "日产蓝牙已" + (enable ? "开启" : "关闭") + "。SDK 状态已同步。";
                }
                if (System.currentTimeMillis() >= deadline) break;
                try { Thread.sleep(400L); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RemoteException("等待蓝牙状态更新时被中断");
                }
            } while (true);

            String actual = status == 1 ? "开启" : status == 0 ? "关闭" : "未知";
            return "日产蓝牙服务已接受" + (enable ? "开启" : "关闭") + "请求；当前 SDK 状态仍为" + actual + "。";
        }, (message, error) -> callback.done(error == null ? message : "日产蓝牙操作失败：" + describe(error)));
    }

    static void toggle(Context context, Result callback) {
        readStatus(context, (status, error) -> {
            if (status == null || (status != 0 && status != 1)) {
                callback.done("无法读取日产蓝牙当前状态，请先刷新状态后重试。");
                return;
            }
            power(context, status == 0, callback);
        });
    }

    /** Raw OEM state values; no device names, addresses or phone numbers leave this probe. */
    static void readAudioProbe(Context context, Result callback) {
        request(context, binder -> audioProbe(binder), (value, error) ->
            callback.done(error == null ? value : "日产音频状态读取失败：" + describe(error)));
    }

    /** Restore the session phone through the OEM reconnect, after rechecking state. */
    static void restoreHfpOnce(Context context, Result callback) {
        final String expected = HfpSessionLease.restorableAddress();
        if (expected == null) {
            callback.done("未发起：需要刚结束的有线 CarPlay 会话，且当前会话已经退出。记录最多保留 10 分钟。");
            return;
        }
        request(context, binder -> {
            if (!expected.equalsIgnoreCase(HfpSessionLease.restorableAddress()))
                return "未发起：CarPlay 会话已变化。";
            String gate = Settings.Global.getString(context.getContentResolver(), HFP_GATE_KEY);
            if (gate != null && !gate.isEmpty()) return "未发起：HFP 拦截参数仍存在。";
            int hfp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(0); p.writeInt(0); });
            int a2dp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(2); p.writeInt(0); });
            int call = callInt(binder, TX_CALL_STATUS, null);
            String connected = a2dp == 1 ? connectedDeviceAddress(binder, 2) : null;
            if (hfp != 0 || a2dp != 1 || call != 0 || connected == null ||
                !expected.equalsIgnoreCase(connected)) {
                Log.i("P350HfpRestore", "skipped state hfp=" + hfp + " a2dp=" + a2dp + " call=" + call +
                    " sameDevice=" + expected.equalsIgnoreCase(connected));
                return "未发起：需要 HFP=0、同一手机 A2DP=1、原车电话空闲；当前 HFP=" + hfp +
                    " A2DP=" + a2dp + " call=" + call + " 同设备=" + expected.equalsIgnoreCase(connected);
            }
            int result;
            synchronized (HfpSessionLease.class) {
                if (!HfpSessionLease.consumeRestorableAddress(expected))
                    return "未发起：会话变化或本次恢复机会已经使用。";
                Log.i("P350HfpRestore", "request_started hfp=0 a2dp=1 call=0 sameDevice=true");
                result = callInt(binder, TX_CONNECT_MOBILE, p -> p.writeString(expected));
            }
            Log.i("P350HfpRestore", "oem_connect_accepted=" + result);
            if (result != 1) return "原车未接受回连请求，返回=" + result + "；没有进行重复尝试。";
            long deadline = SystemClock.elapsedRealtime() + 8000L;
            do {
                try { Thread.sleep(500L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return "等待回连状态时中断；原车请求已发出，请手动刷新状态。";
                }
                hfp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(0); p.writeInt(0); });
                a2dp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(2); p.writeInt(0); });
                call = callInt(binder, TX_CALL_STATUS, null);
                if (hfp == 1 || a2dp != 1 || call != 0) break;
            } while (SystemClock.elapsedRealtime() < deadline);
            Log.i("P350HfpRestore", "result hfp=" + hfp + " a2dp=" + a2dp + " call=" + call);
            return "原车回连请求结果：HFP=" + hfp + " A2DP=" + a2dp + " call=" + call +
                (hfp == 1 && a2dp == 1 ? "；HFP 与 A2DP 已连接。" :
                "；请求已发出但未确认两者同时连接。 ");
        }, (value, error) -> callback.done(error == null ? value : "单次回连请求失败：" + describe(error)));
    }

    /** Release only the session phone's HFP profile; keep media routed under the device gate. */
    static void automaticHfpHandoff(Context context, IBinder session, String candidate, Result callback) {
        final long epoch = HfpSessionLease.authorizationEpoch();
        request(context, binder -> {
            if (HfpSessionLease.currentToken() != session ||
                epoch != HfpSessionLease.authorizationEpoch()) return "skipped_session_changed";
            int call = callInt(binder, TX_CALL_STATUS, null);
            int hfp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(0); p.writeInt(0); });
            int a2dp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(2); p.writeInt(0); });
            if (call != 0 || (hfp != 0 && hfp != 1) || (a2dp != 0 && a2dp != 1))
                return "skipped_state call=" + call + " hfp=" + hfp + " a2dp=" + a2dp;
            String media = a2dp == 1 ? connectedDeviceAddress(binder, 2) : null;
            String phone = hfp == 1 ? connectedDeviceAddress(binder, 0) : null;
            if ((a2dp == 1 && media == null) || (hfp == 1 && phone == null))
                return "skipped_profile_identity_unavailable";
            if ((media != null && !candidate.equalsIgnoreCase(media)) ||
                (phone != null && !candidate.equalsIgnoreCase(phone)))
                return "skipped_identity_mismatch";
            Log.i("P350HfpGate", hfp == 0 && a2dp == 0
                ? "automatic_gate_identity=carplay_session_candidate_only"
                : "automatic_gate_identity=matched_connected_profile");
            String existingGate = Settings.Global.getString(context.getContentResolver(), HFP_GATE_KEY);
            String gateState = classifyExistingHfpGate(existingGate, candidate);
            Log.i("P350HfpGate", "automatic_existing_gate=" + gateState);
            if ("active_other_phone".equals(gateState)) return "skipped_active_hfp_gate_other_phone";
            String started = HfpSessionLease.beginAutomatic(session, candidate, epoch, existingGate);
            if (!HfpSessionLease.isActiveHold(session, candidate, epoch)) return "gate_failed " + started;
            if (hfp == 0 && a2dp == 0) return "holding_hfp_gate_waiting_for_bluetooth_profiles";
            if (hfp == 0) return "holding_existing_hfp_zero";
            boolean completed = false;
            boolean releaseRequested = false;
            boolean identityConflict = false;
            try {
                int accepted;
                synchronized (HfpSessionLease.class) {
                    if (!HfpSessionLease.isActiveHold(session, candidate, epoch)) return "skipped_session_changed";
                    call = callInt(binder, TX_CALL_STATUS, null);
                    hfp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(0); p.writeInt(0); });
                    a2dp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(2); p.writeInt(0); });
                    if (call != 0 || hfp != 1 || (a2dp != 0 && a2dp != 1))
                        return "skipped_pre_release_state call=" + call + " hfp=" + hfp + " a2dp=" + a2dp;
                    String currentPhone = connectedDeviceAddress(binder, 0);
                    String currentMedia = a2dp == 1 ? connectedDeviceAddress(binder, 2) : null;
                    if (!candidate.equalsIgnoreCase(currentPhone) ||
                        (a2dp == 1 && (currentMedia == null || !candidate.equalsIgnoreCase(currentMedia)))) {
                        identityConflict = true;
                        return "skipped_identity_changed_before_release";
                    }
                    accepted = callInt(binder, TX_RELEASE_HFP, p -> p.writeString(candidate));
                    releaseRequested = accepted == 1;
                }
                Log.i("P350HfpGate", "automatic_target_hfp_release_accepted=" + accepted);
                if (!releaseRequested) return "target_hfp_release_rejected=" + accepted;
                long deadline = SystemClock.elapsedRealtime() + 5000L;
                do {
                    Thread.sleep(400L);
                    if (!HfpSessionLease.isActiveHold(session, candidate, epoch)) return "session_ended_during_disconnect";
                    hfp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(0); p.writeInt(0); });
                    if (hfp == 0) break;
                } while (SystemClock.elapsedRealtime() < deadline);
                if (hfp != 0) return "target_hfp_release_not_confirmed hfp=" + hfp;
                if (!HfpSessionLease.isActiveHold(session, candidate, epoch)) return "session_ended_before_reconnect";
                // RFCOMM close is asynchronous; let its HFP callback settle before checking media.
                Thread.sleep(1200L);
                if (!HfpSessionLease.isActiveHold(session, candidate, epoch)) return "session_ended_during_settle";
                hfp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(0); p.writeInt(0); });
                a2dp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(2); p.writeInt(0); });
                if (hfp != 0) return "hfp_reconnected_during_settle";
                if (a2dp == 1) {
                    String currentMedia = connectedDeviceAddress(binder, 2);
                    if (!candidate.equalsIgnoreCase(currentMedia)) {
                        identityConflict = true;
                        return "media_device_changed_after_disconnect";
                    }
                    completed = true;
                    return "handoff_hfp_zero_a2dp_stayed_connected";
                }
                accepted = callInt(binder, TX_A2DP_CONNECT, p -> p.writeString(candidate));
                Log.i("P350HfpGate", "automatic_a2dp_connect_accepted=" + accepted);
                if (accepted != 1) return "oem_a2dp_connect_rejected=" + accepted;
                deadline = SystemClock.elapsedRealtime() + 8000L;
                do {
                    Thread.sleep(400L);
                    if (!HfpSessionLease.isActiveHold(session, candidate, epoch)) return "session_ended_during_reconnect";
                    hfp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(0); p.writeInt(0); });
                    a2dp = callInt(binder, TX_CONNECT_STATUS, p -> { p.writeInt(2); p.writeInt(0); });
                    if (hfp == 0 && a2dp == 1) {
                        String currentMedia = connectedDeviceAddress(binder, 2);
                        if (!candidate.equalsIgnoreCase(currentMedia)) {
                            identityConflict = true;
                            return "media_device_changed_during_reconnect";
                        }
                        completed = true;
                        return "handoff_hfp_zero_a2dp_one";
                    }
                } while (SystemClock.elapsedRealtime() < deadline);
                return "handoff_unconfirmed hfp=" + hfp + " a2dp=" + a2dp;
            } finally {
                if (!completed) {
                    if (HfpSessionLease.isActiveHold(session, candidate, epoch)) HfpSessionLease.stopHold();
                    IBinder current = HfpSessionLease.currentToken();
                    if (releaseRequested && !identityConflict && (current == null || current == session)) {
                        try {
                            if (callInt(binder, TX_CALL_STATUS, null) == 0) {
                                int restore = callInt(binder, TX_CONNECT_MOBILE, p -> p.writeString(candidate));
                                Log.i("P350HfpGate", "automatic_handoff_fallback_connect_accepted=" + restore);
                            }
                        } catch (Exception error) {
                            Log.w("P350HfpGate", "automatic_handoff_fallback_failed", error);
                        }
                    }
                }
            }
        }, (value, error) -> callback.done(error == null ? value : "handoff_error " + describe(error)), 25000);
    }

    private static String connectedDeviceAddress(IBinder binder, int profile) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(profile);
            data.writeInt(1);
            data.writeInt(1);
            data.writeInt(0);
            if (!binder.transact(TX_CONNECTED_DEVICE_INFO, data, reply, 0)) {
                throw new RemoteException("原车服务不支持 HFP 设备查询");
            }
            reply.readException();
            int result = reply.readInt();
            String[] address = new String[1];
            String[] name = new String[1];
            reply.readStringArray(address);
            reply.readStringArray(name);
            return result == 1 ? address[0] : null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * The gate is a persisted 90-second new-HFP-request filter. A non-empty but expired value
     * must not block an otherwise verified same-phone handoff forever. Never replace another
     * phone's still-active authorization.
     */
    static String classifyExistingHfpGate(String value, String candidate) {
        if (value == null || value.isEmpty()) return "none";
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3 || !parts[0].matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))
            return "invalid_replaceable";
        try {
            long uptimeAge = SystemClock.elapsedRealtime() - Long.parseLong(parts[1]);
            long wallAge = System.currentTimeMillis() - Long.parseLong(parts[2]);
            boolean active = uptimeAge >= 0 && uptimeAge <= HFP_GATE_WINDOW_MS &&
                wallAge >= 0 && wallAge <= HFP_GATE_WINDOW_MS;
            if (!active) return "expired_replaceable";
            return parts[0].equalsIgnoreCase(candidate) ? "active_same_phone" : "active_other_phone";
        } catch (NumberFormatException invalidTimestamp) {
            return "invalid_replaceable";
        }
    }

    static String installedAnwApk(Context context) {
        try {
            String path = context.getPackageManager().getApplicationInfo(PKG, 0).sourceDir;
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(new File(path))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) sha.update(buffer, 0, count);
            }
            String hash = hex(sha.digest());
            String kind = hash.equals("5d32cc65e4495efc331209f52f95021ebb0e4583f46301584aee007ed8a04013")
                ? "电话诊断版" : hash.equals("7de9db2389a44c6f5e11c8fc04cc74d73c688d81c48aeb32ec302ec91ede27fa")
                ? "原厂版" : "其他版本";
            return kind + " SHA-256=" + hash;
        } catch (Exception error) {
            return "无法读取：" + describe(error);
        }
    }

    private static String audioProbe(IBinder binder) throws Exception {
        int hfp = callInt(binder, TX_CONNECT_STATUS, parcel -> { parcel.writeInt(0); parcel.writeInt(0); });
        int a2dp = callInt(binder, TX_CONNECT_STATUS, parcel -> { parcel.writeInt(2); parcel.writeInt(0); });
        int avrcp = callInt(binder, TX_CONNECT_STATUS, parcel -> { parcel.writeInt(3); parcel.writeInt(0); });
        int call = callInt(binder, TX_CALL_STATUS, null);
        int play = callInt(binder, TX_AUDIO_PLAY_STATUS, null);
        int playing = callInt(binder, TX_A2DP_PLAYING, null);
        String report = "HFP=" + hfp + " A2DP=" + a2dp + " AVRCP=" + avrcp +
            " call=" + call + " play=" + play + " a2dpPlaying=" + playing;
        Log.i("P350AudioProbe", report);
        return report;
    }

    private static String hex(byte[] value) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            output[i * 2] = digits[(value[i] >>> 4) & 15];
            output[i * 2 + 1] = digits[value[i] & 15];
        }
        return new String(output);
    }

    private static <T> void request(Context context, BinderTask<T> task, Completion<T> completion) {
        request(context, task, completion, REQUEST_TIMEOUT_MS);
    }

    private static <T> void request(Context context, BinderTask<T> task, Completion<T> completion, int timeoutMs) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicBoolean bound = new AtomicBoolean(false);
        final ServiceConnection[] connectionRef = new ServiceConnection[1];
        final Runnable[] timeoutRef = new Runnable[1];

        Completion<T> deliver = (value, error) -> main.post(() -> {
            if (!finished.compareAndSet(false, true)) return;
            if (timeoutRef[0] != null) main.removeCallbacks(timeoutRef[0]);
            if (bound.get()) {
                try { app.unbindService(connectionRef[0]); } catch (Exception ignored) { }
            }
            completion.done(value, error);
        });

        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (finished.get()) return;
                new Thread(() -> {
                    try {
                        verifyBinder(binder);
                        deliver.done(task.run(binder), null);
                    } catch (Exception error) {
                        deliver.done(null, error);
                    }
                }, "nissan-bt-sdk").start();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                deliver.done(null, new RemoteException("日产蓝牙服务已断开"));
            }
        };
        connectionRef[0] = connection;

        Intent intent = new Intent(ACTION).setClassName(PKG, CLS);
        boolean didBind;
        try {
            didBind = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception error) {
            deliver.done(null, error);
            return;
        }
        if (!didBind) {
            deliver.done(null, new RemoteException("无法连接日产蓝牙服务 AnwSdkService"));
            return;
        }
        bound.set(true);
        timeoutRef[0] = () -> deliver.done(null,
            new RemoteException("日产蓝牙服务响应超时（" + timeoutMs + " ms）"));
        main.postDelayed(timeoutRef[0], timeoutMs);
    }

    private static void verifyBinder(IBinder binder) throws Exception {
        if (binder == null || !binder.isBinderAlive()) throw new RemoteException("日产服务未返回有效 Binder");
        String descriptor = binder.getInterfaceDescriptor();
        if (!DESCRIPTOR.equals(descriptor)) {
            throw new RemoteException("日产服务接口不匹配：" + (descriptor == null ? "未知" : descriptor));
        }
    }

    private static int callInt(IBinder binder, int transaction, ParcelWriter writer) throws Exception {
        if (!binder.isBinderAlive()) throw new RemoteException("日产蓝牙 Binder 已断开");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (writer != null) writer.write(data);
            if (!binder.transact(transaction, data, reply, 0)) {
                throw new RemoteException("日产蓝牙 SDK 不支持交易号 " + transaction);
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private interface ParcelWriter { void write(Parcel parcel); }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
