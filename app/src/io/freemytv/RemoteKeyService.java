package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. */
public class RemoteKeyService extends AccessibilityService {
    private VolumeBar volume;

    /** An exception here would kill the service and Android would not bring it back: nothing may escape. */
    @Override protected boolean onKeyEvent(KeyEvent event) {
        try {
            // Hisense keeps its volume keys for itself: Channel +/- change the volume, with a bar that doesn't cover subtitles.
            if (RemoteKeys.hisense()) {
                if (volume == null) volume = new VolumeBar(this);
                if (volume.onKey(event)) return true;
            }
            return RemoteKeys.onKey(this, event);
        } catch (Throwable t) {
            Log.w("FreeMyTV", "key " + event.getKeyCode() + ": " + t);
            return false;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(Intent intent) {
        if (volume != null) volume.release();
        volume = null;
        return super.onUnbind(intent);
    }
}
