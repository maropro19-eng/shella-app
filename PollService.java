package com.waziralsaaeat.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * خدمة بتسأل السيرفر عن الإشعارات كل 20 ثانية (من غير Firebase).
 * التحسينات: WakeLock عشان الـ CPU ما ينامش، إعادة تشغيل تلقائية لو النظام قتل الخدمة
 * أو المستخدم مسح التطبيق من الأخيرة، ومنبّه (Watchdog) كل 15 دقيقة يرجّع الخدمة.
 */
public class PollService extends Service {
    static final String CH_ALERT = "shella_default", CH_SVC = "shella_service";
    private static final long POLL_MS = 20000L;
    private static final long WATCHDOG_MS = 15L * 60L * 1000L;

    private volatile boolean running;
    private boolean noAutoRestart;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true);
        nm.createNotificationChannel(alert);
        nm.createNotificationChannel(new NotificationChannel(CH_SVC, c.getString(R.string.svc_channel), NotificationManager.IMPORTANCE_MIN));
    }

    static void start(Context c) {
        SharedPreferences p = c.getSharedPreferences("app", Context.MODE_PRIVATE);
        if (p.getString("secret", null) == null) return;
        try { c.startForegroundService(new Intent(c, PollService.class)); } catch (Exception ignored) {}
    }

    /** يجدول تشغيل الخدمة تاني بعد فترة (بيستعمل BootReceiver كوسيط). */
    static void scheduleRestart(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, BootReceiver.class).setAction("com.waziralsaaeat.app.RESTART");
            PendingIntent pi = PendingIntent.getBroadcast(c, 77, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        channels(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification fg = new NotificationCompat.Builder(this, CH_SVC).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name)).setContentText("متصل — الإشعارات شغالة")
            .setContentIntent(open).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, fg);
        } catch (Exception e) {
            // النظام رفض تشغيل الخدمة من الخلفية: نجرب تاني بعد دقيقة
            noAutoRestart = true;
            scheduleRestart(this, 60000L);
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWakeLock();
        scheduleRestart(this, WATCHDOG_MS);
        if (worker == null || !worker.isAlive()) { running = true; worker = new Thread(this::loop, "shella-poll"); worker.start(); }
        return START_STICKY;
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shella:poll");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void loop() {
        SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
        long delay = POLL_MS;
        while (running) {
            HttpURLConnection c = null;
            try {
                String api = p.getString("api", null), secret = p.getString("secret", null);
                long uid = p.getLong("uid", 0);
                if (api == null || secret == null) { stopSelf(); return; }
                long since = p.getLong("since", -1);
                c = (HttpURLConnection) new URL(api + "/push/poll?u=" + uid + "&t=" + secret + "&since=" + since).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code == 401) { p.edit().remove("secret").apply(); stopSelf(); return; } // التسجيل اتلغى: لازم يفتح التطبيق ويسجل دخول
                if (code == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder(); String l;
                    while ((l = r.readLine()) != null) sb.append(l);
                    r.close();
                    JSONObject j = new JSONObject(sb.toString());
                    JSONArray items = j.optJSONArray("items");
                    if (items != null) for (int k = 0; k < items.length(); k++) show(items.getJSONObject(k));
                    p.edit().putLong("since", j.optLong("last", since)).apply();
                }
                delay = POLL_MS;
            } catch (Exception e) {
                delay = Math.min(delay * 2, 120000L); // لو النت واقع نهدّي المحاولات
            } finally {
                if (c != null) c.disconnect();
            }
            scheduleRestart(this, WATCHDOG_MS); // نجدد الـ Watchdog
            try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
        }
    }

    private void show(JSONObject o) {
        int nid = (int) (o.optLong("id") % Integer.MAX_VALUE);
        if (nid == 0) nid = (int) (System.currentTimeMillis() & 0x7fffffff);
        Intent it = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        String url = o.optString("url", "");
        if (!url.isEmpty()) it.putExtra("url", url);
        PendingIntent pi = PendingIntent.getActivity(this, nid, it, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = o.optString("body");
        Notification n = new NotificationCompat.Builder(this, CH_ALERT).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(o.optString("title")).setContentText(body).setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL).build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(nid, n);
    }

    /** المستخدم مسح التطبيق من الأخيرة: نرجّع الخدمة بعد ثانيتين. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        scheduleRestart(this, 2000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        // لو اتقتلت من غير ما نوقفها بنفسنا، نرجعها
        if (!noAutoRestart && getSharedPreferences("app", MODE_PRIVATE).getString("secret", null) != null) scheduleRestart(this, 5000L);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
