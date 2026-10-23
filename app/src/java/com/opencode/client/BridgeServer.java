package com.opencode.client;

import android.app.Activity;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 0.18.0: AI 代操作网页・第2步——读桥。
 * 本机回环 HTTP 服务（手写 ServerSocket，零第三方），只绑 127.0.0.1:4098，
 * 随 BrowserActivity 生死（开页即起、关页即停，不常驻），AI 侧用 curl 调。
 * 端点（均为 GET）：
 *   /browser/status  -> {ok,bridge,url,title}
 *   /browser/text?max=N -> {ok,url,title,text}（document.body.innerText，截断）
 * 注意：禁止匿名内部类（D8 坑），线程/回调一律 lambda/具名成员。
 */
public class BridgeServer {

    static final int PORT = 4098;
    static final String VERSION = "0.19.0";

    /** 状态回调（具名接口）：监听中/失败/收到请求都经此报到 UI。 */
    interface Listener {
        void onState(String s);
    }

    /** UI 线程取值器（具名接口，lambda 实现）。 */
    interface UiStr {
        String get();
    }

    private Activity activity;
    private WebView web;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean bound;
    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void note(final String s) {
        final Listener l = listener;
        if (l == null) return;
        try {
            activity.runOnUiThread(() -> l.onState(s));
        } catch (Exception ignored) {
        }
    }

