package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Hisense keeps its volume keys for itself (apps never see them), so Channel +/- do the job: they change the volume
 * with AudioManager. The TV's own bar shows the new level; it opens on every report the soundbar sends back, and stays
 * about 3 s, so it is closed soon after the last change.
 */
final class VolumeKeys {
    private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION",
            MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION", EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private final Context ctx;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getIntExtra(EXTRA_STREAM, -1) == AudioManager.STREAM_MUSIC) closeSystemBar();
        }
    };

    VolumeKeys(Context c) {
        ctx = c;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED);
        filter.addAction(MUTE_CHANGED);
        c.registerReceiver(changed, filter, null, handler);
    }

    /** The service is going away: stop listening. */
    void release() {
        try { ctx.unregisterReceiver(changed); } catch (RuntimeException ignored) {}
        handler.removeCallbacksAndMessages(null);
    }

    private static final long FIRST_REPEAT_MS = 400, REPEAT_MS = 120, MAX_HOLD_MS = 15_000;
    private boolean repeatUp;
    private long heldSince;
    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (System.currentTimeMillis() - heldSince > MAX_HOLD_MS) return;   // the key-up got lost
            step(repeatUp);
            handler.postDelayed(this, REPEAT_MS);
        }
    };

    /** Channel up/down act as volume up/down. Returns true if the key is one of them (down and up are consumed). */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_CHANNEL_UP && code != KeyEvent.KEYCODE_CHANNEL_DOWN) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(repeater);
        } else if (e.getRepeatCount() == 0) {
            // We consume the key, so Android sends no repeats while it is held: we repeat ourselves.
            handler.removeCallbacks(repeater);
            repeatUp = code == KeyEvent.KEYCODE_CHANNEL_UP;
            heldSince = System.currentTimeMillis();
            step(repeatUp);
            handler.postDelayed(repeater, FIRST_REPEAT_MS);
        }
        return true;
    }

    private void step(boolean up) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        closeSystemBar();
    }

    /** Close the TV's bar every 60 ms while volume changes keep coming, and for 1.5 s after the last one. */
    private static final long CLOSE_EVERY_MS = 60, CLOSE_AFTER_LAST_MS = 1_500;
    private long closeUntil;
    private boolean closing;
    private final Runnable closeSystem = new Runnable() {
        @Override public void run() {
            sendClose();
            if (SystemClock.uptimeMillis() < closeUntil) handler.postDelayed(this, CLOSE_EVERY_MS);
            else closing = false;
        }
    };

    @SuppressWarnings("deprecation")
    private void sendClose() {
        ctx.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    private void closeSystemBar() {
        closeUntil = SystemClock.uptimeMillis() + CLOSE_AFTER_LAST_MS;
        if (closing) return;
        closing = true;
        handler.post(closeSystem);
    }
}
