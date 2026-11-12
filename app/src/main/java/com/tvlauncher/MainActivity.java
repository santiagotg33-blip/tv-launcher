package com.tvlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import org.xmlpull.v1.XmlPullParser;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int TOP = 0, DOCK = 1, GRID = 2, MEDIA = 3;
    static final int PILLS = 5;      // Fondos, Iconos, Entradas, Red, Configuracion
    static final int WAVE_N = 56;    // columnas de la onda

    static class App { String pkg, label, cname; Drawable img; boolean banner; Intent launch; int glow; }
    static class Entry { String label; Intent intent; Intent fallback; Runnable action; }

    // ---------- Vista: fondo animado tipo aurora ----------
    static class AuroraView extends View {
        static final float[] SX = { 0.30f, 0.41f, 0.23f, 0.50f };
        static final float[] SY = { 0.37f, 0.25f, 0.45f, 0.31f };
        static final float[] PH = { 0f, 1.9f, 3.7f, 5.1f };
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int base;
        final int[] cols;
        RadialGradient[] g;
        float r;
        boolean running = true;
        final long t0 = System.currentTimeMillis();

        AuroraView(Context c, int base, int[] cols) {
            super(c);
            this.base = base;
            this.cols = cols;
        }

        static int al(int c, int a) { return (a << 24) | (c & 0x00FFFFFF); }

        void setRunning(boolean b) {
            running = b;
            if (b) invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            r = Math.max(w, h) * 0.28f;
            g = new RadialGradient[cols.length];
            for (int i = 0; i < cols.length; i++) {
                g[i] = new RadialGradient(0, 0, r,
                        new int[] { al(cols[i], 0xC8), al(cols[i], 0x66), al(cols[i], 0x00) },
                        new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP);
            }
        }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(base);
            if (g == null) return;
            float t = (System.currentTimeMillis() - t0) / 1000f;
            int w = getWidth(), h = getHeight();
            for (int i = 0; i < g.length; i++) {
                float x = w * (0.5f + 0.42f * (float) Math.sin(t * SX[i] + PH[i]));
                float y = h * (0.5f + 0.34f * (float) Math.cos(t * SY[i] + PH[i] * 1.7f));
                c.save();
                c.translate(x, y);
                p.setShader(g[i]);
                c.drawCircle(0, 0, r, p);
                c.restore();
            }
            if (running) postInvalidateDelayed(40);
        }
    }

    // ---------- Vista: onda de audio (barras cerca del punto de reproduccion + puntos) ----------
    static class WaveView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rf = new RectF();
        final float[] lv;
        final float dot;
        float head = 0.5f;

        WaveView(Context c, float[] lv, float dot) {
            super(c);
            this.lv = lv;
            this.dot = dot;
        }

        @Override protected void onDraw(Canvas c) {
            int n = lv.length;
            float w = getWidth(), h = getHeight();
            float pitch = w / n, cy = h / 2f, barW = pitch * 0.46f;
            float hp = head * (n - 1);
            for (int i = 0; i < n; i++) {
                float cx = pitch * (i + 0.5f);
                float d = i - hp;
                float env = (float) Math.exp(-(d * d) / 40f);
                float bar = dot * 2f + (h - dot * 2f) * Math.min(1f, lv[i]) * env;
                if (bar <= dot * 2f + 1f) {
                    p.setColor(0xCCFFFFFF);
                    c.drawCircle(cx, cy, dot, p);
                } else {
                    p.setColor(Color.WHITE);
                    rf.set(cx - barW / 2f, cy - bar / 2f, cx + barW / 2f, cy + bar / 2f);
                    c.drawRoundRect(rf, barW / 2f, barW / 2f, p);
                }
            }
        }
    }

    // ---------- Vista: botones del reproductor (anterior / play-pausa / siguiente) ----------
    static class Ctl extends View {
        final int kind;
        boolean playing = false;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        Ctl(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.FILL);
        }

        void setPlaying(boolean b) {
            if (b != playing) { playing = b; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float s = Math.min(getWidth(), getHeight()) * 0.30f;
            path.reset();
            if (kind == 0) {
                c.drawRect(cx - s, cy - s, cx - s + s * 0.30f, cy + s, p);
                path.moveTo(cx + s, cy - s);
                path.lineTo(cx - s * 0.55f, cy);
                path.lineTo(cx + s, cy + s);
                path.close();
                c.drawPath(path, p);
            } else if (kind == 2) {
                c.drawRect(cx + s - s * 0.30f, cy - s, cx + s, cy + s, p);
                path.moveTo(cx - s, cy - s);
                path.lineTo(cx + s * 0.55f, cy);
                path.lineTo(cx - s, cy + s);
                path.close();
                c.drawPath(path, p);
            } else if (playing) {
                c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.25f, cy + s, p);
                c.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.75f, cy + s, p);
            } else {
                path.moveTo(cx - s * 0.7f, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s * 0.7f, cy + s);
                path.close();
                c.drawPath(path, p);
            }
        }
    }

    // ---------- Estado ----------
    int W, H;
    float u;
    FrameLayout root, page, bgHolder;
    View glass, glow, menuPanel;
    TextView greeting, clock, date, dockName;
    final TextView[] pills = new TextView[PILLS];
    final List<App> dock = new ArrayList<App>();
    final List<App> grid = new ArrayList<App>();
    final List<View> dockViews = new ArrayList<View>();
    final List<View> gridViews = new ArrayList<View>();
    final List<TextView> gridLabels = new ArrayList<TextView>();
    final List<Entry> entries = new ArrayList<Entry>();
    final List<TextView> menuRows = new ArrayList<TextView>();
    int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0, mediaIdx = 1, menuIdx = 0;
    int menuStart = 0, menuVis = 0, wpCur = 0;
    boolean menuOpen = false;
    boolean moving = false, centerDown = false, longFired = false, paused = false;
    int hiddenInstalled = 0;
    int gridTop, pitch;
    int cell, tw, th, bandX, bandY, bandH, gcw, gtw, gth, gx0;
    GradientDrawable glassNormalBg, glassMoveBg;
    Typeface face;
    String lastNet = "";
    final Handler handler = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    BroadcastReceiver pkgReceiver;

    // Widget multimedia
    MediaSessionManager mediaManager;
    ComponentName listenerCn;
    MediaController ctl;
    FrameLayout mediaWidget;
    ImageView albumArt;
    TextView trackAlbum, trackTitle, trackArtist, trackApp;
    WaveView wave;
    final List<Ctl> ctlViews = new ArrayList<Ctl>();
    final float[] waveLevels = new float[WAVE_N];
    volatile float[] fftLevels = new float[WAVE_N];
    volatile long fftTime = 0;
    Visualizer viz;
    boolean vizOn = false, vizFailed = false;
    boolean isPlaying = false, listenerMissing = false;
    long posMs = -1, posBase = 0, durMs = -1;
    float speed = 1f;
    String lastArtKey = "";
    boolean mediaShown = false, warned = false;
    long lastPlayMs = 0, lastFixMs = 0;
    String artUriFor = "";
    volatile Bitmap uriArt = null;
    volatile String uriLoading = "";
    String packLoaded = "";
    HashMap<String, String> packMap = null;
    Resources packRes = null;

    // Fondo
    AuroraView aurora;
    MediaPlayer player;
    int bgToken = 0;
    Bitmap bgCache;
    String bgCachePath;

    final Runnable rebuild = new Runnable() { public void run() { buildUi(); } };
    final Runnable tickSlow = new Runnable() {
        public void run() { updateClock(); pollMedia(); handler.postDelayed(this, 1000); }
    };
    final Runnable tickFast = new Runnable() {
        public void run() { animateWave(); handler.postDelayed(this, 45); }
    };
    final Runnable longPress = new Runnable() {
        public void run() {
            if (centerDown) { longFired = true; openContextMenu(); }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("nav", MODE_PRIVATE);
        zone = prefs.getInt("zone", DOCK);
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);
        face = loadFace();
        try {
            mediaManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        } catch (Throwable t) { }
        listenerCn = new ComponentName(this, MediaListener.class);
        buildUi();
        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                handler.removeCallbacks(rebuild);
                handler.postDelayed(rebuild, 800);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        vizFailed = false;
        handler.removeCallbacks(tickSlow);
        handler.removeCallbacks(tickFast);
        handler.post(tickSlow);
        handler.post(tickFast);
        if (aurora != null) aurora.setRunning(true);
        if (player != null) { try { player.start(); } catch (Throwable t) { } }
    }

    @Override protected void onPause() {
        super.onPause();
        paused = true;
        handler.removeCallbacks(tickSlow);
        handler.removeCallbacks(tickFast);
        handler.removeCallbacks(longPress);
        releaseViz();
        if (aurora != null) aurora.setRunning(false);
        if (player != null) { try { player.pause(); } catch (Throwable t) { } }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        releaseViz();
        clearBg();
        try { unregisterReceiver(pkgReceiver); } catch (Throwable t) { }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (menuOpen) closeMenu();
        if (moving) { moving = false; saveState(); }
        if (!dock.isEmpty()) zone = DOCK;
        updateFocus();
    }

    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 77) {
            if (res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                openWallpaperMenu();
            } else {
                Toast.makeText(this, "Sin permiso no puedo ver tus imagenes", Toast.LENGTH_LONG).show();
            }
        }
    }

    Typeface loadFace() {
        try { return Typeface.createFromAsset(getAssets(), "fonts/chubby.ttf"); }
        catch (Throwable t) { return Typeface.create("sans-serif-black", Typeface.NORMAL); }
    }

    // ---------- Utilidades de dibujo ----------
    int px(float v) { return Math.round(v * u); }

    FrameLayout.LayoutParams lp(int w, int h, int x, int y) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.leftMargin = x;
        p.topMargin = y;
        return p;
    }

    GradientDrawable rounded(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    /** Efecto "glass": degradado translucido + borde fino brillante (sin blur real). */
    GradientDrawable glassFill(float r, int top, int bottom, int stroke) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { top, bottom });
        g.setCornerRadius(r);
        g.setStroke(Math.max(2, px(2)), stroke);
        return g;
    }

    TextView text(float sizePx, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    int clampi(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    int avgColor(Drawable d) {
        try {
            Drawable.ConstantState cs = d.getConstantState();
            Drawable c = cs != null ? cs.newDrawable().mutate() : d;
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            c.setBounds(0, 0, 16, 16);
            c.draw(cv);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) > 128) {
                        r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
                    }
                }
            }
            b.recycle();
            if (n > 0) {
                float[] hsv = new float[3];
                Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
                hsv[1] = Math.min(1f, hsv[1] * 1.2f);
                hsv[2] = Math.max(hsv[2], 0.8f);
                return Color.HSVToColor(hsv);
            }
        } catch (Throwable t) { }
        return 0xFF4A90E2;
    }

    // ---------- Datos de apps ----------
    String[] parts(String s) {
        if (s == null || s.length() == 0) return new String[0];
        return s.split(",");
    }

    List<App> applyOrder(List<App> list, String saved) {
        if (saved == null || saved.length() == 0) return list;
        List<App> res = new ArrayList<App>();
        List<App> left = new ArrayList<App>(list);
        for (String p : saved.split(",")) {
            for (int i = 0; i < left.size(); i++) {
                if (left.get(i).pkg.equals(p)) { res.add(left.remove(i)); break; }
            }
        }
        res.addAll(left);
        return res;
    }

    String join(List<App> l) {
        StringBuilder sb = new StringBuilder();
        for (App a : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a.pkg);
        }
        return sb.toString();
    }

    void saveState() {
        prefs.edit().putString("dock_list", join(dock)).putString("grid_order", join(grid)).apply();
    }

    void loadApps() {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, App> map = new LinkedHashMap<String, App>();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                Drawable bn = null;
                try { bn = ri.activityInfo.loadBanner(pm); } catch (Throwable t) { }
                if (bn == null) {
                    try { bn = ri.activityInfo.applicationInfo.loadBanner(pm); } catch (Throwable t) { }
                }
                Drawable ic = ri.loadIcon(pm);
                a.cname = pkg + "/" + ri.activityInfo.name;
                if (bn != null) { a.img = bn; a.banner = true; }
                else { a.img = ic; a.banner = false; }
                customIcon(a, ic, bn != null);
                Intent l = new Intent(Intent.ACTION_MAIN);
                l.addCategory(cat);
                l.setClassName(pkg, ri.activityInfo.name);
                l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                a.launch = l;
                map.put(pkg, a);
            }
        }
        HashSet<String> hid = new HashSet<String>();
        for (String h : Config.HIDE) hid.add(h);
        hiddenInstalled = 0;
        for (String h : parts(prefs.getString("hidden", ""))) {
            if (map.containsKey(h)) hiddenInstalled++;
            hid.add(h);
        }
        for (String h : hid) map.remove(h);
        List<App> rest = new ArrayList<App>(map.values());
        String[] dockPk = prefs.contains("dock_list") ? parts(prefs.getString("dock_list", "")) : Config.DOCK;
        for (String p : dockPk) {
            if (dock.size() >= 4) break;
            App a = map.get(p);
            if (a != null && !dock.contains(a)) {
                a.glow = avgColor(a.img);
                dock.add(a);
                rest.remove(a);
            }
        }
        Collections.sort(rest, new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        });
        grid.addAll(rest);
        List<App> og = applyOrder(new ArrayList<App>(grid), prefs.getString("grid_order", ""));
        grid.clear();
        grid.addAll(og);
    }

    boolean hasMedia() { return ctl != null && mediaShown && !ctlViews.isEmpty(); }

    void clampState() {
        if (zone == MEDIA && !hasMedia()) zone = dock.isEmpty() ? (grid.isEmpty() ? TOP : GRID) : DOCK;
        if (zone == DOCK && dock.isEmpty()) zone = grid.isEmpty() ? TOP : GRID;
        if (zone == GRID && grid.isEmpty()) zone = dock.isEmpty() ? TOP : DOCK;
        topIdx = clampi(topIdx, 0, PILLS - 1);
        dockIdx = clampi(dockIdx, 0, Math.max(0, dock.size() - 1));
        gridIdx = clampi(gridIdx, 0, Math.max(0, grid.size() - 1));
    }

    ImageView tile(App a, final int rad) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.img);
        iv.setScaleType(a.banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!a.banner) iv.setPadding(px(30), px(18), px(30), px(18));
        iv.setBackground(rounded(0xFF1C1C1E, rad));
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
            }
        });
        return iv;
    }

    void placeDock(int i) {
        View v = dockViews.get(i);
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) v.getLayoutParams();
        l.leftMargin = bandX + px(20) + i * cell + (cell - tw) / 2;
        l.topMargin = bandY + (bandH - th) / 2;
        v.setLayoutParams(l);
    }

    void placeGrid(int i) {
        int r = i / 4, c = i % 4;
        int x = gx0 + c * gcw + (gcw - gtw) / 2;
        int y = gridTop + r * pitch;
        View v = gridViews.get(i);
        FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) v.getLayoutParams();
        l.leftMargin = x;
        l.topMargin = y;
        v.setLayoutParams(l);
        View lab = gridLabels.get(i);
        FrameLayout.LayoutParams m = (FrameLayout.LayoutParams) lab.getLayoutParams();
        m.leftMargin = gx0 + c * gcw;
        m.topMargin = y + gth + px(22);
        lab.setLayoutParams(m);
    }

    // ---------- Construccion de la interfaz ----------
    void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        u = H / 1080f;
        dock.clear(); grid.clear(); dockViews.clear(); gridViews.clear(); gridLabels.clear();
        ctlViews.clear();
        lastArtKey = "";
        menuOpen = false; menuPanel = null; lastNet = ""; moving = false;
        loadApps();
        clampState();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Fondo (imagen / animado / video) + capa oscura suave para que se lea el texto
        bgHolder = new FrameLayout(this);
        root.addView(bgHolder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        View scrim = new View(this);
        scrim.setBackgroundColor(0x40000000);
        root.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        pitch = px(230);
        gridTop = H + px(40);
        int rows = Math.max(1, (grid.size() + 3) / 4);
        page = new FrameLayout(this);
        root.addView(page, new FrameLayout.LayoutParams(W, gridTop + rows * pitch + px(300)));

        greeting = text(px(34), 0xB3FFFFFF);
        page.addView(greeting, lp(W / 2, px(46), px(80), px(56)));
        clock = text(px(110), Color.WHITE);
        page.addView(clock, lp(W / 2, px(130), px(74), px(96)));
        date = text(px(32), 0x99FFFFFF);
        page.addView(date, lp(W / 2, px(44), px(80), px(232)));

        String[] pt = { "Fondos", "Iconos", "Entradas", "Wi-Fi", "Configuración" };
        for (int i = 0; i < PILLS; i++) {
            TextView p = text(px(26), Color.WHITE);
            p.setText(pt[i]);
            p.setGravity(Gravity.CENTER);
            p.setPadding(px(34), 0, px(34), 0);
            p.setBackground(glassFill(px(34), 0x40FFFFFF, 0x18FFFFFF, 0x55FFFFFF));
            pills[i] = p;
            page.addView(p, lp(px(200), px(68), 0, px(64)));
        }
        layoutPills();

        // Dock (franja inferior de vidrio)
        cell = px(230);
        tw = px(190);
        th = px(107);
        bandH = px(190);
        int bandW = cell * 4 + px(40);
        bandX = (W - bandW) / 2;
        bandY = H - px(250);
        View band = new View(this);
        band.setBackground(glassFill(px(48), 0x33FFFFFF, 0x14FFFFFF, 0x44FFFFFF));
        page.addView(band, lp(bandW, bandH, bandX, bandY));
        glow = new View(this);
        page.addView(glow, lp(px(900), px(520), (W - px(900)) / 2, bandY - px(350)));

        buildMediaWidget();

        dockName = text(px(64), Color.WHITE);
        dockName.setGravity(Gravity.CENTER);
        page.addView(dockName, lp(W, px(80), 0, bandY - px(130)));
        for (int i = 0; i < dock.size(); i++) {
            ImageView t = tile(dock.get(i), px(16));
            page.addView(t, lp(tw, th, 0, 0));
            dockViews.add(t);
            placeDock(i);
        }

        gcw = px(300);
        gtw = px(260);
        gth = px(146);
        gx0 = (W - gcw * 4) / 2;
        for (int i = 0; i < grid.size(); i++) {
            ImageView t = tile(grid.get(i), px(18));
            page.addView(t, lp(gtw, gth, 0, 0));
            gridViews.add(t);
            TextView l = text(px(26), Color.WHITE);
            l.setText(grid.get(i).label);
            l.setGravity(Gravity.CENTER);
            l.setVisibility(View.INVISIBLE);
            page.addView(l, lp(gcw, px(36), 0, 0));
            gridLabels.add(l);
            placeGrid(i);
        }

        glassNormalBg = glassFill(px(24), 0x40FFFFFF, 0x20FFFFFF, 0xDDFFFFFF);
        glassNormalBg.setStroke(Math.max(3, px(3)), 0xDDFFFFFF);
        glassMoveBg = rounded(0x40FFD60A, px(26));
        glassMoveBg.setStroke(px(4), 0xFFFFD60A);
        glass = new View(this);
        glass.setBackground(glassNormalBg);
        glass.setVisibility(View.INVISIBLE);
        page.addView(glass, lp(10, 10, 0, 0));

        setContentView(root);
        applyWallpaper();
        updateClock();
        pollMedia();
        updateFocus();
    }

    void buildMediaWidget() {
        int mw = px(980), mh = px(330);
        mediaWidget = new FrameLayout(this);
        mediaWidget.setBackground(glassFill(px(36), 0x4DFFFFFF, 0x1AFFFFFF, 0x77FFFFFF));

        // 1) Caratula a la izquierda
        albumArt = new ImageView(this);
        albumArt.setBackground(rounded(0x33FFFFFF, px(18)));
        albumArt.setClipToOutline(true);
        albumArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        albumArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(18));
            }
        });
        mediaWidget.addView(albumArt, lp(px(170), px(170), px(30), px(26)));

        // 2) Album / cancion / artista / app en el centro
        int tx = px(230), tw2 = mw - px(230) - px(30);
        trackAlbum = text(px(24), 0xB3FFFFFF);
        trackTitle = text(px(46), Color.WHITE);
        trackArtist = text(px(30), 0xCCFFFFFF);
        trackApp = text(px(26), 0x99FFFFFF);
        mediaWidget.addView(trackAlbum, lp(tw2, px(30), tx, px(28)));
        mediaWidget.addView(trackTitle, lp(tw2, px(58), tx, px(60)));
        mediaWidget.addView(trackArtist, lp(tw2, px(38), tx, px(122)));
        mediaWidget.addView(trackApp, lp(tw2, px(32), tx, px(164)));

        // 3) Onda de audio horizontal (barras + puntos)
        wave = new WaveView(this, waveLevels, px(3.5f));
        mediaWidget.addView(wave, lp(mw - px(60), px(56), px(30), px(204)));

        // 4) Botones: anterior / play-pausa / siguiente
        for (int k = 0; k < 3; k++) {
            Ctl c = new Ctl(this, k);
            c.setVisibility(View.INVISIBLE);
            mediaWidget.addView(c, lp(px(60), px(60), mw / 2 - px(30) + (k - 1) * px(120), px(266)));
            ctlViews.add(c);
        }

        page.addView(mediaWidget, lp(mw, mh, (W - mw) / 2, px(290)));
        mediaWidget.setAlpha(0f);
        mediaWidget.setVisibility(View.INVISIBLE);
        mediaShown = false;
        applyTrackTexts("", "Sin reproducción", "Abre Spotify o Kodi", "");
    }

    void layoutPills() {
        int x = W - px(80);
        for (int i = PILLS - 1; i >= 0; i--) {
            TextView p = pills[i];
            p.measure(0, 0);
            int w = p.getMeasuredWidth();
            x -= w;
            FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) p.getLayoutParams();
            l.width = w;
            l.height = px(68);
            l.leftMargin = x;
            l.topMargin = px(64);
            p.setLayoutParams(l);
            x -= px(20);
        }
    }

    // ---------- Fondos de pantalla ----------
    void clearBg() {
        bgToken++;
        releasePlayer();
        aurora = null;
        if (bgHolder != null) bgHolder.removeAllViews();
    }

    void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable t) { }
            try { player.release(); } catch (Throwable t) { }
            player = null;
        }
    }

    FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    void applyWallpaper() {
        if (bgHolder == null) return;
        clearBg();
        bgHolder.setBackgroundColor(Color.BLACK);
        String type = prefs.getString("wp_type", "anim");
        String val = prefs.getString("wp_val", "aurora_verde");
        if ("none".equals(type)) return;
        if ("image".equals(type)) imageBg(val);
        else if ("video".equals(type)) videoBg(val);
        else if ("system".equals(type)) systemBg();
        else animBg(val);
    }

    void animBg(String id) {
        int base;
        int[] cols;
        if ("aurora_azul".equals(id)) {
            base = 0xFF020716;
            cols = new int[] { 0xFF1E90FF, 0xFF00D4FF, 0xFF5B5BFF, 0xFF0A4FA8 };
        } else if ("aurora_violeta".equals(id)) {
            base = 0xFF0A0314;
            cols = new int[] { 0xFFB04DFF, 0xFFFF4DC8, 0xFF5B3BFF, 0xFF7A1FA2 };
        } else if ("atardecer".equals(id)) {
            base = 0xFF14040A;
            cols = new int[] { 0xFFFF6A00, 0xFFFF2D55, 0xFFFFB300, 0xFF8E24AA };
        } else if ("oceano".equals(id)) {
            base = 0xFF010E14;
            cols = new int[] { 0xFF00BFA5, 0xFF0097FF, 0xFF00E5FF, 0xFF006064 };
        } else {
            base = 0xFF03120A;
            cols = new int[] { 0xFF00E060, 0xFF12B886, 0xFF7CFF3A, 0xFF0B8F4A };
        }
        AuroraView v = new AuroraView(this, base, cols);
        aurora = v;
        v.setRunning(!paused);
        bgHolder.addView(v, matchParent());
    }

    void showBitmap(Bitmap bm) {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bm);
        iv.setAlpha(0f);
        bgHolder.addView(iv, matchParent());
        iv.animate().alpha(1f).setDuration(400).start();
    }

    void imageBg(final String path) {
        if (bgCache != null && path.equals(bgCachePath)) { showBitmap(bgCache); return; }
        if (!new File(path).exists()) {
            Toast.makeText(this, "No encuentro esa imagen", Toast.LENGTH_SHORT).show();
            animBg("aurora_verde");
            return;
        }
        final int token = bgToken;
        final int sw = W, sh = H;
        new Thread(new Runnable() {
            public void run() {
                Bitmap bm = null;
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, o);
                    int s = 1;
                    while (o.outWidth / (s * 2) >= sw && o.outHeight / (s * 2) >= sh) s *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = s;
                    bm = BitmapFactory.decodeFile(path, o2);
                } catch (Throwable t) { bm = null; }
                final Bitmap result = bm;
                handler.post(new Runnable() {
                    public void run() {
                        if (token != bgToken || bgHolder == null) return;
                        if (result == null) {
                            Toast.makeText(MainActivity.this, "No pude abrir la imagen", Toast.LENGTH_SHORT).show();
                            animBg("aurora_verde");
                        } else {
                            bgCache = result;
                            bgCachePath = path;
                            showBitmap(result);
                        }
                    }
                });
            }
        }).start();
    }

    void systemBg() {
        try {
            Drawable d = WallpaperManager.getInstance(this).getDrawable();
            if (d == null) throw new RuntimeException("sin fondo");
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(d);
            bgHolder.addView(iv, matchParent());
        } catch (Throwable t) {
            Toast.makeText(this, "No pude leer el fondo del sistema", Toast.LENGTH_SHORT).show();
            animBg("aurora_verde");
        }
    }

    void videoBg(final String path) {
        if (!new File(path).exists()) {
            Toast.makeText(this, "No encuentro ese video", Toast.LENGTH_SHORT).show();
            animBg("aurora_verde");
            return;
        }
        final TextureView tv = new TextureView(this);
        bgHolder.addView(tv, matchParent());
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { startVideo(tv, st, path); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { releasePlayer(); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
    }

    void startVideo(final TextureView tv, SurfaceTexture st, String path) {
        try {
            releasePlayer();
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setSurface(new Surface(st));
            mp.setDataSource(path);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);   // sin sonido y sin quitarle el audio a Spotify
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    fitVideo(tv, m.getVideoWidth(), m.getVideoHeight());
                    if (!paused) m.start();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    handler.post(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this, "No pude reproducir ese video", Toast.LENGTH_SHORT).show();
                            clearBg();
                            animBg("aurora_verde");
                        }
                    });
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Toast.makeText(this, "No pude reproducir ese video", Toast.LENGTH_SHORT).show();
            animBg("aurora_verde");
        }
    }

    void fitVideo(TextureView tv, int vw, int vh) {
        int w = tv.getWidth(), h = tv.getHeight();
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float va = vw / (float) vh, ca = w / (float) h;
        float sx = 1f, sy = 1f;
        if (va > ca) sx = va / ca; else sy = ca / va;
        Matrix m = new Matrix();
        m.setScale(sx, sy, w / 2f, h / 2f);
        tv.setTransform(m);
    }

    boolean hasStorage() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void requestStorage() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, 77);
        }
    }

    void scanFiles(File dir, int depth, List<String[]> out, HashSet<String> seen) {
        if (dir == null || depth > 3 || out.size() >= 80) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (out.size() >= 80) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (!name.equalsIgnoreCase("Android")) scanFiles(f, depth + 1, out, seen);
                continue;
            }
            String n = name.toLowerCase(Locale.US);
            String kind = null;
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp") || n.endsWith(".bmp")) kind = "image";
            else if (n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv")
                    || n.endsWith(".3gp")) kind = "video";
            if (kind != null && seen.add(f.getAbsolutePath())) {
                out.add(new String[] { kind, f.getAbsolutePath(), name });
            }
        }
    }

    void addWp(String label, String type, final String val) {
        final String ty = type;
        boolean cur = ty.equals(prefs.getString("wp_type", "anim"))
                && (ty.equals("none") || ty.equals("system")
                || val.equals(prefs.getString("wp_val", "aurora_verde")));
        if (cur) wpCur = entries.size();
        addEntry((cur ? "• " : "   ") + label, new Runnable() {
            public void run() { setWallpaper(ty, val); }
        });
    }

    void setWallpaper(String type, String val) {
        prefs.edit().putString("wp_type", type).putString("wp_val", val).apply();
        applyWallpaper();
    }

    void openWallpaperMenu() {
        if (menuOpen || moving) return;
        entries.clear();
        wpCur = 0;
        addWp("Animado: Aurora verde", "anim", "aurora_verde");
        addWp("Animado: Aurora azul", "anim", "aurora_azul");
        addWp("Animado: Aurora violeta", "anim", "aurora_violeta");
        addWp("Animado: Atardecer", "anim", "atardecer");
        addWp("Animado: Océano", "anim", "oceano");
        addWp("Fondo del sistema (Android)", "system", "");
        addWp("Negro", "none", "");
        if (hasStorage()) {
            List<String[]> files = new ArrayList<String[]>();
            HashSet<String> seen = new HashSet<String>();
            File ext = Environment.getExternalStorageDirectory();
            String[] roots = { "Pictures", "Download", "DCIM", "Wallpapers", "Fondos", "Movies" };
            for (String r : roots) scanFiles(new File(ext, r), 0, files, seen);
            for (String[] f : files) {
                addWp(("video".equals(f[0]) ? "Video: " : "Imagen: ") + f[2], f[0], f[1]);
            }
            if (files.isEmpty()) {
                addEntry("   (No hay imágenes en Pictures ni Download)", null);
            }
        } else {
            addEntry("Permitir acceso a mis imágenes", new Runnable() {
                public void run() { requestStorage(); }
            });
        }
        showPanel(true, 760, wpCur);
    }

    // ---------- Acceso a la musica (Spotify / Kodi) ----------
    boolean enableListener() {
        try {
            String cn = listenerCn.flattenToString();
            String cur = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (cur == null) cur = "";
            if (!cur.contains(cn)) {
                Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners",
                        cur.length() == 0 ? cn : cur + ":" + cn);
            }
            if (Build.VERSION.SDK_INT >= 24) {
                android.service.notification.NotificationListenerService.requestRebind(listenerCn);
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Intenta activar solo el permiso; si no se puede, avisa una vez como hacerlo. */
    void fixListener(boolean manual) {
        long now = SystemClock.elapsedRealtime();
        if (!manual && now - lastFixMs < 30000) return;
        lastFixMs = now;
        if (enableListener()) {
            if (manual) Toast.makeText(this, "Listo. Reproduce algo en Spotify.", Toast.LENGTH_LONG).show();
            return;
        }
        if (manual) {
            try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); return; }
            catch (Throwable t) { }
        }
        if (manual || !warned) {
            warned = true;
            Toast.makeText(this, "Falta un permiso: ejecuta dar_permisos.sh desde Termux (una sola vez)",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Caratula por URL (Spotify a veces la manda como enlace y no como imagen). */
    Bitmap uriBitmap(final String uri) {
        if (uri.equals(artUriFor) && uriArt != null) return uriArt;
        if (!uri.equals(uriLoading) && !uri.equals(artUriFor)) {
            uriLoading = uri;
            new Thread(new Runnable() {
                public void run() {
                    Bitmap b = null;
                    try {
                        InputStream in;
                        if (uri.startsWith("http")) {
                            HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                            c.setConnectTimeout(6000);
                            c.setReadTimeout(8000);
                            in = c.getInputStream();
                        } else {
                            in = getContentResolver().openInputStream(Uri.parse(uri));
                        }
                        b = BitmapFactory.decodeStream(in);
                        in.close();
                    } catch (Throwable t) { }
                    if (b != null) { uriArt = b; artUriFor = uri; }
                    else { uriArt = null; artUriFor = uri; }
                    uriLoading = "";
                }
            }).start();
        }
        return null;
    }

    // ---------- Iconos personalizados ----------
    static final String[] STYLE_NAMES = { "Original", "Pack Glass", "Pack Color", "Pack Neón", "Pack Claro", "Pack Mono" };

    File iconDir() {
        File d = new File(getFilesDir(), "icons");
        d.mkdirs();
        return d;
    }

    void customIcon(App a, Drawable ic, boolean hadBanner) {
        try {
            String per = prefs.getString("ic_" + a.pkg, "");
            if (per.equals("file")) {
                File f = new File(iconDir(), a.pkg + ".png");
                Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
                if (b != null) { a.img = new BitmapDrawable(getResources(), b); a.banner = true; return; }
            }
            if (per.equals("orig")) return;
            int style = prefs.getInt("icon_style", 0);
            boolean perStyle = per.startsWith("style:");
            if (perStyle) style = Integer.parseInt(per.substring(6));
            boolean force = prefs.getBoolean("icon_force", false);
            if (hadBanner && !force && !perStyle) return;
            Drawable pk = packIcon(a);
            if (pk != null) ic = pk;
            if (ic == null) return;
            if (style > 0) {
                a.img = new BitmapDrawable(getResources(), styled(ic, style));
                a.banner = true;
            } else if (pk != null || hadBanner) {
                a.img = ic;
                a.banner = false;
            }
        } catch (Throwable t) { }
    }

    /** Dibuja un icono sobre una base 16:9 con el estilo elegido. */
    Bitmap styled(Drawable ic, int style) {
        int w = 320, h = 180;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int col = avgColor(ic);
        float[] hsv = new float[3];
        Color.colorToHSV(col, hsv);
        hsv[2] *= 0.55f;
        int dark = Color.HSVToColor(hsv);
        if (style == 1) {
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF3C3C46, 0xFF1B1B21, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            p.setColor(0x1CFFFFFF);
            c.drawRect(0, 0, w, h / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5);
            p.setColor(0x66FFFFFF);
            c.drawRect(2, 2, w - 2, h - 2, p);
        } else if (style == 2) {
            p.setShader(new LinearGradient(0, 0, w, h, col, dark, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        } else if (style == 3) {
            c.drawColor(0xFF0A0A12);
            p.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.5f,
                    new int[] { (col & 0x00FFFFFF) | 0x77000000, col & 0x00FFFFFF },
                    new float[] { 0f, 1f }, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(8);
            p.setColor(col);
            c.drawRect(4, 4, w - 4, h - 4, p);
        } else if (style == 4) {
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFFFFFFFF, 0xFFD5D9E2, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        } else {
            p.setShader(new LinearGradient(0, 0, w, h, 0xFF2D3340, 0xFF12141A, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        int size = Math.round(h * 0.62f);
        Drawable d = ic;
        Drawable.ConstantState cs = ic.getConstantState();
        if (cs != null) d = cs.newDrawable().mutate();
        Bitmap tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(tmp));
        int left = (w - size) / 2, top = (h - size) / 2;
        if (style != 4 && style != 5) {
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sp.setColorFilter(new PorterDuffColorFilter(0x66000000, PorterDuff.Mode.SRC_IN));
            c.drawBitmap(tmp, left + 3, top + 6, sp);
        }
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (style == 5) {
            ip.setColorFilter(new ColorMatrixColorFilter(new float[] {
                    0, 0, 0, 0, 255,  0, 0, 0, 0, 255,  0, 0, 0, 0, 255,  0, 0, 0, 1, 0 }));
        }
        c.drawBitmap(tmp, left, top, ip);
        tmp.recycle();
        return out;
    }

    // Packs de iconos instalados (formato ADW / Nova / Apex / Go)
    List<String[]> findPacks() {
        List<String[]> res = new ArrayList<String[]>();
        HashSet<String> seen = new HashSet<String>();
        PackageManager pm = getPackageManager();
        String[] acts = { "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME",
                "com.gau.go.launcherex.theme" };
        for (String act : acts) {
            try {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(act), 0)) {
                    String pk = ri.activityInfo.packageName;
                    if (seen.add(pk)) res.add(new String[] { pk, String.valueOf(ri.loadLabel(pm)) });
                }
            } catch (Throwable t) { }
        }
        return res;
    }

    void loadPack(String pk) {
        packLoaded = pk;
        packMap = new HashMap<String, String>();
        packRes = null;
        try {
            packRes = getPackageManager().getResourcesForApplication(pk);
            XmlPullParser xp;
            int id = packRes.getIdentifier("appfilter", "xml", pk);
            if (id != 0) {
                xp = packRes.getXml(id);
            } else {
                xp = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser();
                xp.setInput(packRes.getAssets().open("appfilter.xml"), "utf-8");
            }
            int ev = xp.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "item".equals(xp.getName())) {
                    String comp = xp.getAttributeValue(null, "component");
                    String dr = xp.getAttributeValue(null, "drawable");
                    if (comp != null && dr != null) {
                        int s = comp.indexOf('{'), e = comp.indexOf('}');
                        if (s >= 0 && e > s) {
                            String cn = comp.substring(s + 1, e);
                            int sl = cn.indexOf('/');
                            if (sl > 0) {
                                String pp = cn.substring(0, sl), cls = cn.substring(sl + 1);
                                if (cls.startsWith(".")) cn = pp + "/" + pp + cls;
                                if (!packMap.containsKey(pp)) packMap.put(pp, dr);
                            }
                            if (!packMap.containsKey(cn)) packMap.put(cn, dr);
                        }
                    }
                }
                ev = xp.next();
            }
        } catch (Throwable t) { }
    }

    Drawable packIcon(App a) {
        String pk = prefs.getString("icon_pack", "");
        if (pk.length() == 0) return null;
        if (!pk.equals(packLoaded)) loadPack(pk);
        if (packRes == null || packMap == null) return null;
        String dn = packMap.get(a.cname);
        if (dn == null) dn = packMap.get(a.pkg);
        if (dn == null) return null;
        try {
            int id = packRes.getIdentifier(dn, "drawable", pk);
            if (id == 0) id = packRes.getIdentifier(dn, "mipmap", pk);
            if (id != 0) return packRes.getDrawable(id);
        } catch (Throwable t) { }
        return null;
    }

    void reloadUi() {
        handler.post(rebuild);
    }

    void setPerApp(String pkg, String val) {
        File f = new File(iconDir(), pkg + ".png");
        if (!val.equals("file") && f.exists()) f.delete();
        if (val.length() == 0) prefs.edit().remove("ic_" + pkg).apply();
        else prefs.edit().putString("ic_" + pkg, val).apply();
        reloadUi();
    }

    /** Apartado "Iconos": estilo general, packs instalados y restablecer. */
    void openIconMenu() {
        if (menuOpen || moving) return;
        entries.clear();
        final int st = prefs.getInt("icon_style", 0);
        final String pk = prefs.getString("icon_pack", "");
        for (int i = 0; i < STYLE_NAMES.length; i++) {
            final int s = i;
            addEntry((st == i ? "• " : "   ") + (i == 0 ? "Iconos originales" : STYLE_NAMES[i]), new Runnable() {
                public void run() { prefs.edit().putInt("icon_style", s).apply(); reloadUi(); }
            });
        }
        final boolean force = prefs.getBoolean("icon_force", false);
        addEntry("   Apps con banner: " + (force ? "usar icono con estilo" : "mantener banner") + "  (cambiar)",
                new Runnable() {
            public void run() { prefs.edit().putBoolean("icon_force", !force).apply(); reloadUi(); }
        });
        List<String[]> packs = findPacks();
        for (final String[] p : packs) {
            addEntry((pk.equals(p[0]) ? "• " : "   ") + "Pack instalado: " + p[1], new Runnable() {
                public void run() { packLoaded = ""; prefs.edit().putString("icon_pack", p[0]).apply(); reloadUi(); }
            });
        }
        if (packs.isEmpty()) {
            addEntry("   (Instala un pack de iconos de Play Store y aparecerá aquí)", null);
        }
        if (pk.length() > 0) {
            addEntry("   Quitar pack instalado", new Runnable() {
                public void run() { packLoaded = ""; prefs.edit().remove("icon_pack").apply(); reloadUi(); }
            });
        }
        addEntry("   Restablecer iconos de todas las apps", new Runnable() {
            public void run() {
                SharedPreferences.Editor ed = prefs.edit();
                for (String k : new ArrayList<String>(prefs.getAll().keySet())) {
                    if (k.startsWith("ic_")) ed.remove(k);
                }
                ed.remove("icon_style").remove("icon_pack").remove("icon_force").apply();
                File[] fs = iconDir().listFiles();
                if (fs != null) for (File f : fs) f.delete();
                packLoaded = "";
                reloadUi();
            }
        });
        showPanel(true, 760, st);
    }

    /** Menu "Cambiar icono" de una app concreta. */
    void openAppIconMenu(final App a) {
        if (menuOpen || moving) return;
        entries.clear();
        final String cur = prefs.getString("ic_" + a.pkg, "");
        addEntry((cur.equals("orig") ? "• " : "   ") + "Original (sin cambios)", new Runnable() {
            public void run() { setPerApp(a.pkg, "orig"); }
        });
        for (int i = 1; i < STYLE_NAMES.length; i++) {
            final int s = i;
            addEntry((cur.equals("style:" + i) ? "• " : "   ") + STYLE_NAMES[i], new Runnable() {
                public void run() { setPerApp(a.pkg, "style:" + s); }
            });
        }
        addEntry((cur.equals("file") ? "• " : "   ") + "Elegir imagen de mi galería…", new Runnable() {
            public void run() { handler.post(new Runnable() { public void run() { openIconGallery(a); } }); }
        });
        addEntry("   Usar el estilo general", new Runnable() {
            public void run() { setPerApp(a.pkg, ""); }
        });
        showPanel(true, 640, 0);
    }

    void openIconGallery(final App a) {
        if (menuOpen || moving) return;
        entries.clear();
        if (hasStorage()) {
            List<String[]> files = new ArrayList<String[]>();
            HashSet<String> seen = new HashSet<String>();
            File ext = Environment.getExternalStorageDirectory();
            String[] roots = { "Icons", "Iconos", "Pictures", "Download", "DCIM" };
            for (String r : roots) scanFiles(new File(ext, r), 0, files, seen);
            for (final String[] f : files) {
                if (!"image".equals(f[0])) continue;
                addEntry("   " + f[2], new Runnable() {
                    public void run() { importIcon(a, f[1]); }
                });
            }
            if (entries.isEmpty()) {
                addEntry("   (Pon tus imágenes en la carpeta Download o Pictures)", null);
            }
        } else {
            addEntry("Permitir acceso a mis imágenes", new Runnable() {
                public void run() { requestStorage(); }
            });
        }
        showPanel(true, 760, 0);
    }

    void importIcon(App a, String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int ss = 1;
            while (o.outWidth / ss > 1024 || o.outHeight / ss > 1024) ss *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = ss;
            Bitmap src = BitmapFactory.decodeFile(path, o);
            if (src == null) throw new RuntimeException("x");
            int w = 320, h = 180;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float ar = src.getWidth() / (float) src.getHeight();
            if (ar >= 1.5f) {
                // Panoramica: llena el cuadro
                float sc = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
                float dw = src.getWidth() * sc, dh = src.getHeight() * sc;
                c.drawBitmap(src, null, new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p);
            } else {
                // Cuadrada o vertical: se centra sobre fondo oscuro
                c.drawColor(0xFF16161A);
                float sc = h / (float) src.getHeight() * 0.9f;
                float dw = src.getWidth() * sc, dh = src.getHeight() * sc;
                c.drawBitmap(src, null, new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), p);
            }
            FileOutputStream fo = new FileOutputStream(new File(iconDir(), a.pkg + ".png"));
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            setPerApp(a.pkg, "file");
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo usar esa imagen", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Widget multimedia ----------
    void applyTrackTexts(String album, String title, String artist, String app) {
        if (trackTitle == null) return;
        trackAlbum.setText(album);
        trackTitle.setText(title);
        trackArtist.setText(artist);
        trackApp.setText(app);
    }

    void pollMedia() {
        if (trackTitle == null) return;
        MediaController best = null;
        boolean playing = false;
        if (mediaManager != null) {
            try {
                List<MediaController> cs = mediaManager.getActiveSessions(listenerCn);
                listenerMissing = false;
                if (cs != null) {
                    for (MediaController c : cs) {
                        PlaybackState s = c.getPlaybackState();
                        if (s == null) continue;
                        int st = s.getState();
                        if (st == PlaybackState.STATE_PLAYING) { best = c; playing = true; break; }
                        if (best == null && (st == PlaybackState.STATE_PAUSED
                                || st == PlaybackState.STATE_BUFFERING)) best = c;
                    }
                }
            } catch (SecurityException e) {
                listenerMissing = true;
                fixListener(false);
            } catch (Throwable t) { }
        }
        ctl = best;
        isPlaying = playing;
        long nowMs = SystemClock.elapsedRealtime();
        if (playing) lastPlayMs = nowMs;
        // El cuadro solo aparece al sonar (si pausas, se queda 20 s y se oculta)
        boolean show = best != null && (playing || (lastPlayMs > 0 && nowMs - lastPlayMs < 20000));

        String album = "", title, artist, app = "";
        Bitmap art = null;
        Drawable appIcon = null;
        String artKey = "none";
        durMs = -1;
        posMs = -1;
        if (best == null) {
            title = "Sin reproducción";
            artist = listenerMissing ? "Falta activar el acceso a notificaciones" : "Abre Spotify o Kodi";
        } else {
            title = "Reproduciendo";
            artist = "";
            try {
                MediaMetadata m = best.getMetadata();
                if (m != null) {
                    String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (a == null || a.length() == 0) a = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                    String al = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
                    if (t == null || t.length() == 0) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                    if (a == null || a.length() == 0) a = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                    if (t != null && t.length() > 0) title = t;
                    if (a != null) artist = a;
                    if (al != null) album = al;
                    art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    if (art == null) {
                        String uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                        if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
                        if (uri == null) uri = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                        if (uri != null) art = uriBitmap(uri);
                    }
                    durMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                }
                PlaybackState s = best.getPlaybackState();
                if (s != null && s.getPosition() >= 0) {
                    speed = s.getPlaybackSpeed();
                    long p = s.getPosition();
                    if (playing) {
                        p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * speed);
                    }
                    posMs = p;
                    posBase = SystemClock.elapsedRealtime();
                }
            } catch (Throwable t) { }
            try {
                PackageManager pm = getPackageManager();
                String pkg = best.getPackageName();
                app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                appIcon = pm.getApplicationIcon(pkg);
                artKey = art != null ? "b" + System.identityHashCode(art) : "i" + pkg;
            } catch (Throwable t) { artKey = art != null ? "b" + System.identityHashCode(art) : "app"; }
        }
        applyTrackTexts(album, title, artist, app);

        if (show != mediaShown) {
            mediaShown = show;
            if (show) {
                mediaWidget.setVisibility(View.VISIBLE);
                mediaWidget.animate().alpha(1f).setDuration(350).start();
            } else {
                mediaWidget.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
                    public void run() { if (!mediaShown) mediaWidget.setVisibility(View.INVISIBLE); }
                }).start();
            }
        }

        if (!artKey.equals(lastArtKey)) {
            lastArtKey = artKey;
            if (art != null) {
                albumArt.setImageBitmap(art);
            } else if (appIcon != null) {
                albumArt.setImageDrawable(appIcon);
            } else {
                albumArt.setImageDrawable(null);
            }
        }

        boolean has = show;
        for (Ctl c : ctlViews) c.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        if (ctlViews.size() > 1) ctlViews.get(1).setPlaying(playing);

        ensureViz(playing);
        if (zone == MEDIA && !has) {
            zone = dock.isEmpty() ? (grid.isEmpty() ? TOP : GRID) : DOCK;
            updateFocus();
        }
    }

    void ensureViz(boolean want) {
        if (!want) { releaseViz(); return; }
        if (viz != null || vizFailed) return;
        Visualizer v = null;
        try {
            v = new Visualizer(0);   // 0 = mezcla de salida (lo que suena en el TV)
            int[] r = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(r[1]);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer vz, byte[] w, int sr) { }
                public void onFftDataCapture(Visualizer vz, byte[] fft, int sr) { handleFft(fft); }
            }, Visualizer.getMaxCaptureRate(), false, true);
            v.setEnabled(true);
            viz = v;
            vizOn = true;
        } catch (Throwable t) {
            if (v != null) { try { v.release(); } catch (Throwable t2) { } }
            viz = null;
            vizOn = false;
            vizFailed = true;   // sin permiso de microfono: se usa onda simulada
        }
    }

    void releaseViz() {
        if (viz != null) {
            try { viz.setEnabled(false); } catch (Throwable t) { }
            try { viz.release(); } catch (Throwable t) { }
            viz = null;
        }
        vizOn = false;
    }

    void handleFft(byte[] fft) {
        int bins = fft.length / 2;
        float[] out = new float[WAVE_N];
        double maxBin = Math.min(bins - 1, 300);
        for (int b = 0; b < WAVE_N; b++) {
            int lo = (int) Math.round(Math.pow(maxBin, b / (double) WAVE_N));
            int hi = (int) Math.round(Math.pow(maxBin, (b + 1) / (double) WAVE_N));
            if (lo < 1) lo = 1;
            if (hi <= lo) hi = lo + 1;
            float m = 0f;
            for (int k = lo; k < hi && 2 * k + 1 < fft.length; k++) {
                float re = fft[2 * k], im = fft[2 * k + 1];
                m = Math.max(m, (float) Math.sqrt(re * re + im * im));
            }
            float v = (float) (Math.log10(1.0 + m) / 2.0);
            v *= 0.85f + 0.5f * b / WAVE_N;
            out[b] = Math.min(1f, v);
        }
        fftLevels = out;
        fftTime = SystemClock.elapsedRealtime();
    }

    void animateWave() {
        if (wave == null) return;
        float t = System.currentTimeMillis() / 1000f;
        float[] fl = fftLevels;
        boolean fresh = vizOn && (SystemClock.elapsedRealtime() - fftTime) < 400;
        boolean any = false;
        for (int i = 0; i < WAVE_N; i++) {
            float target = 0f;
            if (isPlaying) {
                if (fresh) {
                    target = fl[i];
                } else {
                    float a = (float) Math.abs(Math.sin(t * 3.1 + i * 0.55) * Math.cos(t * 1.7 + i * 0.31));
                    target = 0.2f + 0.7f * a + (float) Math.random() * 0.1f;
                }
            }
            float cur = waveLevels[i];
            cur = target > cur ? target * 0.6f + cur * 0.4f : cur * 0.82f + target * 0.18f;
            if (cur < 0.01f) cur = 0f;
            if (cur > 0f) any = true;
            waveLevels[i] = cur;
        }
        float head = 0.5f;
        if (ctl != null && durMs > 0 && posMs >= 0) {
            long cur = posMs + (isPlaying ? (long) ((SystemClock.elapsedRealtime() - posBase) * speed) : 0L);
            head = Math.max(0f, Math.min(1f, cur / (float) durMs));
        }
        wave.head = head;
        if (isPlaying || any) wave.invalidate();
    }

    void mediaAction() {
        if (ctl == null) return;
        try {
            MediaController.TransportControls tc = ctl.getTransportControls();
            if (mediaIdx == 0) tc.skipToPrevious();
            else if (mediaIdx == 2) tc.skipToNext();
            else if (isPlaying) tc.pause();
            else tc.play();
        } catch (Throwable t) { }
        handler.postDelayed(new Runnable() { public void run() { pollMedia(); } }, 350);
    }

    // ---------- Reloj y red ----------
    String netName() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null && ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "Ethernet";
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String s = wi != null ? wi.getSSID() : null;
            if (s != null && !s.equals("<unknown ssid>") && !s.equals("0x")) {
                if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
                if (s.length() > 0) return s;
            }
        } catch (Throwable t) { }
        return "Wi-Fi";
    }

    void updateClock() {
        if (greeting == null) return;
        Date now = new Date();
        int hr = Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(now));
        greeting.setText(hr < 12 ? "Buenos días" : (hr < 19 ? "Buenas tardes" : "Buenas noches"));
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.US).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es", "CO")).format(now);
        date.setText(d.substring(0, 1).toUpperCase(new Locale("es")) + d.substring(1));
        String net = netName();
        if (!net.equals(lastNet)) {
            lastNet = net;
            pills[3].setText(net);
            layoutPills();
            if (zone == TOP) updateFocus();
        }
    }

    // ---------- Foco y navegacion ----------
    void updateFocus() {
        for (View v : dockViews) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        for (View v : gridViews) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        for (TextView l : gridLabels) l.setVisibility(View.INVISIBLE);
        boolean has = false;
        float cx = 0, cy = 0, bw = 0, bh = 0;
        float s = 1f;
        float pad = px(8);
        float big = moving ? 1.2f : 1.1f;
        if (moving) pad = px(14);
        if (zone == TOP) {
            FrameLayout.LayoutParams t = (FrameLayout.LayoutParams) pills[topIdx].getLayoutParams();
            cx = t.leftMargin + t.width / 2f; cy = t.topMargin + t.height / 2f;
            bw = t.width; bh = t.height; pad = px(6); has = true;
        } else if (zone == MEDIA && hasMedia()) {
            FrameLayout.LayoutParams wl = (FrameLayout.LayoutParams) mediaWidget.getLayoutParams();
            FrameLayout.LayoutParams t = (FrameLayout.LayoutParams) ctlViews.get(mediaIdx).getLayoutParams();
            cx = wl.leftMargin + t.leftMargin + t.width / 2f;
            cy = wl.topMargin + t.topMargin + t.height / 2f;
            bw = t.width; bh = t.height; pad = px(10); has = true;
        } else if (zone == DOCK && !dock.isEmpty()) {
            View target = dockViews.get(dockIdx);
            FrameLayout.LayoutParams t = (FrameLayout.LayoutParams) target.getLayoutParams();
            s = big;
            target.animate().scaleX(s).scaleY(s).setDuration(120).start();
            cx = t.leftMargin + t.width / 2f; cy = t.topMargin + t.height / 2f;
            bw = t.width; bh = t.height; has = true;
        } else if (zone == GRID && !grid.isEmpty()) {
            View target = gridViews.get(gridIdx);
            FrameLayout.LayoutParams t = (FrameLayout.LayoutParams) target.getLayoutParams();
            s = big;
            target.animate().scaleX(s).scaleY(s).setDuration(120).start();
            gridLabels.get(gridIdx).setVisibility(View.VISIBLE);
            cx = t.leftMargin + t.width / 2f; cy = t.topMargin + t.height / 2f;
            bw = t.width; bh = t.height; has = true;
        }
        if (has) {
            int w = Math.round(bw * s + 2 * pad), h = Math.round(bh * s + 2 * pad);
            FrameLayout.LayoutParams g = (FrameLayout.LayoutParams) glass.getLayoutParams();
            g.width = w;
            g.height = h;
            g.leftMargin = Math.round(cx - w / 2f);
            g.topMargin = Math.round(cy - h / 2f);
            glass.setLayoutParams(g);
            glass.setBackground(moving ? glassMoveBg : glassNormalBg);
            glass.setVisibility(View.VISIBLE);
        } else {
            glass.setVisibility(View.INVISIBLE);
        }
        boolean showName = (zone == DOCK && !dock.isEmpty());
        dockName.setVisibility(showName ? View.VISIBLE : View.INVISIBLE);
        glow.setVisibility(showName ? View.VISIBLE : View.INVISIBLE);
        if (showName) {
            App a = dock.get(dockIdx);
            dockName.setText(a.label);
            GradientDrawable gd = new GradientDrawable();
            gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            gd.setGradientRadius(px(260));
            gd.setColors(new int[] { (a.glow & 0x00FFFFFF) | 0x66000000, a.glow & 0x00FFFFFF });
            glow.setBackground(gd);
        }
        float ty = 0;
        if (zone == GRID) {
            int sr = Math.max(0, gridIdx / 4 - 2);
            ty = -(gridTop - px(200) + sr * pitch);
        }
        page.animate().translationY(ty).setDuration(180).start();
        prefs.edit().putInt("zone", zone).putInt("top", topIdx).putInt("dock", dockIdx)
                .putInt("grid", gridIdx).apply();
    }

    void move(int dx, int dy) {
        if (zone == TOP) {
            if (dx != 0) topIdx = clampi(topIdx + dx, 0, PILLS - 1);
            else if (dy > 0) {
                if (hasMedia()) zone = MEDIA;
                else if (!dock.isEmpty()) zone = DOCK;
                else if (!grid.isEmpty()) { zone = GRID; gridIdx = 0; }
            }
        } else if (zone == MEDIA) {
            if (dx != 0) mediaIdx = clampi(mediaIdx + dx, 0, 2);
            else if (dy < 0) zone = TOP;
            else if (dy > 0) {
                if (!dock.isEmpty()) zone = DOCK;
                else if (!grid.isEmpty()) { zone = GRID; gridIdx = 0; }
            }
        } else if (zone == DOCK) {
            if (dx != 0) dockIdx = clampi(dockIdx + dx, 0, dock.size() - 1);
            else if (dy < 0) zone = hasMedia() ? MEDIA : TOP;
            else if (dy > 0 && !grid.isEmpty()) {
                zone = GRID;
                gridIdx = Math.min(dockIdx, grid.size() - 1);
            }
        } else {
            int r = gridIdx / 4, c = gridIdx % 4;
            if (dx != 0) {
                int n = gridIdx + dx;
                if (n >= 0 && n < grid.size() && n / 4 == r) gridIdx = n;
            } else if (dy > 0) {
                int n = gridIdx + 4;
                if (n >= grid.size()) {
                    int lastRow = (grid.size() - 1) / 4;
                    n = lastRow > r ? grid.size() - 1 : gridIdx;
                }
                gridIdx = n;
            } else if (dy < 0) {
                if (r == 0) {
                    if (!dock.isEmpty()) { zone = DOCK; dockIdx = Math.min(c, dock.size() - 1); }
                    else zone = hasMedia() ? MEDIA : TOP;
                } else gridIdx -= 4;
            }
        }
        updateFocus();
    }

    // ---------- Mover / dock / ocultar / desinstalar ----------
    void startMove() {
        if (zone == TOP || zone == MEDIA || menuOpen) return;
        if (zone == DOCK && dock.isEmpty()) return;
        if (zone == GRID && grid.isEmpty()) return;
        moving = true;
        Toast.makeText(this, "Mover: usa las flechas. OK para soltar", Toast.LENGTH_SHORT).show();
        updateFocus();
    }

    void endMove() {
        moving = false;
        saveState();
        updateFocus();
    }

    void moveItem(int dx, int dy) {
        if (zone == DOCK) {
            if (dx == 0) return;
            int n = dockIdx + dx;
            if (n < 0 || n >= dock.size()) return;
            Collections.swap(dock, dockIdx, n);
            Collections.swap(dockViews, dockIdx, n);
            placeDock(dockIdx);
            placeDock(n);
            dockIdx = n;
        } else if (zone == GRID) {
            int n = gridIdx + dx + dy * 4;
            if (n < 0 || n >= grid.size()) return;
            if (dx != 0 && n / 4 != gridIdx / 4) return;
            Collections.swap(grid, gridIdx, n);
            Collections.swap(gridViews, gridIdx, n);
            Collections.swap(gridLabels, gridIdx, n);
            placeGrid(gridIdx);
            placeGrid(n);
            gridIdx = n;
        }
        updateFocus();
    }

    void addToDock() {
        App a = grid.get(gridIdx);
        a.glow = avgColor(a.img);
        if (dock.size() < 4) {
            dock.add(a);
            grid.remove(gridIdx);
            dockIdx = dock.size() - 1;
        } else {
            App old = dock.get(3);
            dock.set(3, a);
            grid.set(gridIdx, old);
            dockIdx = 3;
        }
        zone = DOCK;
        saveState();
        buildUi();
    }

    void removeFromDock() {
        dock.remove(dockIdx);
        saveState();
        if (dockIdx > 0) dockIdx--;
        zone = dock.isEmpty() ? GRID : DOCK;
        buildUi();
    }

    void hideApp() {
        String pkg = grid.get(gridIdx).pkg;
        String h = prefs.getString("hidden", "");
        prefs.edit().putString("hidden", h.length() == 0 ? pkg : h + "," + pkg).apply();
        saveState();
        zone = GRID;
        buildUi();
    }

    void showHidden() {
        prefs.edit().remove("hidden").apply();
        buildUi();
    }

    void uninstallApp() {
        App a = (zone == DOCK) ? dock.get(dockIdx) : grid.get(gridIdx);
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(a.pkg, 0);
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (sys) {
                Toast.makeText(this, a.label + " es del sistema: no se puede desinstalar", Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Throwable t) { }
        try {
            Intent intent = new Intent(Intent.ACTION_DELETE);
            intent.setData(Uri.parse("package:" + a.pkg));
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo desinstalar " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    void addEntry(String label, Runnable r) {
        Entry e = new Entry();
        e.label = label;
        e.action = r;
        entries.add(e);
    }

    void openContextMenu() {
        if (zone == TOP || zone == MEDIA || menuOpen || moving) return;
        entries.clear();
        if (zone == DOCK) {
            if (dock.isEmpty()) return;
            addEntry("Mover", new Runnable() { public void run() { startMove(); } });
            final App ia = dock.get(dockIdx);
            addEntry("Cambiar icono", new Runnable() {
                public void run() { handler.post(new Runnable() { public void run() { openAppIconMenu(ia); } }); }
            });
            addEntry("Quitar del dock", new Runnable() { public void run() { removeFromDock(); } });
            addEntry("Desinstalar app", new Runnable() { public void run() { uninstallApp(); } });
        } else {
            if (grid.isEmpty()) return;
            addEntry("Mover", new Runnable() { public void run() { startMove(); } });
            final App ga = grid.get(gridIdx);
            addEntry("Cambiar icono", new Runnable() {
                public void run() { handler.post(new Runnable() { public void run() { openAppIconMenu(ga); } }); }
            });
            addEntry(dock.size() < 4 ? "Agregar al dock" : "Agregar al dock (reemplaza última)",
                    new Runnable() { public void run() { addToDock(); } });
            addEntry("Ocultar", new Runnable() { public void run() { hideApp(); } });
            addEntry("Desinstalar app", new Runnable() { public void run() { uninstallApp(); } });
        }
        if (hiddenInstalled > 0) {
            addEntry("Mostrar ocultas (" + hiddenInstalled + ")",
                    new Runnable() { public void run() { showHidden(); } });
        }
        addEntry("Cambiar fondo", new Runnable() {
            public void run() { handler.post(new Runnable() { public void run() { openWallpaperMenu(); } }); }
        });
        if (listenerMissing) {
            addEntry("Activar lectura de música", new Runnable() {
                public void run() { fixListener(true); }
            });
        }
        showPanel(true, 560, 0);
    }

    void activate() {
        if (zone == TOP) {
            if (topIdx == 0) openWallpaperMenu();
            else if (topIdx == 1) openIconMenu();
            else if (topIdx == 2) openMenu();
            else if (topIdx == 3) openSettings(Settings.ACTION_WIFI_SETTINGS);
            else openSettings(Settings.ACTION_SETTINGS);
        } else if (zone == MEDIA) {
            mediaAction();
        } else {
            App a = zone == DOCK ? dock.get(dockIdx) : grid.get(gridIdx);
            try { startActivity(a.launch); }
            catch (Throwable t) { Toast.makeText(this, "No se pudo abrir " + a.label, Toast.LENGTH_SHORT).show(); }
        }
    }

    void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Throwable t2) { Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show(); }
        }
    }

    void openMenu() {
        entries.clear();
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tm != null) {
                for (TvInputInfo info : tm.getTvInputList()) {
                    Entry e = new Entry();
                    CharSequence l = null;
                    try { l = info.loadCustomLabel(this); } catch (Throwable t) { }
                    if (l == null || l.length() == 0) l = info.loadLabel(this);
                    e.label = String.valueOf(l);
                    if (info.isPassthroughInput()) {
                        e.intent = new Intent(Intent.ACTION_VIEW,
                                TvContract.buildChannelUriForPassthroughInput(info.getId()));
                    } else {
                        e.intent = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
                        e.fallback = getPackageManager()
                                .getLaunchIntentForPackage(info.getServiceInfo().packageName);
                    }
                    entries.add(e);
                }
            }
        } catch (Throwable t) { }
        if (entries.isEmpty()) {
            Entry e = new Entry();
            e.label = "Sin entradas TV";
            entries.add(e);
        }
        showPanel(false, 380, 0);
    }

    // ---------- Panel flotante (con scroll) ----------
    void showPanel(boolean centered, int wUnits, int initial) {
        int rowH = px(64), w = px(wUnits);
        menuVis = Math.min(entries.size(), 9);
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(glassFill(px(28), 0xEE2A2A30, 0xEE17171B, 0x55FFFFFF));
        menuRows.clear();
        for (int i = 0; i < entries.size(); i++) {
            TextView r = text(px(28), Color.WHITE);
            r.setText(entries.get(i).label);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(28), 0, px(16), 0);
            panel.addView(r, lp(w - px(24), rowH, px(12), px(12)));
            menuRows.add(r);
        }
        int h = menuVis * rowH + px(24);
        if (centered) {
            root.addView(panel, lp(w, h, (W - w) / 2, (H - h) / 2));
        } else {
            FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) pills[2].getLayoutParams();
            int x = Math.min(pl.leftMargin, W - px(40) - w);
            int y = pl.topMargin + pl.height + px(14);
            page.addView(panel, lp(w, h, x, y));
        }
        menuPanel = panel;
        menuIdx = clampi(initial, 0, Math.max(0, entries.size() - 1));
        menuStart = 0;
        menuOpen = true;
        paintMenu();
    }

    void paintMenu() {
        if (menuIdx < menuStart) menuStart = menuIdx;
        if (menuIdx >= menuStart + menuVis) menuStart = menuIdx - menuVis + 1;
        int rowH = px(64);
        for (int i = 0; i < menuRows.size(); i++) {
            TextView r = menuRows.get(i);
            boolean vis = i >= menuStart && i < menuStart + menuVis;
            r.setVisibility(vis ? View.VISIBLE : View.GONE);
            if (vis) {
                FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) r.getLayoutParams();
                l.topMargin = px(12) + (i - menuStart) * rowH;
                r.setLayoutParams(l);
            }
            r.setBackground(i == menuIdx ? rounded(0x55FFFFFF, px(18)) : null);
        }
    }

    void closeMenu() {
        if (menuPanel != null && menuPanel.getParent() != null) {
            ((android.view.ViewGroup) menuPanel.getParent()).removeView(menuPanel);
        }
        menuPanel = null;
        menuOpen = false;
    }

    void menuKey(int k) {
        if (k == KeyEvent.KEYCODE_DPAD_UP) { menuIdx = Math.max(0, menuIdx - 1); paintMenu(); }
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) { menuIdx = Math.min(entries.size() - 1, menuIdx + 1); paintMenu(); }
        else if (k == KeyEvent.KEYCODE_BACK) closeMenu();
    }

    void menuSelect() {
        if (entries.isEmpty()) return;
        Entry e = entries.get(menuIdx);
        closeMenu();
        if (e.action != null) { e.action.run(); return; }
        if (e.intent == null) return;
        try { startActivity(e.intent); }
        catch (Throwable t) {
            try { if (e.fallback != null) startActivity(e.fallback); else throw t; }
            catch (Throwable t2) { Toast.makeText(this, "No se pudo cambiar de entrada", Toast.LENGTH_SHORT).show(); }
        }
    }

    // ---------- Control remoto ----------
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean center = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean nav = center || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU;
        if (!nav) return super.dispatchKeyEvent(e);
        int act = e.getAction();
        if (center) {
            if (act == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    centerDown = true;
                    longFired = false;
                    if (!menuOpen && !moving && zone != TOP && zone != MEDIA) handler.postDelayed(longPress, 500);
                }
            } else if (act == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPress);
                boolean was = centerDown;
                centerDown = false;
                if (was && !longFired) {
                    if (menuOpen) menuSelect();
                    else if (moving) endMove();
                    else activate();
                }
                longFired = false;
            }
            return true;
        }
        if (act != KeyEvent.ACTION_DOWN) return true;
        if (k == KeyEvent.KEYCODE_MENU) {
            if (moving) endMove();
            else if (!menuOpen) openContextMenu();
            return true;
        }
        if (menuOpen) { menuKey(k); return true; }
        if (moving) {
            if (k == KeyEvent.KEYCODE_BACK) endMove();
            else if (k == KeyEvent.KEYCODE_DPAD_UP) moveItem(0, -1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) moveItem(0, 1);
            else if (k == KeyEvent.KEYCODE_DPAD_LEFT) moveItem(-1, 0);
            else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) moveItem(1, 0);
            return true;
        }
        boolean first = e.getRepeatCount() == 0;
        if (k == KeyEvent.KEYCODE_DPAD_UP) { if (first) move(0, -1); }
        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) { if (first) move(0, 1); }
        else if (k == KeyEvent.KEYCODE_DPAD_LEFT) move(-1, 0);
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) move(1, 0);
        return true;
    }
}
