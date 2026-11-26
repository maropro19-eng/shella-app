package com.waziralsaaeat.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** تحديث تلقائي: بيسأل GitHub Releases عن آخر نسخة، ولو أحدث بيعرض زرار تحديث وينزّلها ويفتح شاشة التثبيت. */
public class Updater {
    private static final String API = "https://api.github.com/repos/maropro19-eng/shella-app/releases/latest";
    private static final long EVERY_MS = 6L * 60L * 60L * 1000L; // نفحص كل 6 ساعات كحد أقصى
    private static volatile boolean busy;

    static void check(Activity a) {
        SharedPreferences sp = a.getSharedPreferences("app", Context.MODE_PRIVATE);
        if (busy || System.currentTimeMillis() - sp.getLong("updChecked", 0) < EVERY_MS) return;
        busy = true;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) return;
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder(); String l;
                while ((l = r.readLine()) != null) sb.append(l);
                r.close();
                JSONObject j = new JSONObject(sb.toString());
                int latest = Integer.parseInt(j.getString("tag_name").replaceAll("[^0-9]", ""));
                String url = null;
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) for (int k = 0; k < assets.length(); k++) {
                    JSONObject as = assets.getJSONObject(k);
                    if (as.optString("name").endsWith(".apk")) { url = as.getString("browser_download_url"); break; }
                }
                sp.edit().putLong("updChecked", System.currentTimeMillis()).apply();
                final String fu = url;
                if (fu != null && latest > BuildConfig.VERSION_CODE) a.runOnUiThread(() -> prompt(a, fu));
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
                busy = false;
            }
        }, "shella-update").start();
    }

    private static void prompt(Activity a, String url) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new AlertDialog.Builder(a)
            .setTitle("تحديث جديد")
            .setMessage("في نسخة أحدث من التطبيق. تحب تحدّث دلوقتي؟")
            .setPositiveButton("تحديث", (d, w) -> download(a, url))
            .setNegativeButton("بعدين", null)
            .show();
    }

    private static void download(Activity a, String url) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "اسمح للتطبيق بالتثبيت ثم ارجع له", Toast.LENGTH_LONG).show();
            a.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putLong("updChecked", 0).apply(); // نعرض التحديث تاني أول ما يرجع
            try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); } catch (Exception ignored) {}
            return;
        }
        Toast.makeText(a, "جاري تنزيل التحديث...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                File dir = new File(a.getCacheDir(), "update");
                dir.mkdirs();
                File out = new File(dir, "app.apk");
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
                InputStream in = c.getInputStream();
                FileOutputStream fo = new FileOutputStream(out);
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close(); in.close();
                if (out.length() < 100000) throw new Exception("small file");
                a.runOnUiThread(() -> install(a, out));
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "فشل تنزيل التحديث، جرب بعدين", Toast.LENGTH_LONG).show());
            } finally {
                if (c != null) c.disconnect();
            }
        }, "shella-download").start();
    }

    private static void install(Activity a, File f) {
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "تعذر فتح شاشة التثبيت", Toast.LENGTH_LONG).show();
        }
    }
}
