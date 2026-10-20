package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** The key codes are TCL's or Hisense's: on other brands they can be other buttons, so nothing here runs there. */
    static boolean supported() {
        return tcl() || hisense();
    }

    private static boolean tcl() {
        return "TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND);
    }

    private static boolean hisense() {
        String names = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.PRODUCT + " " + Build.DEVICE)
                .toLowerCase(Locale.ROOT);
        return names.contains("hisense");
    }

    // Hisense: the remote's streaming buttons (codes read from a 55A53FUA's remote), fixed: Netflix, Prime Video,
    // YouTube, Disney+, Tubi. The Media button (335) is left to the TV. Apps are found by their name on the launcher.
    private static final int H_NETFLIX = 265, H_PRIME = 295, H_YOUTUBE = 293, H_DISNEY = 607, H_TUBI = 606;

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (hisense()) return onHisenseKey(c, e);
        if (!tcl()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == e.getKeyCode();
        if (!ours) return false;
        String pkg = Prefs.keyApp(e.getKeyCode());
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent intent = HomeApps.launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    private static boolean onHisenseKey(Context c, KeyEvent e) {
        int code = e.getKeyCode();
        if (code != H_NETFLIX && code != H_PRIME && code != H_YOUTUBE && code != H_DISNEY && code != H_TUBI) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && code > 200) AppLog.w("remote key " + code + " (not mapped)");
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (code == H_NETFLIX) openApp(c, "Nuvio Chapters");
            else if (code == H_DISNEY) openApp(c, "Nuvio");
            else if (code == H_TUBI) openApp(c, "Spotify");
            else if (code == H_YOUTUBE) openApp(c, "SmartTube");
            else if (!openHdmi(c, "3")) openApp(c, "Spotify");
        }
        return true;   // down and up are ours, so the TV's own action never runs
    }

    /** Opens the installed app with this name: the exact name first, then one that contains it ("Nuvio" vs "Nuvio Chapters"). */
    private static void openApp(Context c, String name) {
        PackageManager pm = c.getPackageManager();
        String want = name.toLowerCase(Locale.ROOT);
        for (boolean exact : new boolean[] {true, false}) {
            for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
                for (ResolveInfo ri : apps) {
                    String label = ri.loadLabel(pm).toString().trim().toLowerCase(Locale.ROOT);
                    if (exact ? !label.equals(want) : !label.contains(want)) continue;
                    AppLog.w("remote key -> " + ri.activityInfo.packageName);
                    c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(category)
                            .setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
        }
        AppLog.w("remote key: app not found: " + name);
        Toast.makeText(c, Ui.tr(name + ": app não encontrado", name + ": app not found", name + ": app no encontrada"),
                Toast.LENGTH_LONG).show();
    }

    /** Switches to the HDMI input whose name ends with this port number ("HDMI 3"). False if there is none. */
    private static boolean openHdmi(Context c, String port) {
        TvInputManager tv = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        for (TvInputInfo in : tv.getTvInputList()) {
            if (in.getType() != TvInputInfo.TYPE_HDMI) continue;
            CharSequence custom = in.loadCustomLabel(c);
            String label = String.valueOf(custom != null ? custom : in.loadLabel(c));
            if (!label.trim().endsWith(port)) continue;
            AppLog.w("remote key -> " + label);
            c.startActivity(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        AppLog.w("remote key: no HDMI " + port);
        return false;
    }
}
