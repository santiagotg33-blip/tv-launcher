package com.ctv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CtvServer {
    static class Job {
        String url;
        volatile String name = "";
        volatile long done = 0, total = -1;
        volatile String state = "Iniciando";
    }

    static final String DENIED = "<meta name=viewport content='width=device-width'>"
            + "<body style='font-family:sans-serif;padding:24px;background:#111;color:#eee'>"
            + "<h2>CTV</h2><p>Abre la dirección completa que aparece en la pantalla del TV "
            + "(incluye el código).</p></body>";

    final Context ctx;
    ServerSocket ss;
    volatile boolean running = false;
    int port = 8080;
    String error = "";
    final ExecutorService pool = Executors.newCachedThreadPool();
    final List<Job> jobs = Collections.synchronizedList(new ArrayList<Job>());

    CtvServer(Context c) { ctx = c; }

    // ---------- utilidades estaticas ----------
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ctv", Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        String t = prefs(c).getString("token", null);
        if (t == null) t = newToken(c);
        return t;
    }

    static String newToken(Context c) {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        prefs(c).edit().putString("token", sb.toString()).apply();
        return sb.toString();
    }

    static File dir(Context c) {
        File d = null;
        try {
            if (Build.VERSION.SDK_INT < 23
                    || c.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == 0) {
                d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CTV");
            }
        } catch (Throwable t) { d = null; }
        if (d == null || (!d.exists() && !d.mkdirs())) {
            File x = c.getExternalFilesDir(null);
            d = x != null ? x : new File(c.getFilesDir(), "CTV");
            d.mkdirs();
        }
        return d;
    }

    static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.0f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        v /= 1024;
        return String.format(Locale.US, "%.2f GB", v);
    }

    static String safe(String n) {
        if (n == null) n = "";
        n = n.replace('\\', '/');
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        n = n.replaceAll("[\\p{Cntrl}:*?\"<>|]", "").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() == 0) n = "archivo";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    static File unique(File d, String name) {
        File f = new File(d, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 1; i < 1000; i++) {
            f = new File(d, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(d, System.currentTimeMillis() + "_" + name);
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String js(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 32) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }

    // ---------- ciclo de vida ----------
    boolean isRunning() { return running; }

    void start() throws IOException {
        IOException last = null;
        for (int p = 8080; p <= 8090; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                ss = s;
                port = p;
                running = true;
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        Thread t = new Thread(new Runnable() { public void run() { acceptLoop(); } });
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException e) { }
        pool.shutdownNow();
    }

    void acceptLoop() {
        while (running) {
            try {
                final Socket s = ss.accept();
                pool.execute(new Runnable() { public void run() { handle(s); } });
            } catch (Throwable e) {
                if (!running) break;
            }
        }
    }

    // ---------- HTTP ----------
    String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("Linea demasiado larga");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    Map<String, String> parseQuery(String q) {
        HashMap<String, String> m = new HashMap<String, String>();
        if (q == null || q.length() == 0) return m;
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                        URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ex) { }
        }
        return m;
    }

    String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    void respond(OutputStream out, int code, String type, byte[] body, String extra) throws IOException {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\n" + extra + "\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    void text(OutputStream out, int code, String s) throws IOException {
        respond(out, code, "text/plain; charset=utf-8", s.getBytes("UTF-8"), "");
    }

    void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16384);
            String line = readLine(in);
            if (line == null || line.length() == 0) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            HashMap<String, String> h = new HashMap<String, String>();
            String l;
            while ((l = readLine(in)) != null && l.length() > 0) {
                int c = l.indexOf(':');
                if (c > 0) h.put(l.substring(0, c).trim().toLowerCase(Locale.US), l.substring(c + 1).trim());
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> qs = parseQuery(query);
            String tk = token(ctx);
            String cookie = h.get("cookie");
            boolean viaQuery = tk.equals(qs.get("k"));
            boolean ok = viaQuery || (cookie != null && cookie.contains("ctv=" + tk));
            if (!ok) {
                respond(out, 403, "text/html; charset=utf-8", DENIED.getBytes("UTF-8"), "");
                return;
            }
            String ck = viaQuery ? "Set-Cookie: ctv=" + tk + "; Path=/\r\n" : "";
            route(method, path, qs, h, in, out, ck);
        } catch (Throwable t) {
            // conexion cortada o error: se ignora
        } finally {
            try { s.close(); } catch (IOException e) { }
        }
    }

    void route(String method, String path, Map<String, String> qs, Map<String, String> h,
               InputStream in, OutputStream out, String ck) throws IOException {
        if (method.equals("GET") && path.equals("/")) {
            respond(out, 200, "text/html; charset=utf-8", page(), ck);
        } else if (method.equals("GET") && path.equals("/status")) {
            respond(out, 200, "application/json", statusJson().getBytes("UTF-8"), "");
        } else if (method.equals("GET") && path.startsWith("/f/")) {
            serveFile(out, URLDecoder.decode(path.substring(3), "UTF-8"));
        } else if (method.equals("POST") && path.equals("/upload")) {
            upload(in, out, h, qs.get("name"));
        } else if (method.equals("POST") && path.equals("/delete")) {
            File f = new File(dir(ctx), safe(qs.get("name")));
            if (f.isFile() && f.delete()) text(out, 200, "ok");
            else text(out, 404, "No se pudo eliminar");
        } else if (method.equals("POST") && path.equals("/fetch")) {
            String u = qs.get("url");
            if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) {
                text(out, 400, "El enlace debe empezar con http:// o https://");
            } else {
                startJob(u);
                text(out, 200, "ok");
            }
        } else {
            text(out, 404, "No encontrado");
        }
    }

    byte[] page() throws IOException {
        InputStream is = ctx.getAssets().open("index.html");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        is.close();
        String html = bo.toString("UTF-8");
        File d = dir(ctx);
        File[] fs = d.listFiles();
        List<File> list = new ArrayList<File>();
        if (fs != null) {
            for (File f : fs) if (f.isFile() && !f.getName().endsWith(".part")) list.add(f);
        }
        Collections.sort(list, new Comparator<File>() {
            public int compare(File a, File c) { return Long.compare(c.lastModified(), a.lastModified()); }
        });
        String tk = token(ctx);
        SimpleDateFormat df = new SimpleDateFormat("d MMM HH:mm", new Locale("es"));
        StringBuilder sb = new StringBuilder();
        if (list.isEmpty()) sb.append("<p class=\"sub\">Todavía no hay archivos.</p>");
        for (File f : list) {
            String e = enc(f.getName());
            sb.append("<div class=\"f\"><div class=\"n\">").append(esc(f.getName()))
                    .append("<br><small>").append(fmt(f.length())).append(" · ")
                    .append(df.format(new Date(f.lastModified()))).append("</small></div>")
                    .append("<a class=\"btn\" href=\"/f/").append(e).append("?k=").append(tk)
                    .append("\">Descargar</a>")
                    .append("<button class=\"btn r\" onclick=\"del('").append(e).append("')\">Eliminar</button></div>");
        }
        html = html.replace("{{TOKEN}}", tk).replace("{{FILES}}", sb.toString())
                .replace("{{FREE}}", fmt(d.getUsableSpace()));
        return html.getBytes("UTF-8");
    }

    String statusJson() {
        StringBuilder sb = new StringBuilder("[");
        synchronized (jobs) {
            int i = 0;
            for (Job j : jobs) {
                if (i++ > 0) sb.append(',');
                sb.append("{\"n\":\"").append(js(j.name)).append("\",\"d\":").append(j.done)
                        .append(",\"t\":").append(j.total).append(",\"s\":\"").append(js(j.state)).append("\"}");
            }
        }
        return sb.append(']').toString();
    }

    void serveFile(OutputStream out, String name) throws IOException {
        File f = new File(dir(ctx), safe(name));
        if (!f.isFile()) { text(out, 404, "No encontrado"); return; }
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length()
                + "\r\nContent-Disposition: attachment; filename*=UTF-8''" + enc(f.getName())
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        FileInputStream fi = new FileInputStream(f);
        try {
            byte[] buf = new byte[32768];
            int n;
            while ((n = fi.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            fi.close();
        }
        out.flush();
    }

    void upload(InputStream in, OutputStream out, Map<String, String> h, String name) throws IOException {
        long len = -1;
        try { len = Long.parseLong(h.get("content-length")); } catch (Exception e) { }
        if (len < 0) { text(out, 411, "Falta el tamaño del archivo"); return; }
        File d = dir(ctx);
        if (d.getUsableSpace() < len + 20L * 1024 * 1024) { text(out, 507, "No hay espacio suficiente en el TV"); return; }
        File f = unique(d, safe(name));
        File tmp = new File(d, f.getName() + ".part");
        long left = len;
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[32768];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                fo.write(buf, 0, n);
                left -= n;
            }
        } finally {
            fo.close();
        }
        if (left > 0) {
            tmp.delete();
            text(out, 400, "La transferencia se cortó");
        } else {
            tmp.renameTo(f);
            text(out, 200, "ok");
        }
    }

    // ---------- descargas desde enlaces ----------
    void startJob(String url) {
        final Job j = new Job();
        j.url = url;
        synchronized (jobs) {
            jobs.add(j);
            while (jobs.size() > 6) jobs.remove(0);
        }
        pool.execute(new Runnable() { public void run() { download(j); } });
    }

    String nameFrom(HttpURLConnection c, String url) {
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) {
            Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) {
                try { return safe(URLDecoder.decode(m.group(1), "UTF-8")); }
                catch (Exception e) { return safe(m.group(1)); }
            }
        }
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int s = p.lastIndexOf('/');
        if (s >= 0 && s < p.length() - 1) {
            try { return safe(URLDecoder.decode(p.substring(s + 1), "UTF-8")); }
            catch (Exception e) { }
        }
        return "descarga";
    }

    void download(Job j) {
        HttpURLConnection c = null;
        File tmp = null;
        try {
            String u = j.url;
            for (int i = 0; i < 6; i++) {
                URL url = new URL(u);
                c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1) CTV");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) throw new IOException("Redirección sin destino");
                    u = new URL(url, loc).toString();
                    continue;
                }
                break;
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("El servidor respondió " + code);
            j.total = c.getContentLength();
            File d = dir(ctx);
            if (j.total > 0 && d.getUsableSpace() < j.total + 20L * 1024 * 1024) throw new IOException("Sin espacio en el TV");
            File f = unique(d, nameFrom(c, u));
            j.name = f.getName();
            j.state = "Descargando";
            tmp = new File(d, f.getName() + ".part");
            InputStream is = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[32768];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    j.done += n;
                }
            } finally {
                fo.close();
                is.close();
            }
            tmp.renameTo(f);
            j.state = "Listo";
        } catch (Throwable t) {
            if (tmp != null) tmp.delete();
            j.state = "Error: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
