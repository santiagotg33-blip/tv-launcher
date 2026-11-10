package com.tvlauncher.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    // Texto grande de arriba a la izquierda
    private static final String TITLE = "Bienvenidos";

    // Fila principal, de izquierda a derecha (se busca por nombre visible)
    private static final String[] DOCK = {"magma", "kodi", "youtube", "aptoide tv"};

    private PackageManager pm;
    private float unit; // pixeles por "pixel de 1080p"
    private TextView dateView;
    private final Handler handler = new Handler();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateDate();
            handler.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        pm = getPackageManager();
        unit = getResources().getDisplayMetrics().heightPixels / 1080f;
        build();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    // Atras no nos saca del inicio
    @Override
    public void onBackPressed() { }

    private int px(int v) {
        return Math.round(v * unit);
    }

    private String label(ResolveInfo r) {
        return r.loadLabel(pm).toString();
    }

    private void updateDate() {
        if (dateView == null) return;
        String s = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es")).format(new Date());
        if (s.length() > 0) s = s.substring(0, 1).toUpperCase() + s.substring(1);
        dateView.setText(s);
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

    // Fondo blanco con el Everest en blancos y grises muy suaves
    private Drawable background() {
        return new MountainDrawable();
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

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(90), px(60), px(90), px(50));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setBackground(background());

        // Encabezado: Bienvenidos a la izquierda, hora y fecha a la derecha
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = text(TITLE, px(96), 0xFF1D1D1F);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("h:mm a");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(56));
        clock.setTextColor(0xFF1D1D1F);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setGravity(Gravity.END);
        right.addView(clock);

        dateView = text("", px(28), 0xFF6E6E73);
        dateView.setGravity(Gravity.END);
        right.addView(dateView);

        header.addView(right);
        page.addView(header);

        // Espacio vacio: empuja los iconos hacia abajo
        page.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View firstFocus = null;

        if (!dock.isEmpty()) {
            HorizontalScrollView dockStrip = strip(dock, px(190), px(28), 0);
            page.addView(dockStrip);
            firstFocus = firstTile(dockStrip);
        }
        if (!apps.isEmpty()) {
            HorizontalScrollView appsStrip = strip(apps, px(130), px(24), px(8));
            page.addView(appsStrip);
            if (firstFocus == null) firstFocus = firstTile(appsStrip);
        }

        setContentView(page);
        if (firstFocus != null) firstFocus.requestFocus(); // Magma
    }

    private View firstTile(HorizontalScrollView hs) {
        ViewGroup row = (ViewGroup) hs.getChildAt(0);
        return row.getChildAt(0);
    }

    private HorizontalScrollView strip(List<ResolveInfo> list, int size, int textPx, int topMargin) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, px(28), 0, px(10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (ResolveInfo r : list) {
            row.addView(tile(r, size, textPx));
        }
        hs.addView(row);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        hs.setLayoutParams(lp);
        return hs;
    }

    private TextView text(String s, int sizePx, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(px(3), stroke);
        return g;
    }

    // Icono cuadrado redondeado; el nombre aparece debajo solo al seleccionar
    private View tile(final ResolveInfo r, int size, int textPx) {
        int radius = Math.round(size * 0.22f);
        final GradientDrawable normal = shape(0x14000000, 0, radius);
        final GradientDrawable focused = shape(0x26000000, 0xFF1D1D1F, radius);

        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setClipChildren(false);
        t.setFocusable(true);
        t.setClickable(true);

        final FrameLayout box = new FrameLayout(this);
        box.setBackground(normal);
        int pad = size / 6;
        box.setPadding(pad, pad, pad, pad);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(r.loadIcon(pm));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.addView(box, new LinearLayout.LayoutParams(size, size));

        final TextView name = text(label(r), textPx, 0xFF1D1D1F);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = px(24);
        t.addView(name, nlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                size, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = size / 6;
        t.setLayoutParams(lp);

        t.setOnFocusChangeListener((v, has) -> {
            box.setBackground(has ? focused : normal);
            float s = has ? 1.15f : 1f;
            box.animate().scaleX(s).scaleY(s).setDuration(130).start();
            name.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        });
        t.setOnClickListener(v -> launch(r));
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

    // Dibuja el cielo blanco y la montana (Everest) con poligonos simples
    private static class MountainDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        private static final float[] FAR = {0, 0.70f, 0.07f, 0.60f, 0.13f, 0.64f, 0.21f, 0.52f,
                0.28f, 0.60f, 0.35f, 0.55f, 0.45f, 0.64f, 0.80f, 0.58f, 0.90f, 0.52f,
                0.96f, 0.56f, 1, 0.54f, 1, 1, 0, 1};
        private static final float[] NUPTSE = {0.30f, 1, 0.36f, 0.66f, 0.42f, 0.56f, 0.45f, 0.52f,
                0.50f, 0.58f, 0.54f, 0.54f, 0.60f, 0.62f, 0.60f, 1};
        private static final float[] MAIN = {0.34f, 1, 0.40f, 0.72f, 0.47f, 0.56f, 0.53f, 0.50f,
                0.57f, 0.38f, 0.62f, 0.30f, 0.66f, 0.20f, 0.69f, 0.26f, 0.73f, 0.24f,
                0.78f, 0.40f, 0.84f, 0.46f, 0.90f, 0.60f, 1, 0.78f, 1, 1};
        private static final float[] SHADE = {0.66f, 0.20f, 0.69f, 0.26f, 0.73f, 0.24f, 0.78f, 0.40f,
                0.84f, 0.46f, 0.90f, 0.60f, 1, 0.78f, 1, 1, 0.60f, 1, 0.62f, 0.72f,
                0.64f, 0.52f, 0.665f, 0.36f};
        private static final float[] LIT1 = {0.57f, 0.38f, 0.62f, 0.30f, 0.625f, 0.45f, 0.58f, 0.50f};
        private static final float[] LIT2 = {0.47f, 0.56f, 0.53f, 0.50f, 0.56f, 0.62f, 0.50f, 0.70f};
        private static final float[] DARK1 = {0.69f, 0.26f, 0.73f, 0.24f, 0.75f, 0.45f, 0.70f, 0.40f};
        private static final float[] DARK2 = {0.78f, 0.40f, 0.84f, 0.46f, 0.86f, 0.70f, 0.80f, 0.62f};
        private static final float[] EDGE = {0.34f, 1, 0.40f, 0.72f, 0.47f, 0.56f, 0.53f, 0.50f,
                0.57f, 0.38f, 0.62f, 0.30f, 0.66f, 0.20f};

        private Path path(float[] pts, float w, float h, boolean close) {
            Path path = new Path();
            path.moveTo(pts[0] * w, pts[1] * h);
            for (int i = 2; i < pts.length; i += 2) {
                path.lineTo(pts[i] * w, pts[i + 1] * h);
            }
            if (close) path.close();
            return path;
        }

        private void poly(Canvas c, float[] pts, int color, float w, float h) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(color);
            c.drawPath(path(pts, w, h, true), p);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();

            // Cielo: blanco que baja a un gris azulado muy claro
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFFFFFFFF, 0xFFEEF2F7,
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);

            poly(c, FAR, 0xFFE3EAF2, w, h);
            poly(c, NUPTSE, 0xFFE0E7F0, w, h);
            poly(c, MAIN, 0xFFFFFFFF, w, h);
            poly(c, SHADE, 0xFFD5DEE9, w, h);
            poly(c, LIT1, 0xFFEAF0F6, w, h);
            poly(c, LIT2, 0xFFEAF0F6, w, h);
            poly(c, DARK1, 0xFFC5D1E0, w, h);
            poly(c, DARK2, 0xFFC5D1E0, w, h);

            // Borde suave en la cara iluminada para que el blanco se note
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, w / 700f));
            p.setColor(0xFFDDE5EE);
            c.drawPath(path(EDGE, w, h, false), p);
            p.setStyle(Paint.Style.FILL);

            // Abajo se funde con el blanco para que los iconos se lean bien
            p.setShader(new LinearGradient(0, h * 0.62f, 0, h, 0x00FFFFFF, 0xF5FFFFFF,
                    Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.62f, w, h, p);
            p.setShader(null);
        }

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(ColorFilter cf) { }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
