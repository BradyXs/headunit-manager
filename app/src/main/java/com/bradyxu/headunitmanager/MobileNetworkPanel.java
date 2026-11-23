package com.bradyxu.headunitmanager;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Mobile network status and data-link controls through the device network service. */
final class MobileNetworkPanel extends LinearLayout {
    private static final String SERVICE_ACTION = "com.lanyou.tcu.protocol.remoteservice";
    private static final String SERVICE_PACKAGE = "com.lanyou.tcu.protocol.protocolservice";
    private static final String SERVICE_CLASS = "com.lanyou.tcu.protocol.ProtocolService";
    private static final String DEVICE_DESCRIPTOR = "com.lanyou.tcu.protocol.protocolservice.IDeviceManager";
    private static final String CALLBACK_DESCRIPTOR = "com.lanyou.tcu.protocol.protocolservice.I4GmoduleStatusListener";

    // Device network-service transaction codes used by this integration.
    private static final int TX_REGISTER_4G_LISTENER = 4;
    private static final int TX_REMOVE_CLIENT_LISTENERS = 5;
    private static final int TX_TCU_READY = 7;
    private static final int TX_TCU_REGISTERED = 8;
    private static final int TX_REQUEST_NETWORK_STATUS = 13;
    private static final int TX_GET_SIGNAL_LEVEL = 17;
    private static final int TX_GET_NETWORK_MODE = 18;
    private static final int TX_ENABLE_MOBILE_DATA_LINK = 19;
    private static final int TX_DISABLE_MOBILE_DATA_LINK = 20;

    private static final int CALLBACK_SIGNAL_LEVEL = 2;
    private static final int CALLBACK_NETWORK_MODE = 3;
    private static final int CALLBACK_DATA_LINK_STATE = 5;
    private static final int CALLBACK_NETWORK_NAME = 6;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView status;
    private final TextView serviceStatus;
    private final Ui.FlatSwitch mobileDataSwitch;
    private final LinearLayout mobileDataRow;
    private final ServiceConnection serviceConnection;
    private final IBinder tcuCallback;

    private volatile IBinder serviceBinder;
    private volatile boolean listenerRegistered;
    private volatile boolean active;
    private boolean serviceBound;
    private boolean serviceBinding;
    private boolean tcuReady;
    private boolean tcuRegistered;
    private boolean mobileDataKnown;
    private boolean mobileDataConnected;
    private boolean mobileDataBusy;
    private boolean pendingCommand;
    private boolean commandSubmitted;
    private boolean expectedDataLink;
    private int signalLevel = -1;
    private int networkMode = -1;
    private volatile int requestSequence;
    private int statusRetryCount;
    private long commandSentAt;
    private String operatorName;
    private Dialog progress;

    private final Runnable retryStatus = new Runnable() {
        @Override public void run() {
            if (!pendingCommand || !commandSubmitted || !active) return;
            if (statusRetryCount >= 6) {
                finishUnconfirmedCommand();
                return;
            }
            statusRetryCount++;
            requestDataLinkStatus(requestSequence);
            main.postDelayed(this, 1200L);
        }
    };

    MobileNetworkPanel(Activity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);

        tcuCallback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
                if (code == IBinder.INTERFACE_TRANSACTION) {
                    reply.writeString(CALLBACK_DESCRIPTOR);
                    return true;
                }
                if (code < 1 || code > CALLBACK_NETWORK_NAME) {
                    return super.onTransact(code, data, reply, flags);
                }
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                switch (code) {
                    case 1:
                        data.readInt();
                        data.readLong();
                        data.readInt();
                        data.readFloat();
                        data.readDouble();
                        data.readString();
                        break;
                    case CALLBACK_SIGNAL_LEVEL:
                        receiveSignalLevel(data.readInt());
                        break;
                    case CALLBACK_NETWORK_MODE:
                        receiveNetworkMode(data.readInt());
                        break;
                    case 4:
                        data.readInt();
                        break;
                    case CALLBACK_DATA_LINK_STATE:
                        receiveDataLinkState(data.readInt() != 0, data.readInt() != 0);
                        break;
                    case CALLBACK_NETWORK_NAME:
                        String name = data.readString();
                        data.readString(); // The second value is an equipment identifier; discard it.
                        receiveOperatorName(name);
                        break;
                    default:
                        break;
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
        };

        serviceConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                serviceBinding = false;
                serviceBound = true;
                serviceBinder = binder;
                inspectAndQueryTcu(binder, requestSequence);
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                serviceBinding = false;
                serviceBound = false;
                serviceBinder = null;
                listenerRegistered = false;
                tcuReady = false;
                tcuRegistered = false;
                clearNetworkState();
                if (active) {
                    serviceStatus.setText("日产 TCU 网络服务已断开");
                    render();
                }
            }
        };

        LinearLayout card = Ui.card(activity);
        card.addView(Ui.sectionHeader(activity, "TCU 移动网络", R.drawable.ic_hm_network));
        TextView hint = Ui.text(activity,
            "信号、网络制式和数据链路均通过日产 HSAE TCU 服务读取；数据链路开关使用 TCU 专用命令。", 13);
        hint.setTextColor(Ui.MUTED);
        card.addView(hint);

        serviceStatus = Ui.text(activity, "正在连接日产 TCU 网络服务…", 13);
        serviceStatus.setTextColor(Ui.ACCENT);
        card.addView(serviceStatus);

        status = Ui.text(activity, "正在读取车机移动网络状态…", 14);
        status.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12),
            Ui.dp(activity, 14), Ui.dp(activity, 12));
        status.setBackground(Ui.shape(activity, Ui.BUTTON, 12));
        card.addView(status, Ui.rowSpacing(activity));

        mobileDataSwitch = new Ui.FlatSwitch(activity);
        mobileDataRow = Ui.switchRow(activity, "车机移动数据", "控制日产 TCU 数据链路",
            mobileDataSwitch, v -> toggleMobileData());
        card.addView(mobileDataRow, Ui.rowSpacing(activity));

        LinearLayout refreshActions = Ui.row(activity);
        refreshActions.addView(Ui.button(activity, "刷新车机网络状态", v -> refresh()));
        card.addView(Ui.actionGrid(activity, refreshActions), Ui.rowSpacing(activity));
        addView(card, Ui.rowSpacing(activity));
        render();
    }

    void refresh() {
        if (mobileDataBusy) {
            Ui.toast(activity, "移动数据操作仍在等待车机确认");
            return;
        }
        active = true;
        final int request = ++requestSequence;
        signalLevel = -1;
        networkMode = -1;
        operatorName = null;
        mobileDataKnown = false;
        tcuReady = false;
        tcuRegistered = false;
        serviceStatus.setText("正在连接日产 TCU 网络服务…");
        status.setText("正在读取车机移动网络状态…");
        render();

        IBinder binder = serviceBinder;
        if (binder != null) {
            inspectAndQueryTcu(binder, request);
            return;
        }
        if (serviceBound || serviceBinding) return;

        Intent intent = new Intent(SERVICE_ACTION);
        intent.setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
        try {
            serviceBinding = true;
            boolean bound = activity.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            serviceBound = bound;
            if (!bound) {
                serviceBinding = false;
                serviceStatus.setText("固件未提供日产 TCU 网络服务");
                status.setText("无法读取车机信号和数据链路状态。");
                render();
            }
        } catch (Exception error) {
            serviceBinding = false;
            serviceBound = false;
            serviceStatus.setText("无法连接日产 TCU 网络服务");
            status.setText("请确认固件已安装 ProtocolService。");
            render();
        }
    }

    void pause() {
        active = false;
        requestSequence++;
        main.removeCallbacks(retryStatus);
        pendingCommand = false;
        mobileDataBusy = false;
        dismissProgress();

        IBinder binder = serviceBinder;
        if (binder != null && listenerRegistered) {
            try { callDeviceManager(binder, TX_REMOVE_CLIENT_LISTENERS, Process.myPid()); }
            catch (Exception ignored) { }
        }
        listenerRegistered = false;
        serviceBinder = null;
        if (serviceBound || serviceBinding) {
            try { activity.unbindService(serviceConnection); }
            catch (Exception ignored) { }
        }
        serviceBound = false;
        serviceBinding = false;
    }

    private void inspectAndQueryTcu(IBinder binder, int request) {
        new Thread(() -> {
            if (!active || request != requestSequence) return;
            boolean ready = false;
            boolean registered = false;
            String failure = null;
            try {
                ready = deviceManagerInt(binder, TX_TCU_READY) != 0;
                registered = deviceManagerInt(binder, TX_TCU_REGISTERED) != 0;
                if (ready && registered) {
                    if (!active || request != requestSequence) return;
                    if (!listenerRegistered) {
                        register4gListener(binder);
                        listenerRegistered = true;
                    }
                    if (!active || request != requestSequence) {
                        callDeviceManager(binder, TX_REMOVE_CLIENT_LISTENERS, Process.myPid());
                        listenerRegistered = false;
                        return;
                    }
                    // Each getter returns a cached value and separately requests an asynchronous update.
                    deviceManagerVoid(binder, TX_REQUEST_NETWORK_STATUS);
                    deviceManagerInt(binder, TX_GET_SIGNAL_LEVEL);
                    deviceManagerInt(binder, TX_GET_NETWORK_MODE);
                }
            } catch (Exception error) {
                failure = error.getClass().getSimpleName();
            }
            final boolean isReady = ready;
            final boolean isRegistered = registered;
            final String error = failure;
            activity.runOnUiThread(() -> {
                if (!active || request != requestSequence || binder != serviceBinder) return;
                tcuReady = isReady;
                tcuRegistered = isRegistered;
                if (error != null) serviceStatus.setText("读取日产 TCU 状态失败，请稍后刷新");
                else if (!isReady) serviceStatus.setText("日产 TCU 尚未就绪");
                else if (!isRegistered) serviceStatus.setText("日产 TCU 尚未完成设备注册");
                else serviceStatus.setText("已连接日产 TCU 网络服务");
                render();
            });
        }, "nissan-tcu-mobile-status").start();
    }

    private void register4gListener(IBinder binder) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DEVICE_DESCRIPTOR);
            data.writeInt(Process.myPid());
            data.writeStrongBinder(tcuCallback);
            if (!binder.transact(TX_REGISTER_4G_LISTENER, data, reply, 0)) {
                throw new RemoteException("4G listener transaction unavailable");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int deviceManagerInt(IBinder binder, int transaction) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DEVICE_DESCRIPTOR);
            if (!binder.transact(transaction, data, reply, 0)) {
                throw new RemoteException("TCU status transaction unavailable");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void deviceManagerVoid(IBinder binder, int transaction, int... args) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DEVICE_DESCRIPTOR);
            for (int arg : args) data.writeInt(arg);
            if (!binder.transact(transaction, data, reply, 0)) {
                throw new RemoteException("TCU command transaction unavailable");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callDeviceManager(IBinder binder, int transaction, int... args) throws Exception {
        deviceManagerVoid(binder, transaction, args);
    }

    private void receiveSignalLevel(int level) {
        main.post(() -> {
            if (!active) return;
            signalLevel = level >= 0 && level <= 5 ? level : -1;
            render();
        });
    }

    private void receiveNetworkMode(int mode) {
        main.post(() -> {
            if (!active) return;
            networkMode = mode >= 2 && mode <= 5 ? mode : -1;
            render();
        });
    }

    private void receiveOperatorName(String name) {
        main.post(() -> {
            if (!active) return;
            operatorName = name == null || name.trim().isEmpty() ? null : name.trim();
            render();
        });
    }

    private void receiveDataLinkState(boolean connected, boolean usbConnected) {
        main.post(() -> {
            if (!active) return;
            mobileDataKnown = true;
            mobileDataConnected = connected;
            if (pendingCommand && commandSubmitted && connected == expectedDataLink &&
                SystemClock.uptimeMillis() >= commandSentAt) {
                completeCommand();
            } else {
                render();
            }
        });
    }

    private void clearNetworkState() {
        signalLevel = -1;
        networkMode = -1;
        operatorName = null;
        mobileDataKnown = false;
        mobileDataConnected = false;
    }

    private void render() {
        String signal = signalLevel < 0 ? "读取中" : signalLevel == 0 ? "无信号（0/5 格）" : signalLevel + "/5 格";
        String mode = networkModeName(networkMode);
        String operator = operatorName == null ? "未返回" : operatorName;
        String link = mobileDataKnown ? mobileDataConnected ? "已接通" : "未接通" : "读取中";
        StringBuilder value = new StringBuilder("车机信号：").append(signal)
            .append("\n网络制式：").append(mode)
            .append("\n运营商：").append(operator)
            .append("\n移动数据链路：").append(link);
        if (!tcuReady) value.append("\n需要日产 TCU 服务就绪");
        else if (!tcuRegistered) value.append("\n需要日产 TCU 完成设备注册");
        status.setText(value.toString());
        status.setTextColor(tcuReady && tcuRegistered ? Ui.TEXT : Ui.AMBER);
        if (mobileDataKnown) mobileDataSwitch.setChecked(mobileDataConnected);
        boolean canToggle = active && serviceBinder != null && tcuReady && tcuRegistered &&
            mobileDataKnown && !mobileDataBusy;
        mobileDataSwitch.setEnabled(canToggle);
        mobileDataRow.setEnabled(canToggle);
    }

    private static String networkModeName(int mode) {
        switch (mode) {
            case 2: return "2G";
            case 3: return "3G";
            case 4: return "4G / LTE";
            case 5: return "其他网络";
            default: return "读取中";
        }
    }

    private void toggleMobileData() {
        if (mobileDataBusy) return;
        if (!tcuReady || !tcuRegistered || serviceBinder == null) {
            showResult("日产 TCU 尚未就绪", "车机网络服务尚未就绪或未完成注册，暂时不能切换数据链路。");
            return;
        }
        if (!mobileDataKnown) {
            showResult("移动数据状态未知", "请先刷新车机网络状态，等 TCU 返回数据链路状态后再操作。");
            return;
        }

        expectedDataLink = !mobileDataConnected;
        mobileDataBusy = true;
        pendingCommand = true;
        commandSubmitted = false;
        statusRetryCount = 0;
        final int request = requestSequence;
        final IBinder binder = serviceBinder;
        render();
        progress = Ui.progress(activity, expectedDataLink ? "正在连接车机移动数据…" : "正在断开车机移动数据…");
        new Thread(() -> {
            String failure = null;
            try {
                deviceManagerVoid(binder, expectedDataLink ? TX_ENABLE_MOBILE_DATA_LINK : TX_DISABLE_MOBILE_DATA_LINK);
            } catch (Exception error) {
                failure = error.getClass().getSimpleName();
            }
            final String error = failure;
            activity.runOnUiThread(() -> {
                if (!active || request != requestSequence) return;
                if (error != null) {
                    pendingCommand = false;
                    mobileDataBusy = false;
                    dismissProgress();
                    render();
                    showResult("移动数据操作失败", "日产 TCU 没有接受数据链路请求。请确认车机服务状态后重试。");
                    return;
                }
                commandSubmitted = true;
                commandSentAt = SystemClock.uptimeMillis();
                requestDataLinkStatus(request);
                main.removeCallbacks(retryStatus);
                statusRetryCount = 1;
                main.postDelayed(retryStatus, 1200L);
            });
        }, "nissan-tcu-mobile-toggle").start();
    }

    private void requestDataLinkStatus(int request) {
        final IBinder binder = serviceBinder;
        if (binder == null) return;
        new Thread(() -> {
            try { deviceManagerVoid(binder, TX_REQUEST_NETWORK_STATUS); }
            catch (Exception ignored) { }
        }, "nissan-tcu-mobile-refresh").start();
    }

    private void completeCommand() {
        pendingCommand = false;
        mobileDataBusy = false;
        main.removeCallbacks(retryStatus);
        dismissProgress();
        render();
        Ui.toast(activity, expectedDataLink ? "车机移动数据已接通" : "车机移动数据已断开");
    }

    private void finishUnconfirmedCommand() {
        pendingCommand = false;
        mobileDataBusy = false;
        dismissProgress();
        render();
        showResult("尚未收到切换确认", "日产 TCU 已收到请求，但没有返回预期的数据链路状态。请稍后刷新状态核实。 ");
    }

    private void showResult(String title, String message) {
        Ui.dialog(activity).setTitle(title).setMessage(message)
            .setPositiveButton("确定", null).show();
    }

    private void dismissProgress() {
        if (progress != null) {
            try { progress.dismiss(); }
            catch (Exception ignored) { }
            progress = null;
        }
    }
}
