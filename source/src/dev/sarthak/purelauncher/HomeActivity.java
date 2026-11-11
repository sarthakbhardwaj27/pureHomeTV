package dev.sarthak.purelauncher;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Home, "Noir" design.
 * Pure black, a large thin clock, the focused app's name, a mirrored glass shelf of favourites and an app grid.
 * Behind it: a faint aurora in the focused app's colour, a ghosted copy of its artwork, film grain and a vignette.
 * Plain framework views, no libraries, no permissions.
 */
public class HomeActivity extends Activity {

    private static final int MUTED = 0xFF7A828E;
    private static final int DIM = 0xFF5B6370;
    private static final int TILE = 0xFF14181F;
    private static final float HERO_ALPHA = 0.38f;

    private static List<AppStore.App> cache;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Interpolator spring = new OvershootInterpolator(0.8f);
    private final Interpolator glide = new DecelerateInterpolator(2.2f);
    private AppStore store;
    private boolean motion = true;

    private FrameLayout root, content;
    private GlideScrollView scroll;
    private View header;
    private Fx.Backdrop backA, backB;
    private Fx.HeroView hero;
    private FrameLayout heroFrame;
    private int accent = AppStore.DEFAULT_ACCENT;
    private ValueAnimator accentAnim;
    private ObjectAnimator scrollAnim;
    private TextView clock, date, shelfTitle, toastView, hintView, appsTitleView;
    private View blackout;
    private static boolean introDone;
    private boolean introRunning, busy;
    private LinearLayout dock, grid;
    private View firstTile, lastFocused;
    private String heroPkg;
    private boolean entered, receiversOn;
    private GlassMenu menu;
    private final Runnable hideToast = new Runnable() {
        @Override public void run() { if (toastView != null) toastView.animate().alpha(0f).translationY(dp(16)).setDuration(220).start(); }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    /**
     * Power on from standby: the launcher process is still alive, so replay WELCOME.
     * Screen off: lay the black intro frame down now, so waking up never flashes the old home screen first.
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!motion) return;
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                boolean wasHome = resumed || android.os.SystemClock.uptimeMillis() - pausedAt < 2000;
                if (wasHome && introOverlay == null) {
                    if (menu != null && menu.isOpen()) menu.dismiss();
                    playIntro(false);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                screenOnAt = android.os.SystemClock.uptimeMillis();
                // give the panel a moment to light up before the line of light starts
                ui.postDelayed(new Runnable() { @Override public void run() { if (resumed) startIntroIfPending(); } }, 700);
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    boolean motion() { return motion; }

    // ================================================================= lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppStore(this);
        motion = readMotionSetting();
        setContentView(buildRoot());
        menu = new GlassMenu(this, root, scroll);
        updateClock();
        if (motion && !introDone) playIntro(true);
        introDone = true;
        if (cache != null) render(cache);
        reload();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf); // protected system broadcasts only

        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, sf); // protected system broadcasts only
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // A prepared intro that never played because the TV woke into another app: drop it rather than
        // surprising you with WELCOME the next time you press Home.
        if (introStarter != null && introOverlay != null && screenOnAt > 0
                && android.os.SystemClock.uptimeMillis() - screenOnAt > 5000) {
            introStarter = null;
            root.removeView(introOverlay);
            introOverlay = null;
            introRunning = false;
            scroll.setAlpha(1f);
        }
        updateClock();
        if (!receiversOn) {
            IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
            tf.addAction(Intent.ACTION_TIME_CHANGED);
            tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(timeReceiver, tf);
            receiversOn = true;
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        resetAfterLaunch();
        if (motion) {
            scroll.setAlpha(0f);
            scroll.setScaleX(1.04f); scroll.setScaleY(1.04f);
            scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(glide).start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        pausedAt = android.os.SystemClock.uptimeMillis();
        if (receiversOn) { unregisterReceiver(timeReceiver); receiversOn = false; }
        if (menu != null && menu.isOpen()) menu.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        io.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) { // Home pressed while already home
        super.onNewIntent(intent);
        if (menu.isOpen()) menu.dismiss();
        goTop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (busy || introRunning) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (menu.isOpen()) { menu.dismiss(); return; }
        goTop();
    }

    // ================================================================= startup and launch animations

    /** ~2 s on cold start: a line of light, WELCOME coming into focus, then the home screen rises in. */
    private View introOverlay;
    private boolean resumed;
    private long pausedAt, screenOnAt;

    private void playIntro(boolean safetyNet) {
        if (introOverlay != null) return;
        introRunning = true;
        final FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(Color.BLACK);
        ov.setClickable(true);
        introOverlay = ov;
        if (safetyNet) ui.postDelayed(new Runnable() { @Override public void run() { startIntroIfPending(); } }, 2500);

        final View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setShape(GradientDrawable.OVAL);
        g.setGradientRadius(dp(260));
        g.setColors(new int[]{0x8C3A6EA5, 0x003A6EA5});
        glow.setBackground(g);
        glow.setAlpha(0f);
        ov.addView(glow, new FrameLayout.LayoutParams(dp(560), dp(300), Gravity.CENTER));

        final TextView mark = text(46, Color.WHITE, "sans-serif-thin");
        mark.setText("WELCOME");
        mark.setLetterSpacing(1.2f);
        mark.setAlpha(0f);
        ov.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        final View line = new View(this);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF});
        line.setBackground(lg);
        line.setScaleX(0f);
        line.setAlpha(0f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(380), Math.max(1, dp(1.2f)), Gravity.CENTER);
        llp.topMargin = dp(80);
        ov.addView(line, llp);

