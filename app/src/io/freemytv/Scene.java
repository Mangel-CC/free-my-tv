package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Background (aurora) and glass, using the shaders in Shaders. The aurora is drawn into a ¼-scale layer, scaled up,
 * and only redrawn when it moves (advance, up to ~30 times per second); in the frames between steps the GPU just
 * reuses the layer. The glass reads the frosted aurora computed in Frost or, with a recommendation's image as the
 * background, its blurred version (setFrost).
 * If AGSL fails (it shouldn't on Android 13+), it falls back to a gradient and plain translucent glass: the home screen
 * is never left blank.
 */
final class Scene {
    final Shaders.Palette palette = new Shaders.Palette();
    private RuntimeShader aurora, glass, liquid;
    private final Frost frost = new Frost();
    private final Paint auroraPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Paint flatFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint();
    // Without AGSL (Android 11/12): a gradient sky with two soft glows that drift slowly, and glass made of a tint, a
    // sheen and a lit rim. The shaders are built once; only their matrices move, so nothing is allocated per frame.
    private final Paint glowA = new Paint(), glowB = new Paint(), sheenPaint = new Paint(), rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private Shader sheenUnit, rimUnit;
    private final RenderNode auroraNode = new RenderNode("aurora");
    // The layer is drawn at a quarter size and scaled up: filtered, its soft shapes stay soft instead of stepping.
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, dim, time;
    private boolean auroraDirty = true, imageFrost, imageGlass;
    private Bitmap frostImage, glassA, glassB;
    private BitmapShader glassShaderA;
    // Liquid Glass without AGSL (Android 11/12): the slightly blurred photo behind (glassA/B) seen through the glass,
    // magnified a little like a lens and more at the rim, where the edge bends the light; then a tint and a lit rim.
    // Bitmap shaders only (the GPU samples the photo): nothing per pixel on the CPU, nothing allocated per frame.
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix lensMatrix = new Matrix();
    private final RectF rimInner = new RectF(), shineRect = new RectF();
    private BitmapShader lensA, lensB;
    private Bitmap lensOfA, lensOfB;
    private boolean lensMixing;
    private float offsetX, offsetY, glassMix;
    private Shader shineUnit;

    Scene() {
        try {
            aurora = new RuntimeShader(Shaders.AURORA);
            glass = new RuntimeShader(Shaders.GLASS);
            liquid = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(aurora);
            glassPaint.setShader(glass);
            liquidPaint.setShader(liquid);
        } catch (RuntimeException | LinkageError e) {   // Android 11/12: no AGSL, flat look instead
            AppLog.w("shader unavailable, using gradient: " + e.getMessage());
            aurora = null;
            glass = null;
            liquid = null;
        }
        auroraNode.setUseCompositingLayer(true, layerPaint);
        flatRim.setStyle(Paint.Style.STROKE);
        flatRim.setColor(0x33FFFFFF);
        flatRim.setStrokeWidth(1.5f);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1.5f);
        // Unit gradients (0 to 1 in y): scaled to each glass panel with a matrix.
        sheenUnit = new LinearGradient(0, 0, 0, 1, 0x30FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP);
        rimUnit = new LinearGradient(0, 0, 0, 1, 0x80FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP);
        sheenPaint.setShader(sheenUnit);
        rimPaint.setShader(rimUnit);
        // The lit rim of the liquid glass: bright at the top left, where the light comes from, a faint echo at the
        // bottom right (unit square, scaled to each panel).
        shineUnit = new LinearGradient(0, 0, 1, 1, new int[] {0xF2FFFFFF, 0x2EFFFFFF, 0x14FFFFFF, 0x73FFFFFF},
                new float[] {0, 0.42f, 0.7f, 1}, Shader.TileMode.CLAMP);
        shinePaint.setStyle(Paint.Style.STROKE);
        shinePaint.setStrokeWidth(2);
        shinePaint.setShader(shineUnit);
    }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round(w / 4f));
        lh = Math.max(1, Math.round(h / 4f));
        auroraNode.setPosition(0, 0, lw, lh);
        shinePaint.setStrokeWidth(Math.max(1.5f, w / 1920f * 2.2f));
        if (aurora != null) {
            aurora.setFloatUniform("res", w, h);
            aurora.setFloatUniform("layer", lw, lh);
            setFrostScale();
            if (imageGlass) liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        }
        push();
    }

    /** speed: 1 on the home screen, less on the screensaver; dim: how much to darken (0 to 1). */
    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    /** Call after changing `palette`. Bright palettes are dimmed a little so white text stays readable. */
    void applyPalette() {
        push();
        fallback.setShader(new LinearGradient(0, 0, 0, h, palette.color(1), palette.color(0), Shader.TileMode.CLAMP));
        float r = Math.max(w, h) * 0.65f;
        glowA.setShader(new RadialGradient(0, 0, r, Ui.alpha(palette.color(2), 0.55f), 0x00000000, Shader.TileMode.CLAMP));
        glowB.setShader(new RadialGradient(0, 0, r * 0.8f, Ui.alpha(palette.color(1), 0.5f), 0x00000000, Shader.TileMode.CLAMP));
    }

    private void push() {
        dim = Math.min(0.6f, baseDim + Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f)));
        if (aurora == null) return;
        aurora.setFloatUniform("speed", speed);
        aurora.setFloatUniform("dim", dim);
        palette.apply(aurora);
        advance(time);
    }

    /** Moves the aurora to `seconds`: redraws the layer on the next draw, recomputes the glass's frosted aurora now. */
    void advance(float seconds) {
        time = seconds;
        if (aurora == null) return;
        aurora.setFloatUniform("t", seconds);
        if (!imageFrost || !imageGlass) {
            BitmapShader s = frost.render(seconds, speed, dim, palette.c, (float) w / h);
            if (!imageFrost) glass.setInputShader("frost", s);
            if (!imageGlass) {
                liquid.setInputShader("imageA", s);
                liquid.setInputShader("imageB", s);
                liquid.setFloatUniform("mixB", 0);
                liquid.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        auroraDirty = true;
    }

    /** The liquid glass now shows this photo (Backdrop.GW × GH, slightly blurred). */
    void setGlassImage(int[] pixels) {
        setGlassImages(pixels, null);
    }

    /** Photo transition: both photos in the glass, blended by setGlassMix (0 = the first one). */
    void setGlassImages(int[] from, int[] to) {
        imageGlass = true;
        glassA = fill(glassA, from);
        if (to != null) glassB = fill(glassB, to);
        // A shader per bitmap, made once: a bitmap filled again later is read with its new pixels.
        if (lensOfA != glassA) {
            lensOfA = glassA;
            lensA = new BitmapShader(glassA, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        if (glassB != null && lensOfB != glassB) {
            lensOfB = glassB;
            lensB = new BitmapShader(glassB, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        lensMixing = to != null;
        glassMix = 0;
        if (liquid == null) return;
        glassShaderA = linear(glassA);
        liquid.setInputShader("imageA", glassShaderA);
        liquid.setInputShader("imageB", to != null ? linear(glassB) : glassShaderA);
        liquid.setFloatUniform("mixB", 0);
        liquid.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        glassMix = Math.max(0, Math.min(1, t));
        if (liquid != null) liquid.setFloatUniform("mixB", glassMix);
    }

    /** The liquid glass goes back to the frosted aurora. */
    void clearGlass() {
        if (!imageGlass) return;
        imageGlass = false;
        advance(time);
    }

    private static Bitmap fill(Bitmap b, int[] pixels) {
        if (b == null) b = Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        b.setPixels(pixels, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        return b;
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    /**
     * Liquid Glass in rectangle `r` (screen coordinates). band and refraction in px (the thick rim and how much it
     * bends the image), specular = rim highlight, alpha = opacity of the whole glass.
     */
    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band, float refraction,
                float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (liquid == null) {
            if (imageGlass && lensA != null) bitmapLiquid(c, r, radius, tint, tintAlpha, band, refraction, specular, alpha);
            else glass(c, r, radius, tint, Math.max(0.5f, tintAlpha), 0, 0, lift, 1, alpha);
            return;
        }
        liquid.setFloatUniform("center", r.centerX(), r.centerY());
        liquid.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquid.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquid.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        liquid.setFloatUniform("lift", lift);
        liquid.setFloatUniform("band", Math.max(1, band));
        liquid.setFloatUniform("refraction", refraction);
        liquid.setFloatUniform("specular", specular);
        liquidPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, liquidPaint);
        liquidPaint.setAlpha(255);
    }

    /**
     * Liquid glass from bitmaps (Android 11/12): the photo behind magnified about the center (the lens) and more in
     * the rim band, where the edge bends the light from further out; then a light tint, the top sheen and a lit rim.
     */
    private void bitmapLiquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float band, float refraction,
                              float specular, float alpha) {
        float half = Math.max(1, Math.min(r.width(), r.height()) / 2);
        float rad = Math.min(radius, half);
        int a = Math.round(255 * Math.min(1, alpha));
        float lens = 1 + Math.min(0.06f, refraction / half * 0.3f);
        float b = Math.min(band, half * 0.7f);
        if (b >= 1 && refraction > 0) {
            // The whole panel with the rim's stronger bend, then the center over it with the plain lens: the rim shows
            // around it, with no clip.
            drawLens(c, r, rad, lens + Math.min(0.22f, refraction / half), a);
            rimInner.set(r.left + b, r.top + b, r.right - b, r.bottom - b);
            drawLens(c, rimInner, Math.max(0, rad - b), lens, a, r);
        } else {
            drawLens(c, r, rad, lens, a);
        }
        flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.min(1, tintAlpha) * 0.5f * alpha));
        c.drawRoundRect(r, rad, rad, flatFill);
        shaderMatrix.setScale(1, r.height());
        shaderMatrix.postTranslate(0, r.top);
        sheenUnit.setLocalMatrix(shaderMatrix);
        sheenPaint.setAlpha(a);
        c.drawRoundRect(r, rad, rad, sheenPaint);
        float inset = shinePaint.getStrokeWidth() / 2;
        shineRect.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
        shaderMatrix.setScale(r.width(), r.height());
        shaderMatrix.postTranslate(r.left, r.top);
        shineUnit.setLocalMatrix(shaderMatrix);
        shinePaint.setAlpha(Math.round(255 * Math.min(1, (0.35f + specular) * alpha)));
        c.drawRoundRect(shineRect, Math.max(0, rad - inset), Math.max(0, rad - inset), shinePaint);
    }

    /**
     * The photo that is behind `r` on screen (the layer may be moved by the offset), scaled by `scale` about r's
     * center; during a photo change, the next one on top by the mix.
     */
    private void drawLens(Canvas c, RectF r, float rad, float scale, int alpha) {
        drawLens(c, r, rad, scale, alpha, r);
    }

    /** Draws in `r`, with the lens centered on `around` (the whole panel, when `r` is only its center). */
    private void drawLens(Canvas c, RectF r, float rad, float scale, int alpha, RectF around) {
        lensMatrix.setScale((float) w / Backdrop.GW, (float) h / Backdrop.GH);
        lensMatrix.postTranslate(-offsetX, -offsetY);
        lensMatrix.postScale(scale, scale, around.centerX(), around.centerY());
        lensA.setLocalMatrix(lensMatrix);
        lensPaint.setShader(lensA);
        lensPaint.setAlpha(alpha);
        c.drawRoundRect(r, rad, rad, lensPaint);
        if (lensMixing && lensB != null && glassMix > 0.004f) {
            lensB.setLocalMatrix(lensMatrix);
            lensPaint.setShader(lensB);
            lensPaint.setAlpha(Math.round(alpha * glassMix));
            c.drawRoundRect(r, rad, rad, lensPaint);
        }
        lensPaint.setShader(null);
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        imageFrost = true;
        if (glass == null) return;
        BitmapShader s = new BitmapShader(frostImage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        glass.setInputShader("frost", s);
        setFrostScale();
    }

    /** Back to the frosted aurora. */
    void clearFrost() {
        if (!imageFrost) return;
        imageFrost = false;
        setFrostScale();
        advance(time);
    }

    /** The current blurred image (so the bottom drawer can blur the background), or null with the aurora. */
    Bitmap frostImage() {
        return imageFrost ? frostImage : null;
    }

    /** Offset (px) of the layer that will draw glass, if it was moved on screen without being redrawn. */
    void setOffset(float x, float y) {
        offsetX = x;
        offsetY = y;
        if (glass != null) glass.setFloatUniform("offset", x, y);
        if (liquid != null) liquid.setFloatUniform("offset", x, y);
    }

    private void setFrostScale() {
        if (glass == null) return;
        if (imageFrost) glass.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glass.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    void drawBackground(Canvas c) {
        if (aurora == null) {
            if (fallback.getShader() == null) applyPalette();
            c.drawRect(0, 0, w, h, fallback);
            float t = time * 0.07f * Math.max(speed, 0.2f);
            drawGlow(c, glowA, w * (0.25f + 0.08f * (float) Math.sin(t)), h * (0.2f + 0.08f * (float) Math.cos(t * 1.3f)));
            drawGlow(c, glowB, w * (0.8f + 0.07f * (float) Math.cos(t * 0.8f)), h * (0.85f + 0.07f * (float) Math.sin(t * 1.1f)));
            return;
        }
        if (auroraDirty) {
            RecordingCanvas rc = auroraNode.beginRecording(lw, lh);
            try {
                rc.drawPaint(auroraPaint);
            } finally {
                auroraNode.endRecording();
            }
            auroraDirty = false;
        }
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        c.drawRenderNode(auroraNode);
        c.restore();
    }

    private void drawGlow(Canvas c, Paint p, float x, float y) {
        shaderMatrix.setTranslate(x, y);
        p.getShader().setLocalMatrix(shaderMatrix);
        c.drawRect(0, 0, w, h, p);
    }

    /** Plain glass for the TVs without AGSL: tint, a soft sheen from the top and a rim that is lit at the top. */
    private void flatGlass(Canvas c, RectF r, float radius, int tint, float tintAlpha, float body, float alpha) {
        if (body > 0) {
            flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.max(0.35f, tintAlpha) * alpha));
            c.drawRoundRect(r, radius, radius, flatFill);
            shaderMatrix.setScale(1, r.height());
            shaderMatrix.postTranslate(0, r.top);
            sheenUnit.setLocalMatrix(shaderMatrix);
            sheenPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            c.drawRoundRect(r, radius, radius, sheenPaint);
        }
        shaderMatrix.setScale(1, r.height());
        shaderMatrix.postTranslate(0, r.top);
        rimUnit.setLocalMatrix(shaderMatrix);
        rimPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRoundRect(r, radius, radius, rimPaint);
    }

    /**
     * Glass in rectangle `r` (screen coordinates, no canvas transform: the shader uses the real position).
     * body = 1: full glass; body = 0: just the light rim, on top of something already drawn.
     */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    /** Same, with an opacity (0 to 1) for the whole glass. */
    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha, float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glass == null) {
            flatGlass(c, r, radius, tint, tintAlpha, body, alpha);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glass.setFloatUniform("center", r.centerX(), r.centerY());
        glass.setFloatUniform("halfSize", hw, hh);
        glass.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glass.setFloatUniform("radius", radius);
        glass.setFloatUniform("top", r.top);
        glass.setFloatUniform("depth", 1 / Math.max(inner * 0.6f, 1), Math.min(24, inner * 0.5f), 1 / Math.max(inner * 0.9f, 1), 1 / Math.max(r.height(), 1));
        glass.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f, tintAlpha);
        glass.setFloatUniform("glow", Color.red(glow) / 255f, Color.green(glow) / 255f, Color.blue(glow) / 255f, glowAlpha);
        glass.setFloatUniform("lift", lift);
        glass.setFloatUniform("body", body);
        glassPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, glassPaint);
        glassPaint.setAlpha(255);
    }
}