    public BridgeServer(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    public void start() {
        stop();
        acceptThread = new Thread(() -> runLoop());
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        bound = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        acceptThread = null;
    }

    /** 重进浏览器页：换绑新 Activity/WebView，socket 不动，不断线。 */
    public void attach(Activity a, WebView w) {
        activity = a;
        web = w;
    }

    /** 关页时只解绑 WebView，不断 socket（进程活着桥就活着）。 */
    public void detachWeb() {
        web = null;
    }

    public boolean isBound() {
        ServerSocket s = server;
        return bound && s != null && !s.isClosed();
    }

    private void runLoop() {
        try {
            server = new ServerSocket(PORT, 5, InetAddress.getByName("127.0.0.1"));
            bound = true;
            note("桥：监听中 127.0.0.1:" + PORT);
        } catch (Exception e) {
            bound = false;
            note("桥启动失败：" + e);
            return;
        }
        ServerSocket srv = server;
        while (srv != null && !srv.isClosed()) {
            try {
                Socket s = srv.accept();
                handle(s);
            } catch (Exception e) {
                break; // close() 唤醒或异常：直接退出
            }
        }
    }

    private void handle(Socket s) {
        try {
            try { s.setSoTimeout(8000); } catch (Exception ignored) { }
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String req = r.readLine();
            int contentLength = 0;
            try {
                String h;
                while ((h = r.readLine()) != null && !h.isEmpty()) {
                    int c = h.indexOf(':');
                    if (c > 0 && "content-length".equalsIgnoreCase(h.substring(0, c).trim())) {
                        try { contentLength = Integer.parseInt(h.substring(c + 1).trim()); }
                        catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) {
            }
            if (req == null) { s.close(); return; }
            String[] parts = req.split(" ");
            if (parts.length < 2) { s.close(); return; }
            String method = parts[0];
            String path = parts[1];
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            note("收到 " + method + " " + path);
            String body = "";
            if ("POST".equals(method) && contentLength > 0) body = readBody(r, contentLength);
            if ("GET".equals(method) && "/browser/status".equals(path)) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("bridge", VERSION);
                o.put("url", uiStr(() -> safeCall(() -> web.getUrl())));
                o.put("title", uiStr(() -> safeCall(() -> web.getTitle())));
                respond(s, 200, "OK", o);
            } else if ("GET".equals(method) && "/browser/text".equals(path)) {
                int max = 20000;
                try {
                    String m = queryParam(query, "max");
                    if (!m.isEmpty()) max = Integer.parseInt(m);
                } catch (Exception ignored) {
                }
                if (max < 1000) max = 1000;
                if (max > 60000) max = 60000;
                String raw = evalJs(
                        "(function(){try{return document.title+'\\n'+document.body.innerText;}"
                                + "catch(e){return 'JS-ERR:'+e;}})()");
                String text = decodeJsString(raw);
                int total = text.length();
                if (text.length() > max) text = text.substring(0, max);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("url", uiStr(() -> safeCall(() -> web.getUrl())));
                o.put("title", uiStr(() -> safeCall(() -> web.getTitle())));
                o.put("total", total);
                o.put("text", text.length() < total
                        ? text + "\n…（截断，共" + total + "字）" : text);
                respond(s, 200, "OK", o);
            } else if ("POST".equals(method) && "/browser/open".equals(path)) {
                String url = "";
                try { url = new JSONObject(body).optString("url", ""); } catch (Exception ignored) { }
                url = url.trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "";
                if (url.isEmpty()) {
                    respond(s, 400, "Bad Request",
                            new JSONObject().put("ok", false).put("error", "need http(s) url"));
                } else {
                    final String u = url;
                    final WebView w = web;
                    final Activity a = activity;
                    if (a == null || w == null) {
                        respond(s, 409, "Conflict",
                                new JSONObject().put("ok", false).put("error", "page closed, reopen browser tab"));
                    } else {
                        final CountDownLatch latch = new CountDownLatch(1);
                        try {
                            a.runOnUiThread(() -> {
                                try { w.loadUrl(u); } catch (Exception ignored) { }
                                latch.countDown();
                            });
                        } catch (Exception ignored) {
                            latch.countDown();
                        }
                        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                        respond(s, 200, "OK",
                                new JSONObject().put("ok", true).put("url", u));
                    }
                }
            } else if ("POST".equals(method) && "/browser/eval".equals(path)) {
                String js = "";
                try { js = new JSONObject(body).optString("js", ""); } catch (Exception ignored) { }
                if (js.isEmpty() || js.length() > 20000) {
                    respond(s, 400, "Bad Request",
                            new JSONObject().put("ok", false).put("error", "need js (<=20000 chars)"));
                } else if (web == null) {
                    respond(s, 409, "Conflict",
                            new JSONObject().put("ok", false).put("error", "page closed, reopen browser tab"));
                } else {
                    String result = decodeJsString(evalJs(js));
                    JSONObject o = new JSONObject();
                    o.put("ok", true);
                    o.put("result", result.length() > 20000
                            ? result.substring(0, 20000) + "\n…（截断）" : result);
                    respond(s, 200, "OK", o);
                }
            } else {
                respond(s, 404, "Not Found",
                        new JSONObject().put("ok", false).put("error", "unknown path"));
            }
            s.close();
        } catch (Exception ignored) {
            try { s.close(); } catch (Exception ignored2) { }
        }
    }

    /** POST body 按字符读（上限10万；多字节字符按字符计，读满或8秒超时即停）。
     *  建议发纯 ASCII JSON，中文先做 unicode 转义，免得 Content-Length（字节）对不上。 */
    private static String readBody(BufferedReader r, int contentLength) {
        try {
            StringBuilder sb = new StringBuilder();
            int remaining = Math.min(contentLength, 100000);
            char[] buf = new char[4096];
            while (remaining > 0) {
                int n = r.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) break;
                sb.append(buf, 0, n);
                remaining -= n;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String queryParam(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            String k = e >= 0 ? kv.substring(0, e) : kv;
            try { k = URLDecoder.decode(k, "UTF-8"); } catch (Exception ignored) { }
            if (key.equals(k)) {
                String v = e >= 0 ? kv.substring(e + 1) : "";
                try { v = URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) { }
                return v;
            }
        }
        return "";
    }

    private void respond(Socket s, int code, String reason, JSONObject body) throws Exception {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        String h = "HTTP/1.0 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        o.write(h.getBytes(StandardCharsets.UTF_8));
        o.write(b);
        o.flush();
    }

    /** WebView 取值必须在 UI 线程，同步等（5秒超时，拿不到给空串）。 */
    private String uiStr(UiStr fn) {
        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            activity.runOnUiThread(() -> {
                try { out[0] = fn.get(); } catch (Exception ignored) { }
                latch.countDown();
            });
        } catch (Exception e) {
            return "";
        }
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0] == null ? "" : out[0];
    }

    private static String safeCall(UiStr fn) {
        try {
            String v = fn.get();
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** evaluateJavascript 必须 UI 线程发起、回调异步：latch 同步等（12秒超时）。 */
    private String evalJs(String js) {
        final Activity a = activity;
        final WebView w = web;
        if (a == null || w == null) return null;
        final String[] out = new String[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            a.runOnUiThread(() -> {
                try {
                    w.evaluateJavascript(js, v -> {
                        out[0] = v;
                        latch.countDown();
                    });
                } catch (Exception e) {
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            return null;
        }
        try { latch.await(12, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0];
    }

    /** evaluateJavascript 回的是 JSON 字符串字面量（如 "a\nb"），解一次；失败原样回。 */
    private static String decodeJsString(String r) {
        if (r == null || r.isEmpty() || "null".equals(r)) return "";
        try {
            JSONObject w = new JSONObject("{\"v\":" + r + "}");
            String s = w.optString("v", "");
            return "null".equals(s) ? "" : s;
        } catch (Exception e) {
            return r;
        }
    }
}
