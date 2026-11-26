package com.waziralsaaeat.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {
    private WebView web;
    private SwipeRefreshLayout swipe;
    private ValueCallback<Uri[]> filePicker;
    private static final int REQ_FILE = 11, REQ_NOTIF = 12;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PollService.channels(this); PollService.start(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);

        swipe = new SwipeRefreshLayout(this);
        web = new WebView(this);
        swipe.addView(web);
        setContentView(swipe);
        swipe.setColorSchemeColors(0xFF10B981);
        swipe.setProgressBackgroundColorSchemeColor(0xFF111827);
        swipe.setOnRefreshListener(() -> { String u = web.getUrl(); if (u == null || u.startsWith("data:")) web.loadUrl(BuildConfig.SITE_URL); else web.reload(); });
        // السحب للتحديث بس لما الصفحة فوق خالص (عشان الشات والسكرول)
        swipe.setOnChildScrollUpCallback((p, c) -> web.getScrollY() > 0);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " WazirApp/1.0");
        CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("waziralsaaeat.com".equals(u.getHost()) || "www.waziralsaaeat.com".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true; // الروابط الخارجية (واتساب وغيره) برا التطبيق
            }
            @Override public void onPageFinished(WebView v, String url) { swipe.setRefreshing(false); cm.flush(); registerPush(); }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.loadData("<html dir='rtl'><body style='background:#090d16;color:#fff;font-family:sans-serif;text-align:center;padding-top:30vh'><h3>مفيش اتصال بالإنترنت</h3><p>اسحب لتحت للمحاولة تاني</p></body></html>", "text/html; charset=utf-8", "utf-8");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePicker != null) filePicker.onReceiveValue(null);
                filePicker = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { filePicker = null; return false; }
                return true;
            }
        });

        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void setCredentials(String api, long uid, String secret) {
                getSharedPreferences("app", MODE_PRIVATE).edit().putString("api", api).putLong("uid", uid).putString("secret", secret).putLong("since", -1).apply();
                PollService.start(MainActivity.this);
            }
            @JavascriptInterface public boolean hasCredentials(long uid) { SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE); return sp.getString("secret", null) != null && sp.getLong("uid", 0) == uid; }
        }, "WazirApp");

        web.loadUrl(BuildConfig.SITE_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (web.canGoBack()) web.goBack(); else finish(); }
        });
    }

    /** بعد تسجيل الدخول: بياخد مفتاح جهاز من السيرفر مرة واحدة ويبدأ خدمة الإشعارات */
    private void registerPush() {
        String js = "(function(){try{var F=window.FB;if(!F||!F.me||!F.api||!window.WazirApp||WazirApp.hasCredentials(F.me.id))return;"
            + "fetch(F.api+'/push/device',{method:'POST',credentials:'same-origin',headers:{'X-WP-Nonce':F.nonce}}).then(function(r){return r.json()}).then(function(d){if(d&&d.secret)WazirApp.setCredentials(F.api,d.uid,d.secret);});}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && filePicker != null) { filePicker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); filePicker = null; }
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onPause() { web.onPause(); CookieManager.getInstance().flush(); super.onPause(); }
}
