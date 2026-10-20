package io.freemytv;

import android.content.Context;
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

/**
 * Replaces the TV's volume bar, which sits at the bottom and covers subtitles, with a small vertical one at the
 * right edge, centered. The volume keys are taken here (RemoteKeyService) and applied with AudioManager without its own panel;
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

    /** True if the key is a volume key (it is then consumed, down and up). */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
                && code != KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int dir = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                    : code == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0);   // flags 0: no system panel
            show();
        }
        return true;
    }

    private void show() {
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
            try { wm.addView(bar, lp); } catch (RuntimeException ex) { AppLog.w("volume bar: " + ex.getMessage()); bar = null; return; }
        }
        bar.set(level, muted);
        handler.removeCallbacks(hider);
        handler.postDelayed(hider, SHOW_MS);
    }

    void hide() {
        handler.removeCallbacks(hider);
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
            text.setTextSize(18 * d);
        }

        void set(float level, boolean muted) {
            this.level = muted ? 0 : level;
            label = muted ? Ui.tr("Mudo", "Muted", "Silencio") : String.valueOf(Math.round(level * 100));
            invalidate();
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
