package com.bradyxu.headunitmanager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Holds the OEM wake recorder only while a firmware bridge microphone lease is live. */
final class VoiceWakeGate {
    private static final String TAG = "P350FirmwareBridge";
    private static final String DESCRIPTOR = "com.hsae.autosdk.voice.IVoiceManager";
    private static final ComponentName COMPONENT = new ComponentName(
        "com.hsae.voice.adapter", "com.hsae.voice.adapter.service.VoiceManage");
    private final Context context;
    private ServiceConnection connection;
    private IBinder service;
    private boolean restoreNeeded;

    VoiceWakeGate(Context context) { this.context = context; }

    boolean pause() {
        restore();
        CountDownLatch ready = new CountDownLatch(1);
        ServiceConnection next = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (connection == this) service = binder;
                ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (connection == this) service = null;
                ready.countDown();
            }
        };
        connection = next;
        try {
            Intent intent = new Intent("com.hsae.auto.VOICE_SERVICE").setComponent(COMPONENT);
            if (!context.bindService(intent, next, Context.BIND_AUTO_CREATE) ||
                !ready.await(1500, TimeUnit.MILLISECONDS) || service == null) {
                restore();
                return false;
            }
            boolean active = query();
            if (!active) return true;
            restoreNeeded = true;
            command(16, false);
            if (query()) throw new RemoteException("OEM wake recorder remained active");
            Thread.sleep(200);
            return true;
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w(TAG, "OEM wake pause failed: " + error.getClass().getSimpleName());
            restore();
            return false;
        }
    }

    void restore() {
        IBinder current = service;
        if (restoreNeeded && current != null) {
            try { command(9, true); }
            catch (Exception error) { Log.w(TAG, "OEM wake restore failed", error); }
        }
        restoreNeeded = false;
        if (connection != null) {
            try { context.unbindService(connection); } catch (Exception ignored) { }
        }
        connection = null;
        service = null;
    }

    private boolean query() throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (service == null || !service.transact(14, data, reply, 0))
                throw new RemoteException("OEM wake query unavailable");
            reply.readException();
            return reply.readInt() != 0;
        } finally { reply.recycle(); data.recycle(); }
    }

    private void command(int code, boolean oneway) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = oneway ? null : Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (service == null || !service.transact(code, data, reply, oneway ? IBinder.FLAG_ONEWAY : 0))
                throw new RemoteException("OEM wake command unavailable");
            if (reply != null) reply.readException();
        } finally { if (reply != null) reply.recycle(); data.recycle(); }
    }
}
