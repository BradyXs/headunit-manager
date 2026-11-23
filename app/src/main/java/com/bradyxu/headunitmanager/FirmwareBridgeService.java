package com.bradyxu.headunitmanager;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.lang.reflect.Method;
import java.security.MessageDigest;

/** System-UID firmware interface for the Nissan Android 4.4 build. No raw OEM Binder is exposed. */
public final class FirmwareBridgeService extends Service {
    public static final String DESCRIPTOR = "com.bradyxu.headunitmanager.IFirmwareBridge.v1";
    public static final int VERSION = 1;
    public static final int CAP_VOICE_LEASE = 1;
    public static final int CAP_HOTSPOT = 2;
    public static final int CAP_PHONE_STATE = 4;
    public static final int CAP_HFP_SESSION_PROBE = 8; // Existing DiPlay capability bit for wired HFP session control.
    public static final int CAP_HFP_RECONNECT_GRACE = 16;
    private static final String TAG = "P350FirmwareBridge";
    private static final String DIPLAY_PACKAGE = "com.shihab.diplay.hudtest";
    private static final String DIPLAY_CERT = "f676c7bd731b97adedfbc198cf1f750c03a7a577ae037e644b29f8ac7e046fed";
    private static final int TX_INFO = 1;
    private static final int TX_VOICE_ACQUIRE = 2;
    private static final int TX_VOICE_RELEASE = 3;
    private static final int TX_HOTSPOT_STATE = 4;
    private static final int TX_HOTSPOT_SET = 5;
    private static final int TX_PHONE_STATE = 6;
    private final Object gate = new Object();
    private final VoiceWakeGate wake = new VoiceWakeGate(this);
    private IBinder voiceOwner;
    private IBinder.DeathRecipient voiceDeath;
    private IBinder hotspotOwner;
    private IBinder.DeathRecipient hotspotDeath;
    private boolean hotspotStartedHere;
    private boolean wifiDisabledHere;

    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            enforceClient();
            switch (code) {
                case TX_INFO:
                    reply.writeNoException();
                    reply.writeInt(VERSION);
                    reply.writeInt(CAP_VOICE_LEASE | CAP_HOTSPOT | CAP_PHONE_STATE |
                        CAP_HFP_SESSION_PROBE | CAP_HFP_RECONNECT_GRACE);
                    return true;
                case TX_VOICE_ACQUIRE: {
                    IBinder token = data.readStrongBinder();
                    int result = acquireVoice(token);
                    reply.writeNoException(); reply.writeInt(result);
                    return true;
                }
                case TX_VOICE_RELEASE: {
                    int result = releaseVoice(data.readStrongBinder());
                    reply.writeNoException(); reply.writeInt(result);
                    return true;
                }
                case TX_HOTSPOT_STATE:
                    reply.writeNoException(); reply.writeInt(hotspotState());
                    return true;
                case TX_HOTSPOT_SET: {
                    IBinder token = data.readStrongBinder();
                    boolean enabled = data.readInt() != 0;
                    int result = setHotspot(token, enabled);
                    reply.writeNoException(); reply.writeInt(result);
                    return true;
                }
                case TX_PHONE_STATE:
                    reply.writeNoException(); reply.writeInt(phoneState());
                    return true;
                case 7: {
                    IBinder token = data.readStrongBinder();
                    String candidate = data.readString();
                    boolean wired = data.readInt() == 1;
                    int result = HfpSessionLease.pulse(FirmwareBridgeService.this, token, candidate, wired);
                    if (result == 1 && HfpSessionLease.claimAutomaticAttempt(token)) {
                        final android.content.Context app = getApplicationContext();
                        Log.i("P350HfpGate", "automatic_handoff_scheduled");
                        new Handler(Looper.getMainLooper()).postDelayed(() ->
                            HsaeBluetooth.automaticHfpHandoff(app, token, candidate, outcome ->
                                {
                                    HfpSessionLease.finishAutomaticAttempt(token);
                                    Log.i("P350HfpGate", "automatic_handoff_result=" + outcome);
                                }), 500L);
                    }
                    reply.writeNoException(); reply.writeInt(result);
                    return true;
                }
                case 8: {
                    boolean restore = HfpSessionLease.release(data.readStrongBinder(), "client_session_ended");
                    if (restore) {
                        final android.content.Context app = getApplicationContext();
                        Log.i("P350HfpRestore", "automatic_restore_scheduled delay_ms=2000");
                        new Handler(Looper.getMainLooper()).postDelayed(() ->
                            HsaeBluetooth.restoreHfpOnce(app, result ->
                                Log.i("P350HfpRestore", "automatic_restore_finished result=" + result)), 2000L);
                    }
                    reply.writeNoException(); reply.writeInt(1);
                    return true;
                }
                case 9: {
                    IBinder token = data.readStrongBinder();
                    String candidate = data.readString();
                    long graceMillis = data.readLong();
                    int call = phoneState();
                    int result = HfpSessionLease.preserveForReconnect(
                        FirmwareBridgeService.this, token, candidate, graceMillis, call);
                    reply.writeNoException(); reply.writeInt(result);
                    return true;
                }
                default: return super.onTransact(code, data, reply, flags);
            }
        }
    };

    @Override public IBinder onBind(Intent intent) { return endpoint; }

    private void enforceClient() {
        int uid = Binder.getCallingUid();
        PackageManager pm = getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null) for (String name : packages) {
            if (!DIPLAY_PACKAGE.equals(name)) continue;
            try {
                PackageInfo info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
                if (info.signatures == null || info.signatures.length != 1) break;
                Signature signature = info.signatures[0];
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                if (DIPLAY_CERT.equals(hex(hash))) return;
            } catch (Exception error) { break; }
        }
        throw new SecurityException("Untrusted firmware bridge caller uid=" + uid);
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = digits[(data[i] >> 4) & 15];
            out[i * 2 + 1] = digits[data[i] & 15];
        }
        return new String(out);
    }

    private int acquireVoice(IBinder token) {
        if (token == null || !token.isBinderAlive()) return -1;
        synchronized (gate) {
            if (voiceOwner == token) return 1;
            if (voiceOwner != null) return 0;
            IBinder.DeathRecipient death = () -> releaseVoice(token);
            try { token.linkToDeath(death, 0); }
            catch (RemoteException error) { return -1; }
            voiceOwner = token;
            voiceDeath = death;
            if (wake.pause()) {
                Log.i(TAG, "voice lease acquired");
                return 1;
            }
            clearVoiceOwner();
            return -1;
        }
    }

    private int releaseVoice(IBinder token) {
        synchronized (gate) {
            if (token == null || voiceOwner != token) return 0;
            clearVoiceOwner();
            Log.i(TAG, "voice lease released");
            return 1;
        }
    }

    private void clearVoiceOwner() {
        if (voiceOwner != null && voiceDeath != null) voiceOwner.unlinkToDeath(voiceDeath, 0);
        voiceOwner = null;
        voiceDeath = null;
        wake.restore();
    }

    private WifiManager wifi() { return (WifiManager) getSystemService(WIFI_SERVICE); }

    private int phoneState() {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod("getService", String.class)
                .invoke(null, "com.hsae.auto.IBINDER_BT_PHONE_MANAGER");
            if (binder == null) return -1;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.hsae.autosdk.bt.phone.IBTPhoneManager");
                if (!binder.transact(5, data, reply, 0)) return -1;
                reply.readException();
                return reply.readInt();
            } finally { reply.recycle(); data.recycle(); }
        } catch (Exception error) { return -1; }
    }

    private int hotspotState() {
        try {
            int state = ((Integer) WifiManager.class.getMethod("getWifiApState").invoke(wifi()));
            return state == 13 ? 1 : state == 11 ? 0 : -1;
        } catch (Exception error) { return -1; }
    }

    /** Only turns off an AP started through this lease. Never takes ownership of a user AP. */
    private int setHotspot(IBinder token, boolean enabled) {
        if (token == null || !token.isBinderAlive()) return -1;
        synchronized (gate) {
            int state = hotspotState();
            if (enabled) {
                if (hotspotOwner == token) return state == 1 ? 1 : -1;
                if (hotspotOwner != null) return 0;
                if (state == 1) return 2; // Existing user hotspot; leave its ownership unchanged.
                if (state != 0) return -1;
                IBinder.DeathRecipient death = () -> releaseHotspot(token);
                try { token.linkToDeath(death, 0); }
                catch (RemoteException error) { return -1; }
                hotspotOwner = token;
                hotspotDeath = death;
                try {
                    wifiDisabledHere = wifi().isWifiEnabled();
                    if (wifiDisabledHere && !wifi().setWifiEnabled(false)) throw new IllegalStateException("wifi disable rejected");
                    if (!setAp(true)) throw new IllegalStateException("AP enable rejected");
                    hotspotStartedHere = true;
                    Log.i(TAG, "hotspot enable requested");
                    return 1;
                } catch (Exception error) {
                    Log.w(TAG, "hotspot enable failed", error);
                    releaseHotspot(token);
                    return -1;
                }
            }
            return releaseHotspot(token);
        }
    }

    private int releaseHotspot(IBinder token) {
        synchronized (gate) {
            if (token == null || hotspotOwner != token) return 0;
            if (hotspotDeath != null) token.unlinkToDeath(hotspotDeath, 0);
            hotspotOwner = null;
            hotspotDeath = null;
            int result = 1;
            if (hotspotStartedHere) {
                try { if (!setAp(false)) result = -1; }
                catch (Exception error) { result = -1; Log.w(TAG, "hotspot disable failed", error); }
            }
            if (wifiDisabledHere && result == 1) wifi().setWifiEnabled(true);
            hotspotStartedHere = false;
            wifiDisabledHere = false;
            Log.i(TAG, "hotspot lease released result=" + result);
            return result;
        }
    }

    private boolean setAp(boolean enabled) throws Exception {
        Method method = WifiManager.class.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration.class, boolean.class);
        return Boolean.TRUE.equals(method.invoke(wifi(), null, enabled));
    }

    @Override public void onDestroy() {
        HfpSessionLease.shutdown();
        synchronized (gate) {
            if (voiceOwner != null) clearVoiceOwner();
            if (hotspotOwner != null) releaseHotspot(hotspotOwner);
        }
        super.onDestroy();
    }
}
