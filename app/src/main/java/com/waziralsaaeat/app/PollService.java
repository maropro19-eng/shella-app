package com.waziralsaaeat.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.*;

/** خدمة بتسحب الإشعارات من الموقع كل 20 ثانية (من غير Firebase) وتعرضها كإشعارات أندرويد */
public class PollService extends Service {
    static final String CH_ALERT = "shella_default", CH_SVC = "shella_service";
    private volatile boolean running;
    private Thread worker;

    static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_SVC, c.getString(R.string.svc_channel), NotificationManager.IMPORTANCE_MIN));
    }
    static void start(Context c) {
        SharedPreferences p = c.getSharedPreferences("app", MODE_PRIVATE);
        if (p.getString("secret", null) == null) return;
        try { c.startForegroundService(new Intent(c, PollService.class)); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        channels(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new NotificationCompat.Builder(this, CH_SVC).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name)).setContentText("متصل — الإشعارات شغالة")
            .setContentIntent(open).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build());
        if (worker == null || !worker.isAlive()) { running = true; worker = new Thread(this::loop); worker.start(); }
        return START_STICKY;
    }

    private void loop() {
        SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
        while (running) {
            try {
                String api = p.getString("api", null), secret = p.getString("secret", null); long uid = p.getLong("uid", 0);
                if (api == null || secret == null) { stopSelf(); return; }
                long since = p.getLong("since", -1);
                HttpURLConnection c = (HttpURLConnection) new URL(api + "/push/poll?u=" + uid + "&t=" + secret + "&since=" + since).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code == 401) { p.edit().remove("secret").apply(); stopSelf(); return; } // اتعمل تسجيل خروج/المفتاح اتلغى
                if (code == 200) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8")); StringBuilder sb = new StringBuilder(); String l;
                    while ((l = r.readLine()) != null) sb.append(l); r.close();
                    JSONObject j = new JSONObject(sb.toString()); JSONArray items = j.getJSONArray("items");
                    for (int k = 0; k < items.length(); k++) show(items.getJSONObject(k));
                    p.edit().putLong("since", j.getLong("last")).apply();
                }
                c.disconnect();
            } catch (Exception ignored) {}
            try { Thread.sleep(20000); } catch (InterruptedException e) { return; }
        }
    }

    private void show(JSONObject o) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = o.optString("body");
        Notification n = new NotificationCompat.Builder(this, CH_ALERT).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(o.optString("title")).setContentText(body).setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL).build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify((int) (o.optLong("id") % Integer.MAX_VALUE), n);
    }

    @Override public void onDestroy() { running = false; if (worker != null) worker.interrupt(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
