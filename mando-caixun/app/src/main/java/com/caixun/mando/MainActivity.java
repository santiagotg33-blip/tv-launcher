package com.caixun.mando;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int WIFI = 0, BT = 1, IR = 2;
    private static final int REQ_VOICE = 77, REQ_BT = 78;

    private static final int BG = 0xFF101114;
    private static final int BODY = 0xFF2A2C31;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int INK = 0xFF1D1D1F;
    private static final int RED = 0xFFE53935;
    private static final int GREEN = 0xFF43A047;
    private static final int YELLOW = 0xFFFDD835;
    private static final int BLUE = 0xFF1E88E5;
    private static final int MUTED = 0xFF9AA0A6;

    // ------------------------------------------------------------- Teclas
    // adb = codigo Android, kb = tecla USB de teclado, cons = tecla de control (vol, home...)
    private static class Key {
        final int adb, kb, cons;
        final String pkg;

        Key(int adb, int kb, int cons, String pkg) {
            this.adb = adb;
            this.kb = kb;
            this.cons = cons;
            this.pkg = pkg;
        }
    }

    private static final Map<String, Key> KEYS = new HashMap<>();
    private static final Set<String> REPEAT = new LinkedHashSet<>(Arrays.asList(
            "UP", "DOWN", "LEFT", "RIGHT", "VOLUP", "VOLDOWN", "CHUP", "CHDOWN"));
    private static final Map<String, String> KNOWN = new HashMap<>();

    private static void k(String id, int adb, int kb, int cons, String pkg) {
        KEYS.put(id, new Key(adb, kb, cons, pkg));
    }

    static {
        k("POWER", 26, 0, 0x30, null);
        for (int i = 0; i <= 9; i++) {
            k(String.valueOf(i), 7 + i, i == 0 ? 0x27 : 0x1E + i - 1, 0, null);
        }
        k("DOT", 56, 0x37, 0, null);
        k("VOLUP", 24, 0, 0xE9, null);
        k("VOLDOWN", 25, 0, 0xEA, null);
        k("MUTE", 164, 0, 0xE2, null);
        k("CHUP", 166, 0, 0x9C, null);
        k("CHDOWN", 167, 0, 0x9D, null);
        k("UP", 19, 0x52, 0, null);
        k("DOWN", 20, 0x51, 0, null);
        k("LEFT", 21, 0x50, 0, null);
        k("RIGHT", 22, 0x4F, 0, null);
        k("OK", 23, 0x28, 0, null);
        k("BACK", 4, 0x29, 0x224, null);
        k("HOME", 3, 0, 0x223, null);
        k("MENU", 82, 0, 0x40, null);
        k("APPS", 284, 0, 0x1A2, null);
        k("SETTINGS", 176, 0, 0, null);
        k("INFO", 165, 0, 0, null);
        k("EPG", 172, 0, 0x8D, null);
        k("SOURCE", 178, 0, 0, null);
        k("RED", 183, 0, 0, null);
        k("GREEN", 184, 0, 0, null);
        k("YELLOW", 185, 0, 0, null);
        k("BLUE", 186, 0, 0, null);
        k("CC", 175, 0, 0, null);
        k("SLEEP", 223, 0, 0, null);
        k("NETFLIX", 0, 0, 0, "com.netflix.ninja");
        k("PRIME", 0, 0, 0, "com.amazon.amazonvideo.livingroom");
        k("YOUTUBE", 0, 0, 0, "com.google.android.youtube.tv");
        k("DISNEY", 0, 0, 0, "com.disney.disneyplus");
        k("FILES", 0, 0, 0, "com.android.documentsui");

        KNOWN.put("com.netflix.ninja", "Netflix");
        KNOWN.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        KNOWN.put("com.google.android.youtube.tv", "YouTube");
        KNOWN.put("com.google.android.youtube.tvunplugged", "YouTube TV");
        KNOWN.put("com.google.android.youtube.tvmusic", "YouTube Music");
        KNOWN.put("com.disney.disneyplus", "Disney+");
        KNOWN.put("org.xbmc.kodi", "Kodi");
        KNOWN.put("com.android.vending", "Play Store");
        KNOWN.put("com.android.tv.settings", "Ajustes");
        KNOWN.put("com.android.documentsui", "Archivos");
        KNOWN.put("com.spotify.tv.android", "Spotify");
        KNOWN.put("com.android.chrome", "Chrome");
        KNOWN.put("com.google.android.tvlauncher", "Inicio");
        KNOWN.put("com.google.android.apps.tv.launcherx", "Inicio");
        KNOWN.put("com.mxtech.videoplayer.ad", "MX Player");
        KNOWN.put("com.google.android.videos", "Google TV");
    }

    // ------------------------------------------------------------- Estado
    private SharedPreferences prefs;
    private int mode;
    private float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final HidController hid = new HidController();
    private ConsumerIrManager ir;

    private TextView status;
    private EditText ipField;
    private LinearLayout panelWifi, panelBt, panelIr;
    private final TextView[] modeTabs = new TextView[3];
    private final TextView[] pageTabs = new TextView[3];
    private final View[] pages = new View[3];
    private int tvW = 1920, tvH = 1080;

    // mando de juegos
    private int padBtns = 0;
    private boolean pUp, pDown, pLeft, pRight;
    private int lx = 128, ly = 128, rx = 128, ry = 128;

    private interface StickCb {
        void onMove(float x, float y);
    }

    // ---------------------------------------------------------- Arranque
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("mando", MODE_PRIVATE);
        mode = prefs.getInt("mode", BT);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);

        buildUi();
        selectMode(mode);
        selectPage(0);

        if (btPermsOk()) startHid();
    }

    private boolean btPermsOk() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_BT);
                return false;
            }
        }
        return true;
    }

    private void startHid() {
        hid.init(this, s -> ui.post(() -> setStatus("Bluetooth: " + s)));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_BT) {
            boolean ok = res.length > 0;
            for (int r : res) {
                if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            }
            if (ok) {
                startHid();
            } else {
                setStatus("Sin permiso de Bluetooth: la parte Bluetooth no va a funcionar");
            }
        }
    }

    // ----------------------------------------------------------- Ayudas
    private int dp(int v) {
        return Math.round(v * density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void setStatus(String s) {
        if (status != null) status.setText(s);
    }

    private void postStatus(final String s) {
        ui.post(() -> setStatus(s));
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int lighten(int c) {
        if ((c >>> 24) < 0xFF) return 0x44FFFFFF;
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        r = r + (255 - r) / 4;
        g = g + (255 - g) / 4;
        b = b + (255 - b) / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private Drawable shape(int color, int radius) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(color);
        normal.setCornerRadius(radius);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(lighten(color));
        pressed.setCornerRadius(radius);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    private TextView btn(String text, int bg, int fg, int sp, String id) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(bg, dp(14)));
        if (id != null) bindKey(t, id);
        return t;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void add(LinearLayout row, View v, float weight, int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(hDp), weight);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(v, lp);
    }

    private void addFixed(LinearLayout row, View v, int wDp, int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(v, lp);
    }

    private View space() {
        return new View(this);
    }

    // ---------------------------------------------------------- Interfaz
    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = column();
        col.setPadding(dp(14), dp(16), dp(14), dp(28));
        sv.addView(col);

        TextView title = label("Mando Caixun", 22, WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        // Como se conecta: WiFi / Bluetooth / IR
        LinearLayout modes = row();
        String[] mn = {"WiFi", "Bluetooth", "IR"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView t = new TextView(this);
            t.setText(mn[i]);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setOnClickListener(v -> selectMode(m));
            modeTabs[i] = t;
            add(modes, t, 1, 40);
        }
        col.addView(modes);

        panelWifi = buildWifiPanel();
        panelBt = buildBtPanel();
        panelIr = buildIrPanel();
        col.addView(panelWifi);
        col.addView(panelBt);
        col.addView(panelIr);

        status = label("", 13, MUTED);
        status.setPadding(dp(4), dp(4), dp(4), dp(8));
        col.addView(status);

        // Pestanas: Mando / Raton / Juegos
        LinearLayout tabs = row();
        String[] pn = {"Mando", "Rat\u00f3n", "Juegos"};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            TextView t = new TextView(this);
            t.setText(pn[i]);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setOnClickListener(v -> selectPage(p));
            pageTabs[i] = t;
            add(tabs, t, 1, 40);
        }
        col.addView(tabs);

        FrameLayout box = new FrameLayout(this);
        pages[0] = buildRemote();
        pages[1] = buildMouse();
        pages[2] = buildPad();
        for (View v : pages) box.addView(v);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        col.addView(box, blp);

        // Acceso a todas las aplicaciones
        TextView apps = btn("Ver todas las aplicaciones", WHITE, INK, 16, null);
        apps.setOnClickListener(v -> showApps());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        alp.topMargin = dp(20);
        col.addView(apps, alp);

        setContentView(sv);
    }

    private void styleTab(TextView t, boolean on) {
        t.setBackground(shape(on ? RED : BODY, dp(12)));
        t.setTextColor(WHITE);
    }

    private void selectMode(int m) {
        mode = m;
        prefs.edit().putInt("mode", m).apply();
        panelWifi.setVisibility(m == WIFI ? View.VISIBLE : View.GONE);
        panelBt.setVisibility(m == BT ? View.VISIBLE : View.GONE);
        panelIr.setVisibility(m == IR ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 3; i++) styleTab(modeTabs[i], i == m);
        if (m == WIFI) {
            setStatus(AdbBridge.isConnected() ? "WiFi: conectado \u2713" : "WiFi: sin conectar");
        } else if (m == BT) {
            setStatus(hid.isConnected() ? "Bluetooth: conectado \u2713" : "Bluetooth: sin conectar");
        } else {
            boolean has = ir != null && ir.hasIrEmitter();
            setStatus(has ? "IR: este celular tiene emisor" : "IR: este celular NO tiene emisor infrarrojo");
        }
    }

    private void selectPage(int p) {
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == p ? View.VISIBLE : View.GONE);
            styleTab(pageTabs[i], i == p);
        }
    }

    // ------------------------------------------------- Paneles de conexion
    private LinearLayout buildWifiPanel() {
        LinearLayout p = column();
        p.setPadding(0, dp(8), 0, 0);
        TextView hint = label("En el TV activa Opciones de desarrollador \u2192 Depuraci\u00f3n USB y por red. "
                + "Escribe aqu\u00ed la IP del TV (Ajustes \u2192 Red).", 12, MUTED);
        p.addView(hint);
        ipField = new EditText(this);
        ipField.setHint("IP del TV, por ejemplo 192.168.1.50");
        ipField.setText(prefs.getString("ip", ""));
        ipField.setTextColor(WHITE);
        ipField.setHintTextColor(MUTED);
        ipField.setSingleLine(true);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT);
        p.addView(ipField);
        TextView go = btn("Conectar", BLUE, WHITE, 15, null);
        go.setOnClickListener(v -> connectWifi());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(6);
        p.addView(go, lp);
        return p;
    }

    private LinearLayout buildBtPanel() {
        LinearLayout p = column();
        p.setPadding(0, dp(8), 0, 0);
        p.addView(label("1) Toca \u201cHacer visible\u201d. 2) En el TV busca un accesorio Bluetooth y elige este celular. "
                + "Despu\u00e9s se reconecta con \u201cElegir TV\u201d.", 12, MUTED));
        LinearLayout r = row();
        TextView a = btn("Hacer visible", BLUE, WHITE, 14, null);
        a.setOnClickListener(v -> makeVisible());
        TextView b = btn("Elegir TV", BODY, WHITE, 14, null);
        b.setOnClickListener(v -> chooseTv());
        add(r, a, 1, 46);
        add(r, b, 1, 46);
        p.addView(r);
        return p;
    }

    private LinearLayout buildIrPanel() {
        LinearLayout p = column();
        p.setPadding(0, dp(8), 0, 0);
        p.addView(label("El control IR necesita los c\u00f3digos del TV. Mant\u00e9n pulsado un bot\u00f3n del mando "
                + "para escribirle su c\u00f3digo (direcci\u00f3n y comando en hexadecimal, formato NEC).", 12, MUTED));
        return p;
    }

    // --------------------------------------------------------- WiFi (ADB)
    private void connectWifi() {
        final String ip = ipField.getText().toString().trim();
        if (ip.isEmpty()) {
            setStatus("Escribe la IP del TV");
            return;
        }
        prefs.edit().putString("ip", ip).apply();
        setStatus("WiFi: conectando\u2026 acepta el aviso que sale en la pantalla del TV");
        final File dir = getFilesDir();
        io.execute(() -> {
            try {
                AdbBridge.connect(dir, ip);
                String sz = AdbBridge.shell("wm size");
                Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(sz);
                while (m.find()) {
                    tvW = Integer.parseInt(m.group(1));
                    tvH = Integer.parseInt(m.group(2));
                }
                postStatus("WiFi: conectado \u2713");
            } catch (Throwable t) {
                postStatus("WiFi: no se pudo conectar (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        });
    }

    private void adb(final String cmd) {
        if (!AdbBridge.isConnected()) {
            toast("Conecta primero por WiFi");
            return;
        }
        io.execute(() -> {
            try {
                AdbBridge.shell(cmd);
            } catch (Throwable t) {
                postStatus("WiFi: error (" + t.getMessage() + ")");
            }
        });
    }

    private void launchPkg(final String pkg) {
        if (!pkg.matches("[A-Za-z0-9._]+")) return;
        if (!AdbBridge.isConnected()) {
            toast("Conecta primero por WiFi");
            return;
        }
        io.execute(() -> {
            try {
                String o = AdbBridge.shell("monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1");
                if (o.contains("No activities")) {
                    AdbBridge.shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
                }
            } catch (Throwable t) {
                postStatus("WiFi: error (" + t.getMessage() + ")");
            }
        });
    }

    private static Set<String> pkgs(String out) {
        Set<String> s = new LinkedHashSet<>();
        for (String line : out.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("package:")) s.add(line.substring(8).trim());
        }
        return s;
    }

    private static String nice(String pkg) {
        String n = pkg.substring(pkg.lastIndexOf('.') + 1);
        if (n.isEmpty()) return pkg;
        return n.substring(0, 1).toUpperCase() + n.substring(1);
    }

    private void showApps() {
        if (mode != WIFI || !AdbBridge.isConnected()) {
            toast("Para ver las aplicaciones conecta por WiFi");
            return;
        }
        setStatus("Buscando aplicaciones\u2026");
        io.execute(() -> {
            try {
                Set<String> all = pkgs(AdbBridge.shell("pm list packages"));
                Set<String> user = pkgs(AdbBridge.shell("pm list packages -3"));
                final List<String[]> list = new ArrayList<>();
                for (String p : all) {
                    String n = KNOWN.get(p);
                    if (n != null || user.contains(p)) {
                        list.add(new String[]{n != null ? n : nice(p), p});
                    }
                }
                Collections.sort(list, (a, b) -> a[0].compareToIgnoreCase(b[0]));
                ui.post(() -> {
                    setStatus("WiFi: conectado \u2713");
                    showAppsDialog(list);
                });
            } catch (Throwable t) {
                postStatus("WiFi: error (" + t.getMessage() + ")");
            }
        });
    }

    private void showAppsDialog(final List<String[]> list) {
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("Aplicaciones del TV")
                .setItems(names, (d, i) -> launchPkg(list.get(i)[1]))
                .show();
    }

    // ----------------------------------------------------------- Bluetooth
    private void makeVisible() {
        if (!btPermsOk()) return;
        startHid();
        try {
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(i);
        } catch (Exception e) {
            toast("No se pudo hacer visible el celular");
        }
    }

    private void chooseTv() {
        if (!btPermsOk()) return;
        final List<BluetoothDevice> list = hid.bonded();
        if (list.isEmpty()) {
            toast("Primero empareja el celular con el TV");
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            String n = null;
            try {
                n = list.get(i).getName();
            } catch (Exception ignored) {
            }
            names[i] = n != null ? n : list.get(i).getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("Elige el TV")
                .setItems(names, (d, i) -> hid.connect(list.get(i)))
                .show();
    }

    // ----------------------------------------------------------------- IR
    private static int[] nec(int address, int command) {
        int a1, a2;
        if (address > 0xFF) {
            a1 = address & 0xFF;
            a2 = (address >> 8) & 0xFF;
        } else {
            a1 = address;
            a2 = (~address) & 0xFF;
        }
        int c1 = command & 0xFF, c2 = (~command) & 0xFF;
        long data = (long) a1 | ((long) a2 << 8) | ((long) c1 << 16) | ((long) c2 << 24);
        int[] p = new int[2 + 64 + 2];
        int n = 0;
        p[n++] = 9000;
        p[n++] = 4500;
        for (int i = 0; i < 32; i++) {
            p[n++] = 560;
            p[n++] = ((data >> i) & 1L) == 1L ? 1690 : 560;
        }
        p[n++] = 560;
        p[n++] = 40000;
        return Arrays.copyOf(p, n);
    }

    private void sendIr(String id) {
        if (ir == null || !ir.hasIrEmitter()) {
            toast("Este celular no tiene emisor IR");
            return;
        }
        String s = prefs.getString("ir_" + id, null);
        if (s == null) {
            toast("Sin c\u00f3digo IR: mant\u00e9n pulsado el bot\u00f3n para ponerle uno");
            return;
        }
        try {
            String[] p = s.split(",");
            int a = Integer.parseInt(p[0].trim(), 16);
            int c = Integer.parseInt(p[1].trim(), 16);
            ir.transmit(38000, nec(a, c));
        } catch (Exception e) {
            toast("C\u00f3digo IR inv\u00e1lido");
        }
    }

    private void editIr(final String id) {
        String cur = prefs.getString("ir_" + id, "");
        String[] p = cur.split(",");
        LinearLayout box = column();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText a = new EditText(this);
        a.setHint("Direcci\u00f3n (hex), ej. 04");
        a.setText(p.length > 0 ? p[0] : "");
        final EditText c = new EditText(this);
        c.setHint("Comando (hex), ej. 08");
        c.setText(p.length > 1 ? p[1] : "");
        box.addView(a);
        box.addView(c);
        new AlertDialog.Builder(this)
                .setTitle("C\u00f3digo IR de " + id)
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    String x = a.getText().toString().trim();
                    String y = c.getText().toString().trim();
                    if (x.isEmpty() || y.isEmpty()) {
                        prefs.edit().remove("ir_" + id).apply();
                    } else {
                        prefs.edit().putString("ir_" + id, x + "," + y).apply();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------ Pulsar botones
    private void press(String id) {
        Key k = KEYS.get(id);
        if (k == null) return;
        if (mode == WIFI) {
            if (k.adb > 0) {
                adb("input keyevent " + k.adb);
            } else if (k.pkg != null) {
                launchPkg(k.pkg);
            } else {
                toast("Este bot\u00f3n no funciona por WiFi");
            }
        } else if (mode == BT) {
            if (!hid.isConnected()) {
                toast("Bluetooth sin conectar");
                return;
            }
            if (k.cons > 0) {
                hid.consumer(k.cons);
            } else if (k.kb > 0) {
                hid.key(k.kb, 0);
            } else {
                toast("Este bot\u00f3n no existe por Bluetooth");
            }
        } else {
            sendIr(id);
        }
    }

    private void bindKey(View v, final String id) {
        final boolean rep = REPEAT.contains(id);
        final Runnable loop = new Runnable() {
            @Override
            public void run() {
                press(id);
                ui.postDelayed(this, 110);
            }
        };
        final Runnable longIr = () -> editIr(id);
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    if (mode == IR) {
                        ui.postDelayed(longIr, 900);
                    }
                    press(id);
                    if (rep) ui.postDelayed(loop, 450);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    ui.removeCallbacks(loop);
                    ui.removeCallbacks(longIr);
                    return true;
                default:
                    return true;
            }
        });
    }

    // ---------------------------------------------------------------- Voz
    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            toast("Este celular no tiene reconocimiento de voz");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) askSend(r.get(0));
        }
    }

    private void askSend(String text) {
        final EditText et = new EditText(this);
        et.setText(text);
        new AlertDialog.Builder(this)
                .setTitle("Escribir en el TV")
                .setView(et)
                .setPositiveButton("Escribir", (d, w) -> sendText(et.getText().toString(), false))
                .setNeutralButton("Escribir + Enter", (d, w) -> sendText(et.getText().toString(), true))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void sendText(String text, final boolean enter) {
        final String t = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (t.trim().isEmpty()) return;
        if (mode == WIFI) {
            StringBuilder sb = new StringBuilder();
            for (char c : t.toCharArray()) {
                if (c < 128 && Character.isLetterOrDigit(c)) {
                    sb.append(c);
                } else if (c == ' ') {
                    sb.append("%s");
                } else if (".,-_@".indexOf(c) >= 0) {
                    sb.append(c);
                }
            }
            adb("input text " + sb);
            if (enter) adb("input keyevent 66");
        } else if (mode == BT) {
            if (!hid.isConnected()) {
                toast("Bluetooth sin conectar");
                return;
            }
            io.execute(() -> {
                hid.type(t);
                if (enter) hid.key(0x28, 0);
            });
        } else {
            toast("Por IR no se puede escribir");
        }
    }

    // ------------------------------------------------------ Pagina: Mando
    private LinearLayout buildRemote() {
        LinearLayout p = column();

        // Fuente / microfono / encendido
        LinearLayout r1 = row();
        add(r1, btn("FUENTE", BODY, WHITE, 12, "SOURCE"), 1, 48);
        TextView mic = btn("\uD83C\uDFA4", BODY, WHITE, 20, null);
        mic.setOnClickListener(v -> startVoice());
        add(r1, mic, 1, 48);
        TextView pw = btn("\u23FB", RED, WHITE, 22, "POWER");
        pw.setBackground(circle(RED));
        addFixed(r1, pw, 56, 56);
        p.addView(r1);

        // Teclado numerico
        String[][] nums = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}};
        for (String[] rowNums : nums) {
            LinearLayout r = row();
            for (String n : rowNums) add(r, btn(n, BODY, WHITE, 20, n), 1, 50);
            p.addView(r);
        }
        LinearLayout r0 = row();
        add(r0, btn("\u2022", BODY, WHITE, 20, "DOT"), 1, 50);
        add(r0, btn("0", BODY, WHITE, 20, "0"), 1, 50);
        add(r0, btn("MUTE", BODY, WHITE, 12, "MUTE"), 1, 50);
        p.addView(r0);

        // Apps / Menu / Info
        LinearLayout ra = row();
        add(ra, btn("Apps", WHITE, INK, 14, "APPS"), 1, 46);
        add(ra, btn("MENU", BODY, WHITE, 12, "MENU"), 1, 46);
        add(ra, btn("INFO", BODY, WHITE, 12, "INFO"), 1, 46);
        p.addView(ra);

        // Volumen y canales
        LinearLayout rv = row();
        add(rv, pillBox("VOL", "VOLDOWN", "VOLUP"), 1, 52);
        add(rv, pillBox("CH", "CHDOWN", "CHUP"), 1, 52);
        p.addView(rv);

        // Files / Library
        LinearLayout rf = row();
        add(rf, btn("Files", WHITE, INK, 14, "FILES"), 1, 44);
        TextView lib = btn("Library", WHITE, INK, 14, null);
        lib.setOnClickListener(v -> showApps());
        add(rf, lib, 1, 44);
        p.addView(rf);

        // Cruceta
        LinearLayout dp = new LinearLayout(this);
        dp.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0xFF1E2024);
        dp.setBackground(ring);
        dp.addView(rowCells(null, arrow("\u25B2", "UP"), null));
        LinearLayout mid = rowCells(arrow("\u25C0", "LEFT"), null, arrow("\u25B6", "RIGHT"));
        TextView ok = btn("OK", BODY, WHITE, 20, "OK");
        ok.setBackground(circle(BODY));
        replaceMiddle(mid, ok);
        dp.addView(mid);
        dp.addView(rowCells(null, arrow("\u25BC", "DOWN"), null));
        LinearLayout holder = row();
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(240), dp(240));
        dlp.setMargins(0, dp(10), 0, dp(10));
        holder.addView(dp, dlp);
        p.addView(holder);

        // Inicio / Atras / Ajustes
        LinearLayout rn = row();
        add(rn, btn("INICIO", BODY, WHITE, 12, "HOME"), 1, 46);
        add(rn, btn("ATR\u00c1S", BODY, WHITE, 12, "BACK"), 1, 46);
        add(rn, btn("\u2699", BODY, WHITE, 20, "SETTINGS"), 1, 46);
        p.addView(rn);

        // Colores
        LinearLayout rc = row();
        add(rc, btn("", RED, WHITE, 12, "RED"), 1, 30);
        add(rc, btn("", GREEN, WHITE, 12, "GREEN"), 1, 30);
        add(rc, btn("", YELLOW, WHITE, 12, "YELLOW"), 1, 30);
        add(rc, btn("", BLUE, WHITE, 12, "BLUE"), 1, 30);
        p.addView(rc);

        // Extras
        LinearLayout re = row();
        add(re, btn("EPG", BODY, WHITE, 12, "EPG"), 1, 40);
        add(re, btn("SLEEP", BODY, WHITE, 12, "SLEEP"), 1, 40);
        add(re, btn("CC", BODY, WHITE, 12, "CC"), 1, 40);
        p.addView(re);

        // Streaming
        LinearLayout s1 = row();
        add(s1, btn("NETFLIX", WHITE, 0xFFE50914, 15, "NETFLIX"), 1, 50);
        add(s1, btn("prime video", WHITE, 0xFF0F79AF, 14, "PRIME"), 1, 50);
        p.addView(s1);
        LinearLayout s2 = row();
        add(s2, btn("YouTube", WHITE, 0xFFD50000, 15, "YOUTUBE"), 1, 50);
        add(s2, btn("Disney+", WHITE, 0xFF113CCF, 15, "DISNEY"), 1, 50);
        p.addView(s2);

        return p;
    }

    private Drawable circle(int color) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(color);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(lighten(color));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(new int[]{}, normal);
        return s;
    }

    private TextView arrow(String sym, String id) {
        TextView t = btn(sym, 0x00000000, WHITE, 22, id);
        return t;
    }

    // Fila de 3 celdas iguales (null = vacia)
    private LinearLayout rowCells(View a, View b, View c) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        View[] vs = {a, b, c};
        for (View v : vs) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            r.addView(v != null ? v : space(), lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        r.setLayoutParams(rl);
        return r;
    }

    private void replaceMiddle(LinearLayout rowView, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        rowView.removeViewAt(1);
        rowView.addView(v, 1, lp);
    }

    // Boton ancho tipo pastilla:  -  VOL  +
    private LinearLayout pillBox(String name, String idMinus, String idPlus) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(shape(0xFF1E2024, dp(26)));
        TextView minus = btn("\u2212", 0x00000000, WHITE, 22, idMinus);
        TextView lab = label(name, 12, MUTED);
        lab.setGravity(Gravity.CENTER);
        TextView plus = btn("+", 0x00000000, WHITE, 22, idPlus);
        box.addView(minus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        box.addView(lab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(plus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return box;
    }

    // ------------------------------------------------------ Pagina: Raton
    private LinearLayout buildMouse() {
        LinearLayout p = column();
        final TextView pad = new TextView(this);
        pad.setText("Desliza aqu\u00ed para mover el cursor\nToca para hacer clic");
        pad.setTextColor(MUTED);
        pad.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1C1E22);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(2), 0xFF3A3D44);
        pad.setBackground(g);
        pad.setOnTouchListener(new View.OnTouchListener() {
            float lastX, lastY, startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = startX = e.getX();
                        lastY = startY = e.getY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - lastX, dy = e.getY() - lastY;
                        lastX = e.getX();
                        lastY = e.getY();
                        if (Math.abs(e.getX() - startX) + Math.abs(e.getY() - startY) > dp(10)) moved = true;
                        if (mode == BT && hid.isConnected()) {
                            hid.mouse(0, Math.round(dx * 1.8f), Math.round(dy * 1.8f), 0);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (mode == BT) {
                            if (!moved) hid.click(1);
                        } else if (mode == WIFI) {
                            int x1 = Math.round(startX / v.getWidth() * tvW);
                            int y1 = Math.round(startY / v.getHeight() * tvH);
                            int x2 = Math.round(e.getX() / v.getWidth() * tvW);
                            int y2 = Math.round(e.getY() / v.getHeight() * tvH);
                            if (!moved) {
                                adb("input tap " + x1 + " " + y1);
                            } else {
                                adb("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 300");
                            }
                        } else {
                            toast("El rat\u00f3n no funciona por IR");
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        plp.setMargins(dp(4), dp(8), dp(4), dp(8));
        p.addView(pad, plp);

        LinearLayout r1 = row();
        TextView left = btn("Clic izquierdo", BODY, WHITE, 14, null);
        left.setOnClickListener(v -> mouseClick(1));
        TextView right = btn("Clic derecho", BODY, WHITE, 14, null);
        right.setOnClickListener(v -> mouseClick(2));
        add(r1, left, 1, 50);
        add(r1, right, 1, 50);
        p.addView(r1);

        LinearLayout r2 = row();
        TextView up = btn("\u25B2 Subir", BODY, WHITE, 14, null);
        up.setOnClickListener(v -> mouseScroll(true));
        TextView down = btn("\u25BC Bajar", BODY, WHITE, 14, null);
        down.setOnClickListener(v -> mouseScroll(false));
        add(r2, up, 1, 50);
        add(r2, down, 1, 50);
        p.addView(r2);

        TextView note = label("Bluetooth: cursor de verdad. WiFi: la superficie representa la pantalla del TV "
                + "(toca donde quieras hacer clic).", 12, MUTED);
        note.setPadding(dp(4), dp(8), dp(4), 0);
        p.addView(note);
        return p;
    }

    private void mouseClick(int button) {
        if (mode == BT) {
            if (hid.isConnected()) {
                hid.click(button);
            } else {
                toast("Bluetooth sin conectar");
            }
        } else if (mode == WIFI) {
            adb("input tap " + (tvW / 2) + " " + (tvH / 2));
        } else {
            toast("El rat\u00f3n no funciona por IR");
        }
    }

    private void mouseScroll(boolean up) {
        if (mode == BT) {
            if (hid.isConnected()) {
                for (int i = 0; i < 3; i++) hid.mouse(0, 0, 0, up ? 1 : -1);
            } else {
                toast("Bluetooth sin conectar");
            }
        } else if (mode == WIFI) {
            int cx = tvW / 2;
            int y1 = tvH / 2;
            int y2 = up ? y1 + tvH / 3 : y1 - tvH / 3;
            adb("input swipe " + cx + " " + y1 + " " + cx + " " + y2 + " 250");
        } else {
            toast("El rat\u00f3n no funciona por IR");
        }
    }

    // ------------------------------------------------------ Pagina: Juegos
    private int hat() {
        if (pUp && pRight) return 2;
        if (pRight && pDown) return 4;
        if (pDown && pLeft) return 6;
        if (pLeft && pUp) return 8;
        if (pUp) return 1;
        if (pRight) return 3;
        if (pDown) return 5;
        if (pLeft) return 7;
        return 0;
    }

    private void sendPad() {
        if (hid.isConnected()) hid.gamepad(padBtns, hat(), lx, ly, rx, ry);
    }

    private void padDown(int bit, int adbKey) {
        if (mode == BT) {
            if (!hid.isConnected()) {
                toast("Bluetooth sin conectar");
                return;
            }
            if (bit >= 0) padBtns |= (1 << bit);
            else if (bit == -1) pUp = true;
            else if (bit == -2) pDown = true;
            else if (bit == -3) pLeft = true;
            else if (bit == -4) pRight = true;
            sendPad();
        } else if (mode == WIFI) {
            adb("input keyevent " + adbKey);
        } else {
            toast("Los juegos no funcionan por IR");
        }
    }

    private void padUp(int bit) {
        if (mode != BT) return;
        if (bit >= 0) padBtns &= ~(1 << bit);
        else if (bit == -1) pUp = false;
        else if (bit == -2) pDown = false;
        else if (bit == -3) pLeft = false;
        else if (bit == -4) pRight = false;
        sendPad();
    }

    private TextView padBtn(String text, final int bit, final int adbKey, int bg) {
        TextView t = btn(text, bg, WHITE, 16, null);
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    padDown(bit, adbKey);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    padUp(bit);
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    private LinearLayout grid3(View up, View left, View center, View right, View down, int sizeDp) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.addView(rowCells(null, up, null));
        g.addView(rowCells(left, center, right));
        g.addView(rowCells(null, down, null));
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return g;
    }

    private LinearLayout buildPad() {
        LinearLayout p = column();

        // Gatillos
        LinearLayout t = row();
        add(t, padBtn("L2", 8, 104, BODY), 1, 44);
        add(t, padBtn("L1", 6, 102, BODY), 1, 44);
        add(t, space(), 0.6f, 44);
        add(t, padBtn("R1", 7, 103, BODY), 1, 44);
        add(t, padBtn("R2", 9, 105, BODY), 1, 44);
        p.addView(t);

        // Cruceta - centro - ABXY
        LinearLayout m = row();
        LinearLayout dpad = grid3(
                padBtn("\u25B2", -1, 19, BODY), padBtn("\u25C0", -3, 21, BODY), null,
                padBtn("\u25B6", -4, 22, BODY), padBtn("\u25BC", -2, 20, BODY), 150);
        LinearLayout center = column();
        center.setGravity(Gravity.CENTER);
        TextView sel = padBtn("SELECT", 10, 109, BODY);
        TextView sta = padBtn("START", 11, 108, BODY);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        clp.setMargins(dp(4), dp(6), dp(4), dp(6));
        center.addView(sel, clp);
        center.addView(sta, clp);
        LinearLayout face = grid3(
                padBtn("Y", 4, 100, YELLOW), padBtn("X", 3, 99, BLUE), null,
                padBtn("B", 1, 97, RED), padBtn("A", 0, 96, GREEN), 150);
        m.addView(dpad);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        m.addView(center, ml);
        m.addView(face);
        p.addView(m);

        // Palancas
        LinearLayout s = row();
        Stick left = new Stick(this, (x, y) -> {
            lx = 128 + Math.round(x * 127);
            ly = 128 + Math.round(y * 127);
            sendPad();
        });
        Stick right = new Stick(this, (x, y) -> {
            rx = 128 + Math.round(x * 127);
            ry = 128 + Math.round(y * 127);
            sendPad();
        });
        addFixed(s, left, 150, 150);
        add(s, space(), 1, 10);
        addFixed(s, right, 150, 150);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        p.addView(s, slp);

        TextView note = label("Bluetooth: mando completo con palancas. WiFi: solo botones y cruceta.", 12, MUTED);
        note.setPadding(dp(4), dp(8), dp(4), 0);
        p.addView(note);
        return p;
    }

    private class Stick extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final StickCb cb;
        private float kx = 0, ky = 0;

        Stick(Context c, StickCb cb) {
            super(c);
            this.cb = cb;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF26282D);
            c.drawCircle(w / 2f, h / 2f, r, paint);
            paint.setColor(0xFF4A4E57);
            c.drawCircle(w / 2f + kx * r * 0.6f, h / 2f + ky * r * 0.6f, r * 0.4f, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    float x = (e.getX() - w / 2f) / (r * 0.6f);
                    float y = (e.getY() - h / 2f) / (r * 0.6f);
                    float m = (float) Math.sqrt(x * x + y * y);
                    if (m > 1f) {
                        x /= m;
                        y /= m;
                    }
                    kx = x;
                    ky = y;
                    cb.onMove(x, y);
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    kx = 0;
                    ky = 0;
                    cb.onMove(0, 0);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }
}
