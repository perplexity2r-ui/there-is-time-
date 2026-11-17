package com.thereistime.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Serves bundled files from a fixed HTTPS origin without any network access. */
public final class MainActivity extends Activity {
    private static final String HOST = "game.thereistime.local";
    private static final String HOME = "https://" + HOST + "/index.html";
    private WebView web;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(17, 24, 32));
        getWindow().setNavigationBarColor(Color.rgb(17, 24, 32));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(17, 24, 32));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new LocalClient());
        setContentView(web);
        // Android 15+ enforces edge-to-edge for target 35+: keep game controls out of system bars.
        if (Build.VERSION.SDK_INT >= 35) {
            web.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            web.requestApplyInsets();
        }
        web.loadUrl(HOME);
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("typeof window.androidHandleBack === 'function' && window.androidHandleBack()", value -> {
            if (!"true".equals(value) && !isFinishing()) {
                new AlertDialog.Builder(this).setMessage("Выйти из игры? Прогресс сохранён.")
                    .setNegativeButton("Остаться", null)
                    .setPositiveButton("Выйти", (dialog, which) -> finish()).show();
            }
        });
    }

    @Override protected void onPause() {
        if (web != null) { web.evaluateJavascript("window.gamePause && window.gamePause()", null); web.onPause(); }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.evaluateJavascript("window.gameResume && window.gameResume()", null); }
    }
    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }

    private final class LocalClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            return !"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost());
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())) return error(403, "Forbidden");
            String path = uri.getPath();
            if (path == null || path.equals("/")) path = "/index.html";
            if (path.contains("..") || path.contains("\\")) return error(403, "Forbidden");
            path = path.substring(1);
            try {
                AssetManager assets = getAssets();
                InputStream stream = assets.open(path, AssetManager.ACCESS_STREAMING);
                long length = stream.available();
                String range = request.getRequestHeaders().get("Range");
                Map<String, String> headers = new HashMap<>();
                headers.put("Accept-Ranges", "bytes");
                headers.put("Cache-Control", "no-cache");
                if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
                    String[] parts = range.substring(6).split("-", -1);
                    long start, end;
                    if (parts[0].isEmpty()) { long suffix = Long.parseLong(parts[1]); start = Math.max(0, length - suffix); end = length - 1; }
                    else { start = Long.parseLong(parts[0]); end = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : length - 1; }
                    end = Math.min(end, length - 1);
                    if (start < 0 || start >= length || end < start) { stream.close(); return error(416, "Range Not Satisfiable"); }
                    long left = start;
                    while (left > 0) { long skipped = stream.skip(left); if (skipped <= 0) { if (stream.read() == -1) throw new IOException(); skipped = 1; } left -= skipped; }
                    long count = end - start + 1;
                    headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
                    headers.put("Content-Length", Long.toString(count));
                    return new WebResourceResponse(mime(path), encoding(path), 206, "Partial Content", headers, new LimitedStream(stream, count));
                }
                headers.put("Content-Length", Long.toString(length));
                return new WebResourceResponse(mime(path), encoding(path), 200, "OK", headers, stream);
            } catch (IOException | NumberFormatException ex) { return error(404, "Not Found"); }
        }
    }
    private static String encoding(String path) { return path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".html") ? "UTF-8" : null; }
    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }
    private static WebResourceResponse error(int code, String message) {
        return new WebResourceResponse("text/plain", "UTF-8", code, message, new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
    }
    private static final class LimitedStream extends FilterInputStream {
        private long remaining;
        LimitedStream(InputStream in, long remaining) { super(in); this.remaining = remaining; }
        @Override public int read() throws IOException { if (remaining == 0) return -1; int result = super.read(); if (result != -1) remaining--; return result; }
        @Override public int read(byte[] b, int off, int len) throws IOException { if (remaining == 0) return -1; int count = super.read(b, off, (int) Math.min(len, remaining)); if (count > 0) remaining -= count; return count; }
    }
}
