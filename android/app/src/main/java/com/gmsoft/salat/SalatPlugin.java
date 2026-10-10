package com.gmsoft.salat;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

@CapacitorPlugin(name = "SalatNative")
public class SalatPlugin extends Plugin {

    public static final String CH_NOTIFY = "salat_notify";
    public static final String CH_LIVE = "salat_live";
    private static final String PREFS = "salat_native";
    private static final int REQ_POST_NOTIF = 5173;

    @Override
    public void load() {
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel general = new NotificationChannel(CH_NOTIFY, "تذكيرات الصلاة", NotificationManager.IMPORTANCE_HIGH);
        general.setDescription("إشعارات العدّاد التنازلي وتنبيهات الصلاة");
        NotificationChannel live = new NotificationChannel(CH_LIVE, "الأذان", NotificationManager.IMPORTANCE_LOW);
        live.setDescription("تنبيه أثناء تشغيل الأذان");
        nm.createNotificationChannel(general);
        nm.createNotificationChannel(live);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        JSObject ret = new JSObject();
        boolean granted = NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        if (!granted && Build.VERSION.SDK_INT >= 33) {
            try {
                getActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
            } catch (Exception ignored) {}
        }
        ret.put("granted", granted);
        call.resolve(ret);
    }

    @PluginMethod
    public void notify(PluginCall call) {
        String tag = call.getString("tag", "salat");
        String title = call.getString("title", "");
        String body = call.getString("body", "");
        JSObject ret = new JSObject();

        if (!NotificationManagerCompat.from(getContext()).areNotificationsEnabled()) {
            ret.put("granted", false);
            call.resolve(ret);
            return;
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(getContext(), CH_NOTIFY)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        NotificationManagerCompat.from(getContext()).notify(tag, tag.hashCode(), b.build());
        ret.put("granted", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void state(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", NotificationManagerCompat.from(getContext()).areNotificationsEnabled());
        ret.put("exact", Build.VERSION.SDK_INT < 31 || ((AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE)).canScheduleExactAlarms());
        call.resolve(ret);
    }

    @PluginMethod
    public void closeNotify(PluginCall call) {
        String tag = call.getString("tag", "salat");
        NotificationManagerCompat.from(getContext()).cancel(tag, tag.hashCode());
        call.resolve();
    }

    @PluginMethod
    public void scheduleAlarms(PluginCall call) {
        JSArray alarms = call.getArray("alarms");
        cancelAllInternal();

        AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            call.reject("AlarmManager unavailable");
            return;
        }

        boolean canExact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try { canExact = am.canScheduleExactAlarms(); } catch (Exception e) { canExact = false; }
        }

        long now = System.currentTimeMillis();
        Set<String> stored = new HashSet<>();

        if (alarms != null) {
            try {
                for (int i = 0; i < alarms.length(); i++) {
                    JSONObject o = alarms.getJSONObject(i);
                    long at = o.optLong("at", 0);
                    if (at <= now + 1500) continue;

                    String id = o.optString("id", "p" + i);
                    String title = o.optString("title", "حان الآن وقت الصلاة");
                    String src = o.optString("src", "");
                    int rc = requestCode(id);

                    Intent intent = new Intent(getContext(), AdhanReceiver.class)
                            .putExtra("title", title)
                            .putExtra("src", src)
                            .putExtra("id", id);
                    PendingIntent pi = PendingIntent.getBroadcast(getContext(), rc, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

                    if (canExact) {
                        Intent open = new Intent(getContext(), MainActivity.class);
                        PendingIntent show = PendingIntent.getActivity(getContext(), rc, open,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
                    } else {
                        am.set(AlarmManager.RTC_WAKEUP, at, pi);
                    }
                    stored.add(id + "|" + rc);
                }
            } catch (Exception e) {
                call.reject("schedule failed: " + e.getMessage());
                return;
            }
        }

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putStringSet("ids", stored).apply();

        JSObject ret = new JSObject();
        ret.put("scheduled", stored.size());
        ret.put("exact", canExact);
        call.resolve(ret);
    }

    @PluginMethod
    public void cancelAlarms(PluginCall call) {
        cancelAllInternal();
        call.resolve();
    }

    private void cancelAllInternal() {
        try {
            AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> ids = prefs.getStringSet("ids", new HashSet<>());
            if (am != null && ids != null) {
                for (String s : ids) {
                    String[] p = s.split("\\|", -1);
                    if (p.length != 2) continue;
                    int rc = Integer.parseInt(p[1]);
                    Intent intent = new Intent(getContext(), AdhanReceiver.class);
                    PendingIntent pi = PendingIntent.getBroadcast(getContext(), rc, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    am.cancel(pi);
                    pi.cancel();
                }
            }
            prefs.edit().remove("ids").apply();
        } catch (Exception ignored) {}
    }

    private int requestCode(String id) {
        int h = id.hashCode() & 0x7fffffff;
        return h == 0 ? 1 : h;
    }
}