        root.addView(ov, match());
        scroll.setAlpha(0f);
        // The overlay sits black until the window is actually on screen; on a cold start the TV
        // shows its own loading frame first, so timing the animation from onCreate would waste it.
        introStarter = new Runnable() {
            @Override public void run() {

                line.animate().scaleX(1f).alpha(1f).setDuration(760).setInterpolator(glide).start();
                ObjectAnimator lineOut = ObjectAnimator.ofFloat(line, "alpha", 1f, 0f);
                lineOut.setStartDelay(900); lineOut.setDuration(500); lineOut.start();

                glow.setScaleX(0.6f); glow.setScaleY(0.6f);
                glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(800).setInterpolator(glide).start();
                ObjectAnimator glowOut = ObjectAnimator.ofFloat(glow, "alpha", 1f, 0f);
                glowOut.setStartDelay(1100); glowOut.setDuration(700); glowOut.start();
                for (String prop : new String[]{"scaleX", "scaleY"}) {
                    ObjectAnimator grow = ObjectAnimator.ofFloat(glow, prop, 1f, 1.6f);
                    grow.setStartDelay(1100); grow.setDuration(700); grow.start();
                }

                ValueAnimator track = ValueAnimator.ofFloat(1.2f, 0.5f);
                track.setStartDelay(300);
                track.setDuration(700);
                track.setInterpolator(glide);
                track.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        float t = a.getAnimatedFraction();
                        mark.setLetterSpacing((float) a.getAnimatedValue());
                        mark.setAlpha(t);
                    }
                });
                track.start();
                mark.animate().alpha(0f).setStartDelay(1500).setDuration(350).start();

                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        introRunning = false;
                        scroll.setAlpha(1f);
                        if (cache != null) { entrance(); entered = true; }
                        View cur = getCurrentFocus();
                        boolean onSomething = cur != null && (cur.getTag() instanceof AppStore.App || isInside(cur, header));
                        if (firstTile != null && !onSomething) firstTile.requestFocus();
                    }
                }, 1250);
                ov.animate().alpha(0f).setStartDelay(1450).setDuration(550).withEndAction(new Runnable() {
                    @Override public void run() { root.removeView(ov); if (introOverlay == ov) introOverlay = null; }
                }).start();
    
            }
        };
    }

    private Runnable introStarter;

    private void startIntroIfPending() {
        if (introStarter == null) return;
        Runnable r = introStarter;
        introStarter = null;
        r.run();
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        ui.postDelayed(new Runnable() { @Override public void run() { startIntroIfPending(); } }, 120);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ui.postDelayed(new Runnable() { @Override public void run() { startIntroIfPending(); } }, 350);
    }

    /** ~1 s: the chosen card grows and glows, everything else falls away, fade to black, open the app. */
    private void launchWithAnimation(final View tile, final AppStore.App app) {
        busy = true;
        tile.animate().cancel();
        tile.animate().scaleX(1.32f).scaleY(1.32f).translationZ(dp(40)).setDuration(750).setInterpolator(glide).start();
        for (View t : allTiles()) {
            if (t == tile) continue;
            t.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(300).setInterpolator(glide).start();
        }
        for (View v : new View[]{header, shelfTitle, hintView, appsTitleView})
            v.animate().alpha(0f).setDuration(280).start();
        for (int i = 0; i < dock.getChildCount(); i++) { // reflections
            View slot = dock.getChildAt(i);
            if (slot instanceof ViewGroup && ((ViewGroup) slot).getChildCount() > 1)
                ((ViewGroup) slot).getChildAt(1).animate().alpha(0f).setDuration(200).start();
        }
        final Drawable band = dock.getBackground();
        ValueAnimator bandOut = ValueAnimator.ofInt(255, 0);
        bandOut.setDuration(300);
        bandOut.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { if (band != null) band.setAlpha((int) a.getAnimatedValue()); }
        });
        bandOut.start();
        hero.animate().alpha(0.6f).setDuration(500).start();
        blackout.animate().alpha(1f).setStartDelay(620).setDuration(300).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                safeStart(app.launchIntent());
                ui.postDelayed(new Runnable() { @Override public void run() { if (busy) resetAfterLaunch(); } }, 1500);
            }
        }, 950);
    }

    /** Put everything back exactly as it was (called when we return, or if the app failed to open). */
    private void resetAfterLaunch() {
        busy = false;
        blackout.animate().cancel();
        blackout.setAlpha(0f);
        for (View t : allTiles()) {
            t.animate().cancel();
            t.setAlpha(1f);
            boolean f = t.isFocused();
            t.setScaleX(f ? 1.08f : 1f); t.setScaleY(f ? 1.08f : 1f);
            t.setTranslationZ(f ? dp(14) : 0);
        }
        for (View v : new View[]{header, shelfTitle, hintView, appsTitleView}) { v.animate().cancel(); v.setAlpha(1f); }
        for (int i = 0; i < dock.getChildCount(); i++) {
            View slot = dock.getChildAt(i);
            if (slot instanceof ViewGroup && ((ViewGroup) slot).getChildCount() > 1) ((ViewGroup) slot).getChildAt(1).setAlpha(1f);
        }
        if (dock.getBackground() != null) dock.getBackground().setAlpha(255);
        hero.animate().cancel();
        hero.setAlpha(HERO_ALPHA);
    }

    private List<View> allTiles() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View slot = dock.getChildAt(i);
            if (slot instanceof ViewGroup && ((ViewGroup) slot).getChildCount() > 0) out.add(((ViewGroup) slot).getChildAt(0));
        }
        for (int r = 0; r < grid.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) grid.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) out.add(row.getChildAt(c));
        }
        return out;
    }

    private void goTop() {
        if (firstTile != null) firstTile.requestFocus();
        animateScrollTo(0);
    }

    // ================================================================= layout

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        int W = getResources().getDisplayMetrics().widthPixels;

        // backdrop: two cached layers that cross-fade when the focused app's colour changes
        backA = new Fx.Backdrop(this);
        backB = new Fx.Backdrop(this);
        backA.setColor(accent);
        backB.setAlpha(0f);
        root.addView(backA, match());
        root.addView(backB, match());

        // ghosted artwork of the focused app, behind the clock
        heroFrame = new FrameLayout(this);
        hero = new Fx.HeroView(this);
        hero.setAlpha(0f);
        heroFrame.addView(hero, match());
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(Math.min(dp(840), W), dp(300), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(34);
        root.addView(heroFrame, hlp);

        scroll = new GlideScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false); // focus belongs to the cards, never the scroll container
        scroll.setClipChildren(false);
        root.addView(scroll, match());

        content = new FrameLayout(this);
        content.setClipChildren(false);
        scroll.addView(content);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(64), dp(30), dp(64), dp(120));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        content.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- header: centred clock, quiet icons top-right
        FrameLayout h = new FrameLayout(this);
        LinearLayout timeBox = new LinearLayout(this);
        timeBox.setOrientation(LinearLayout.VERTICAL);
        timeBox.setGravity(Gravity.CENTER_HORIZONTAL);
        clock = text(96, Color.WHITE, "sans-serif-thin");
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        date = text(14, MUTED, "sans-serif");
        date.setLetterSpacing(0.18f);
        timeBox.addView(clock);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        timeBox.addView(date, dlp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = dp(30);
        h.addView(timeBox, tlp);
        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.addView(iconButton(R.drawable.ic_input, "Inputs", new View.OnClickListener() {
            @Override public void onClick(View v) { showInputs(); }
        }));
        icons.addView(iconButton(R.drawable.ic_wifi, "Network", new View.OnClickListener() {
            @Override public void onClick(View v) { safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        }));
        icons.addView(iconButton(R.drawable.ic_settings, "Settings", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        }));
        icons.addView(iconButton(R.drawable.ic_edit, "Edit", new View.OnClickListener() {
            @Override public void onClick(View v) { showEditMenu(); }
        }));
        h.addView(icons, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        header = h;
        col.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- shelf: focused app name
        shelfTitle = text(30, Color.WHITE, "sans-serif-light");
        shelfTitle.setSingleLine(true);
        shelfTitle.setEllipsize(TextUtils.TruncateAt.END);
        shelfTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView hint = text(14, DIM, "sans-serif");
        hintView = hint;
        hint.setText("OK to open  ·  hold OK for options");
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(shelfTitle, marginTop(24));
        col.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- dock: mirrored glass shelf
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        dock.setPadding(dp(22), dp(22), dp(22), dp(16));
        GradientDrawable band = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x10FFFFFF, 0x03FFFFFF});
        band.setStroke(dp(1), 0x14FFFFFF);
        band.setCornerRadius(dp(30));
        dock.setBackground(band);
        LinearLayout.LayoutParams dkl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dkl.topMargin = dp(18);
        col.addView(dock, dkl);

        // ---- grid
        TextView appsTitle = text(13, DIM, "sans-serif-medium");
        appsTitleView = appsTitle;
        appsTitle.setText("ALL APPS");
        appsTitle.setLetterSpacing(0.16f);
        appsTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(appsTitle, marginTop(30));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(16), 0, 0);
        col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- black layer used when an app opens
        blackout = new View(this);
        blackout.setBackgroundColor(Color.BLACK);
        blackout.setAlpha(0f);
        root.addView(blackout, match());

        // ---- glass toast
        toastView = text(15, Color.WHITE, "sans-serif-medium");
        toastView.setPadding(dp(20), dp(11), dp(20), dp(11));
        GradientDrawable tg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xA6383C46, 0xB31E2027});
        tg.setCornerRadius(dp(22));
        tg.setStroke(dp(1), 0x24FFFFFF);
        toastView.setBackground(tg);
        toastView.setAlpha(0f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = dp(34);
        root.addView(toastView, tl);

        root.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override public void onGlobalFocusChanged(View oldF, View newF) { onFocusMoved(newF); }
        });
        return root;
    }

    private void render(List<AppStore.App> apps) {
        String focusedPkg = lastFocused != null && lastFocused.getTag() instanceof AppStore.App
                ? ((AppStore.App) lastFocused.getTag()).pkg : null;
        Map<String, AppStore.App> byPkg = new HashMap<>();
        for (AppStore.App a : apps) byPkg.put(a.pkg, a);
        Set<String> hidden = store.hidden();
        List<String> favs = store.favourites();
        View restore = null;
        int avail = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density) - 128;

        // dock
        dock.removeAllViews();
        firstTile = null;
        List<AppStore.App> favApps = new ArrayList<>();
        for (String p : favs) if (byPkg.containsKey(p)) favApps.add(byPkg.get(p));
        int n = Math.max(4, favApps.size()), gap = 22;
        int w = Math.min(232, (avail - 44 - gap * (n - 1)) / n), hgt = w * 9 / 16;
        for (int i = 0; i < favApps.size(); i++) {
            AppStore.App a = favApps.get(i);
            LinearLayout slot = new LinearLayout(this);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setClipChildren(false);
            View t = tile(a, w, hgt);
            slot.addView(t, new LinearLayout.LayoutParams(dp(w), dp(hgt)));
            Fx.ReflectionView refl = new Fx.ReflectionView(this, a.banner, a.banner != null ? TILE : TILE, dp(18));
            refl.setTileHeight(dp(hgt));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(w), dp(hgt * 0.36f));
            rl.topMargin = dp(8);
            slot.addView(refl, rl);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < favApps.size() - 1) sl.rightMargin = dp(gap);
            dock.addView(slot, sl);
            if (firstTile == null) firstTile = t;
            if (a.pkg.equals(focusedPkg)) restore = t;
        }
        if (favApps.isEmpty()) {
            TextView hint = text(15, MUTED, "sans-serif-light");
            hint.setText("Hold OK on any app below and choose “Add to favourites”.");
            hint.setMinHeight(dp(80));
            hint.setGravity(Gravity.CENTER);
            dock.addView(hint);
        }

        // grid
        grid.removeAllViews();
        List<AppStore.App> visible = new ArrayList<>();
        for (AppStore.App a : apps) if (!a.isInput() && !hidden.contains(a.pkg) && !favs.contains(a.pkg)) visible.add(a);
        int tg = 20;
        int cols = Math.max(4, (avail + tg) / (150 + tg));
        int tw = (avail - tg * (cols - 1)) / cols, th = tw * 9 / 16;
        LinearLayout row = null;
        for (int i = 0; i < visible.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(tg);
                grid.addView(row, rp);
            }
            View t = tile(visible.get(i), tw, th);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(tw), dp(th));
            if (i % cols != cols - 1) lp.rightMargin = dp(tg);
            row.addView(t, lp);
            if (firstTile == null) firstTile = t;
            if (visible.get(i).pkg.equals(focusedPkg)) restore = t;
        }

        if (!entered && motion && !introRunning) { entrance(); entered = true; }
        if (!motion) entered = true;

        final View target = restore != null ? restore : firstTile;
        View cur = getCurrentFocus();
        if (target != null && (menu == null || !menu.isOpen()) && (cur == null || !cur.isAttachedToWindow() || cur.getTag() instanceof AppStore.App)) {
            content.post(new Runnable() { @Override public void run() { target.requestFocus(); } });
        }
    }

    /** First appearance: clock fades down into place, cards rise in a quick stagger. */
    private void entrance() {
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(glide).start();
        shelfTitle.setAlpha(0f);
        shelfTitle.animate().alpha(1f).setStartDelay(160).setDuration(420).start();
        int i = 0;
        for (int d = 0; d < dock.getChildCount(); d++) riseIn(dock.getChildAt(d), 80 + 35 * i++);
        for (int r = 0; r < grid.getChildCount() && r < 3; r++) {
            ViewGroup row = (ViewGroup) grid.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) riseIn(row.getChildAt(c), 160 + 18 * i++);
        }
    }

    private void riseIn(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(dp(28));
        v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(delay, 420)).setDuration(320).setInterpolator(glide).start();
    }

    private View tile(final AppStore.App app, int wDp, int hDp) {
        final FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1C222B, TILE});
        bg.setCornerRadius(dp(18));
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setFocusable(true);
        f.setFocusableInTouchMode(true);
        f.setContentDescription(app.label);
        f.setTag(app);
        f.setCameraDistance(dp(1600) * getResources().getDisplayMetrics().density);
        Fx.shadowColor(f, (0xFF << 24) | (app.accent & 0xFFFFFF));

        if (app.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(8), dp(6), dp(8), dp(6));
            if (app.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(app.icon);
                int s = dp(hDp > 100 ? 52 : 36);
                box.addView(iv, new LinearLayout.LayoutParams(s, s));
            }
            TextView tv = text(hDp > 100 ? 15 : 12, 0xFFDDE3EA, "sans-serif");
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setText(app.label);
            tv.setPadding(0, dp(6), 0, 0);
            box.addView(tv);
            f.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        final Fx.GlassOverlay glass = new Fx.GlassOverlay(dp(18), getResources().getDisplayMetrics().density);
        f.setForeground(glass);

        f.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                glass.setFocused(has);
                float s = has ? 1.08f : 1f;
                if (motion) {
                    v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                            .setDuration(has ? 170 : 130).setInterpolator(has ? spring : glide).start();
                    if (has) {
                        ValueAnimator sw = ValueAnimator.ofFloat(0f, 1f);
                        sw.setStartDelay(40);
                        sw.setDuration(480);
                        sw.setInterpolator(new AccelerateDecelerateInterpolator());
                        sw.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                            @Override public void onAnimationUpdate(ValueAnimator a) {
                                float t = (float) a.getAnimatedValue();
                                glass.setSweep(t >= 1f ? -1f : t);
                            }
                        });
                        sw.start();
                    }
                } else { v.setScaleX(s); v.setScaleY(s); }
            }
        });
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (motion) launchWithAnimation(v, app);
                else safeStart(app.launchIntent());
            }
        });
        f.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showAppMenu(app); return true; }
        });
        f.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { showAppMenu(app); return true; }
                return false;
            }
        });
        return f;
    }

    // ================================================================= focus choreography

    private void onFocusMoved(View v) {
        if (v == null || menu == null || menu.isOpen()) return;
        boolean isTile = v.getTag() instanceof AppStore.App;
        if (isTile) {
            lastFocused = v;
            AppStore.App app = (AppStore.App) v.getTag();
            setShelf(app.label);
            animateAccent(app.accent);
            setHero(app);
        }
        boolean top = isInside(v, header) || isInside(v, dock);
        if (top) animateScrollTo(0);
        else if (isTile) {
            Rect r = new Rect();
            v.getDrawingRect(r);
            content.offsetDescendantRectToMyCoords(v, r);
            animateScrollTo(Math.max(0, r.top - (int) (scroll.getHeight() * 0.38f)));
        }
        heroFrame.animate().alpha(top ? 1f : 0f).setDuration(200).start();
    }

    private void setHero(final AppStore.App app) {
        if (app.pkg.equals(heroPkg)) return;
        heroPkg = app.pkg;
        if (!motion) { hero.setTitle(app.label, app.accent); hero.setAlpha(HERO_ALPHA); return; }
        hero.animate().alpha(0f).setDuration(90).withEndAction(new Runnable() {
            @Override public void run() {
                hero.setTitle(app.label, app.accent);
                hero.setScaleX(1f); hero.setScaleY(1f);
                hero.animate().alpha(HERO_ALPHA).setDuration(220).setInterpolator(glide).start();
            }
        }).start();
    }

    private void setShelf(final String title) {
        if (title.contentEquals(shelfTitle.getText())) return;
        if (!motion) { shelfTitle.setText(title); return; }
        shelfTitle.animate().alpha(0f).translationY(-dp(4)).setDuration(60).withEndAction(new Runnable() {
            @Override public void run() {
                shelfTitle.setText(title);
                shelfTitle.setTranslationY(dp(4));
                shelfTitle.animate().alpha(1f).translationY(0).setDuration(140).setInterpolator(glide).start();
            }
        }).start();
    }

    /** Cross-fade to a backdrop tinted with the new colour: one GPU-composited fade, no per-frame redraw. */
    private int shown = AppStore.DEFAULT_ACCENT;

    private void animateAccent(final int to) {
        if ((to & 0xFFFFFF) == (accent & 0xFFFFFF)) return;
        accent = to;
        backB.animate().cancel();
        if (backB.getAlpha() > 0.5f) { backA.setColor(shown = backBColor); } // an interrupted fade: settle on what was showing
        if (!motion) { backA.setColor(shown = to); backB.setAlpha(0f); return; }
        backBColor = to;
        backB.setColor(to);
        backB.setAlpha(0f);
        backB.animate().alpha(1f).setDuration(260).setInterpolator(glide).withEndAction(new Runnable() {
            @Override public void run() {
                backA.setColor(shown = to);
                backB.postDelayed(new Runnable() { // hide the top layer once the bottom one has redrawn
                    @Override public void run() { if (accent == to) backB.setAlpha(0f); }
                }, 48);
            }
        }).start();
    }
    private int backBColor;

    private void animateScrollTo(int y) {
        if (scrollAnim != null) scrollAnim.cancel();
        if (Math.abs(scroll.getScrollY() - y) < 2) return;
        if (!motion) { scroll.scrollTo(0, y); return; }
        scrollAnim = ObjectAnimator.ofInt(scroll, "scrollY", y);
        scrollAnim.setDuration(260);
        scrollAnim.setInterpolator(glide);
        scrollAnim.start();
    }

    private static boolean isInside(View v, View parent) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == parent) return true;
        return false;
    }

    /** ScrollView whose automatic focus jumps are disabled; the activity glides it instead. */
    static final class GlideScrollView extends ScrollView {
        GlideScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }
    }

    // ================================================================= data

    private void reload() {
        io.execute(new Runnable() {
            @Override public void run() {
                final List<AppStore.App> apps = store.loadAll();
                ui.post(new Runnable() {
                    @Override public void run() {
                        cache = apps;
                        if (!isFinishing() && !isDestroyed()) render(apps);
                    }
                });
            }
        });
    }

    private void rerender() { if (cache != null) render(cache); }

    private void updateClock() {
        Date now = new Date();
        clock.setText(android.text.format.DateFormat.getTimeFormat(this).format(now));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
    }

    private boolean readMotionSetting() {
        try {
            return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Exception e) { return true; }
    }

    // ================================================================= menus

    private void showAppMenu(final AppStore.App app) {
        final boolean fav = store.isFavourite(app.pkg);
        List<GlassMenu.Item> items = new ArrayList<>();
        if (fav) {
            items.add(new GlassMenu.Item("Remove from favourites", R.drawable.ic_star, null, new Runnable() {
                @Override public void run() { store.toggleFavourite(app.pkg); rerender(); }
            }));
            items.add(new GlassMenu.Item("Move left", R.drawable.ic_left, null, new Runnable() {
                @Override public void run() { store.moveFavourite(app.pkg, -1); rerender(); }
            }));
            items.add(new GlassMenu.Item("Move right", R.drawable.ic_right, null, new Runnable() {
                @Override public void run() { store.moveFavourite(app.pkg, 1); rerender(); }
            }));
        } else if (!store.favouritesFull()) {
            items.add(new GlassMenu.Item("Add to favourites", R.drawable.ic_star, null, new Runnable() {
                @Override public void run() { store.toggleFavourite(app.pkg); rerender(); }
            }));
        }
        if (!app.isInput()) {
            items.add(new GlassMenu.Item("Hide from home screen", R.drawable.ic_hide, null, new Runnable() {
                @Override public void run() { store.setHidden(app.pkg, true); rerender(); toast(app.label + " hidden · Edit › Show hidden apps"); }
            }));
            items.add(new GlassMenu.Item("App info", R.drawable.ic_info, null, new Runnable() {
                @Override public void run() { safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))); }
            }));
        }
        menu.show(app.label, items, false);
    }

    private void showEditMenu() {
        final Set<String> hidden = store.hidden();
        List<GlassMenu.Item> items = new ArrayList<>();
        items.add(new GlassMenu.Item("Pin an HDMI input", R.drawable.ic_input, null, new Runnable() {
            @Override public void run() { ui.post(new Runnable() { @Override public void run() { pinInput(); } }); }
        }));
        items.add(new GlassMenu.Item("Show hidden apps", R.drawable.ic_show, String.valueOf(hidden.size()), new Runnable() {
            @Override public void run() { ui.post(new Runnable() { @Override public void run() { showHidden(hidden); } }); }
        }));
        items.add(new GlassMenu.Item("Reset favourites", R.drawable.ic_reset, null, new Runnable() {
            @Override public void run() { store.resetFavourites(); reload(); toast("Favourites reset"); }
        }));
        menu.show("Edit home", items, false);
    }

    private void pinInput() {
        if (cache == null) return;
        if (store.favouritesFull()) { toast("Favourites are full. Remove one first."); return; }
        List<GlassMenu.Item> items = new ArrayList<>();
        for (final AppStore.App a : cache) {
            if (!a.isInput() || store.isFavourite(a.pkg)) continue;
            items.add(new GlassMenu.Item(a.label, R.drawable.ic_input, a.pkg.replace(AppStore.INPUT_PREFIX, "HDMI "), new Runnable() {
                @Override public void run() { store.toggleFavourite(a.pkg); rerender(); }
            }));
        }
        if (items.isEmpty()) { toast("No more HDMI inputs to pin"); return; }
        menu.show("Pin an input", items, false);
    }

    private void showHidden(Set<String> hidden) {
        if (hidden.isEmpty() || cache == null) { toast("No hidden apps"); return; }
        List<GlassMenu.Item> items = new ArrayList<>();
        for (final AppStore.App a : cache) {
            if (!hidden.contains(a.pkg)) continue;
            items.add(new GlassMenu.Item("Show " + a.label, R.drawable.ic_show, null, new Runnable() {
                @Override public void run() { store.setHidden(a.pkg, false); rerender(); }
            }));
        }
        menu.show("Hidden apps", items, false);
    }

    private void showInputs() {
        List<GlassMenu.Item> items = new ArrayList<>();
        if (cache != null) for (final AppStore.App a : cache) {
            if (!a.isInput()) continue;
            String port = a.pkg.replace(AppStore.INPUT_PREFIX, "HDMI ");
            boolean named = !a.label.equals(port);
            items.add(new GlassMenu.Item(port, R.drawable.ic_input, named ? a.label : null, new Runnable() {
                @Override public void run() { safeStart(a.launchIntent()); }
            }));
        }
        if (items.isEmpty()) { safeStart(new Intent(Settings.ACTION_SETTINGS)); return; }
        menu.show("Inputs", items, false);
    }

    /** Control-Centre-style sheet: shortcuts into the TV's own settings screens. */
    private void showSettings() {
        List<GlassMenu.Item> items = new ArrayList<>();
        items.add(settingsItem("Wi-Fi", R.drawable.ic_wifi, null, Settings.ACTION_WIFI_SETTINGS));
        items.add(settingsItem("Bluetooth & remotes", R.drawable.ic_bt, null, Settings.ACTION_BLUETOOTH_SETTINGS));
        items.add(settingsItem("Picture", R.drawable.ic_display, null, Settings.ACTION_DISPLAY_SETTINGS));
        items.add(settingsItem("Sound", R.drawable.ic_sound, null, Settings.ACTION_SOUND_SETTINGS));
        items.add(new GlassMenu.Item("Inputs", R.drawable.ic_input, null, new Runnable() {
            @Override public void run() { ui.post(new Runnable() { @Override public void run() { showInputs(); } }); }
        }));
        items.add(settingsItem("Date & time", R.drawable.ic_clock, null, Settings.ACTION_DATE_SETTINGS));
        items.add(settingsItem("Apps", R.drawable.ic_apps, null, Settings.ACTION_APPLICATION_SETTINGS));
        items.add(settingsItem("All settings", R.drawable.ic_settings, null, Settings.ACTION_SETTINGS));
        menu.show("Settings", items, true);
    }

    private GlassMenu.Item settingsItem(String label, int icon, String sub, final String action) {
        return new GlassMenu.Item(label, icon, sub, new Runnable() {
            @Override public void run() { safeStart(new Intent(action)); }
        });
    }

    // ================================================================= helpers

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            if (!Settings.ACTION_SETTINGS.equals(i.getAction())) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                catch (Exception ignored) { }
            }
            toast("Can't open that on this TV");
        }
    }

    void toast(String s) {
        toastView.setText(s);
        toastView.animate().cancel();
        toastView.setTranslationY(dp(16));
        toastView.animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(glide).start();
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 2400);
    }

    /** Quiet grey icon; focused it becomes a white disc with a dark glyph and shows its label. */
    private View iconButton(int icon, String label, View.OnClickListener click) {
        final LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMinimumWidth(dp(48));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setFocusable(true);
        b.setContentDescription(label);
        final ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(0xFF6F7784));
        b.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        final TextView tv = text(15, Color.BLACK, "sans-serif-medium");
        tv.setText(label);
        tv.setPadding(dp(8), 0, dp(4), 0);
        tv.setVisibility(View.GONE);
        b.addView(tv);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(24));
        b.setBackground(bg);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? Color.WHITE : Color.TRANSPARENT);
                iv.setImageTintList(ColorStateList.valueOf(has ? Color.BLACK : 0xFF6F7784));
                tv.setVisibility(has ? View.VISIBLE : View.GONE);
                if (motion) v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(220).setInterpolator(spring).start();
            }
        });
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams marginTop(int d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(d);
        return lp;
    }

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
