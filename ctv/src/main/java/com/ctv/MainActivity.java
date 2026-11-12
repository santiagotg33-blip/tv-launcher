package com.ctv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    final Handler h = new Handler(Looper.getMainLooper());
    final List<File> files = new ArrayList<File>();
    float d;
    TextView urlView, codeView, errView, emptyView;
    ListView list;
    FilesAdapter adapter;
    String sig = "";

    final Runnable tick = new Runnable() {
        public void run() { refresh(); h.postDelayed(this, 2500); }
    };

    int dp(int v) { return Math.round(v * d); }

    GradientDrawable shape(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    TextView button(String text, final Runnable action) {
        TextView t = label(text, 18, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(26), dp(14), dp(26), dp(14));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_focused }, shape(0xFF0A84FF, 14));
        s.addState(new int[] { android.R.attr.state_pressed }, shape(0xFF0A84FF, 14));
        s.addState(new int[] {}, shape(0xFF2C2C2E, 14));
        t.setBackground(s);
        t.setFocusable(true);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(14), 0);
        t.setLayoutParams(p);
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_ctv);
        head.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(76)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(18), 0, 0, 0);
        titles.addView(label("CTV", 40, Color.WHITE, true));
        titles.addView(label("Envía y recibe archivos y aplicaciones", 18, 0xFF9A9AA0, false));
        head.addView(titles);
        root.addView(head);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(0xFF1C1C1E, 18));
        card.setPadding(dp(24), dp(18), dp(24), dp(18));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, dp(18), 0, dp(12));
        card.setLayoutParams(cp);
        card.addView(label("Abre esta dirección en tu celular o computador (misma red Wi-Fi):", 16, 0xFF9A9AA0, false));
        urlView = label("Iniciando...", 28, 0xFF4DA3FF, true);
        urlView.setPadding(0, dp(6), 0, dp(4));
        card.addView(urlView);
        codeView = label("", 16, 0xFF9A9AA0, false);
        card.addView(codeView);
        errView = label("", 14, 0xFFFF453A, false);
        card.addView(errView);
        root.addView(card);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView b1 = button("Cambiar código", new Runnable() {
            public void run() {
                CtvServer.newToken(MainActivity.this);
                refresh();
                Toast.makeText(MainActivity.this, "Código nuevo. Usa la dirección actualizada.", Toast.LENGTH_LONG).show();
            }
        });
        TextView b2 = button("Actualizar lista", new Runnable() {
            public void run() { sig = ""; refresh(); }
        });
        buttons.addView(b1);
        buttons.addView(b2);
        root.addView(buttons);

        TextView listTitle = label("Archivos recibidos (carpeta Descargas/CTV)", 18, Color.WHITE, true);
        listTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(listTitle);

        emptyView = label("Todavía no hay archivos. Envía uno desde tu celular con la dirección de arriba.", 16, 0xFF9A9AA0, false);
        root.addView(emptyView);

        list = new ListView(this);
        adapter = new FilesAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(shape(0x33FFFFFF, 12));
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { showOptions(files.get(pos)); }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        b1.requestFocus();

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE }, 1);
        }
        startService(new Intent(this, CtvService.class));
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        sig = "";
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        startService(new Intent(this, CtvService.class));
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable t) { }
        return "?";
    }

    void refresh() {
        CtvServer s = CtvService.server;
        String tk = CtvServer.token(this);
        if (s != null && s.running) {
            urlView.setText("http://" + localIp() + ":" + s.port + "/?k=" + tk);
            errView.setText("");
        } else {
            urlView.setText("Iniciando...");
            errView.setText(s != null ? s.error : "");
        }
        codeView.setText("Código de acceso: " + tk);

        File[] fs = CtvServer.dir(this).listFiles();
        List<File> now = new ArrayList<File>();
        if (fs != null) {
            for (File f : fs) if (f.isFile() && !f.getName().endsWith(".part")) now.add(f);
        }
        Collections.sort(now, new Comparator<File>() {
            public int compare(File a, File c) { return Long.compare(c.lastModified(), a.lastModified()); }
        });
        StringBuilder sb = new StringBuilder();
        for (File f : now) sb.append(f.getName()).append(f.length()).append(';');
        String ns = sb.toString();
        if (!ns.equals(sig)) {
            sig = ns;
            files.clear();
            files.addAll(now);
            adapter.notifyDataSetChanged();
        }
        emptyView.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------- archivos ----------
    String mimeOf(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        if (i >= 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(i + 1).toLowerCase(Locale.US));
            if (m != null) return m;
        }
        return "*/*";
    }

    void openFile(File f, boolean install) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), install ? "application/vnd.android.package-archive" : mimeOf(f));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "No hay una aplicación para abrir este archivo", Toast.LENGTH_LONG).show();
        }
    }

    void showOptions(final File f) {
        final boolean apk = f.getName().toLowerCase(Locale.US).endsWith(".apk");
        final String[] opts = apk ? new String[] { "Instalar", "Eliminar" } : new String[] { "Abrir", "Eliminar" };
        new AlertDialog.Builder(this).setTitle(f.getName()).setItems(opts, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dlg, int which) {
                if (which == 0) {
                    openFile(f, apk);
                } else {
                    if (f.delete()) Toast.makeText(MainActivity.this, "Eliminado", Toast.LENGTH_SHORT).show();
                    sig = "";
                    refresh();
                }
            }
        }).show();
    }

    class FilesAdapter extends BaseAdapter {
        public int getCount() { return files.size(); }
        public Object getItem(int i) { return files.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View cv, ViewGroup parent) {
            if (cv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));
                TextView a = label("", 20, Color.WHITE, true);
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                TextView c = label("", 14, 0xFF9A9AA0, false);
                row.addView(a);
                row.addView(c);
                row.setTag(new TextView[] { a, c });
                cv = row;
            }
            TextView[] t = (TextView[]) cv.getTag();
            File f = files.get(i);
            t[0].setText(f.getName());
            String kind = f.getName().toLowerCase(Locale.US).endsWith(".apk") ? "  ·  Aplicación (APK)" : "";
            t[1].setText(CtvServer.fmt(f.length()) + "  ·  "
                    + new SimpleDateFormat("d MMM HH:mm", new Locale("es")).format(new Date(f.lastModified())) + kind);
            return cv;
        }
    }
}
