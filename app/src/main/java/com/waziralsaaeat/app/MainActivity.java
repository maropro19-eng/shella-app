package com.waziralsaaeat.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.os.Message;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String HOST = "waziralsaaeat.com";
    private static final int REQ_FILE = 11, REQ_NOTIF = 12, REQ_MEDIA = 13;

    private WebView web;
    private SwipeRefreshLayout swipe;
    private ValueCallback<Uri[]> filePicker;
    private PermissionRequest pendingWebPerm;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    /** true لو المستخدم لمس عنصر داخلي (شات/نافذة/قائمة) وهو مش في أوله، عشان السحب ما يعملش ريفريش */
    private volatile boolean innerScrolled = false;
    private volatile boolean touchInTopStrip = false;

    /** سكربت بيراقب اللمس: لو العنصر اللي تحت الصباع (أو أي أب ليه) عليه سكرول، نمنع السحب للتحديث */
    private static final String PROBE_JS =
        "(function(){try{if(window.__wzProbe||!window.WazirApp||!WazirApp.setInnerScrolled)return;window.__wzProbe=1;"
        + "function chk(t){var n=t;while(n&&n.nodeType===1){var cs=getComputedStyle(n),oy=cs.overflowY;"
        + "if((oy==='auto'||oy==='scroll'||oy==='overlay')&&n.scrollHeight>n.clientHeight&&n.scrollTop>0)return true;n=n.parentElement;}"
        + "var d=document.scrollingElement||document.documentElement;return d.scrollTop>0;}"
        + "function f(e){try{WazirApp.setInnerScrolled(chk(e.target));}catch(x){}}"
        + "document.addEventListener('touchstart',f,{capture:true,passive:true});"
        + "}catch(e){}})();";

    @SuppressLint({"SetJavaScriptEnabled", "deprecation"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PollService.channels(this);
        PollService.start(this);
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        swipe = new SwipeRefreshLayout(this);
        web = new WebView(this);
        swipe.addView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(swipe);
        swipe.setColorSchemeColors(0xFF10B981);
        swipe.setProgressBackgroundColorSchemeColor(0xFF111827);
        swipe.setOnRefreshListener(() -> {
            if (web == null) return;
            String u = web.getUrl();
            if (u == null || u.startsWith("data:")) web.loadUrl(BuildConfig.SITE_URL); else web.reload();
        });
        // التحديث بالسحب بس لو الصفحة كلها وأي عنصر داخلي (شات/نافذة) في أولهم
        swipe.setOnChildScrollUpCallback((p, c) -> web == null || web.getScrollY() > 0 || innerScrolled || !touchInTopStrip);
        // التحديث بالسحب يشتغل بس لو اللمس بدأ من شريط صغير فوق، عشان سحب القرعة والسبورة ما يعملش ريفريش
        web.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchInTopStrip = ev.getY() < getResources().getDisplayMetrics().density * 90;
                // أي لمسة برا الشريط العلوي: ممنوع التطبيق يلتقط السحب، الصفحة (السبورة/القرعة) هي اللي تتعامل معاه
                boolean allowRefresh = touchInTopStrip && v.getScrollY() == 0;
                swipe.requestDisallowInterceptTouchEvent(!allowRefresh);
                v.getParent().requestDisallowInterceptTouchEvent(!allowRefresh);
            }
            return false;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setLoadWithOverviewMode(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowContentAccess(true);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(0xFF090D16);
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // السبورة والقرعة لازم الشاشة ما تطفيش
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);                 // حجم خط النظام ما يكبّرش الصفحة ويبوّظ التصميم
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 33) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF); // الوضع الداكن للموبايل ما يقلبش ألوان الموقع
        }
        s.setUserAgentString(s.getUserAgentString() + " WazirApp/1.0");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false; // الـ iframes تتحمل عادي ومتفتحش برا التطبيق
                return handleNav(r.getUrl());
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                innerScrolled = false;
                v.evaluateJavascript("(function(){if(window.__wzErr)return;window.__wzErr=1;"
                    + "window.addEventListener('error',function(e){try{WazirApp.reportError((e.message||'')+' @'+(e.filename||'').split('/').pop()+':'+e.lineno);}catch(x){}});"
                    + "window.addEventListener('unhandledrejection',function(e){try{WazirApp.reportError('promise: '+(e.reason&&e.reason.message||e.reason));}catch(x){}});})();", null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                swipe.setRefreshing(false);
                cm.flush();
                v.evaluateJavascript(PROBE_JS, null);
                registerPush();
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    swipe.setRefreshing(false);
                    v.loadData("<html dir='rtl'><body style='background:#090d16;color:#fff;font-family:sans-serif;text-align:center;padding-top:30vh'>"
                        + "<h3>مفيش اتصال بالإنترنت</h3><p>اسحب لتحت للمحاولة تاني</p></body></html>", "text/html; charset=utf-8", "utf-8");
                }
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // لو عملية الـ WebView وقعت، نعيد بناء الشاشة بدل ما التطبيق يقفل
                destroyWeb();
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePicker != null) filePicker.onReceiveValue(null);
                filePicker = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { filePicker = null; return false; }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> handleWebPermission(req));
            }
            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message resultMsg) {
                // window.open(): نحمّل الرابط في نفس الشاشة بدل ما يتجاهل
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                        Uri u = r.getUrl();
                        if (isOwn(u) && web != null) web.loadUrl(u.toString()); else openExternal(u);
                        w.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.d("WazirWeb", m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customCb = cb;
                v.setBackgroundColor(0xFF000000);
                ((FrameLayout) getWindow().getDecorView()).addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setFullscreen(true);
            }
            @Override public void onHideCustomView() { hideCustomView(); }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                rq.setMimeType(mime);
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) rq.addRequestHeader("cookie", ck);
                rq.addRequestHeader("User-Agent", ua);
                rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(rq);
            } catch (Exception e) {
                openExternal(Uri.parse(url));
            }
        });

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void setCredentials(String api, long uid, String secret) {
                getSharedPreferences("app", MODE_PRIVATE).edit().putString("api", api).putLong("uid", uid).putString("secret", secret).putLong("since", -1).apply();
                PollService.start(MainActivity.this);
                runOnUiThread(MainActivity.this::askBatteryExemption);
            }
            @JavascriptInterface public boolean hasCredentials(long uid) {
                SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
                return sp.getString("secret", null) != null && sp.getLong("uid", 0) == uid;
            }
            @JavascriptInterface public void setInnerScrolled(boolean v) { innerScrolled = v; }
            @JavascriptInterface public void reportError(String m) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "JS: " + m, android.widget.Toast.LENGTH_LONG).show());
            }
        }, "WazirApp");

        web.clearCache(true);
        android.widget.Toast.makeText(this, "نسخة التطبيق " + BuildConfig.VERSION_NAME, android.widget.Toast.LENGTH_LONG).show();
        String start = urlFromIntent(getIntent());
        web.loadUrl(start != null ? start : BuildConfig.SITE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (customView != null) hideCustomView();
                else if (web != null && web.canGoBack()) web.goBack();
                else finish();
            }
        });
    }

    // ---------- روابط ----------
    private static boolean isOwn(Uri u) {
        String h = u.getHost();
        return h != null && (h.equals(HOST) || h.endsWith("." + HOST));
    }

    private boolean handleNav(Uri u) {
        String sc = u.getScheme();
        if (sc == null) return false;
        if (sc.equals("http") || sc.equals("https")) {
            if (isOwn(u)) return false;
            openExternal(u);
            return true;
        }
        if (sc.equals("about") || sc.equals("data") || sc.equals("blob") || sc.equals("javascript")) return false;
        openExternal(u); // tel: mailto: whatsapp: ...
        return true;
    }

    private void openExternal(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
    }

    private String urlFromIntent(Intent i) {
        if (i == null) return null;
        String u = i.getStringExtra("url");
        if (u == null) u = i.getDataString();
        if (u == null) return null;
        Uri p = Uri.parse(u);
        return ("https".equals(p.getScheme()) && isOwn(p)) ? u : null;
    }

    // ---------- صلاحيات الكاميرا/المايك للموقع ----------
    private void handleWebPermission(PermissionRequest req) {
        if (!isOwn(req.getOrigin())) { req.deny(); return; }
        List<String> need = new ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        }
        if (need.isEmpty()) { req.grant(req.getResources()); return; }
        if (pendingWebPerm != null) pendingWebPerm.deny();
        pendingWebPerm = req;
        requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
    }

    @Override public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_MEDIA && pendingWebPerm != null) {
            boolean ok = res.length > 0;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendingWebPerm.grant(pendingWebPerm.getResources()); else pendingWebPerm.deny();
            pendingWebPerm = null;
        }
    }

    // ---------- ملء الشاشة للفيديو ----------
    private void hideCustomView() {
        if (customView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(customView);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        setFullscreen(false);
    }

    private void setFullscreen(boolean on) {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (on) {
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            c.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    // ---------- الإشعارات ----------
    /** نطلب استثناء التطبيق من توفير البطارية مرة واحدة، عشان الإشعارات توصل والتطبيق مقفول */
    private void askBatteryExemption() {
        try {
            SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName()) || sp.getBoolean("battAsked", false)) return;
            sp.edit().putBoolean("battAsked", true).apply();
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    /** بيعد تسجيل الدخول: بيطلب من الموقع مفتاح جهاز مرة واحدة ويبدأ خدمة الإشعارات */
    private void registerPush() {
        if (web == null) return;
        String js = "(function(){try{var F=window.FB;if(!F||!F.me||!F.api||!window.WazirApp||WazirApp.hasCredentials(F.me.id))return;"
            + "fetch(F.api+'/push/device',{method:'POST',credentials:'same-origin',headers:{'X-WP-Nonce':F.nonce}}).then(function(r){return r.json()}).then(function(d){if(d&&d.secret)WazirApp.setCredentials(F.api,d.uid,d.secret);});}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    // ---------- دورة الحياة ----------
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && filePicker != null) {
            filePicker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            filePicker = null;
        }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String u = urlFromIntent(i);
        if (u != null && web != null) web.loadUrl(u);
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        PollService.start(this); // نتأكد إن الخدمة شغالة كل ما التطبيق يتفتح
        Updater.check(this);     // فحص التحديثات (كل 6 ساعات كحد أقصى)
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    private void destroyWeb() {
        if (web == null) return;
        try {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
        } catch (Exception ignored) {}
        web = null;
    }

    @Override protected void onDestroy() {
        destroyWeb();
        super.onDestroy();
    }
}
