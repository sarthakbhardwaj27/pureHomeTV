package dev.sarthak.purelauncher;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Apple-style translucent menu drawn inside the activity (no separate window):
 * a blurred, dimmed snapshot of the home screen behind a frosted panel.
 * Either a centred card or a full-height sheet sliding in from the right.
 */
final class GlassMenu {

    static final class Item {
        final String label, sub;
        final int icon;
        final Runnable action;
        Item(String label, int icon, String sub, Runnable action) { this.label = label; this.icon = icon; this.sub = sub; this.action = action; }
    }

    private final HomeActivity act;
    private final FrameLayout root;
    private final ViewGroup content;
    private View overlay;
    private View returnFocus;

    GlassMenu(HomeActivity act, FrameLayout root, ViewGroup content) {
        this.act = act;
        this.root = root;
        this.content = content;
    }

    boolean isOpen() { return overlay != null; }

    void show(String title, List<Item> items, boolean sheet) {
        if (overlay != null) dismissNow();
        returnFocus = act.getCurrentFocus();
        float d = act.getResources().getDisplayMetrics().density;

        FrameLayout ov = new FrameLayout(act);
        ov.setClickable(true);
        // backdrop: blurred snapshot + dim
        try {
            Bitmap snap = Fx.blurredSnapshot(root, 12);
            BitmapDrawable bd = new BitmapDrawable(act.getResources(), snap);
            bd.setFilterBitmap(true);
            Drawable dim = new ColorDrawable(sheet ? 0x59000000 : 0x47000000);
            ov.setBackground(new LayerDrawable(new Drawable[]{bd, dim}));
        } catch (Throwable t) {
            ov.setBackgroundColor(0xB3000000);
        }

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        int ph = Math.round(10 * d);
        panel.setPadding(ph, Math.round((sheet ? 24 : 18) * d), ph, ph);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8C4A4E5A, 0x99202229});
        glass.setCornerRadius((sheet ? 30 : 26) * d);
        glass.setStroke(Math.max(1, Math.round(d)), 0x2EFFFFFF);
        panel.setBackground(glass);
        panel.setElevation(30 * d);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(sheet ? Color.WHITE : 0x9EFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sheet ? 24 : 14);
        t.setTypeface(Typeface.create(sheet ? "sans-serif-light" : "sans-serif-medium", Typeface.NORMAL));
        if (!sheet) t.setLetterSpacing(0.04f);
        t.setPadding(Math.round(14 * d), 0, Math.round(14 * d), Math.round((sheet ? 14 : 10) * d));
        panel.addView(t);

        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        View first = null;
        for (final Item it : items) {
            View row = row(it, d);
            list.addView(row);
            if (first == null) first = row;
        }
        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.addView(list);
        panel.addView(sv);

        FrameLayout.LayoutParams plp;
        if (sheet) {
            plp = new FrameLayout.LayoutParams(Math.round(340 * d), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
            int m = Math.round(24 * d);
            plp.setMargins(m, m, m, m);
        } else {
            plp = new FrameLayout.LayoutParams(Math.round(400 * d), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        }
        ov.addView(panel, plp);

        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(ov, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = ov;

        if (act.motion()) {
            ov.setAlpha(0f);
            ov.animate().alpha(1f).setDuration(140).start();
            if (sheet) { panel.setTranslationX(60 * d); panel.animate().translationX(0).setDuration(220).setInterpolator(new DecelerateInterpolator(2.2f)).start(); }
            else { panel.setScaleX(0.94f); panel.setScaleY(0.94f); panel.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new OvershootInterpolator(1.1f)).start(); }
        }
        if (first != null) first.requestFocus();
    }

    private View row(final Item it, float d) {
        final LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int px = Math.round(14 * d), py = Math.round(11 * d);
        r.setPadding(px, py, px, py);
        r.setFocusable(true);
        r.setContentDescription(it.label);
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(15 * d);
        pill.setColor(Color.TRANSPARENT);
        r.setBackground(pill);

        final ImageView iv = new ImageView(act);
        if (it.icon != 0) {
            iv.setImageResource(it.icon);
            iv.setImageTintList(ColorStateList.valueOf(0xE0FFFFFF));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Math.round(22 * d), Math.round(22 * d));
            ilp.rightMargin = Math.round(14 * d);
            r.addView(iv, ilp);
        }
        final TextView label = new TextView(act);
        label.setText(it.label);
        label.setTextColor(0xF0FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        r.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView sub = new TextView(act);
        if (it.sub != null && !it.sub.isEmpty()) {
            sub.setText(it.sub);
            sub.setTextColor(0x99FFFFFF);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            r.addView(sub);
        }
        r.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                pill.setColor(has ? 0xF0FFFFFF : Color.TRANSPARENT);
                int ink = has ? 0xFF0B0D11 : 0xF0FFFFFF;
                label.setTextColor(ink);
                sub.setTextColor(has ? 0x990B0D11 : 0x99FFFFFF);
                iv.setImageTintList(ColorStateList.valueOf(has ? 0xFF0B0D11 : 0xE0FFFFFF));
                if (act.motion()) v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(140)
                        .setInterpolator(new OvershootInterpolator(1.0f)).start();
            }
        });
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                if (it.action != null) it.action.run();
            }
        });
        r.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { dismiss(); return true; }
                return false;
            }
        });
        return r;
    }

    void dismiss() {
        final View ov = overlay;
        if (ov == null) return;
        overlay = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (returnFocus != null && returnFocus.isAttachedToWindow()) returnFocus.requestFocus();
        if (act.motion()) ov.animate().alpha(0f).setDuration(110).withEndAction(new Runnable() {
            @Override public void run() { root.removeView(ov); }
        }).start();
        else root.removeView(ov);
    }

    private void dismissNow() {
        if (overlay != null) { root.removeView(overlay); overlay = null; }
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }
}
