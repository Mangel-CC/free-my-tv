package io.freemytv.volumebar;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Sees the volume keys before any app does and shows our own bar instead of the TV's. */
public class VolumeService extends AccessibilityService {
    private VolumeBar volume;

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (volume == null) volume = new VolumeBar(this);
        boolean ours = volume.onKey(event);
        // Diagnostic: any key shows its code, so we can see whether the TV hands us the volume keys.
        if (!ours && event.getAction() == KeyEvent.ACTION_DOWN) volume.note("k" + event.getKeyCode());
        return ours;
    }

    @Override protected void onServiceConnected() {
        if (volume == null) volume = new VolumeBar(this);
        volume.note("OK");   // diagnostic: the service is running and can draw
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(Intent intent) {
        if (volume != null) volume.hide();
        return super.onUnbind(intent);
    }
}
