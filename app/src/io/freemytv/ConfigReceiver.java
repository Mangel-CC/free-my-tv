package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sets the TMDB key from the computer, so it never goes into the APK or the repo:
 * adb shell am broadcast -n io.freemytv/.ConfigReceiver --es tmdb_key YOUR_KEY
 * Only the shell can send it (the manifest asks for the DUMP permission); the key is never logged.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String key = intent.getStringExtra("tmdb_key");
        if (key == null) return;
        Prefs.setTmdbKey(key);
        AppLog.w("TMDB key " + (key.trim().isEmpty() ? "cleared" : "set (" + key.trim().length() + " characters)"));
    }
}
