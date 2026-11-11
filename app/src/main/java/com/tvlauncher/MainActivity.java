package com.tvlauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final int TOP = 0, DOCK = 1, GRID = 2;

    static class App { String pkg, label; Drawable img; boolean banner; Intent launch; int glow; }
    static class Entry { String label; Intent intent; Intent fallback; Runnable action; }

    int W, H;
    float u;
    FrameLayout root, page;
    View glass, glow, menuPanel;
    TextView greeting, clock, date, dockName;
    final TextView[] pills = new TextView[3];
    final List<App> dock = new ArrayList<App>();
    final List<App> grid = new ArrayList<App>();
    final List<View> dockViews = new ArrayList<View>();
    final List<View> gridViews = new ArrayList<View>();
    final List<TextView> gridLabels = new ArrayList<TextView>();
    final List<Entry> entries = new ArrayList<Entry>();
    final List<TextView> menuRows = new ArrayList<TextView>();
    int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0, menuIdx = 0;
    boolean menuOpen = false;
    boolean moving = false, centerDown = false, longFired = false;
    int hiddenInstalled = 0;
    int gridTop, pitch;
    int cell, tw, th, bandX, bandY, bandH, gcw, gtw, gth, gx0;
    GradientDrawable glassNormalBg, glassMoveBg;
    Typeface face;
    String lastNet = "";
    final Handler handler = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    BroadcastReceiver pkgReceiver;

    final Runnable rebuild = new Runnable() { public void run() { buildUi(); } };
    final Runnable tick = new Runnable() {
        public void run() { updateClock(); handler.postDelayed(this, 15000); }
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

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(longPress);
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Throwable t) { }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (menuOpen) closeMenu();
        if (moving) { moving = false; saveState(); }
        if (!dock.isEmpty()) zone = DOCK;
        updateFocus();
    }

    Typeface loadFace() {
        try { return Typeface.createFromAsset(getAssets(), "fonts/chubby.ttf"); }
        catch (Throwable t) { return Typeface.create("sans-serif-black", Typeface.NORMAL); }
    }

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

    TextView text(float sizePx, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
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
                if (bn != null) { a.img = bn; a.banner = true; }
                else { a.img = ri.loadIcon(pm); a.banner = false; }
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

    void clampState() {
        if (zone == DOCK && dock.isEmpty()) zone = grid.isEmpty() ? TOP : GRID;
        if (zone == GRID && grid.isEmpty()) zone = dock.isEmpty() ? TOP : DOCK;
        topIdx = clampi(topIdx, 0, 2);
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

    void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        u = H / 1080f;
        dock.clear(); grid.clear(); dockViews.clear(); gridViews.clear(); gridLabels.clear();
        menuOpen = false; menuPanel = null; lastNet = ""; moving = false;
        loadApps();
        clampState();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        pitch = px(230);
        gridTop = H + px(40);
        int rows = Math.max(1, (grid.size() + 3) / 4);
        page = new FrameLayout(this);
        root.addView(page, new FrameLayout.LayoutParams(W, gridTop + rows * pitch + px(300)));

        greeting = text(px(34), 0xB3FFFFFF, "sans-serif-light");
        page.addView(greeting, lp(W / 2, px(46), px(80), px(56)));
        clock = text(px(110), Color.WHITE, "sans-serif-thin");
        page.addView(clock, lp(W / 2, px(130), px(74), px(96)));
        date = text(px(32), 0x99FFFFFF, "sans-serif-light");
        page.addView(date, lp(W / 2, px(44), px(80), px(232)));

        String[] pt = { "Entradas", "Wi-Fi", "Configuración" };
        for (int i = 0; i < 3; i++) {
            TextView p = text(px(26), Color.WHITE, "sans-serif");
            p.setText(pt[i]);
            p.setGravity(Gravity.CENTER);
            p.setPadding(px(34), 0, px(34), 0);
            p.setBackground(rounded(0x26FFFFFF, px(34)));
            pills[i] = p;
            page.addView(p, lp(px(200), px(68), 0, px(64)));
        }
        layoutPills();

        cell = px(230);
        tw = px(190);
        th = px(107);
        bandH = px(190);
        int bandW = cell * 4 + px(40);
        bandX = (W - bandW) / 2;
        bandY = H - px(250);
        View band = new View(this);
        band.setBackground(rounded(0x1FFFFFFF, px(48)));
        page.addView(band, lp(bandW, bandH, bandX, bandY));
        glow = new View(this);
        page.addView(glow, lp(px(900), px(520), (W - px(900)) / 2, bandY - px(350)));
        dockName = text(px(64), Color.WHITE, "sans-serif-light");
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
            TextView l = text(px(26), Color.WHITE, "sans-serif");
            l.setText(grid.get(i).label);
            l.setGravity(Gravity.CENTER);
            l.setVisibility(View.INVISIBLE);
            page.addView(l, lp(gcw, px(36), 0, 0));
            gridLabels.add(l);
            placeGrid(i);
        }

        glassNormalBg = rounded(0x33FFFFFF, px(24));
        glassNormalBg.setStroke(px(3), 0xAAFFFFFF);
        glassMoveBg = rounded(0x40FFD60A, px(26));
        glassMoveBg.setStroke(px(4), 0xFFFFD60A);
        glass = new View(this);
        glass.setBackground(glassNormalBg);
        glass.setVisibility(View.INVISIBLE);
        page.addView(glass, lp(10, 10, 0, 0));

        setContentView(root);
        updateClock();
        updateFocus();
    }

    void layoutPills() {
        int x = W - px(80);
        for (int i = 2; i >= 0; i--) {
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
            pills[1].setText(net);
            layoutPills();
            if (zone == TOP) updateFocus();
        }
    }

    void updateFocus() {
        for (View v : dockViews) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        for (View v : gridViews) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        for (TextView l : gridLabels) l.setVisibility(View.INVISIBLE);
        View target = null;
        float s = 1f;
        float pad = px(8);
        float big = moving ? 1.2f : 1.1f;
        if (moving) pad = px(14);
        if (zone == TOP) {
            target = pills[topIdx];
            pad = px(6);
        } else if (zone == DOCK && !dock.isEmpty()) {
            target = dockViews.get(dockIdx);
            s = big;
        } else if (zone == GRID && !grid.isEmpty()) {
            target = gridViews.get(gridIdx);
            s = big;
            gridLabels.get(gridIdx).setVisibility(View.VISIBLE);
        }
        if (target != null) {
            if (s != 1f) target.animate().scaleX(s).scaleY(s).setDuration(120).start();
            FrameLayout.LayoutParams t = (FrameLayout.LayoutParams) target.getLayoutParams();
            float cx = t.leftMargin + t.width / 2f, cy = t.topMargin + t.height / 2f;
            int w = Math.round(t.width * s + 2 * pad), h = Math.round(t.height * s + 2 * pad);
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
            if (dx != 0) topIdx = clampi(topIdx + dx, 0, 2);
            else if (dy > 0) {
                if (!dock.isEmpty()) zone = DOCK;
                else if (!grid.isEmpty()) { zone = GRID; gridIdx = 0; }
            }
        } else if (zone == DOCK) {
            if (dx != 0) dockIdx = clampi(dockIdx + dx, 0, dock.size() - 1);
            else if (dy < 0) zone = TOP;
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
                    else zone = TOP;
                } else gridIdx -= 4;
            }
        }
        updateFocus();
    }

    void startMove() {
        if (zone == TOP || menuOpen) return;
        if (zone == DOCK && dock.isEmpty()) return;
        if (zone == GRID && grid.isEmpty()) return;
        moving = true;
        Toast.makeText(this, "Mover: usa las flechas. Pulsa OK para soltar", Toast.LENGTH_LONG).show();
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

    void addEntry(String label, Runnable r) {
        Entry e = new Entry();
        e.label = label;
        e.action = r;
        entries.add(e);
    }

    void openContextMenu() {
        if (zone == TOP || menuOpen || moving) return;
        entries.clear();
        if (zone == DOCK) {
            if (dock.isEmpty()) return;
            addEntry("Mover", new Runnable() { public void run() { startMove(); } });
            addEntry("Quitar del dock", new Runnable() { public void run() { removeFromDock(); } });
        } else {
            if (grid.isEmpty()) return;
            addEntry("Mover", new Runnable() { public void run() { startMove(); } });
            addEntry(dock.size() < 4 ? "Agregar al dock" : "Agregar al dock (cambia la última)",
                    new Runnable() { public void run() { addToDock(); } });
            addEntry("Ocultar", new Runnable() { public void run() { hideApp(); } });
        }
        if (hiddenInstalled > 0) {
            addEntry("Mostrar apps ocultas (" + hiddenInstalled + ")",
                    new Runnable() { public void run() { showHidden(); } });
        }
        showPanel(true);
    }

    void activate() {
        if (zone == TOP) {
            if (topIdx == 0) openMenu();
            else if (topIdx == 1) openSettings(Settings.ACTION_WIFI_SETTINGS);
            else openSettings(Settings.ACTION_SETTINGS);
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
            e.label = "Sin entradas";
            entries.add(e);
        }
        showPanel(false);
    }

    void showPanel(boolean centered) {
        int rowH = px(64), w = px(centered ? 460 : 380);
        FrameLayout panel = new FrameLayout(this);
        panel.setBackground(rounded(0xF0242428, px(28)));
        menuRows.clear();
        for (int i = 0; i < entries.size(); i++) {
            TextView r = text(px(28), Color.WHITE, "sans-serif");
            r.setText(entries.get(i).label);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(28), 0, px(16), 0);
            panel.addView(r, lp(w - px(24), rowH, px(12), px(12) + i * rowH));
            menuRows.add(r);
        }
        int h = entries.size() * rowH + px(24);
        if (centered) {
            root.addView(panel, lp(w, h, (W - w) / 2, (H - h) / 2));
        } else {
            FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) pills[0].getLayoutParams();
            int x = Math.min(pl.leftMargin, W - px(40) - w);
            int y = pl.topMargin + pl.height + px(14);
            page.addView(panel, lp(w, h, x, y));
        }
        menuPanel = panel;
        menuIdx = 0;
        menuOpen = true;
        paintMenu();
    }

    void paintMenu() {
        for (int i = 0; i < menuRows.size(); i++) {
            menuRows.get(i).setBackground(i == menuIdx ? rounded(0x44FFFFFF, px(18)) : null);
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
                    if (!menuOpen && !moving && zone != TOP) handler.postDelayed(longPress, 600);
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
