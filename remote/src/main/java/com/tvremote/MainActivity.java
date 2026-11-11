package com.tvremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    static final int GRAY = 0xFF2C2C2E, GRAY_P = 0xFF58585C;
    static final int BLUE = 0xFF0A84FF, BLUE_P = 0xFF4DA3FF;
    static final int RED = 0xFFC0392B, RED_P = 0xFFE5604F;
    static final int OK_GREEN = 0xFF30D158, BAD_RED = 0xFFFF453A, MUTED = 0xFF9A9AA0;

    final Adb adb = new Adb();
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    final AtomicInteger pending = new AtomicInteger();
    SharedPreferences prefs;
    TextView status;
    EditText ipField, textField;
    volatile String host = "192.168.1.34";
    float d;

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
    void connectAsync() {
        host = ipField.getText().toString().trim();
        prefs.edit().putString("ip", host).apply();
        setStatus("Conectando...", MUTED);
        io.execute(new Runnable() {
            public void run() {
                try {
                    adb.connect(host);
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
                    catch (IOException e) { adb.connect(host); adb.shell(cmd); }
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

    Runnable appAction(final String pkg) {
        return new Runnable() {
            public void run() {
                exec("o=$(monkey -p " + pkg + " -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1); "
                        + "case \"$o\" in *\"No activities\"*) monkey -p " + pkg
                        + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1;; esac");
            }
        };
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
        t.setTextSize(18);
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
        return btn(label, GRAY, GRAY_P, action, repeat, 64);
    }

    LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    View space(int hDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(hDp)));
        return v;
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1f);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        e.setLayoutParams(p);
        return e;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        host = prefs.getString("ip", "192.168.1.34");

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF111113);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(22), dp(14), dp(28));
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("Control TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(5), 0, 0, dp(8));
        col.addView(title);

        ipField = field("IP del TV", host);
        col.addView(row(ipField, btn("Conectar", BLUE, BLUE_P, new Runnable() {
            public void run() { connectAsync(); }
        }, false, 56)));
        status = new TextView(this);
        status.setText("Sin conectar");
        status.setTextColor(MUTED);
        status.setTextSize(14);
        status.setPadding(dp(8), dp(2), 0, dp(8));
        col.addView(status);

        col.addView(row(gray("Atrás", keyAction(4), false), gray("▲", keyAction(19), true),
                gray("Inicio", keyAction(3), false)));
        col.addView(row(gray("◀", keyAction(21), true),
                btn("OK", BLUE, BLUE_P, keyAction(23), false, 64), gray("▶", keyAction(22), true)));
        col.addView(row(gray("Menú", keyAction(82), false), gray("▼", keyAction(20), true),
                gray("⏯", keyAction(85), false)));
        col.addView(space(10));
        col.addView(row(gray("Vol −", keyAction(25), true), gray("Silencio", keyAction(164), false),
                gray("Vol +", keyAction(24), true)));
        col.addView(row(gray("Entrada", keyAction(178), false),
                btn("Encender / Apagar", RED, RED_P, keyAction(26), false, 64)));
        col.addView(space(10));
        col.addView(row(gray("Magma", appAction("com.magmaplayer"), false),
                gray("Kodi", appAction("org.xbmc.kodi"), false)));
        col.addView(row(gray("YouTube", appAction("com.google.android.youtube.tv"), false),
                gray("Aptoide", appAction("cm.aptoidetv.pt.top_tech"), false)));
        col.addView(space(10));

        textField = field("Escribir en el TV", "");
        col.addView(row(textField, btn("Enviar", BLUE, BLUE_P, new Runnable() {
            public void run() {
                String s = textField.getText().toString();
                if (s.length() == 0) return;
                String esc = s.replace("'", "'\\''").replace(" ", "%s");
                exec("input text '" + esc + "'");
                textField.setText("");
            }
        }, false, 56), btn("⌫", GRAY, GRAY_P, keyAction(67), true, 56)));

        setContentView(sv);
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
