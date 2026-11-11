package dev.sarthak.purelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Random;

/** Visual building blocks for the Noir look. All drawing is local; nothing here touches data or the network. */
final class Fx {
    private Fx() { }

    /** Coloured focus shadow (API 28+); silently ignored on older Android. */
    static void shadowColor(View v, int color) {
        try {
            Method spot = View.class.getMethod("setOutlineSpotShadowColor", int.class);
            Method amb = View.class.getMethod("setOutlineAmbientShadowColor", int.class);
            spot.invoke(v, color);
            amb.invoke(v, color);
        } catch (Throwable ignored) { }
    }

    /** Tiled film-grain texture. */
    static Drawable grain() {
        final int s = 96;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Random r = new Random(7);
        int[] px = new int[s * s];
        for (int i = 0; i < px.length; i++) { int g = r.nextInt(256); px[i] = Color.argb(255, g, g, g); }
        b.setPixels(px, 0, s, 0, 0, s, s);
        final Paint p = new Paint();
        p.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        return new Drawable() {
            @Override public void draw(Canvas c) { c.drawRect(getBounds(), p); }
            @Override public void setAlpha(int a) { p.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** Small, fast box blur on a downscaled bitmap (used for the frosted menu backdrop). */
    static Bitmap blurredSnapshot(View root, int downscale) {
        int w = Math.max(1, root.getWidth() / downscale), h = Math.max(1, root.getHeight() / downscale);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(1f / downscale, 1f / downscale);
        root.draw(c);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) { boxH(px, w, h, 3); boxV(px, w, h, 3); }
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    private static void boxH(int[] px, int w, int h, int r) {
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int rr = 0, gg = 0, bb = 0, n = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++) {
                    int p = px[o + k]; rr += (p >> 16) & 255; gg += (p >> 8) & 255; bb += p & 255; n++;
                }
                row[x] = 0xFF000000 | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
            System.arraycopy(row, 0, px, o, w);
        }
    }

    private static void boxV(int[] px, int w, int h, int r) {
        int[] col = new int[h];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int rr = 0, gg = 0, bb = 0, n = 0;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++) {
                    int p = px[k * w + x]; rr += (p >> 16) & 255; gg += (p >> 8) & 255; bb += p & 255; n++;
                }
                col[y] = 0xFF000000 | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
            for (int y = 0; y < h; y++) px[y * w + x] = col[y];
        }
    }

    /**
     * Glass finish for a tile, used as its foreground: a gloss from the top-left, a hairline rim,
     * a brighter rim when focused, and a light sweep that runs once each time the tile gains focus.
     */
    static final class GlassOverlay extends Drawable {
        private final float radius, density;
        private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean focused;
        private float sweep = -1f; // -1 = idle, 0..1 = position of the light band

        GlassOverlay(float radiusPx, float density) {
            this.radius = radiusPx;
            this.density = density;
            rim.setStyle(Paint.Style.STROKE);
        }

        void setFocused(boolean f) { focused = f; invalidateSelf(); }
        void setSweep(float s) { sweep = s; invalidateSelf(); }

        @Override protected void onBoundsChange(Rect b) {
            gloss.setShader(new LinearGradient(b.left, b.top, b.left + b.width() * 0.55f, b.top + b.height() * 0.9f,
                    new int[]{0x3DFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.62f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            r.set(b);
            c.drawRoundRect(r, radius, radius, gloss);
            if (sweep >= 0f && sweep <= 1f) {
                float w = b.width(), band = w * 0.45f, x = b.left - band + (w + band * 2) * sweep;
                sheen.setShader(new LinearGradient(x, b.top, x + band, b.top + b.height() * 0.35f,
                        new int[]{0x00FFFFFF, 0x6BFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                int save = c.save();
                Path clip = new Path();
                clip.addRoundRect(r, radius, radius, Path.Direction.CW);
                c.clipPath(clip);
                c.drawRect(r, sheen);
                c.restoreToCount(save);
            }
            float sw = (focused ? 1.6f : 1f) * density;
            rim.setStrokeWidth(sw);
            rim.setColor(focused ? 0x99FFFFFF : 0x1FFFFFFF);
            r.inset(sw / 2, sw / 2);
            c.drawRoundRect(r, radius, radius, rim);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** The ghosted title behind the clock: the app's name in huge heavy type, fading out in an ellipse. */
    static final class HeroView extends View {
        private String text;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        HeroView(Context c) {
            super(c);
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            ink.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
            ink.setTextAlign(Paint.Align.CENTER);
            ink.setLetterSpacing(-0.03f);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        /** colour: the app's accent, lifted so it reads on black. */
        void setTitle(String t, int color) {
            text = t;
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hsv[1] = Math.max(hsv[1], 0.7f);
            hsv[2] = Math.max(hsv[2], 0.9f);
            ink.setColor(Color.HSVToColor(hsv));
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            RadialGradient g = new RadialGradient(w / 2f, h / 2f, w / 2f,
                    new int[]{Color.BLACK, Color.BLACK, Color.TRANSPARENT}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(1f, (float) h / w * 1.35f, w / 2f, h / 2f);
            g.setLocalMatrix(m);
            mask.setShader(g);
        }

        @Override protected void onDraw(Canvas c) {
            if (text == null || text.isEmpty()) return;
            int w = getWidth(), h = getHeight();
            float size = h * 0.56f;
            ink.setTextSize(size);
            float tw = ink.measureText(text);
            if (tw > w * 0.92f) { size *= (w * 0.92f) / tw; ink.setTextSize(size); }
            bounds.set(0, 0, w, h);
            int save = c.saveLayer(bounds, null);
            Paint.FontMetrics fm = ink.getFontMetrics();
            c.drawText(text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, ink);
            c.drawRect(bounds, mask);
            c.restoreToCount(save);
        }
    }

    /** Mirror image of a tile's artwork, fading out downward, drawn under dock tiles. */
    static final class ReflectionView extends View {
        private final Drawable art;
        private final int bg;
        private final float radius;
        private final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int tileH;

        ReflectionView(Context c, Drawable art, int bg, float radiusPx) {
            super(c);
            this.art = art == null ? null : (art.getConstantState() != null ? art.getConstantState().newDrawable(c.getResources()).mutate() : art);
            this.bg = bg;
            this.radius = radiusPx;
            fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            fill.setColor(bg);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        void setTileHeight(int h) { tileH = h; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            fade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x38000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || tileH == 0) return;
            rect.set(0, 0, w, h);
            int save = c.saveLayer(rect, null);
            c.save();
            c.scale(1f, -1f, 0, tileH / 2f); // tile bottom (y = tileH) lands at the top of this view
            rect.set(0, 0, w, tileH);
            c.drawRoundRect(rect, radius, radius, fill);
            if (art != null) {
                int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
                if (iw > 0 && ih > 0) {
                    float s = Math.max((float) w / iw, (float) tileH / ih);
                    int dw = Math.round(iw * s), dh = Math.round(ih * s);
                    art.setBounds((w - dw) / 2, (tileH - dh) / 2, (w + dw) / 2, (tileH + dh) / 2);
                } else art.setBounds(0, 0, w, tileH);
                art.draw(c);
            }
            c.restore();
            rect.set(0, 0, w, h);
            c.drawRect(rect, fade);
            c.restoreToCount(save);
        }
    }

    /**
     * Static backdrop: three soft colour pools, film grain and a vignette, drawn once into a GPU layer.
     * Only redrawn when the accent colour changes, so it costs nothing while you browse.
     */
    static final class Backdrop extends View {
        private final Paint pool = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grainP = new Paint();
        private final Paint vig = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = 0x3A6EA5;

        Backdrop(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            final int s = 96;
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Random r = new Random(7);
            int[] px = new int[s * s];
            for (int i = 0; i < px.length; i++) { int g = r.nextInt(256); px[i] = Color.argb(10, g, g, g); }
            b.setPixels(px, 0, s, 0, 0, s, s);
            grainP.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        void setColor(int rgb) { color = rgb & 0xFFFFFF; invalidate(); }

        private static int mix(int a, int b, float t) {
            int r = (int) (((a >> 16) & 255) * t + ((b >> 16) & 255) * (1 - t));
            int g = (int) (((a >> 8) & 255) * t + ((b >> 8) & 255) * (1 - t));
            int bl = (int) ((a & 255) * t + (b & 255) * (1 - t));
            return (r << 16) | (g << 8) | bl;
        }

        private void blob(Canvas c, float cx, float cy, float rx, float ry, int rgb, int alpha) {
            pool.setShader(new RadialGradient(0, 0, 1f, new int[]{(alpha << 24) | rgb, rgb}, null, Shader.TileMode.CLAMP));
            c.save();
            c.translate(cx, cy);
            c.scale(rx, ry);
            c.drawCircle(0, 0, 1f, pool);
            c.restore();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            RadialGradient g = new RadialGradient(w * 0.5f, h * 0.36f, w * 0.62f,
                    new int[]{0x00000000, 0x00000000, 0x14000000, 0x99000000}, new float[]{0f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP);
            vig.setShader(g);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(Color.BLACK);
            blob(c, w * 0.18f, h * 0.12f, w * 0.38f, h * 0.62f, color, 0x66);
            blob(c, w * 0.86f, h * 0.30f, w * 0.30f, h * 0.50f, mix(color, 0x2A3CFF, 0.55f), 0x48);
            blob(c, w * 0.50f, h * 1.02f, w * 0.45f, h * 0.40f, mix(color, 0x0B1A2E, 0.3f), 0x52);
            c.drawRect(0, 0, w, h, grainP);
            c.drawRect(0, 0, w, h, vig);
        }
    }
}
