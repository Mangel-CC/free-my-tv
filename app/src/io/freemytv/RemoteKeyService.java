package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest.
 * With custom_volume it also takes the volume keys, so the TV's bar (which covers subtitles) never shows.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (Config.CUSTOM_VOLUME) {
            if (volume == null) volume = new VolumeBar(this);
            if (volume.onKey(event)) return true;
        }
        return RemoteKeys.onKey(this, event);
    }

    private VolumeBar volume;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (volume != null) volume.hide();
        return super.onUnbind(intent);
    }
}
