package com.tvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final int BG_DARK = 0xFF0B0B0F;
    static final int CARD_BG = 0xFF14141A;
    static final int CARD_PRESS = 0xFF22222C;
    static final int MUTED = 0xFF8A8A93;
    static final int OK_GREEN = 0xFF30D158;
    static final int BAD_RED = 0xFFFF453A;

    int accentColor = 0xFF00E5FF;
    int accentPressColor = 0xFF00838F;

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
    final ScrollView[] pages = new ScrollView[6];
    final TextView[] tabs = new TextView[6];
    SharedPreferences prefs;
    TextView status, appsStatus, chipAll, chipMine, modeChip, btStatus, irStatus;
    EditText ipField, textField, search;
    LinearLayout appList, btDeviceList;
    List<AppEntry> apps = new ArrayList<AppEntry>();
    boolean onlyMine = false;
    boolean gamepadMode = true;
    boolean vibrationEnabled = true;
    volatile String host = "192.168.1.34";
    volatile int tvW = 1920, tvH = 1080;
    float d;
    Vibrator vibrator;
    BluetoothAdapter bluetoothAdapter;
    ConsumerIrManager irManager;

    int dp(int v) { return Math.round(v * d); }

    void vibrate() {
        if (!vibrationEnabled) return;
        try {
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(45, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(45);
                }
            }
        } catch (Exception e) {}
    }

    void sendIrSignal(String deviceName) {
        vibrate();
        if (irManager == null || !irManager.hasIrEmitter()) {
            Toast.makeText(this, "Este dispositivo no cuenta con emisor Infrarrojo (IR) físico.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            int carrierFrequency = 38000;
            int[] pattern = {9000, 4500, 560, 560, 560, 1690, 560, 560, 560, 560, 560, 560, 560, 560, 560, 560};
            irManager.transmit(carrierFrequency, pattern);
            Toast.makeText(this, "Señal IR enviada (" + deviceName + ")", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error transmitiendo señal IR: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    GradientDrawable neonShape(int color, int borderCol, int rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        if (borderCol != 0) {
            g.setStroke(dp(2), borderCol);
        }
        return g;
    }

    StateListDrawable neonPad(int normalBg, int pressBg, int borderCol) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, neonShape(pressBg, borderCol, 14));
        s.addState(new int[] {}, neonShape(normalBg, borderCol, 14));
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

    void key(int code) { vibrate(); exec("input keyevent " + code); }

    Runnable keyAction(final int code) {
        return new Runnable() { public void run() { key(code); } };
    }

    Runnable gpAction(final int code) {
        return new Runnable() {
            public void run() {
                vibrate();
                exec((gamepadMode ? "input gamepad keyevent " : "input keyevent ") + code);
            }
        };
    }

    Runnable appAction(final String pkg) {
        return new Runnable() {
            public void run() {
                vibrate();
                exec("o=$(monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1); "
                        + "case \"$o\" in *\"No activities\"*) monkey -p " + pkg
                        + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1;; esac");
            }
        };
    }

    Runnable scrollAction(final boolean down) {
        return new Runnable() {
            public void run() {
                vibrate();
                int x = tvW / 2;
                int y1 = down ? tvH * 3 / 4 : tvH / 4;
                int y2 = down ? tvH / 4 : tvH * 3 / 4;
                exec("input swipe " + x + " " + y1 + " " + x + " " + y2 + " 300");
            }
        };
    }

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
            fill.setColor(CARD_BG);
            line.setColor(accentColor);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            dot.setColor(accentColor);
            txt.setColor(MUTED);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTextSize(dp(15));
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, w * 9 / 16);
        }

        @Override protected void onDraw(Canvas c) {
            RectF r = new RectF(dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1));
            c.drawRoundRect(r, dp(14), dp(14), fill);
            c.drawRoundRect(r, dp(14), dp(14), line);
            c.drawText("Pantalla Táctil Neón (TV)", getWidth() / 2f, getHeight() / 2f, txt);
            if (lx >= 0) c.drawCircle(lx, ly, dp(16), dot);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                sx = e.getX(); sy = e.getY(); lx = sx; ly = sy;
                t0 = SystemClock.uptimeMillis();
                moved = false;
                vibrate();
                invalidate();
            } else if (a == MotionEvent.ACTION_MOVE) {
                lx = e.getX(); ly = e.getY();
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
        chipAll.setBackground(neonPad(mine ? CARD_BG : accentColor, mine ? CARD_PRESS : accentColor, mine ? MUTED : accentColor));
        chipMine.setBackground(neonPad(mine ? accentColor : CARD_BG, mine ? accentColor : CARD_PRESS, mine ? accentColor : MUTED));
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
        r.setBackground(neonPad(CARD_BG, CARD_PRESS, accentColor));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(5), dp(4), dp(5), dp(4));
        r.setLayoutParams(p);
        TextView nameView = new TextView(this);
        nameView.setText(a.name);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(17);
        nameView.setTypeface(null, Typeface.BOLD);
        TextView pkgView = new TextView(this);
        pkgView.setText(a.pkg);
        pkgView.setTextColor(MUTED);
        pkgView.setTextSize(12);
        r.addView(nameView);
        r.addView(pkgView);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Abriendo " + a.name, Toast.LENGTH_SHORT).show();
                appAction(a.pkg).run();
            }
        });
        return r;
    }

    void loadBluetoothDevices() {
        if (btDeviceList == null) return;
        btDeviceList.removeAllViews();
        if (bluetoothAdapter == null) {
            btStatus.setText("Bluetooth no disponible en este dispositivo.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            btStatus.setText("Bluetooth desactivado. Toca para encenderlo.");
            return;
        }
        btStatus.setText("Dispositivos Bluetooth vinculados (Mandos, TV, etc.):");
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            if (pairedDevices != null && pairedDevices.size() > 0) {
                for (final BluetoothDevice device : pairedDevices) {
                    TextView devView = new TextView(this);
                    devView.setText("🎮 " + device.getName() + "\n[" + device.getAddress() + "]");
                    devView.setTextColor(Color.WHITE);
                    devView.setTextSize(14);
                    devView.setBackground(neonPad(CARD_BG, CARD_PRESS, accentColor));
                    devView.setPadding(dp(12), dp(10), dp(12), dp(10));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(dp(5), dp(4), dp(5), dp(4));
                    devView.setLayoutParams(lp);
                    devView.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            vibrate();
                            Toast.makeText(MainActivity.this, "Seleccionado: " + device.getName(), Toast.LENGTH_SHORT).show();
                        }
                    });
                    btDeviceList.addView(devView);
                }
            } else {
                TextView none = new TextView(this);
                none.setText("No hay dispositivos vinculados.");
                none.setTextColor(MUTED);
                none.setPadding(dp(8), dp(6), dp(8), dp(6));
                btDeviceList.addView(none);
            }
        } catch (SecurityException e) {
            btStatus.setText("Permisos de Bluetooth requeridos.");
        }
    }

    View.OnTouchListener touch(final Runnable action, final boolean repeat) {
        return new View.OnTouchListener() {
            final Runnable loop = new Runnable() {
                public void run() { action.run(); ui.postDelayed(this, 130); }
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getAction();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
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

    TextView btn(String label, int normalBg, int pressBg, int borderCol, Runnable action, boolean repeat, int hDp) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(neonPad(normalBg, pressBg, borderCol));
        t.setOnTouchListener(touch(action, repeat));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(hDp), 1f);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        t.setLayoutParams(p);
        return t;
    }

    TextView neonBtn(String label, Runnable action, boolean repeat) {
        return btn(label, CARD_BG, CARD_PRESS, accentColor, action, repeat, 56);
    }

    View slot() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, dp(56), 1f));
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
        e.setBackground(neonShape(CARD_BG, accentColor, 12));
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
        for (int k = 0; k < 6; k++) {
            pages[k].setVisibility(k == i ? View.VISIBLE : View.GONE);
            tabs[k].setBackground(neonShape(k == i ? accentColor : CARD_BG, k == i ? accentColor : MUTED, 8));
            tabs[k].setTextColor(k == i ? Color.BLACK : Color.WHITE);
        }
        if (i == 2 && apps.isEmpty()) loadApps();
        if (i == 4) loadBluetoothDevices();
    }

    void setMode(boolean gamepad) {
        gamepadMode = gamepad;
        modeChip.setText(gamepad ? "Modo: Gamepad (Universal)" : "Modo: Teclado");
    }

    void updateThemeColor(int color, String colorName) {
        accentColor = color;
        // Calcular un tono de pulsación ligeramente más oscuro automáticamente
        int r = Math.max(0, Color.red(color) - 40);
        int g = Math.max(0, Color.green(color) - 40);
        int b = Math.max(0, Color.blue(color) - 40);
        accentPressColor = Color.rgb(r, g, b);

        prefs.edit().putInt("accent", accentColor).putInt("press", accentPressColor).putString("colorName", colorName).apply();
        Toast.makeText(this, "Color de Neón aplicado: " + colorName, Toast.LENGTH_SHORT).show();
        recreate();
    }

    void showCustomColorDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Selector de Color Infinito (HEX)");
        final EditText input = new EditText(this);
        input.setHint("#00E5FF o #FF007F");
        input.setText(String.format("#%06X", (0xFFFFFF & accentColor)));
        input.setTextColor(Color.WHITE);
        input.setPadding(dp(16), dp(16), dp(16), dp(16));
        builder.setView(input);

        builder.setPositiveButton("Aplicar", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                try {
                    int newColor = Color.parseColor(input.getText().toString().trim());
                    updateThemeColor(newColor, "Personalizado");
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Código HEX inválido. Ej: #00E5FF", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        host = prefs.getString("ip", "192.168.1.34");
        accentColor = prefs.getInt("accent", 0xFF00E5FF);
        accentPressColor = prefs.getInt("press", 0xFF00838F);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG_DARK);
        outer.setPadding(dp(10), dp(18), dp(10), dp(6));

        TextView title = new TextView(this);
        title.setText("⚡ CONTROL TV • YK2 INFINITY V5");
        title.setTextColor(accentColor);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(5), 0, 0, dp(4));
        outer.addView(title);

        ipField = field("IP del TV", host);
        outer.addView(row(ipField, btn("Conectar", accentColor, accentPressColor, accentColor, new Runnable() {
            public void run() { connectAsync(); }
        }, false, 52)));

        status = new TextView(this);
        status.setText("Sin conectar");
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setPadding(dp(8), 0, 0, dp(6));
        outer.addView(status);

        String[] tabNames = { "Control", "Táctil", "Apps", "Juegos", "IR", "Ajustes" };
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(tabNames[i]);
            t.setTextColor(Color.WHITE);
            t.setTextSize(12);
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
            p.setMargins(dp(1), dp(2), dp(1), dp(4));
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

        // ----- Pestaña 1: Control -----
        LinearLayout c1 = newPage(0, body);
        c1.addView(row(neonBtn("Atrás", keyAction(4), false), neonBtn("▲", keyAction(19), true),
                neonBtn("Inicio", keyAction(3), false)));
        c1.addView(row(neonBtn("◀", keyAction(21), true),
                btn("OK", accentColor, accentPressColor, accentColor, keyAction(23), false, 56), neonBtn("▶", keyAction(22), true)));
        c1.addView(row(neonBtn("Menú", keyAction(82), false), neonBtn("▼", keyAction(20), true),
                neonBtn("Entrada", keyAction(178), false)));
        c1.addView(space(8));
        c1.addView(row(neonBtn("« Atrasar", keyAction(89), true), neonBtn("Play / Pausa", keyAction(85), false),
                neonBtn("Adelantar »", keyAction(90), true)));
        c1.addView(row(neonBtn("Anterior", keyAction(88), false), neonBtn("■ Stop", keyAction(86), false),
                neonBtn("Siguiente", keyAction(87), false)));
        c1.addView(space(8));
        c1.addView(row(neonBtn("Vol −", keyAction(25), true), neonBtn("Silencio", keyAction(164), false),
                neonBtn("Vol +", keyAction(24), true)));
        c1.addView(row(btn("Encender / Apagar", BAD_RED, 0xFFB71C1C, BAD_RED, keyAction(26), false, 56)));
        c1.addView(space(8));
        textField = field("Escribir texto en TV", "");
        c1.addView(row(textField, btn("Enviar", accentColor, accentPressColor, accentColor, new Runnable() {
            public void run() {
                String s = textField.getText().toString();
                if (s.length() == 0) return;
                String esc = s.replace("'", "'\\''").replace(" ", "%s");
                exec("input text '" + esc + "'");
                textField.setText("");
            }
        }, false, 52), neonBtn("⌫", keyAction(67), true)));

        // ----- Pestaña 2: Táctil -----
        LinearLayout c2 = newPage(1, body);
        c2.addView(note("Panel táctil YK2 Neon. Toca para hacer clic, arrastra para deslizar, mantén presionado para clic largo."));
        Pad padView = new Pad(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.setMargins(dp(5), dp(4), dp(5), dp(8));
        padView.setLayoutParams(pp);
        c2.addView(padView);
        c2.addView(row(neonBtn("Atrás", keyAction(4), false), neonBtn("Inicio", keyAction(3), false)));
        c2.addView(row(neonBtn("Desplazar ▲", scrollAction(false), true),
                neonBtn("Desplazar ▼", scrollAction(true), true)));

        // ----- Pestaña 3: Apps -----
        LinearLayout c3 = newPage(2, body);
        search = field("Buscar aplicación del TV", "");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) { renderApps(); }
        });
        c3.addView(row(search, neonBtn("Actualizar", new Runnable() {
            public void run() { loadApps(); }
        }, false)));
        chipAll = btn("Todas", accentColor, accentPressColor, accentColor, new Runnable() {
            public void run() { setFilter(false); }
        }, false, 40);
        chipMine = btn("Instaladas por mí", CARD_BG, CARD_PRESS, MUTED, new Runnable() {
            public void run() { setFilter(true); }
        }, false, 40);
        c3.addView(row(chipAll, chipMine));
        appsStatus = note("Carga las apps instaladas en la TV vía ADB.");
        c3.addView(appsStatus);
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        c3.addView(appList);

        // ----- Pestaña 4: Juegos -----
        LinearLayout c4 = newPage(3, body);
        c4.addView(note("Mando de Juego Universal compatible con vibración y cualquier control físico Bluetooth."));
        c4.addView(row(neonBtn("L2", gpAction(104), false), neonBtn("L1", gpAction(102), false),
                neonBtn("R1", gpAction(103), false), neonBtn("R2", gpAction(105), false)));
        c4.addView(space(6));
        LinearLayout left = cluster(
                row(slot(), neonBtn("▲", gpAction(19), true), slot()),
                row(neonBtn("◀", gpAction(21), true), slot(), neonBtn("▶", gpAction(22), true)),
                row(slot(), neonBtn("▼", gpAction(20), true), slot()));
        LinearLayout right = cluster(
                row(slot(), btn("Y", 0xFFFFB300, 0xFFFFA000, 0xFFFFB300, gpAction(100), false, 56), slot()),
                row(btn("X", accentColor, accentPressColor, accentColor, gpAction(99), false, 56), slot(),
                        btn("B", BAD_RED, 0xFFB71C1C, BAD_RED, gpAction(97), false, 56)),
                row(slot(), btn("A", OK_GREEN, 0xFF1B5E20, OK_GREEN, gpAction(96), false, 56), slot()));
        LinearLayout both = new LinearLayout(this);
        both.setOrientation(LinearLayout.HORIZONTAL);
        both.addView(left);
        both.addView(right);
        both.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        c4.addView(both);
        c4.addView(space(6));
        c4.addView(row(neonBtn("Select", gpAction(109), false), neonBtn("Mode", gpAction(110), false),
                neonBtn("Start", gpAction(108), false)));
        c4.addView(row(neonBtn("L3", gpAction(106), false), neonBtn("R3", gpAction(107), false)));
        modeChip = btn("", CARD_BG, CARD_PRESS, accentColor, new Runnable() {
            public void run() { setMode(!gamepadMode); }
        }, false, 44);
        modeChip.setTextSize(13);
        c4.addView(row(modeChip));
        setMode(true);

        // ----- Pestaña 5: IR (Infrarrojo) -----
        LinearLayout c5 = newPage(4, body);
        c5.addView(note("Apartado de Infrarrojo (IR / Blaster). Emite señales universales directamente desde el puerto infrarrojo de tu móvil a la TV."));
        
        irStatus = new TextView(this);
        boolean hasIr = irManager != null && irManager.hasIrEmitter();
        irStatus.setText(hasIr ? "🟢 Puerto Infrarrojo (IR) detectado y listo." : "🔴 Este dispositivo no posee emisor Infrarrojo (IR) integrado.");
        irStatus.setTextColor(hasIr ? OK_GREEN : BAD_RED);
        irStatus.setTextSize(14);
        irStatus.setPadding(dp(8), dp(6), dp(8), dp(10));
        c5.addView(irStatus);

        c5.addView(row(btn("Power IR", BAD_RED, 0xFFB71C1C, BAD_RED, new Runnable() {
            public void run() { sendIrSignal("Power"); }
        }, false, 56), neonBtn("Mute IR", new Runnable() {
            public void run() { sendIrSignal("Mute"); }
        }, false)));

        c5.addView(row(neonBtn("Vol − IR", new Runnable() {
            public void run() { sendIrSignal("Vol -"); }
        }, true), neonBtn("Vol + IR", new Runnable() {
            public void run() { sendIrSignal("Vol +"); }
        }, true)));

        c5.addView(row(neonBtn("Canal − IR", new Runnable() {
            public void run() { sendIrSignal("Channel -"); }
        }, false), neonBtn("Canal + IR", new Runnable() {
            public void run() { sendIrSignal("Channel +"); }
        }, false)));

        c5.addView(row(neonBtn("Entrada / Source IR", new Runnable() {
            public void run() { sendIrSignal("Source"); }
        }, false)));

        // ----- Pestaña 6: Ajustes (Selector Infinito de Colores, Bluetooth & Vibración) -----
        LinearLayout c6 = newPage(5, body);
        c6.addView(note("Configuración de Estilo Neón con Selector de Color Infinito (Todos los Colores), Bluetooth y Vibración."));

        TextView stNeonHeader = new TextView(this);
        stNeonHeader.setText("🎨 Selector de Color Infinito (Todos los Colores)");
        stNeonHeader.setTextColor(accentColor);
        stNeonHeader.setTextSize(15);
        stNeonHeader.setTypeface(null, Typeface.BOLD);
        stNeonHeader.setPadding(dp(5), dp(10), dp(5), dp(4));
        c6.addView(stNeonHeader);

        // Botones rápidos con los colores más populares
        c6.addView(row(
            btn("Cian Neón", 0xFF00E5FF, 0xFF00838F, 0xFF00E5FF, new Runnable() { public void run() { updateThemeColor(0xFF00E5FF, "Cian"); }}, false, 40),
            btn("Verde Neón", 0xFF00E676, 0xFF00C853, 0xFF00E676, new Runnable() { public void run() { updateThemeColor(0xFF00E676, "Verde"); }}, false, 40)
        ));
        c6.addView(row(
            btn("Rosa Fucsia", 0xFFFF007F, 0xC51162, 0xFFFF007F, new Runnable() { public void run() { updateThemeColor(0xFFFF007F, "Rosa"); }}, false, 40),
            btn("Púrpura", 0xFFD500F9, 0xAA00FF, 0xFFD500F9, new Runnable() { public void run() { updateThemeColor(0xFFD500F9, "Púrpura"); }}, false, 40)
        ));
        c6.addView(row(
            btn("Amarillo", 0xFFFFEA00, 0xFFFBC02D, 0xFFFFEA00, new Runnable() { public void run() { updateThemeColor(0xFFFFEA00, "Amarillo"); }}, false, 40),
            btn("Naranja", 0xFFFF6D00, 0xFFE65100, 0xFFFF6D00, new Runnable() { public void run() { updateThemeColor(0xFFFF6D00, "Naranja"); }}, false, 40)
        ));
        c6.addView(row(
            btn("Azul Eléc.", 0xFF2979FF, 0xFF1565C0, 0xFF2979FF, new Runnable() { public void run() { updateThemeColor(0xFF2979FF, "Azul Eléctrico"); }}, false, 40),
            btn("Rojo Neón", 0xFFFF1744, 0xFFD50000, 0xFFFF1744, new Runnable() { public void run() { updateThemeColor(0xFFFF1744, "Rojo"); }}, false, 40)
        ));

        // Botón para ingresar cualquier color HEX existente en el universo digital
        c6.addView(row(btn("🌈 Elegir Cualquier Color (HEX Personalizado)", accentColor, accentPressColor, accentColor, new Runnable() {
            public void run() { showCustomColorDialog(); }
        }, false, 48)));

        TextView stBtHeader = new TextView(this);
        stBtHeader.setText("📶 Conectividad Bluetooth (Móvil y TV)");
        stBtHeader.setTextColor(accentColor);
        stBtHeader.setTextSize(15);
        stBtHeader.setTypeface(null, Typeface.BOLD);
        stBtHeader.setPadding(dp(5), dp(15), dp(5), dp(4));
        c6.addView(stBtHeader);

        c6.addView(row(neonBtn("Abrir Ajustes BT", new Runnable() {
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No se pudo abrir el menú Bluetooth", Toast.LENGTH_SHORT).show();
                }
            }
        }, false), neonBtn("Escanear BT", new Runnable() {
            public void run() { loadBluetoothDevices(); }
        }, false)));

        btStatus = note("Dispositivos Bluetooth vinculados:");
        c6.addView(btStatus);
        btDeviceList = new LinearLayout(this);
        btDeviceList.setOrientation(LinearLayout.VERTICAL);
        c6.addView(btDeviceList);

        TextView stVibHeader = new TextView(this);
        stVibHeader.setText("📳 Respuesta Háptica y Vibración");
        stVibHeader.setTextColor(accentColor);
        stVibHeader.setTextSize(15);
        stVibHeader.setTypeface(null, Typeface.BOLD);
        stVibHeader.setPadding(dp(5), dp(15), dp(5), dp(4));
        c6.addView(stVibHeader);

        c6.addView(row(neonBtn(vibrationEnabled ? "Desactivar Vibración" : "Activar Vibración", new Runnable() {
            public void run() {
                vibrationEnabled = !vibrationEnabled;
                Toast.makeText(MainActivity.this, "Vibración: " + (vibrationEnabled ? "Activada" : "Desactivada"), Toast.LENGTH_SHORT).show();
                recreate();
            }
        }, false)));

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
