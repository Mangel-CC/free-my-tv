package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sets the TMDB key and the place of the weather from the computer, so they never go into the APK or the repo:
 * adb shell am broadcast -n io.freemytv/.ConfigReceiver --es tmdb_key YOUR_KEY --es city Name --es latitude 18.0 --es longitude -92.9
 * Only the shell can send it (the manifest asks for the DUMP permission); the key is never logged.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String key = intent.getStringExtra("tmdb_key");
        if (key != null) {
            Prefs.setTmdbKey(key);
            AppLog.w("TMDB key " + (key.trim().isEmpty() ? "cleared" : "set (" + key.trim().length() + " characters)"));
        }
        String lat = intent.getStringExtra("latitude"), lon = intent.getStringExtra("longitude");
        String city = intent.getStringExtra("city");
        if (lat != null || lon != null || city != null) {
            Prefs.setPlace(city != null ? city : Prefs.city(), lat != null ? lat : Prefs.latitude(), lon != null ? lon : Prefs.longitude());
            AppLog.w("weather place set: " + Prefs.city() + " " + Prefs.latitude() + "," + Prefs.longitude());
        }
    }
}
