package com.bradyxu.headunitmanager;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/** Passive listener for supported steering-wheel and control-panel key events. */
public final class NissanHmiAutomationService extends Service {
    private static final String TAG = "HeadunitManagerAutomation";
    private static final String SERVICE_NAME = "com.hsae.auto.IBINDER_HMI";
    private static final String HMI_DESCRIPTOR = "com.hsae.autosdk.hmi.IHmi";
    private static final String CALLBACK_DESCRIPTOR = "com.hsae.autosdk.hmi.IHmiCallback";
    private static final int TX_REGISTER_CALLBACK = 1;
    private static final int TX_UNREGISTER_CALLBACK = 2;
    private static final int CALLBACK_VIRTUAL_KEY = 2;
    private static final int EVENT_BASE = 0x6200;
    private static final int CAPABILITY = 0x62ff;
    private static final long RETRY_MS = 5000L;
    private static final long HANDSHAKE_MS = 2500L;
    private static final int[] SUPPORTED_OPERATIONS = {6, 7, 26, 27, 28, 29, 31, 32};
    private static final String PREF = "automation";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Capture { void onKey(int operation); }

    private static volatile Capture capture;
    private static volatile boolean learningRequested;
    private static volatile boolean ready;
    private static volatile String state = "stopped";
    private volatile boolean running;
    private Thread worker;

    static void sync(Context context) {
        Context app = context.getApplicationContext();
        boolean shouldRun = learningRequested ||
            (Rules.enabled(app) && Rules.hasAnyEnabledTrigger(app, Rules.TRIGGER_NISSAN_HMI));
        Intent intent = new Intent(app, NissanHmiAutomationService.class);
        if (shouldRun) {
            try { app.startService(intent); }
            catch (RuntimeException error) { setState(app, "start_failed"); }
        } else {
            capture = null;
            ready = false;
            state = "stopped";
            app.stopService(intent);
            setState(app, "stopped");
        }
    }

    static void startForRules(Context context) { sync(context); }

    static void beginLearning(Context context) {
        learningRequested = true;
        sync(context);
    }

    static void finishLearning(Context context) {
        capture = null;
        learningRequested = false;
        sync(context);
    }

    static void capture(Capture listener) { capture = listener; }

    static boolean isReady() { return ready; }

    static String state(Context context) {
        String stored = context.getSharedPreferences(PREF, 0).getString("nissanHmiState", state);
        if (ready) return "已连接日产 HMI 按键服务";
        if ("stopped".equals(stored)) return "监听未启动";
        if ("looking_for_service".equals(stored)) return "正在查找日产 HMI 服务";
        if ("registering".equals(stored)) return "正在注册日产 HMI 监听";
        if ("waiting_capability".equals(stored)) return "等待固件能力确认";
        if ("unsupported_firmware".equals(stored)) return "当前固件未提供 HMI 按键扩展";
        if ("service_unavailable".equals(stored)) return "未找到日产 HMI 服务";
        if ("access_denied".equals(stored)) return "访问日产 HMI 服务被拒绝";
        if ("connection_failed".equals(stored)) return "日产 HMI 连接失败，正在重试";
        if ("start_failed".equals(stored)) return "日产 HMI 监听服务启动失败";
        return "正在连接日产 HMI 按键服务";
    }

    static int[] supportedOperations() { return SUPPORTED_OPERATIONS.clone(); }

    static String operationName(int operation) {
        switch (operation) {
            case 6: return "面板音量加";
            case 7: return "面板音量减";
            case 26: return "挂断键";
            case 27: return "接听键";
            case 28: return "方向盘音量加";
            case 29: return "方向盘音量减";
            case 31: return "方向盘上一曲";
            case 32: return "方向盘下一曲";
            default: return "日产 HMI 操作 " + operation;
        }
    }

