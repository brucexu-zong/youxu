package app.youxu.planner;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 「有序」安卓外壳。
 * 界面文件随安装包内置，从 https://appassets.androidplatform.net/ 这个只在本机存在的地址提供，
 * 所以离线、国内网络下都能打开。界面的新版本由网页端下载后交给这里保存（在线更新），
 * 新版本打不开时自动退回内置版本。
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    static final String ACTION_CAPTURE = "app.youxu.planner.CAPTURE";
    static final int REQ_FILE = 7;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean jsReady = false;
    private int loadGeneration = 0;
    private String pendingShare = null;
    private int nightMode = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);
        dropStaleUpdate();
        styleSystemBars();

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "YouxuNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        pendingShare = sharedText(getIntent());
        web.loadUrl(START);
    }

    /* ---------- 系统栏颜色跟随浅色/深色 ---------- */
    private void styleSystemBars() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean night = mode == Configuration.UI_MODE_NIGHT_YES;
        nightMode = mode;
        Window w = getWindow();
        w.setStatusBarColor(night ? 0xFF0F1316 : 0xFFF3F4F1);
        w.setNavigationBarColor(night ? 0xFF171C21 : 0xFFFFFFFF);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(night ? (f & ~light) : (f | light));
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        int before = nightMode;
        styleSystemBars();
        if (before != -1 && before != nightMode && web != null) web.reload();   // 让界面跟着换成深色/浅色
    }

    /* ---------- 从别的应用分享进来、长按图标“随手记” ---------- */
    private String sharedText(Intent i) {
        if (i == null) return null;
        if (ACTION_CAPTURE.equals(i.getAction())) return "";
        if (Intent.ACTION_SEND.equals(i.getAction()) && "text/plain".equals(i.getType())) {
            CharSequence subject = i.getCharSequenceExtra(Intent.EXTRA_SUBJECT), text = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String t = ((subject != null ? subject + " " : "") + (text != null ? text : "")).trim();
            return t;
        }
        return null;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String t = sharedText(i);
        if (t != null) { pendingShare = t; deliverShare(); }
    }

    private void deliverShare() {
        if (!jsReady || pendingShare == null || web == null) return;
        String t = pendingShare;
        pendingShare = null;
        web.evaluateJavascript("window.__youxuShared&&window.__youxuShared(" + JSONObject.quote(t) + ")", null);
    }

    /* ---------- 剪贴板里的配对码：只在还没开启同步时读，只转交 YXP1./YXC1. 开头的内容 ---------- */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) offerClipboard();
    }

    private void offerClipboard() {
        if (!jsReady || web == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipDescription d = cm.getPrimaryClipDescription();
        if (d == null || !(d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) return;
        web.evaluateJavascript("!!(window.__youxuWantsClip&&window.__youxuWantsClip())", (v) -> {
            if (!"true".equals(v)) return;
            ClipData c = cm.getPrimaryClip();
            if (c == null || c.getItemCount() == 0) return;
            CharSequence t = c.getItemAt(0).coerceToText(this);
            String s = t == null ? "" : t.toString().trim();
            if (s.matches("^YX[CP]1\\.[A-Za-z0-9+/=]{20,4000}$")) {
                web.evaluateJavascript("window.__youxuClip&&window.__youxuClip(" + JSONObject.quote(s) + ")", null);
            }
        });
    }

    /* ---------- 返回键：先交给界面处理（关详情、回到今天），界面不处理才退出 ---------- */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.__youxuBack?window.__youxuBack():false", (v) -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    /* ---------- 选择备份文件 ---------- */
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || fileCallback == null) return;
        Uri[] r = (res == RESULT_OK && data != null && data.getData() != null) ? new Uri[]{data.getData()} : null;
        fileCallback.onReceiveValue(r);
        fileCallback = null;
    }

    /* ---------- 界面文件：内置版本，或在线更新下来的版本 ---------- */
    private File updateDir() { return new File(getFilesDir(), "web"); }

    private String readText(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8").trim();
        }
    }

    private String bundledVersion() {
        try { return readText(getAssets().open("web/VERSION")); } catch (Exception e) { return ""; }
    }

    /** 装了新的安装包（内置界面更新了）：之前在线更新下来的旧版本作废 */
    private void dropStaleUpdate() {
        File base = new File(updateDir(), "BASE");
        try {
            if (base.isFile() && !readText(new FileInputStream(base)).equals(bundledVersion())) deleteUpdate();
        } catch (Exception e) { deleteUpdate(); }
    }

    private void deleteUpdate() {
        File d = updateDir();
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    private boolean usingUpdate() { return new File(updateDir(), "index.html").isFile(); }

    private InputStream openWebFile(String name) {
        if ("index.html".equals(name)) {
            File f = new File(updateDir(), "index.html");
            if (f.isFile()) { try { return new FileInputStream(f); } catch (Exception ignored) { } }
        }
        try { return getAssets().open("web/" + name); } catch (Exception e) { return null; }
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".webmanifest")) return "application/manifest+json";
        return "application/octet-stream";
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            String name = (path == null || path.equals("/") || path.isEmpty()) ? "index.html" : path.substring(1);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            InputStream in = name.contains("..") ? null : openWebFile(name);
            if (in == null) return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
            return new WebResourceResponse(mimeOf(name), "UTF-8", 200, "OK", h, in);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
            jsReady = false;
            loadGeneration++;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            // 在线更新下来的版本 8 秒内没有正常启动：删掉它，换回内置版本
            if (!usingUpdate()) return;
            final int gen = loadGeneration;
            ui.postDelayed(() -> {
                if (!jsReady && gen == loadGeneration && usingUpdate()) {
                    deleteUpdate();
                    Toast.makeText(MainActivity.this, "新版本打不开，已换回内置版本", Toast.LENGTH_LONG).show();
                    web.loadUrl(START);
                }
            }, 8000);
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            recreate();
            return true;
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        @SuppressWarnings("deprecation")
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(i, "选择备份文件"), REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    /** 界面可以调用的功能（window.YouxuNative） */
    private class Bridge {
        @JavascriptInterface
        public void ready() {
            jsReady = true;
            ui.post(() -> {
                deliverShare();
                if (hasWindowFocus()) offerClipboard();
            });
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
                return p.versionName;
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String webVersion() {
            try {
                File v = new File(updateDir(), "VERSION");
                if (usingUpdate() && v.isFile()) return readText(new FileInputStream(v));
            } catch (Exception ignored) { }
            return bundledVersion();
        }

        @JavascriptInterface
        public String installWeb(String html, String sha) {
            try {
                if (html == null || sha == null || !sha.matches("^[0-9a-f]{40}$")) return "err:bad version";
                if (html.length() > 5_000_000 || !html.regionMatches(true, 0, "<!DOCTYPE html>", 0, 15)) return "err:bad content";
                File d = updateDir();
                if (!d.isDirectory() && !d.mkdirs()) return "err:no space";
                File tmp = new File(d, "index.html.tmp");
                try (OutputStream o = new FileOutputStream(tmp)) { o.write(html.getBytes(StandardCharsets.UTF_8)); }
                try (OutputStream o = new FileOutputStream(new File(d, "VERSION"))) { o.write(sha.getBytes(StandardCharsets.UTF_8)); }
                try (OutputStream o = new FileOutputStream(new File(d, "BASE"))) { o.write(bundledVersion().getBytes(StandardCharsets.UTF_8)); }
                if (!tmp.renameTo(new File(d, "index.html"))) return "err:rename";
                return "ok";
            } catch (Exception e) { return "err:" + e.getMessage(); }
        }

        @JavascriptInterface
        public String resetWeb() { deleteUpdate(); return "ok"; }

        @JavascriptInterface
        public String copyText(String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("有序", text == null ? "" : text));
            });
            return "ok";
        }

        /** 保存到“下载/有序”；日历文件保存后直接用日历打开 */
        @JavascriptInterface
        public String saveFile(String name, String mime, String text) {
            try {
                String safe = (name == null ? "youxu.txt" : name).replaceAll("[^A-Za-z0-9._\\-]", "_");
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "text/plain" : mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/有序");
                ContentResolver cr = getContentResolver();
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "err:无法创建文件";
                try (OutputStream o = cr.openOutputStream(uri)) {
                    if (o == null) return "err:无法写入文件";
                    o.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                }
                if (safe.endsWith(".ics")) {
                    ui.post(() -> {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(uri, "text/calendar");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "已保存到“下载/有序”，请用日历打开 " + safe, Toast.LENGTH_LONG).show();
                        }
                    });
                }
                return "ok:Download/有序/" + safe;
            } catch (Exception e) { return "err:" + e.getMessage(); }
        }
    }
}
