package com.tvlauncher.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    // Dock de izquierda a derecha. Se busca cada app por su nombre visible.
    // Magma va primero y recibe el foco al abrir.
    private static final String[] DOCK = {"magma", "kodi", "youtube", "aptoide tv"};
    private static final int GRID_COLUMNS = 6;

    private PackageManager pm;
    private float unit; // pixeles por "pixel de 1080p", independiente de la densidad

    @Override
    protected void onResume() {
        super.onResume();
        pm = getPackageManager();
        unit = getResources().getDisplayMetrics().heightPixels / 1080f;
        build();
    }

    // El boton Atras no debe sacarnos del inicio
    @Override
    public void onBackPressed() { }

    private int px(int v) {
        return Math.round(v * unit);
    }

    private String label(ResolveInfo r) {
        return r.loadLabel(pm).toString();
    }

    private List<ResolveInfo> loadApps() {
        Map<String, ResolveInfo> map = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (!map.containsKey(pkg)) map.put(pkg, r);
            }
        }
        List<ResolveInfo> list = new ArrayList<>(map.values());
        Collections.sort(list, (a, b) -> label(a).compareToIgnoreCase(label(b)));
        return list;
    }

    private ResolveInfo find(List<ResolveInfo> apps, String key) {
        for (ResolveInfo r : apps) {
            if (label(r).trim().toLowerCase().equals(key)) return r;
        }
        for (ResolveInfo r : apps) {
            if (label(r).toLowerCase().contains(key)) return r;
        }
        return null;
    }

    private void build() {
        List<ResolveInfo> apps = loadApps();
        List<ResolveInfo> dock = new ArrayList<>();
        for (String key : DOCK) {
            ResolveInfo r = find(apps, key);
            if (r != null) {
                dock.add(r);
                apps.remove(r);
            }
        }

        int sidePad = px(64);
        int usable = getResources().getDisplayMetrics().widthPixels - 2 * sidePad;
        int margin = px(8);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(sidePad, px(40), sidePad, px(24));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1220, 0xFF1B2A4A}));

        // Encabezado: titulo + reloj
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Inicio", px(40)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("h:mm a");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(40));
        clock.setTextColor(0xFFFFFFFF);
        header.addView(clock);
        page.addView(header);

        // Dock (4 apps favoritas)
        LinearLayout dockRow = row();
        dockRow.setPadding(0, px(28), 0, px(20));
        int dockW = usable / 4 - 2 * margin;
        for (ResolveInfo r : dock) {
            dockRow.addView(tile(r, dockW, px(190), true, px(26), margin));
        }
        page.addView(dockRow);

        // Resto de las apps
        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(px(8), px(8), px(8), px(8));
        int gridW = usable / GRID_COLUMNS - 2 * margin;
        LinearLayout current = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % GRID_COLUMNS == 0) {
                current = row();
                col.addView(current);
            }
            current.addView(tile(apps.get(i), gridW, px(160), false, px(22), margin));
        }
        scroll.addView(col);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(page);

        if (dockRow.getChildCount() > 0) {
            dockRow.getChildAt(0).requestFocus(); // Magma
        } else if (col.getChildCount() > 0) {
            ((ViewGroup) col.getChildAt(0)).getChildAt(0).requestFocus();
        }
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setClipChildren(false);
        return l;
    }

    private TextView text(String s, int sizePx) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(0xFFFFFFFF);
        return t;
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(14));
        if (stroke != 0) g.setStroke(px(4), stroke);
        return g;
    }

    private View tile(final ResolveInfo r, int w, int h, boolean banner, int textPx, int margin) {
        final GradientDrawable normal = shape(0x33FFFFFF, 0);
        final GradientDrawable focused = shape(0x66FFFFFF, 0xFFFFFFFF);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(10), px(10), px(10), px(10));
        t.setBackground(normal);
        t.setFocusable(true);
        t.setClickable(true);

        Drawable d = banner ? r.activityInfo.loadBanner(pm) : null;
        if (d == null) d = r.loadIcon(pm);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        t.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView tv = text(label(r), textPx);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        t.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(margin, margin, margin, margin);
        t.setLayoutParams(lp);

        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.setBackground(has ? focused : normal);
                float s = has ? 1.08f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(120).start();
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(r);
            }
        });
        return t;
    }

    private void launch(ResolveInfo r) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir " + label(r), Toast.LENGTH_SHORT).show();
        }
    }
}