    static String diagnostics(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREF, 0);
        int operation = prefs.getInt("lastNissanHmiOperation", -1);
        long when = prefs.getLong("lastNissanHmiTime", 0L);
        int count = prefs.getInt("nissanHmiEventCount", 0);
        String last = operation < 0 ? "尚未收到日产 HMI 按键事件" :
            operationName(operation) + "（操作码 " + operation + "）";
        String time = when <= 0L ? "—" :
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(when));
        return "监听状态：" + state(context) + "\n本次安装收到事件：" + count + " 次\n最近事件：" + last +
            "\n事件时间：" + time + "\n\n事件由 AutoCore HmiKeys 固件回调提供；未检测到 0x62ff 能力确认时，不会把普通固件误判为支持。";
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        worker = new Thread(this::listen, "nissan-hmi-automation");
        worker.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(this::listen, "nissan-hmi-automation");
            worker.start();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = false;
        ready = false;
        capture = null;
        state = "stopped";
        setState(this, "stopped");
        Thread current = worker;
        if (current != null) current.interrupt();
        super.onDestroy();
    }

    private void listen() {
        while (running) {
            IBinder service = null;
            IBinder callbackBinder = null;
            IBinder.DeathRecipient death = null;
            boolean registered = false;
            try {
                setState(this, "looking_for_service");
                service = serviceBinder();
                if (!running) break;
                if (service == null || !service.isBinderAlive()) {
                    setState(this, "service_unavailable");
                } else {
                    final IBinder currentService = service;
                    ready = false;
                    callbackBinder = createCallback();
                    setState(this, "registering");
                    death = () -> {
                        ready = false;
                        if (running) setState(NissanHmiAutomationService.this, "connection_failed");
                    };
                    service.linkToDeath(death, 0);
                    transactCallback(service, TX_REGISTER_CALLBACK, callbackBinder);
                    registered = true;
                    setState(this, "waiting_capability");
                    long deadline = SystemClock.elapsedRealtime() + HANDSHAKE_MS;
                    while (running && service.isBinderAlive() && !ready &&
                        SystemClock.elapsedRealtime() < deadline) Thread.sleep(100L);
                    if (running && !ready) setState(this, "unsupported_firmware");
                    while (running && currentService.isBinderAlive()) Thread.sleep(500L);
                    ready = false;
                    if (running) setState(this, "connection_failed");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (SecurityException error) {
                ready = false;
                if (running) {
                    setState(this, "access_denied");
                    Log.w(TAG, "nissan_hmi_access_denied", error);
                }
            } catch (Exception error) {
                ready = false;
                if (running) {
                    setState(this, "connection_failed");
                    Log.w(TAG, "nissan_hmi_connection_failed", error);
                }
            } finally {
                if (registered && service != null && service.isBinderAlive() && callbackBinder != null) {
                    try { transactCallback(service, TX_UNREGISTER_CALLBACK, callbackBinder); }
                    catch (Exception ignored) { }
                }
                if (service != null && death != null) {
                    try { service.unlinkToDeath(death, 0); }
                    catch (Exception ignored) { }
                }
                ready = false;
            }
            if (!running || Thread.currentThread().isInterrupted()) break;
            try { Thread.sleep(RETRY_MS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
    }

    private IBinder createCallback() {
        return new Binder() {
            { attachInterface(null, CALLBACK_DESCRIPTOR); }

            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
                if (code == IBinder.INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                }
                if (code != CALLBACK_VIRTUAL_KEY) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                int key = data.readInt();
                boolean down = data.readInt() != 0;
                if (running && key == CAPABILITY && down) {
                    ready = true;
                    setState(NissanHmiAutomationService.this, "connected");
                } else if (running && ready && !down && isSupportedEvent(key)) {
                    int operation = key - EVENT_BASE;
                    recordEvent(NissanHmiAutomationService.this, operation);
                    Capture listener = capture;
                    if (learningRequested && listener != null) {
                        capture = null;
                        listener.onKey(operation);
                    } else if (!learningRequested) {
                        MAIN.post(() -> {
                            if (ready && running) Rules.dispatch(NissanHmiAutomationService.this,
                                Rules.TRIGGER_NISSAN_HMI, String.valueOf(operation), KeyService.activeService());
                        });
                    }
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
    }

    private static boolean isSupportedEvent(int key) {
        if (key < EVENT_BASE) return false;
        int operation = key - EVENT_BASE;
        for (int candidate : SUPPORTED_OPERATIONS) if (candidate == operation) return true;
        return false;
    }

    private static void recordEvent(Context context, int operation) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREF, 0);
        int count = prefs.getInt("nissanHmiEventCount", 0) + 1;
        long now = System.currentTimeMillis();
        prefs.edit().putInt("nissanHmiEventCount", count)
            .putInt("lastNissanHmiOperation", operation)
            .putLong("lastNissanHmiTime", now).apply();
    }

    private static IBinder serviceBinder() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        Object result = manager.getMethod("getService", String.class)
            .invoke(null, SERVICE_NAME);
        return result instanceof IBinder ? (IBinder) result : null;
    }

    private static void transactCallback(IBinder service, int transaction, IBinder callback)
        throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HMI_DESCRIPTOR);
            data.writeStrongBinder(callback);
            if (!service.transact(transaction, data, reply, 0))
                throw new RemoteException("HMI transaction " + transaction + " unavailable");
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void setState(Context context, String next) {
        state = next;
        context.getSharedPreferences(PREF, 0).edit().putString("nissanHmiState", next).apply();
    }

}
