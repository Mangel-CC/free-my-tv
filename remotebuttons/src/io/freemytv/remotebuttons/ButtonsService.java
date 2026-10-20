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
    private static final int NETFLIX = 265, PRIME = 295, YOUTUBE = 293;

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != NETFLIX && code != PRIME && code != YOUTUBE) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (code == NETFLIX) openApp("Nuvio");
            else if (code == YOUTUBE) openApp("SmartTube");
            else openHdmi("3");
        }
        return true;   // down and up are ours, so the TV's own action never runs
    }

    /** Opens the installed app whose name contains this text (so we don't need its package name). */
    private void openApp(String name) {
        PackageManager pm = getPackageManager();
        String want = name.toLowerCase(Locale.ROOT);
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> apps = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            for (ResolveInfo ri : apps) {
                if (!ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT).contains(want)) continue;
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(category)
                        .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
        Toast.makeText(this, name + ": app not found", Toast.LENGTH_LONG).show();
    }

    /** Switches to the HDMI input whose name ends with this port number ("HDMI 3"). */
    private void openHdmi(String port) {
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
            return;
        }
        Toast.makeText(this, "HDMI " + port + " not found. Inputs: " + seen, Toast.LENGTH_LONG).show();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}
