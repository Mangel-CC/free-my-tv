package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * The TV handles its volume keys itself (apps never see them), so the channel keys do the job: they change the
 * volume and show a small vertical bar at the right edge, centered, instead of the TV's one that covers subtitles. The channel keys are taken here (RemoteKeyService) and applied with AudioManager without its own panel;
 * with a soundbar on HDMI-ARC the system sends the change on to it, as it does for the TV's panel.
 */
final class VolumeBar {
    private static final long SHOW_MS = 2_000;

    private final Context ctx;
    private final AudioManager audio;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hider = this::hide;
    private Bar bar;

    VolumeBar(Context c) {
        ctx = c;
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
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
        show(up);
    }

    /** Hisense's SystemUI shows its own bar for any volume change, flags or not: close it as soon as it opens. */
    private final Runnable closeSystem = this::sendClose;

    @SuppressWarnings("deprecation")
    private void sendClose() {
        ctx.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    private void closeSystemBar() {
        handler.removeCallbacks(closeSystem);
        for (long delay : new long[] {0, 80, 200, 450}) handler.postDelayed(closeSystem, delay);
    }

    private void show(boolean up) {
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        float level = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || level == 0;
        if (bar == null) {
            bar = new Bar(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams((int) (64 * d), (int) (320 * d),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
            lp.x = (int) (48 * d);
            try { wm.addView(bar, lp); } catch (RuntimeException ex) { bar = null; return; }
        }
        bar.set(level, muted, audio.isVolumeFixed() ? (up ? "+" : "\u2212") : null);
        handler.removeCallbacks(hider);
        handler.postDelayed(hider, SHOW_MS);
    }

    void hide() {
        handler.removeCallbacks(hider);
        handler.removeCallbacks(repeater);
        handler.removeCallbacks(closeSystem);
        if (bar == null) return;
        try { wm.removeView(bar); } catch (RuntimeException ignored) {}
        bar = null;
    }

    private static final class Bar extends View {
        private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG),
                fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;
        private float level;
        private String label = "";

        Bar(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            panel.setColor(0xCC10141C);
            track.setColor(0x33FFFFFF);
            fill.setColor(0xFFFFFFFF);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(14 * d);
        }

        void set(float level, boolean muted, String fixedLabel) {
            this.level = fixedLabel != null ? 1 : muted ? 0 : level;
            label = fixedLabel != null ? fixedLabel : muted ? muted() : String.valueOf(Math.round(level * 100));
            invalidate();
        }

        private static String muted() {
            String l = Locale.getDefault().getLanguage();
            return l.equals("pt") ? "Mudo" : l.equals("es") ? "Silencio" : "Muted";
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            c.drawRoundRect(r, w / 2, w / 2, panel);
            float mid = w / 2, th = 6 * d, top = 24 * d, bottom = h - 56 * d;
            r.set(mid - th / 2, top, mid + th / 2, bottom);
            c.drawRoundRect(r, th / 2, th / 2, track);
            r.set(mid - th / 2, bottom - (bottom - top) * level, mid + th / 2, bottom);
            c.drawRoundRect(r, th / 2, th / 2, fill);
            c.drawText(label, mid - text.measureText(label) / 2, h - 20 * d, text);
        }
    }
}
