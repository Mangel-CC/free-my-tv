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
    private final RectF shineRect = new RectF();
    private BitmapShader lensA, lensB;
    private Bitmap lensOfA, lensOfB;
    // The sharp background photo (Backdrop.W × H), when there is one: the glass reads it, clear like Apple's, with just
    // a touch of the blurred copy on top (FROST). Without it, the blurred copy alone.
    private static final float FROST = 0.25f;
    // The lens is a mesh (drawVertices, GPU from API 29): each vertex reads the photo from where a curved glass would
    // bend it, so straight lines curve near the edge as through a real lens. Denser toward the edges, where it bends.
    // Coarse on purpose: on this Android 11 GPU a ~13×6 dock mesh draws in 6 ms a frame, ~20×8 already took 19 ms.
    private static final int MESH_MAX = 48;
    private final float[] meshVerts = new float[(MESH_MAX + 1) * (MESH_MAX + 1) * 2];
    private final float[] meshTexs = new float[meshVerts.length];
    private final short[] meshIdx = new short[MESH_MAX * MESH_MAX * 6];
    private final float[] meshCol = new float[MESH_MAX + 1];
    private int meshFloats, meshIndices;
    // Tinted glass: the photo's own color, saturated (the glass and its rim take the picture's hue, as tinted glass
    // does). 0 = no color, when the picture is grey.
    private static final float TINT_ALPHA = 0.22f, COLOR_RIM_ALPHA = 0.6f;
    private int glassColor;
    private final float[] hsv = new float[3];
    private final Paint colorRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BitmapShader photoA, photoB;
    private Bitmap photoOfA, photoOfB;
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
        colorRim.setStyle(Paint.Style.STROKE);
        colorRim.setStrokeWidth(Math.max(2f, w / 1920f * 3f));
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

    /**
     * The sharp photos behind the glass (Android 11/12): `from` alone, or `from` fading into `to` by setGlassMix.
     * null: no sharp photo (the blurred copy is used).
     */
    void setPhotos(Bitmap from, Bitmap to) {
        if (from != photoOfA) {
            photoOfA = from;
            photoA = from == null ? null : new BitmapShader(from, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        if (to != photoOfB) {
            photoOfB = to;
            photoB = to == null ? null : new BitmapShader(to, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
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
        float hw = Math.max(1, r.width() / 2), hh = Math.max(1, r.height() / 2), half = Math.min(hw, hh);
        float rad = Math.min(radius, half);
        int a = Math.round(255 * Math.min(1, alpha));
        // In pixels at the panel's edge, the same on every side whatever the panel's size: the body draws the picture
        // `grow` toward its center (a slight magnification), the very edge shows what is `pull` outside the glass.
        // (One scale for both axes moved a wide panel's ends by ~100 px, which tore the rings apart.)
        float grow = Math.min(refraction * 0.8f, half * 0.25f), pull = Math.min(refraction * 3, half * 0.45f);
        float b = Math.max(1, Math.min(Math.max(band * 2.5f, half * 0.7f), half * 0.95f));
        buildMesh(r, rad, scaleFor(hw, -grow), scaleFor(hh, -grow), b, refraction > 0 ? pull : 0);
        drawLens(c, a, false);
        if (photoA != null) drawLens(c, Math.round(a * FROST), true);
        flatFill.setColor(Ui.alpha(tint | 0xFF000000, Math.min(1, tintAlpha) * 0.5f * alpha));
        c.drawRoundRect(r, rad, rad, flatFill);
        if (glassColor != 0) {
            flatFill.setColor(Ui.alpha(glassColor, TINT_ALPHA * alpha));
            c.drawRoundRect(r, rad, rad, flatFill);
        }
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
        if (glassColor != 0) {
            colorRim.setColor(Ui.alpha(glassColor, COLOR_RIM_ALPHA * alpha));
            c.drawRoundRect(shineRect, Math.max(0, rad - inset), Math.max(0, rad - inset), colorRim);
        }
    }

    /**
     * The glass color for a blurred picture: its average, saturated and brightened, fading out (in the alpha) as the
     * picture gets grey. Smooth in every step: it is computed again on each frame of a photo change, and a threshold
     * there made the color blink off and on while the blend of two hues passed through grey.
     */
    private int tintOf(int[] pixels) {
        long r = 0, g = 0, b = 0;
        for (int p : pixels) {
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            b += p & 0xFF;
        }
        int n = Math.max(1, pixels.length);
        Color.colorToHSV(Color.rgb((int) (r / n), (int) (g / n), (int) (b / n)), hsv);
        float t = Math.max(0, Math.min(1, (hsv[1] - 0.04f) / 0.16f)), strength = t * t * (3 - 2 * t);
        if (strength <= 0) return 0;
        hsv[1] = Math.min(1, 0.25f + hsv[1] * 1.8f);
        hsv[2] = Math.max(0.55f, Math.min(0.9f, hsv[2] * 1.6f));
        return Ui.alpha(Color.HSVToColor(hsv), strength);
    }

    /** The scale about a panel's center that makes its edge, `half` px away, show the picture `px` further out. */
    private static float scaleFor(float half, float px) {
        return half / (half + px);
    }

    /**
     * The lens mesh over panel `r`: a grid, denser toward the edges, where each vertex reads the photo from the point a
     * curved glass would show there. The body is magnified about the center (bodyX/Y, per axis so a wide panel's ends
     * don't move), and within `b` px of the edge the picture is read up to `pull` px further inside, along the
     * edge's normal, with a round-lens profile (steep at the very edge, flat where it meets the body): lines curve
     * there, as through Nuvio's glass. The normal blends smoothly around the corners, so the bend has no seams.
     */
    private void buildMesh(RectF r, float rad, float bodyX, float bodyY, float b, float pull) {
        int cols = Math.max(4, Math.min(MESH_MAX, Math.round(r.width() / 80)));
        int rows = Math.max(4, Math.min(MESH_MAX, Math.round(r.height() / 40)));
        float cx = r.centerX(), cy = r.centerY(), ix = r.width() / 2 - rad, iy = r.height() / 2 - rad;
        // The texture says where to read: the inverse of the picture's scale (bodyX > 1 magnifies).
        float readX = 1 / bodyX, readY = 1 / bodyY;
        for (int i = 0; i <= cols; i++) meshCol[i] = r.left + r.width() * (0.5f - 0.5f * (float) Math.cos(Math.PI * i / cols));
        int v = 0;
        for (int j = 0; j <= rows; j++) {
            float y0 = r.top + r.height() * (0.5f - 0.5f * (float) Math.cos(Math.PI * j / rows));
            for (int i = 0; i <= cols; i++) {
                float x = meshCol[i], y = y0;
                float px = x - cx, py = y - cy, qx = Math.abs(px) - ix, qy = Math.abs(py) - iy;
                float corner = (float) Math.sqrt(qx * qx + qy * qy);
                if (qx > 0 && qy > 0 && corner > rad) {
                    // Outside a rounded corner: onto its arc, so the mesh has the panel's shape and needs no clip
                    // (a curved clip costs a mask on every draw).
                    qx *= rad / corner;
                    qy *= rad / corner;
                    px = Math.signum(px) * (ix + qx);
                    py = Math.signum(py) * (iy + qy);
                    x = cx + px;
                    y = cy + py;
                }
                float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
                float depth = rad - (float) Math.sqrt(ox * ox + oy * oy) - Math.min(Math.max(qx, qy), 0);
                float sx = cx + px * readX, sy = cy + py * readY;
                if (pull > 0 && depth < b) {
                    float nx = Math.max(qx + b, 0), ny = Math.max(qy + b, 0), len = (float) Math.sqrt(nx * nx + ny * ny);
                    if (len > 0) {
                        float t = 1 - Math.max(0, depth) / b;
                        float d = pull * (1 - (float) Math.sqrt(Math.max(0, 1 - t * t))) / len;
                        // Inward: the edge shows what is just inside it, stretched and curved (as Nuvio's and
                        // Apple's glass). Outward it read as a compressed strip, a second pane.
                        sx -= Math.signum(px) * nx * d;
                        sy -= Math.signum(py) * ny * d;
                    }
                }
                meshVerts[v] = x;
                meshVerts[v + 1] = y;
                meshTexs[v] = sx;
                meshTexs[v + 1] = sy;
                v += 2;
            }
        }
        meshFloats = v;
        int k = 0;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                short p = (short) (j * (cols + 1) + i), q = (short) (p + cols + 1);
                meshIdx[k++] = p;
                meshIdx[k++] = (short) (p + 1);
                meshIdx[k++] = q;
                meshIdx[k++] = (short) (p + 1);
                meshIdx[k++] = (short) (q + 1);
                meshIdx[k++] = q;
            }
        }
        meshIndices = k;
    }

    /**
     * Draws the lens mesh with the picture behind it on screen (the layer may be moved by the offset): the sharp photo
     * when there is one, else the blurred copy; `frost` draws the blurred copy (the touch of frost). During a photo
     * change, the next one on top by the mix.
     */
    private void drawLens(Canvas c, int alpha, boolean frost) {
        if (photoA != null && !frost) drawSource(c, alpha, photoA, photoB, Backdrop.W, Backdrop.H);
        else drawSource(c, alpha, lensA, lensMixing ? lensB : null, Backdrop.GW, Backdrop.GH);
        lensPaint.setShader(null);
    }

    /** One picture (`sw` × `sh`, stretched to the screen) through the mesh, and `next` over it by the mix. */
    private void drawSource(Canvas c, int alpha, BitmapShader first, BitmapShader next, int sw, int sh) {
        if (first == null || alpha <= 0) return;
        lensMatrix.setScale((float) w / sw, (float) h / sh);
        lensMatrix.postTranslate(-offsetX, -offsetY);
        first.setLocalMatrix(lensMatrix);
        lensPaint.setShader(first);
        lensPaint.setAlpha(alpha);
        c.drawVertices(Canvas.VertexMode.TRIANGLES, meshFloats, meshVerts, 0, meshTexs, 0, null, 0, meshIdx, 0,
                meshIndices, lensPaint);
        if (next != null && glassMix > 0.004f) {
            next.setLocalMatrix(lensMatrix);
            lensPaint.setShader(next);
            lensPaint.setAlpha(Math.round(alpha * glassMix));
            c.drawVertices(Canvas.VertexMode.TRIANGLES, meshFloats, meshVerts, 0, meshTexs, 0, null, 0, meshIdx, 0,
                    meshIndices, lensPaint);
        }
    }

    /** The glass now reads the blurred background image (Backdrop.FW × FH, ARGB). */
    void setFrost(int[] pixels) {
        if (frostImage == null) frostImage = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostImage.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        imageFrost = true;
        glassColor = tintOf(pixels);
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
