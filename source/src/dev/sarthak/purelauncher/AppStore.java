package dev.sarthak.purelauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads launchable apps and keeps favourites / hidden apps in private SharedPreferences.
 * Only reads the package list through PackageManager; never touches files or the network.
 */
final class AppStore {

    static final class App {
        final String pkg;
        final ComponentName component;
        final String label;
        final Drawable banner; // 16:9 TV banner, may be null
        final Drawable icon;
        final boolean leanback;
        final int accent;      // dominant colour of the artwork, used for the ambient glow
        String inputId;        // set for HDMI input tiles (pkg starts with INPUT_PREFIX)

        boolean isInput() { return inputId != null; }

        App(String pkg, ComponentName component, String label, Drawable banner, Drawable icon, boolean leanback, int accent) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.leanback = leanback;
            this.accent = accent;
        }

        /** Explicit intent to the exact component PackageManager reported: no implicit resolution. */
        Intent launchIntent() {
            if (inputId != null) {
                Intent v = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
                v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return v;
            }
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
            i.setComponent(component);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return i;
        }
    }

    /** Pinned on first run, in this order. Matched by package, then by label (regional builds differ). */
    private static final String[][] DEFAULT_FAVOURITES = {
            {"com.netflix.ninja", "netflix"},
            {"com.google.android.youtube.tv", "youtube"},
            {"com.amazon.amazonvideo.livingroom", "prime video"},
            {"pure.input.hdmi2", ""},            // Airtel set-top box on HDMI 2
    };

    /** Favourites entries for HDMI inputs look like "pure.input.hdmi2". */
    static final String INPUT_PREFIX = "pure.input.hdmi";
    /** Friendly names for HDMI ports (number → name). Unnamed ports show as "HDMI n". */
    private static final String[] INPUT_NAMES = {"", "", "Airtel TV"};
    private static final int AIRTEL_RED = 0xFFE4002B;

    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final int MAX_FAVOURITES = 6;
    static final int DEFAULT_ACCENT = 0xFF3A6EA5;

    private final Context ctx;
    private final SharedPreferences prefs;

    AppStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences("pure", Context.MODE_PRIVATE);
    }

    /** Runs on a background thread. */
    List<App> loadAll() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        String self = ctx.getPackageName();

        Intent lb = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(lb, 0)) addApp(pm, byPkg, ri, true, self);
        Intent l = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(l, 0)) addApp(pm, byPkg, ri, false, self);

        addInputs(byPkg);
        List<App> apps = new ArrayList<>(byPkg.values());
        Collections.sort(apps, new Comparator<App>() {
            @Override public int compare(App a, App b) { return a.label.compareToIgnoreCase(b.label); }
        });
        seedFavourites(apps);
        return apps;
    }

    /**
     * Known HDMI input ids per port, used when the TV input API returns nothing to this app.
     * Values from this TV (Xiaomi MITV-AXSO2, Amlogic "droidlogic" input service).
     */
    private static final String[] KNOWN_HDMI_IDS = {
            "",
            "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5",
            "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6",
            "com.droidlogic.tvinput/.services.Hdmi3InputService/HW7",
    };
    private static final Pattern INPUT_ID = Pattern.compile("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+(/[A-Za-z0-9_]+)?");

    /** HDMI ports become tiles that can be pinned; they never appear in the app grid. */
    private void addInputs(Map<String, App> out) {
        Map<Integer, String> ports = new java.util.TreeMap<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        try {
            if (tim != null) {
                int order = 0;
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.getParentId() != null) continue;             // skip CEC devices behind a port
                    CharSequence l = info.loadLabel(ctx);
                    String id = info.getId(), label = l == null ? "" : l.toString();
                    boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI
                            || id.toLowerCase(Locale.ROOT).contains("hdmi") || label.toLowerCase(Locale.ROOT).contains("hdmi");
                    if (!hdmi) continue;
                    order++;
                    int n = portNumber(label, portNumber(id, order));
                    if (!ports.containsKey(n)) ports.put(n, id);
                }
            }
        } catch (Throwable ignored) { }
        // Fallback for firmware that hides inputs from third-party apps
        for (int n = 1; n < KNOWN_HDMI_IDS.length; n++) if (!ports.containsKey(n)) ports.put(n, KNOWN_HDMI_IDS[n]);

        for (Map.Entry<Integer, String> e : ports.entrySet()) {
            int n = e.getKey();
            String id = e.getValue();
            if (!INPUT_ID.matcher(id).matches()) continue;
            String name = n < INPUT_NAMES.length && !INPUT_NAMES[n].isEmpty() ? INPUT_NAMES[n] : "HDMI " + n;
            int accent = name.toLowerCase(Locale.ROOT).contains("airtel") ? AIRTEL_RED : DEFAULT_ACCENT;
            App a = new App(INPUT_PREFIX + n, null, name, new InputBanner(name, "HDMI " + n, accent), null, false, accent);
            a.inputId = id;
            out.put(a.pkg, a);
        }
    }

    /** "HDMI 2", "HDMI2", "Hdmi2InputService" → 2; otherwise the fallback. */
    static int portNumber(String text, int fallback) {
        java.util.regex.Matcher m = Pattern.compile("(?i)hdmi\\D{0,3}(\\d)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static void addApp(PackageManager pm, Map<String, App> out, ResolveInfo ri, boolean leanback, String self) {
        ActivityInfo ai = ri.activityInfo;
        if (ai == null || !ai.exported || !ai.enabled || ai.packageName.equals(self) || out.containsKey(ai.packageName)) return;
        CharSequence lab = ri.loadLabel(pm);
        String label = TextUtils.isEmpty(lab) ? ai.packageName : lab.toString().trim();
        if (label.length() > 60) label = label.substring(0, 60);
        Drawable banner = null, icon = null;
        try {
            banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
        } catch (Throwable ignored) { }
        try { icon = ri.loadIcon(pm); } catch (Throwable ignored) { }
        int accent = accentOf(banner != null ? banner : icon);
        out.put(ai.packageName, new App(ai.packageName, new ComponentName(ai.packageName, ai.name),
                label, banner, banner == null ? icon : null, leanback, accent));
    }

    /** Saturation-weighted average colour of a drawable, drawn into a tiny private bitmap. */
    static int accentOf(Drawable src) {
        if (src == null) return DEFAULT_ACCENT;
        try {
            Drawable d = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
            Bitmap b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 18);
            d.draw(new Canvas(b));
            float[] hsv = new float[3];
            double r = 0, g = 0, bl = 0, w = 0;
            for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[2] < 0.18f) continue;
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; bl += Color.blue(p) * weight; w += weight;
            }
            b.recycle();
            if (w == 0) return DEFAULT_ACCENT;
            int c = Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w));
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.max(hsv[1], 0.6f);
            hsv[2] = Math.max(0.55f, Math.min(hsv[2], 0.9f));
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return DEFAULT_ACCENT;
        }
    }

    private void seedFavourites(List<App> apps) {
        if (prefs.getBoolean("seeded", false)) {
            // Drop favourites whose app or input no longer exists, so stale entries can't fill the dock.
            Set<String> present = new HashSet<>();
            for (App a : apps) present.add(a.pkg);
            List<String> f = favourites();
            f.retainAll(present);
            if (prefs.getInt("favVersion", 1) < 3) {
                // v3: the 4th favourite is the Airtel set-top box on HDMI 2 (replaces Xstream).
                f.remove("tv.accedo.airtel.wynk");
                if (present.contains(INPUT_PREFIX + 2) && !f.contains(INPUT_PREFIX + 2)) {
                    f.add(Math.min(3, f.size()), INPUT_PREFIX + 2);
                    if (f.size() > MAX_FAVOURITES) f = new ArrayList<>(f.subList(0, MAX_FAVOURITES));
                }
                prefs.edit().putInt("favVersion", 3).apply();
            }
            setFavourites(f);
            Log.i("PureHome", "favourites=" + f + " inputs=" + countInputs(apps));
            return;
        }
        List<String> favs = new ArrayList<>();
        for (String[] rule : DEFAULT_FAVOURITES) {
            App hit = null;
            for (App a : apps) if (!rule[0].isEmpty() && a.pkg.equals(rule[0])) { hit = a; break; }
            if (hit == null && !rule[1].isEmpty()) {
                for (App a : apps) {
                    String lab = a.label.toLowerCase(Locale.ROOT);
                    if (lab.contains(rule[1]) && !lab.contains("kids") && !lab.contains("music")) { hit = a; break; }
                }
            }
            if (hit != null && !favs.contains(hit.pkg)) favs.add(hit.pkg);
        }
        setFavourites(favs);
        prefs.edit().putBoolean("seeded", true).putInt("favVersion", 3).apply();
        Log.i("PureHome", "seeded favourites=" + favs + " inputs=" + countInputs(apps));
    }

    private static int countInputs(List<App> apps) {
        int n = 0;
        for (App a : apps) if (a.isInput()) n++;
        return n;
    }

    List<String> favourites() {
        String s = prefs.getString("favs", "");
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (PKG.matcher(p).matches() && !out.contains(p)) out.add(p);
        return out;
    }

    private void setFavourites(List<String> favs) {
        List<String> clean = new ArrayList<>();
        for (String p : favs) if (PKG.matcher(p).matches() && !clean.contains(p) && clean.size() < MAX_FAVOURITES) clean.add(p);
        prefs.edit().putString("favs", TextUtils.join(",", clean)).apply();
    }

    boolean isFavourite(String pkg) { return favourites().contains(pkg); }
    boolean favouritesFull() { return favourites().size() >= MAX_FAVOURITES; }

    void toggleFavourite(String pkg) {
        List<String> f = favourites();
        if (f.contains(pkg)) f.remove(pkg); else f.add(pkg);
        setFavourites(f);
    }

    void moveFavourite(String pkg, int delta) {
        List<String> f = favourites();
        int i = f.indexOf(pkg), j = i + delta;
        if (i < 0 || j < 0 || j >= f.size()) return;
        Collections.swap(f, i, j);
        setFavourites(f);
    }

    Set<String> hidden() {
        Set<String> out = new HashSet<>();
        for (String p : prefs.getStringSet("hidden", Collections.<String>emptySet())) if (PKG.matcher(p).matches()) out.add(p);
        return out;
    }

    void setHidden(String pkg, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(pkg); else h.remove(pkg);
        prefs.edit().putStringSet("hidden", h).apply();
        if (hide) {
            List<String> f = favourites();
            if (f.remove(pkg)) setFavourites(f);
        }
    }

    void resetFavourites() {
        prefs.edit().remove("favs").putBoolean("seeded", false).apply();
    }
}
