package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. */
public class RemoteKeyService extends AccessibilityService {
    /** An exception here would kill the service and Android would not bring it back: nothing may escape. */
    @Override protected boolean onKeyEvent(KeyEvent event) {
        try {
            return RemoteKeys.onKey(this, event);
        } catch (Throwable t) {
            Log.w("FreeMyTV", "key " + event.getKeyCode() + ": " + t);
            return false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
