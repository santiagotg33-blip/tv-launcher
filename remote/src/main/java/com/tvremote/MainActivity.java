package com.tvremote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final int GRAY = 0xFF2C2C2E, GRAY_P = 0xFF58585C;
    static final int BLUE = 0xFF0A84FF, BLUE_P = 0xFF4DA3FF;
    static final int RED = 0xFFC0392B, RED_P = 0xFFE5604F;
    static final int GREEN = 0xFF2E9E4F, GREEN_P = 0xFF4CC474;
    static final int AMBER = 0xFFB8860B, AMBER_P = 0xFFE0A81E;
    static final int OK_GREEN = 0xFF30D158, BAD_RED = 0xFFFF453A, MUTED = 0xFF9A9AA0;

    static final HashMap<String, String> NAMES = new HashMap<String, String>();
    static {
        NAMES.put("com.magmaplayer", "Magma");
        NAMES.put("org.xbmc.kodi", "Kodi");
        NAMES.put("com.google.android.youtube.tv", "YouTube");
        NAMES.put("com.vanced.android.youtube", "YouTube Vanced");
        NAMES.put("com.youtube.armanych", "YouTube (2)");
        NAMES.put("cm.aptoidetv.pt.top_tech", "Aptoide TV");
        NAMES.put("com.toptech.aptoide", "Aptoide Guide");
        NAMES.put("com.esaba.downloader", "Downloader");
        NAMES.put("com.android.chrome", "Chrome");
        NAMES.put("com.android.tv.settings", "Configuración");
        NAMES.put("com.android.vending", "Play Store");
        NAMES.put("com.android.tv", "TV en vivo");
        NAMES.put("com.movievn.cinevi", "Movie (Cinevi)");
        NAMES.put("com.toptech.am", "MemManager");
        NAMES.put("com.toptech.hyundai", "Hyundai Store");
        NAMES.put("hyundaielectronics.installapp", "InstallApp");
        NAMES.put("com.rtk.android.miracast", "Screen Mirroring");
        NAMES.put("com.realtek.media.player", "Reproductor");
        NAMES.put("com.tvlauncher", "Inicio (lanzador)");
        NAMES.put("com.toptech.launcher", "Inicio de fábrica");
    }

    static class AppEntry { String pkg, name; boolean mine; }

    final Adb adb = new Adb();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    final AtomicInteger pending = new AtomicInteger();
    final ScrollView[] pages = new ScrollView[4];
    final TextView[] tabs = new TextView[4];
    SharedPreferences prefs;
    TextView status, appsStatus, chipAll, chipMine, modeChip;
    EditText ipField, textField, search;
    LinearLayout appList;
    List<AppEntry> apps = new ArrayList<AppEntry>();
    boolean onlyMine = false;
    boolean gamepadMode = true;
    volatile String host = "192.168.1.34";
    volatile int tvW = 1920, tvH = 1080;
    float d;

    // ---------- utilidades ----------
    int dp(int v) { return Math.round(v * d); }

    GradientDrawable shape(int color, int rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    StateListDrawable pad(int normal, int pressed) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, shape(pressed, 18));
        s.addState(new int[] {}, shape(normal, 18));
        return s;
    }

    String msg(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    void setStatus(final String s, final int c) {
        ui.post(new Runnable() {
            public void run() { status.setText(s); status.setTextColor(c); }
        });
    }

    // ---------- red ----------
    void connectNow() throws IOException {
        adb.connect(host);
        try {
            String s = adb.shell("wm size");
            Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(s);
            int w = 0, h = 0;
            while (m.find()) { w = Integer.parseInt(m.group(1)); h = Integer.parseInt(m.group(2)); }
            if (w > 0 && h > 0) { tvW = w; tvH = h; }
        } catch (Exception e) { }
    }

    void connectAsync() {
        host = ipField.getText().toString().trim();
        prefs.edit().putString("ip", host).apply();
        setStatus("Conectando...", MUTED);
        io.execute(new Runnable() {
            public void run() {
                try {
                    connectNow();
                    setStatus("Conectado a " + host, OK_GREEN);
                } catch (Throwable t) {
                    setStatus("Sin conexión: " + msg(t), BAD_RED);
                }
            }
        });
    }

    void exec(final String cmd) {
        if (pending.get() >= 3) return;
        pending.incrementAndGet();
        io.execute(new Runnable() {
            public void run() {
                try {
                    try { adb.shell(cmd); }
                    catch (IOException e) { connectNow(); adb.shell(cmd); }
                    setStatus("Conectado a " + host, OK_GREEN);
                } catch (Throwable t) {
                    setStatus("Sin conexión: " + msg(t), BAD_RED);
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
    }

    void key(int code) { exec("input keyevent " + code); }

    Runnable keyAction(final int code) {
        return new Runnable() { public void run() { key(code); } };
    }

    Runnable gpAction(final int code) {
        return new Runnable() {
            public void run() {
                exec((gamepadMode ? "input gamepad keyevent " : "input keyevent ") + code);
            }
        };
    }

    Runnable appAction(final String pkg) {
        return new Runnable() {
            public void run() {
                exec("o=$(monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1); "
                        + "case \"$o\" in *\"No activities\"*) monkey -p " + pkg
                        + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1;; esac");
            }
        };
    }

    Runnable scrollAction(final boolean down) {
        return new Runnable() {
            public void run() {
                int x = tvW / 2;
                int y1 = down ? tvH * 3 / 4 : tvH / 4;
                int y2 = down ? tvH / 4 : tvH * 3 / 4;
                exec("input swipe " + x + " " + y1 + " " + x + " " + y2 + " 300");
            }
        };
    }

    // ---------- tactil ----------
    int scalePos(float v, int size, int tv) {
        int r = Math.round(v / size * tv);
        return Math.max(0, Math.min(tv - 1, r));
    }

    void gesture(float x1, float y1, float x2, float y2, long dt, boolean moved, int w, int h) {
        if (w <= 0 || h <= 0) return;
        int ax = scalePos(x1, w, tvW), ay = scalePos(y1, h, tvH);
        int bx = scalePos(x2, w, tvW), by = scalePos(y2, h, tvH);
        if (!moved) {
            if (dt >= 600) exec("input swipe " + ax + " " + ay + " " + ax + " " + ay + " 900");
            else exec("input tap " + ax + " " + ay);
        } else {
            long dur = Math.max(120, Math.min(dt, 900));
            exec("input swipe " + ax + " " + ay + " " + bx + " " + by + " " + dur);
        }
    }

    class Pad extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        float sx, sy, lx = -1, ly = -1;
        long t0;
        boolean moved;

        Pad(Context c) {
            super(c);
            fill.setColor(0xFF1C1C1E);
            line.setColor(0xFF3A3A3C);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            dot.setColor(0xAA0A84FF);
            txt.setColor(0xFF6E6E73);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(dp(15));
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, w * 9 / 16);
        }

        @Override protected void onDraw(Canvas c) {
            RectF r = new RectF(dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1));
            c.drawRoundRect(r, dp(18), dp(18), fill);
            c.drawRoundRect(r, dp(18), dp(18), line);
            c.drawText("Pantalla del TV", getWidth() / 2f, getHeight() / 2f, txt);
            if (lx >= 0) c.drawCircle(lx, ly, dp(18), dot);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                sx = e.getX();
                sy = e.getY();
                lx = sx;
                ly = sy;
                t0 = SystemClock.uptimeMillis();
                moved = false;
                invalidate();
            } else if (a == MotionEvent.ACTION_MOVE) {
                lx = e.getX();
                ly = e.getY();
                if (Math.abs(lx - sx) > dp(10) || Math.abs(ly - sy) > dp(10)) moved = true;
                invalidate();
            } else if (a == MotionEvent.ACTION_UP) {
                long dt = SystemClock.uptimeMillis() - t0;
                gesture(sx, sy, e.getX(), e.getY(), dt, moved, getWidth(), getHeight());
                lx = -1;
                invalidate();
            } else if (a == MotionEvent.ACTION_CANCEL) {
                lx = -1;
                invalidate();
            }
            return true;
        }
    }

    // ---------- apps del TV ----------
    String pretty(String pkg) {
        String n = NAMES.get(pkg);
        if (n != null) return n;
        String[] p = pkg.split("\\.");
        String last = p[p.length - 1].replace('_', ' ');
        if (last.length() == 0) return pkg;
        return Character.toUpperCase(last.charAt(0)) + last.substring(1);
    }

    LinkedHashSet<String> pkgSet(String out) {
        LinkedHashSet<String> s = new LinkedHashSet<String>();
        for (String l : out.split("\n")) {
            l = l.replace("\r", "").trim();
            if (l.startsWith("package:")) s.add(l.substring(8));
        }
        return s;
    }

    LinkedHashSet<String> mainPackages(String dump) {
        LinkedHashSet<String> pk = new LinkedHashSet<String>();
        Pattern header = Pattern.compile("^\\s+([A-Za-z0-9_.]+):\\s*");
        Pattern comp = Pattern.compile("^\\s+[0-9a-f]+ ([A-Za-z0-9_.]+)/\\S+ filter .*");
        boolean inMain = false;
        for (String l : dump.split("\n")) {
            l = l.replace("\r", "");
            Matcher m = header.matcher(l);
            if (m.matches()) {
                inMain = m.group(1).equals("android.intent.action.MAIN");
                continue;
            }
            if (inMain) {
                Matcher c = comp.matcher(l);
                if (c.matches()) pk.add(c.group(1));
            }
        }
        return pk;
    }

    void loadApps() {
        appsStatus.setText("Cargando aplicaciones del TV...");
        io.execute(new Runnable() {
            public void run() {
                try {
                    if (!adb.isConnected()) connectNow();
                    String mineOut = adb.shell("pm list packages -3");
                    String dump = adb.shell("dumpsys package r activity");
                    HashSet<String> mine = pkgSet(mineOut);
                    LinkedHashSet<String> pk = mainPackages(dump);
                    if (pk.isEmpty()) pk.addAll(pkgSet(adb.shell("pm list packages")));
                    final List<AppEntry> res = new ArrayList<AppEntry>();
                    for (String p : pk) {
                        if (p.equals("com.tvremote")) continue;
                        AppEntry e = new AppEntry();
                        e.pkg = p;
                        e.name = pretty(p);
                        e.mine = mine.contains(p);
                        res.add(e);
                    }
                    Collections.sort(res, new Comparator<AppEntry>() {
                        public int compare(AppEntry x, AppEntry y) {
                            return x.name.compareToIgnoreCase(y.name);
                        }
                    });
                    ui.post(new Runnable() {
                        public void run() { apps = res; renderApps(); }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        public void run() { appsStatus.setText("No se pudo cargar: " + msg(t)); }
                    });
                }
            }
        });
    }

    void setFilter(boolean mine) {
        onlyMine = mine;
        chipAll.setBackground(pad(mine ? GRAY : BLUE, mine ? GRAY_P : BLUE_P));
        chipMine.setBackground(pad(mine ? BLUE : GRAY, mine ? BLUE_P : GRAY_P));
        renderApps();
    }

    void renderApps() {
        appList.removeAllViews();
        String q = search.getText().toString().trim().toLowerCase();
        int n = 0;
        for (final AppEntry a : apps) {
            if (onlyMine && !a.mine) continue;
            if (q.length() > 0 && !a.name.toLowerCase().contains(q) && !a.pkg.toLowerCase().contains(q)) continue;
            appList.addView(appRow(a));
            n++;
        }
        appsStatus.setText(apps.isEmpty() ? "Sin datos todavía" : (n + " aplicaciones. Toca una para abrirla en el TV."));
    }

    View appRow(final AppEntry a) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackground(pad(0xFF1C1C1E, 0xFF3A3A3C));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(5), dp(4), dp(5), dp(4));
        r.setLayoutParams(p);
        TextView n = new TextView(this);
        n.setText(a.name);
        n.setTextColor(Color.WHITE);
        n.setTextSize(17);
        n.setTypeface(null, Typeface.BOLD);
        TextView k = new TextView(this);
        k.setText(a.pkg);
        k.setTextColor(MUTED);
        k.setTextSize(12);
        r.addView(n);
        r.addView(k);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Abriendo " + a.name, Toast.LENGTH_SHORT).show();
                appAction(a.pkg).run();
            }
        });
        return r;
    }

    // ---------- interfaz ----------
    View.OnTouchListener touch(final Runnable action, final boolean repeat) {
        return new View.OnTouchListener() {
            final Runnable loop = new Runnable() {
                public void run() { action.run(); ui.postDelayed(this, 130); }
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getAction();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    action.run();
                    if (repeat) ui.postDelayed(loop, 450);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    ui.removeCallbacks(loop);
                }
                return true;
            }
        };
    }

    TextView btn(String label, int normal, int pressed, Runnable action, boolean repeat, int hDp) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pad(normal, pressed));
        t.setOnTouchListener(touch(action, repeat));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(hDp), 1f);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        t.setLayoutParams(p);
        return t;
    }

    TextView gray(String label, Runnable action, boolean repeat) {
        return btn(label, GRAY, GRAY_P, action, repeat, 60);
    }

    View slot() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, dp(60), 1f));
        return v;
    }

    LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    LinearLayout cluster(View... rows) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        for (View v : rows) c.addView(v);
        c.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return c;
    }

    View space(int hDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(hDp)));
        return v;
    }

    TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(MUTED);
        t.setTextSize(13);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        return t;
    }

    EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(MUTED);
        e.setBackground(shape(0xFF1C1C1E, 14));
        e.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        e.setLayoutParams(p);
        return e;
    }

    LinearLayout newPage(int index, FrameLayout body) {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), dp(6), dp(4), dp(24));
        sv.addView(col);
        body.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages[index] = sv;
        return col;
    }

    void showTab(int i) {
        for (int k = 0; k < 4; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setBackground(shape(k == i ? BLUE : 0xFF1C1C1E, 12));
        }
        if (i == 2 && apps.isEmpty()) loadApps();
    }

    void setMode(boolean gamepad) {
        gamepadMode = gamepad;
        modeChip.setText(gamepad ? "Modo: Gamepad (toca para cambiar a Teclado)"
                : "Modo: Teclado (toca para cambiar a Gamepad)");
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        host = prefs.getString("ip", "192.168.1.34");

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xFF111113);
        outer.setPadding(dp(10), dp(18), dp(10), dp(6));

        TextView title = new TextView(this);
        title.setText("Control TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(5), 0, 0, dp(4));
        outer.addView(title);

        ipField = field("IP del TV", host);
        outer.addView(row(ipField, btn("Conectar", BLUE, BLUE_P, new Runnable() {
            public void run() { connectAsync(); }
        }, false, 52)));
        status = new TextView(this);
        status.setText("Sin conectar");
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setPadding(dp(8), 0, 0, dp(6));
        outer.addView(status);

        String[] names = { "Control", "Táctil", "Apps", "Juegos" };
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(14);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
            p.setMargins(dp(3), dp(2), dp(3), dp(6));
            t.setLayoutParams(p);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showTab(idx); }
            });
            tabs[i] = t;
            tabRow.addView(t);
        }
        outer.addView(tabRow);

        FrameLayout body = new FrameLayout(this);
        outer.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ----- Pestana 1: Control -----
        LinearLayout c1 = newPage(0, body);
        c1.addView(row(gray("Atrás", keyAction(4), false), gray("▲", keyAction(19), true),
                gray("Inicio", keyAction(3), false)));
        c1.addView(row(gray("◀", keyAction(21), true),
                btn("OK", BLUE, BLUE_P, keyAction(23), false, 60), gray("▶", keyAction(22), true)));
        c1.addView(row(gray("Menú", keyAction(82), false), gray("▼", keyAction(20), true),
                gray("Entrada", keyAction(178), false)));
        c1.addView(space(8));
        c1.addView(row(gray("« Atrasar", keyAction(89), true), gray("Play / Pausa", keyAction(85), false),
                gray("Adelantar »", keyAction(90), true)));
        c1.addView(row(gray("Anterior", keyAction(88), false), gray("■ Stop", keyAction(86), false),
                gray("Siguiente", keyAction(87), false)));
        c1.addView(row(gray("▶ Play", keyAction(126), false), gray("Pausa", keyAction(127), false)));
        c1.addView(space(8));
        c1.addView(row(gray("Vol −", keyAction(25), true), gray("Silencio", keyAction(164), false),
                gray("Vol +", keyAction(24), true)));
        c1.addView(row(btn("Encender / Apagar", RED, RED_P, keyAction(26), false, 60)));
        c1.addView(space(8));
        textField = field("Escribir en el TV", "");
        c1.addView(row(textField, btn("Enviar", BLUE, BLUE_P, new Runnable() {
            public void run() {
                String s = textField.getText().toString();
                if (s.length() == 0) return;
                String esc = s.replace("'", "'\\''").replace(" ", "%s");
                exec("input text '" + esc + "'");
                textField.setText("");
            }
        }, false, 52), btn("⌫", GRAY, GRAY_P, keyAction(67), true, 52)));

        // ----- Pestana 2: Tactil -----
        LinearLayout c2 = newPage(1, body);
        c2.addView(note("Toca = clic. Arrastra = deslizar o desplazar. Mantén = clic largo."));
        Pad padView = new Pad(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.setMargins(dp(5), dp(4), dp(5), dp(8));
        padView.setLayoutParams(pp);
        c2.addView(padView);
        c2.addView(note("El TV no muestra un cursor. Esta zona representa toda su pantalla: "
                + "lo que toques aquí se toca en la misma posición del TV."));
        c2.addView(row(gray("Atrás", keyAction(4), false), gray("Inicio", keyAction(3), false)));
        c2.addView(row(gray("Desplazar ▲", scrollAction(false), true),
                gray("Desplazar ▼", scrollAction(true), true)));

        // ----- Pestana 3: Apps -----
        LinearLayout c3 = newPage(2, body);
        search = field("Buscar aplicación", "");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) { renderApps(); }
        });
        c3.addView(row(search, btn("Actualizar", GRAY, GRAY_P, new Runnable() {
            public void run() { loadApps(); }
        }, false, 52)));
        chipAll = btn("Todas", BLUE, BLUE_P, new Runnable() {
            public void run() { setFilter(false); }
        }, false, 44);
        chipMine = btn("Instaladas por mí", GRAY, GRAY_P, new Runnable() {
            public void run() { setFilter(true); }
        }, false, 44);
        c3.addView(row(chipAll, chipMine));
        appsStatus = note("Abre esta pestaña con el TV conectado para ver sus aplicaciones.");
        c3.addView(appsStatus);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        c3.addView(appList);

        // ----- Pestana 4: Juegos -----
        LinearLayout c4 = newPage(3, body);
        c4.addView(row(gray("L2", gpAction(104), false), gray("L1", gpAction(102), false),
                gray("R1", gpAction(103), false), gray("R2", gpAction(105), false)));
        c4.addView(space(6));
        LinearLayout left = cluster(
                row(slot(), gray("▲", gpAction(19), true), slot()),
                row(gray("◀", gpAction(21), true), slot(), gray("▶", gpAction(22), true)),
                row(slot(), gray("▼", gpAction(20), true), slot()));
        LinearLayout right = cluster(
                row(slot(), btn("Y", AMBER, AMBER_P, gpAction(100), false, 60), slot()),
                row(btn("X", BLUE, BLUE_P, gpAction(99), false, 60), slot(),
                        btn("B", RED, RED_P, gpAction(97), false, 60)),
                row(slot(), btn("A", GREEN, GREEN_P, gpAction(96), false, 60), slot()));
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.HORIZONTAL);
        both.addView(left);
        both.addView(right);
        both.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        c4.addView(both);
        c4.addView(space(6));
        c4.addView(row(gray("Select", gpAction(109), false), gray("Mode", gpAction(110), false),
                gray("Start", gpAction(108), false)));
        c4.addView(row(gray("L3", gpAction(106), false), gray("R3", gpAction(107), false)));
        modeChip = btn("", GRAY, GRAY_P, new Runnable() {
            public void run() { setMode(!gamepadMode); }
        }, false, 48);
        modeChip.setTextSize(13);
        c4.addView(row(modeChip));
        setMode(true);
        c4.addView(note("Cada pulsación tarda cerca de 0,3 s y no se puede mantener un botón ni usar "
                + "palancas. Sirve para juegos de menú, por turnos o arcade lentos, no para acción rápida. "
                + "Si un juego no responde en modo Gamepad, prueba el modo Teclado."));

        setContentView(outer);
        showTab(0);
        connectAsync();
    }

    @Override protected void onResume() {
        super.onResume();
        if (ipField != null && !adb.isConnected()) connectAsync();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        new Thread(new Runnable() { public void run() { adb.close(); } }).start();
    }
}
