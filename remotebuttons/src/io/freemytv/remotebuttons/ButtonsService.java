package io.freemytv.remotebuttons;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * Sees the remote's keys before any app does and sends the streaming buttons where we want. The key codes are this
 * Hisense's (read with the diagnostic build); a key not listed here goes on as if we weren't here.
 */
public class ButtonsService extends AccessibilityService {
    private static final int NETFLIX = 265, PRIME = 295, YOUTUBE = 293, DISNEY = 607, TUBI = 606;

    private VolumeBar volume;

    /** An exception here would kill the service and Android would not bring it back: nothing may escape. */
    @Override protected boolean onKeyEvent(KeyEvent e) {
        try {
            return handle(e);
        } catch (Throwable t) {
            Log.w("FMTVButtons", "key " + e.getKeyCode() + ": " + t);
            return false;
        }
    }

    private boolean handle(KeyEvent e) {
        if (volume == null) volume = new VolumeBar(this);
        if (volume.onKey(e)) return true;
        int code = e.getKeyCode();
        if (code != NETFLIX && code != PRIME && code != YOUTUBE && code != DISNEY && code != TUBI) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (code == NETFLIX) openApp("Nuvio Chapters");
            else if (code == DISNEY) openApp("Nuvio");
            else if (code == TUBI) openApp("Spotify");
            else if (code == YOUTUBE) openApp("SmartTube");
            else if (!openHdmi("3")) openApp("Spotify");
        }
        return true;   // down and up are ours, so the TV's own action never runs
    }

    /** Opens the installed app with this name: the exact name first, then one that contains it ("Nuvio" vs "Nuvio Chapters"). */
    private void openApp(String name) {
        PackageManager pm = getPackageManager();
        String want = name.toLowerCase(Locale.ROOT);
        for (boolean exact : new boolean[] {true, false}) {
            for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                List<ResolveInfo> apps = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
                for (ResolveInfo ri : apps) {
                    String label = ri.loadLabel(pm).toString().trim().toLowerCase(Locale.ROOT);
                    if (exact ? !label.equals(want) : !label.contains(want)) continue;
                    startActivity(new Intent(Intent.ACTION_MAIN).addCategory(category)
                            .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
        }
        Toast.makeText(this, name + ": app not found", Toast.LENGTH_LONG).show();
    }

    /** Switches to the HDMI input whose name ends with this port number ("HDMI 3"). False if there is none. */
    private boolean openHdmi(String port) {
        TvInputManager tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        StringBuilder seen = new StringBuilder();
        for (TvInputInfo in : tv.getTvInputList()) {
            if (in.getType() != TvInputInfo.TYPE_HDMI) continue;
            CharSequence custom = in.loadCustomLabel(this);
            String label = String.valueOf(custom != null ? custom : in.loadLabel(this));
            seen.append(seen.length() > 0 ? ", " : "").append(label);
            if (!label.trim().endsWith(port)) continue;
            startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        Toast.makeText(this, "HDMI " + port + " not found. Inputs: " + seen, Toast.LENGTH_LONG).show();
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override protected void onServiceConnected() {
        Log.i("FMTVButtons", "service connected");
    }

    @Override public void onInterrupt() {}

    @Override public boolean onUnbind(Intent intent) {
        if (volume != null) volume.hide();
        return super.onUnbind(intent);
    }
}
