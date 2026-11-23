package com.bradyxu.headunitmanager;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.os.Handler;
import java.util.HashMap;
import java.util.Map;

public final class KeyService extends AccessibilityService {
    interface Capture { void onKey(int code, int scanCode); }
    private static volatile Capture capture;
    private static volatile KeyService activeService;
    private static volatile int simulatedKey = -1;
    private static volatile long simulatedKeyUntil;
    static void simulatedKey(int code) {
        long now = android.os.SystemClock.uptimeMillis();
        if (code >= 0) {
            simulatedKey = code;
            simulatedKeyUntil = now + 3000L;
        } else {
            // Keep the code briefly after the shell command returns because injected
            // KeyEvents can reach AccessibilityService on the next main-loop turn.
            simulatedKeyUntil = now + 500L;
        }
    }
    private static boolean isSimulatedKey(int code) {
        return code == simulatedKey && android.os.SystemClock.uptimeMillis() <= simulatedKeyUntil;
    }
    static void capture(Capture next) { capture = next; }
    static KeyService activeService() { return activeService; }
    @Override protected void onServiceConnected() { super.onServiceConnected(); activeService = this; }
    private final Handler handler = new Handler();
    private final Map<Integer, Long> downTimes = new HashMap<>();
    private final Map<Integer, Long> lastTapTimes = new HashMap<>();
    private final Map<Integer, Runnable> pendingShorts = new HashMap<>();
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (isSimulatedKey(event.getKeyCode())) return false;
        if (capture != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Capture listener = capture;
                capture = null;
                downTimes.remove(event.getKeyCode());
                recordKeyEvent(event);
                listener.onKey(event.getKeyCode(), event.getScanCode());
            }
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int code = event.getKeyCode();
            downTimes.put(code, event.getEventTime());
            recordKeyEvent(event);
            Rules.dispatch(this, Rules.TRIGGER_KEY, String.valueOf(code), this);
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            recordKeyEvent(event);
            int code = event.getKeyCode();
            Long downAt = downTimes.remove(code);
            if (downAt == null) return false;
            long now = event.getEventTime();
            int longMs = getSharedPreferences(Rules.PREF, 0).getInt("longMs", 600);
            int doubleMs = getSharedPreferences(Rules.PREF, 0).getInt("doubleMs", 400);
            if (now - downAt >= longMs) {
                Rules.dispatch(this, Rules.TRIGGER_KEY, code + ":LONG", this);
            } else if (!Rules.hasKeyRule(this, code + ":DOUBLE")) {
                Rules.dispatch(this, Rules.TRIGGER_KEY, code + ":SHORT", this);
            } else if (pendingShorts.containsKey(code) && now - lastTapTimes.get(code) <= doubleMs) {
                handler.removeCallbacks(pendingShorts.remove(code));
                lastTapTimes.remove(code);
                Rules.dispatch(this, Rules.TRIGGER_KEY, code + ":DOUBLE", this);
            } else {
                lastTapTimes.put(code, now);
                String scheduledProfile = Rules.profile(this);
                Runnable pendingShort = () -> {
                    if (scheduledProfile.equals(Rules.profile(this))) Rules.dispatch(this, Rules.TRIGGER_KEY, code + ":SHORT", this);
                    pendingShorts.remove(code);
                    lastTapTimes.remove(code);
                };
                Runnable previous = pendingShorts.put(code, pendingShort);
                if (previous != null) handler.removeCallbacks(previous);
                handler.postDelayed(pendingShort, doubleMs);
            }
        }
        return false;
    }

    private void recordKeyEvent(KeyEvent event) {
        android.content.SharedPreferences prefs = getSharedPreferences(Rules.PREF, 0);
        int sequence = prefs.getInt("lastKeySequence", 0) + 1;
        prefs.edit().putInt("lastKey", event.getKeyCode())
            .putInt("lastScanCode", event.getScanCode())
            .putInt("lastKeySequence", sequence)
            .putLong("lastKeyTime", System.currentTimeMillis())
            .putInt("lastKeyDevice", event.getDeviceId())
            .putInt("lastKeyAction", event.getAction())
            .putInt("lastKeyRepeat", event.getRepeatCount())
            .apply();
    }

    @Override public void onDestroy() {
        for (Runnable pending : pendingShorts.values()) handler.removeCallbacks(pending);
        if (activeService == this) activeService = null;
        capture = null;
        pendingShorts.clear();
        downTimes.clear();
        lastTapTimes.clear();
        super.onDestroy();
    }
}
