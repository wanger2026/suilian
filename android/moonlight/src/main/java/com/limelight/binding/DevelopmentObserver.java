package com.limelight.binding;

import android.app.Activity;

/** Optional host integration; standalone Moonlight behavior is unchanged. */
public final class DevelopmentObserver {
    public interface Listener { void state(Activity activity, String host, boolean connected); }
    public static Listener listener;
    // Counts only, never key names, text or pointer positions.
    public static volatile int keyEvents, pointerEvents, keySends, buttonSends;
    public static volatile boolean grabbed, automationInputSuppressed;
    public static void resetInput(boolean suppressed) {
        keyEvents = pointerEvents = keySends = buttonSends = 0;
        automationInputSuppressed = suppressed;
    }
    public static void state(Activity activity, String host, boolean connected) {
        Listener current = listener;
        if (current != null) current.state(activity, host, connected);
    }
    private DevelopmentObserver() {}
}
