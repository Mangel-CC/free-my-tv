package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone as a remote: a small web page the TV serves on the local network (assets/remote.html). Off until the person
 * turns it on in Settings; a phone pairs once with the 6-digit PIN the TV shows. Keys, typed text and apps are sent as
 * `input keyevent`, `input text` and `am start` through the TV's own ADB (the same commands GenTV's phone app sends).
 * Runs in the :home process, which the accessibility service keeps alive. Only a fixed list of keys is accepted.
 */
final class PhoneRemote {
    private PhoneRemote() {}

    static final int PORT = 8765;
    private static final String PREFS = "phone_remote";
    private static final int MAX_BODY = 4096, MAX_PHONES = 8, PIN_MS = 10 * 60_000, LOCK_MS = 5 * 60_000;

    /** The buttons on the web remote, as Android key codes (GenTV's list, without Power: the phone couldn't turn the TV back on). */
    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("UP", "KEYCODE_DPAD_UP");
        KEYS.put("DOWN", "KEYCODE_DPAD_DOWN");
        KEYS.put("LEFT", "KEYCODE_DPAD_LEFT");
        KEYS.put("RIGHT", "KEYCODE_DPAD_RIGHT");
        KEYS.put("OK", "KEYCODE_DPAD_CENTER");
        KEYS.put("BACK", "KEYCODE_BACK");
        KEYS.put("HOME", "KEYCODE_HOME");
        KEYS.put("MENU", "KEYCODE_MENU");
        KEYS.put("VOLUME_UP", "KEYCODE_VOLUME_UP");
        KEYS.put("VOLUME_DOWN", "KEYCODE_VOLUME_DOWN");
        KEYS.put("MUTE", "KEYCODE_VOLUME_MUTE");
        KEYS.put("PLAY_PAUSE", "KEYCODE_MEDIA_PLAY_PAUSE");
        KEYS.put("REWIND", "KEYCODE_MEDIA_REWIND");
        KEYS.put("FAST_FORWARD", "KEYCODE_MEDIA_FAST_FORWARD");
        KEYS.put("DELETE", "KEYCODE_DEL");
        KEYS.put("ENTER", "KEYCODE_ENTER");
    }

    private static final SecureRandom RNG = new SecureRandom();
    private static final Object ADB = new Object();

    private static Context app;
    private static ServerSocket server;
    private static ExecutorService pool;
    private static byte[] page;
    private static String pin;
    private static long pinUntil, lockedUntil;
    private static int failures;
    private static Adb adb;

    // MARK: - On and off

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("on", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (on) {
            start(c);
            newPin();
        } else {
            stop();
        }
    }

    static void startIfEnabled(Context c) {
        if (enabled(c)) start(c);
    }

    static synchronized boolean running() {
        return server != null;
    }

    static synchronized boolean start(Context c) {
        app = c.getApplicationContext();
        if (server != null) return true;
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            server = s;
        } catch (IOException e) {
            AppLog.w("phone remote: port " + PORT + " is busy");
            return false;
        }
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "free-my-tv-phone");
            t.setDaemon(true);
            return t;
        });
        final ServerSocket mine = server;
        Thread accept = new Thread(() -> acceptLoop(mine), "free-my-tv-phone-accept");
        accept.setDaemon(true);
        accept.start();
        AppLog.w("phone remote on");
        return true;
    }

    static synchronized void stop() {
        if (server == null) return;
        try { server.close(); } catch (IOException ignored) {}
        server = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
        pin = null;
        synchronized (ADB) {
            if (adb != null) adb.close();
            adb = null;
        }
        AppLog.w("phone remote off");
    }

    // MARK: - Pairing

    static synchronized String newPin() {
        pin = String.format(Locale.ROOT, "%06d", RNG.nextInt(1_000_000));
        pinUntil = SystemClock.elapsedRealtime() + PIN_MS;
        return pin;
    }

    /** The PIN to show, or null once it has expired or been used. */
    static synchronized String pin() {
        return pin != null && SystemClock.elapsedRealtime() < pinUntil ? pin : null;
    }

    static int phones(Context c) {
        return prefs(c).getStringSet("tokens", Collections.<String>emptySet()).size();
    }

    static void forgetPhones(Context c) {
        prefs(c).edit().remove("tokens").apply();
    }

    /** The TV's addresses on private networks, where a phone can reach it. */
    static List<String> addresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) found.add(a.getHostAddress());
                }
            }
        } catch (IOException ignored) {}
        return found;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean known(String token) {
        if (token == null || token.length() < 16 || token.length() > 128) return false;
        byte[] want = hash(token).getBytes(StandardCharsets.US_ASCII);
        boolean ok = false;
        for (String h : prefs(app).getStringSet("tokens", Collections.<String>emptySet())) {
            ok |= MessageDigest.isEqual(want, h.getBytes(StandardCharsets.US_ASCII));
        }
        return ok;
    }

    // MARK: - HTTP

    private static void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                final Socket c = s.accept();
                ExecutorService p;
                synchronized (PhoneRemote.class) { p = pool; }
                if (p == null) { c.close(); continue; }
                p.execute(() -> handle(c));
            } catch (IOException | RuntimeException e) {
                if (s.isClosed()) return;
            }
        }
    }

    private static void handle(Socket socket) {
        try (Socket s = socket) {
            InetAddress peer = s.getInetAddress();
            if (!(peer.isSiteLocalAddress() || peer.isLoopbackAddress() || peer.isLinkLocalAddress())) return;
            s.setSoTimeout(5000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String request = readLine(in);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 3) { reply(out, 400, "text/plain", "bad request"); return; }
            String method = parts[0], path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 40; i++) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            int length = 0;
            try { length = Integer.parseInt(headers.containsKey("content-length") ? headers.get("content-length") : "0"); }
            catch (NumberFormatException ignored) { length = -1; }
            if (length < 0 || length > MAX_BODY) { reply(out, 413, "text/plain", "too large"); return; }
            byte[] body = new byte[length];
            int got = 0;
            while (got < length) {
                int n = in.read(body, got, length - got);
                if (n < 0) return;
                got += n;
            }
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) servePage(out);
            else if (path.startsWith("/api/")) api(out, method, path, headers, body);
            else reply(out, 404, "text/plain", "not found");
        } catch (IOException | RuntimeException e) {
            // A phone that dropped the connection: nothing to do.
        }
    }

    private static void servePage(OutputStream out) throws IOException {
        byte[] html;
        synchronized (PhoneRemote.class) {
            if (page == null) {
                try (InputStream in = app.getAssets().open("remote.html")) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    page = b.toByteArray();
                } catch (IOException e) {
                    reply(out, 500, "text/plain", "remote page missing");
                    return;
                }
            }
            html = page;
        }
        send(out, 200, "text/html; charset=utf-8", html);
    }

    private static void api(OutputStream out, String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        // A custom header keeps another website's page from calling the API from the phone's browser.
        if (!"1".equals(headers.get("x-remote"))) { json(out, 400, new JSONObject()); return; }
        JSONObject in;
        try {
            in = body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            json(out, 400, new JSONObject());
            return;
        }
        if (method.equals("POST") && path.equals("/api/pair")) { pair(out, in); return; }
        if (!known(headers.get("x-token"))) { json(out, 401, new JSONObject()); return; }
        try {
            if (method.equals("GET") && path.equals("/api/apps")) {
                json(out, 200, new JSONObject().put("apps", apps()));
            } else if (method.equals("POST") && path.equals("/api/key")) {
                String code = KEYS.get(in.optString("key"));
                if (code == null) { json(out, 400, new JSONObject()); return; }
                run("input keyevent " + code);
                json(out, 200, new JSONObject().put("ok", true));
            } else if (method.equals("POST") && path.equals("/api/text")) {
                String command = textCommand(in.optString("text"));
                if (command == null) { json(out, 400, new JSONObject().put("error", "nothing_typable")); return; }
                run(command);
                json(out, 200, new JSONObject().put("ok", true));
            } else if (method.equals("POST") && path.equals("/api/launch")) {
                String component = componentFor(in.optString("pkg"));
                if (component == null) { json(out, 404, new JSONObject()); return; }
                run("am start -n " + component);
                json(out, 200, new JSONObject().put("ok", true));
            } else if (method.equals("POST") && path.equals("/api/unpair")) {
                String h = hash(headers.get("x-token"));
                Set<String> tokens = new HashSet<>(prefs(app).getStringSet("tokens", Collections.<String>emptySet()));
                tokens.remove(h);
                prefs(app).edit().putStringSet("tokens", tokens).apply();
                json(out, 200, new JSONObject().put("ok", true));
            } else if (method.equals("GET") && path.equals("/api/ping")) {
                json(out, 200, new JSONObject().put("ok", true));
            } else {
                json(out, 404, new JSONObject());
            }
        } catch (JSONException e) {
            json(out, 500, new JSONObject());
        } catch (IOException e) {
            // The TV's ADB is off or its permission was revoked.
            try { json(out, 503, new JSONObject().put("error", "adb")); } catch (JSONException ignored) {}
        }
    }

    private static void pair(OutputStream out, JSONObject in) throws IOException {
        String token = null;
        int code;
        synchronized (PhoneRemote.class) {
            long now = SystemClock.elapsedRealtime();
            String given = in.optString("pin");
            if (now < lockedUntil) {
                code = 429;
            } else if (pin != null && now < pinUntil && given.length() == pin.length()
                    && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8))) {
                failures = 0;
                pin = null;
                byte[] raw = new byte[16];
                RNG.nextBytes(raw);
                StringBuilder sb = new StringBuilder();
                for (byte b : raw) sb.append(String.format(Locale.ROOT, "%02x", b));
                token = sb.toString();
                Set<String> tokens = new HashSet<>(prefs(app).getStringSet("tokens", Collections.<String>emptySet()));
                if (tokens.size() >= MAX_PHONES) tokens.clear();
                tokens.add(hash(token));
                prefs(app).edit().putStringSet("tokens", tokens).apply();
                code = 200;
            } else {
                if (++failures >= 5) {
                    lockedUntil = now + LOCK_MS;
                    failures = 0;
                }
                code = 403;
            }
        }
        try {
            json(out, code, token == null ? new JSONObject() : new JSONObject().put("token", token));
        } catch (JSONException e) {
            json(out, 500, new JSONObject());
        }
    }

    // MARK: - Commands (through the TV's own ADB)

    /** One kept-open connection; if it dropped, connect again once. */
    private static String run(String command) throws IOException {
        synchronized (ADB) {
            IOException last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (adb == null) adb = Adb.connect(app, 4000, null);
                    return adb.shell(command);
                } catch (IOException e) {
                    last = e;
                    if (adb != null) adb.close();
                    adb = null;
                }
            }
            throw last;
        }
    }

    /**
     * The `input text` command, as GenTV builds it: it runs inside `sh -c` on the TV, so the argument is single-quoted
     * and every quote escaped (nothing typed can start another command). Spaces become %s and a percent sign \%.
     * Only printable ASCII is kept, since `input text` can't type anything else.
     */
    static String textCommand(String text) {
        StringBuilder arg = new StringBuilder();
        int kept = 0;
        for (int i = 0; i < text.length() && kept < 200; i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) continue;
            kept++;
            if (c == ' ') arg.append("%s");
            else if (c == '%') arg.append("\\%");
            else if (c == '\'') arg.append("'\\''");
            else arg.append(c);
        }
        return kept == 0 ? null : "input text '" + arg + "'";
    }

    private static List<String[]> launchable() {
        PackageManager pm = app.getPackageManager();
        List<String[]> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(app.getPackageName());
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (seen.add(pkg)) found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
            }
        }
        Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        return found;
    }

    private static JSONArray apps() throws JSONException {
        JSONArray list = new JSONArray();
        for (String[] a : launchable()) list.put(new JSONObject().put("pkg", a[0]).put("label", a[1]));
        return list;
    }

    /** The "package/activity" to start for an installed app, or null (only apps from the launcher list are allowed). */
    private static String componentFor(String pkg) {
        boolean listed = false;
        for (String[] a : launchable()) listed |= a[0].equals(pkg);
        if (!listed) return null;
        Intent intent = HomeApps.launchIntent(app.getPackageManager(), pkg);
        if (intent == null || intent.getComponent() == null) return null;
        String component = intent.getComponent().flattenToShortString();
        return component.matches("[A-Za-z0-9_.$/]+") ? component : null;
    }

    // MARK: - Wire format

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return b.toString("ISO-8859-1");
            if (b.size() >= 4096) throw new IOException("line too long");
            if (c != '\r') b.write(c);
        }
        return b.size() == 0 ? null : b.toString("ISO-8859-1");
    }

    private static void json(OutputStream out, int code, JSONObject body) throws IOException {
        reply(out, code, "application/json", body.toString());
    }

    private static void reply(OutputStream out, int code, String type, String body) throws IOException {
        send(out, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : code == 413 ? "Payload Too Large" : code == 429 ? "Too Many Requests"
                : code == 503 ? "Service Unavailable" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
