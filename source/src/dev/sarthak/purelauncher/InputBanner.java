package dev.sarthak.purelauncher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/** Banner artwork for an HDMI input tile: gradient, input glyph, the source's name and an "HDMI n" chip. */
final class InputBanner extends Drawable {
    private final String title, chip;
    private final int accent;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);

    InputBanner(String title, String chip, int accent) {
        this.title = title;
        this.chip = chip;
        this.accent = accent;
        ink.setColor(Color.WHITE);
        ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chipBg.setColor(0x33FFFFFF);
        glyph.setColor(Color.WHITE);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        int dark = Color.rgb(Color.red(accent) / 6 + 14, Color.green(accent) / 6 + 16, Color.blue(accent) / 6 + 22);
        bg.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{blend(accent, dark, 0.55f), dark}, null, Shader.TileMode.CLAMP));
        c.drawRect(b, bg);

        // input glyph: a screen outline with an arrow entering it
        float g = h * 0.22f, gx = b.left + w * 0.09f, gy = b.top + h * 0.17f;
        glyph.setStrokeWidth(Math.max(2f, g * 0.1f));
        RectF scr = new RectF(gx + g * 0.25f, gy, gx + g * 1.35f, gy + g * 0.85f);
        c.drawRoundRect(scr, g * 0.1f, g * 0.1f, glyph);
        Path arrow = new Path();
        float ay = gy + g * 0.425f;
        arrow.moveTo(gx, ay); arrow.lineTo(gx + g * 0.8f, ay);
        arrow.moveTo(gx + g * 0.6f, ay - g * 0.2f); arrow.lineTo(gx + g * 0.8f, ay); arrow.lineTo(gx + g * 0.6f, ay + g * 0.2f);
        c.drawPath(arrow, glyph);

        // title
        ink.setTextSize(h * 0.2f);
        String t = title;
        while (ink.measureText(t) > w * 0.84f && t.length() > 3) t = t.substring(0, t.length() - 2) + "…";
        c.drawText(t, b.left + w * 0.09f, b.top + h * 0.7f, ink);

        // chip
        ink.setTextSize(h * 0.11f);
        float cw = ink.measureText(chip) + h * 0.14f, ch = h * 0.17f;
        float cx = b.left + w * 0.09f, cy = b.top + h * 0.78f;
        c.drawRoundRect(new RectF(cx, cy, cx + cw, cy + ch), ch / 2, ch / 2, chipBg);
        c.drawText(chip, cx + h * 0.07f, cy + ch * 0.72f, ink);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * t + Color.red(b) * (1 - t)),
                (int) (Color.green(a) * t + Color.green(b) * (1 - t)),
                (int) (Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    @Override public ConstantState getConstantState() {
        return new ConstantState() {
            @Override public Drawable newDrawable() { return new InputBanner(title, chip, accent); }
            @Override public int getChangingConfigurations() { return 0; }
        };
    }
}
