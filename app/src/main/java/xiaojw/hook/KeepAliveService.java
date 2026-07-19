package xiaojw.hook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import de.robv.android.xposed.XposedBridge;

/**
 * 前台保活 Service：用于把微信进程优先级提升到前台，避免后台网络被系统限流/Doze延迟。
 * 从 WxLoginHook 中抽离为独立 top-level 类。
 *
 * 注意：需在 AndroidManifest.xml 中声明
 * {@code <service android:name=".hook.KeepAliveService"
 *         android:exported="false"
 *         android:foregroundServiceType="dataSync" />}
 */
public class KeepAliveService extends Service {
    public static final String TAG = "xiaojw-wxcode";

    static final int FOREGROUND_NOTIF_ID = 1001;
    static final String FOREGROUND_CHANNEL_ID = "wxcode_foreground_keepalive";

    private static final int ALARM_INTERVAL_MS = 5 * 60 * 1000;
    private static final String EXTRA_ALARM_TRIGGER = "alarm_trigger";

    private static PendingIntent alarmPendingIntent;

    // ========== 共享静态状态（供 WxLoginHook 读写） ==========

    /** 前台 Service 已真正 startForeground 完成 */
    public static volatile boolean fgServiceActive = false;

    /** 省电模式：true=AlarmManager 定时唤醒，false=常驻 WakeLock */
    public static volatile boolean powerSaverMode = false;

    // ======================================================

    private PowerManager.WakeLock serviceWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean isAlarmTrigger = intent != null && intent.getBooleanExtra(EXTRA_ALARM_TRIGGER, false);
        if (isAlarmTrigger) {
            // 闹钟触发：acquire 短时 wakeLock(10s 自动释放)
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock alarmWl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wxcode:alarm_wakeup");
                alarmWl.acquire(10000);
                scheduleNextAlarm();
            } catch (Throwable ignored) {}
            return START_STICKY;
        }
        // 正常启动：startForeground + 根据模式选择保活策略
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID, "wxcode后台保活", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("wxcode HTTP服务保活，避免后台网络被限流");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, FOREGROUND_CHANNEL_ID)
                    .setContentTitle("wxcode 服务运行中")
                    .setContentText(powerSaverMode ? "省电模式：定时唤醒保活" : "性能模式：常驻保活中")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("wxcode 服务运行中")
                    .setContentText(powerSaverMode ? "省电模式：定时唤醒保活" : "性能模式：常驻保活中")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();
        }
        try {
            // Android 14+ 需要指定前台服务类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_NOTIF_ID, notification);
            }
            fgServiceActive = true;
            cancelAlarm();
            if (powerSaverMode) {
                // 省电模式：AlarmManager 每 2 分钟唤醒一次，不持有常驻 WakeLock
                scheduleNextAlarm();
                XposedBridge.log(TAG + " KeepAliveService 省电模式：AlarmManager 定时唤醒(间隔" + ALARM_INTERVAL_MS / 1000 + "s)");
            } else {
                // 性能模式：常驻 WakeLock，CPU 始终可调度，HTTP 即时响应
                if (serviceWakeLock == null || !serviceWakeLock.isHeld()) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wxcode:keepalive");
                    serviceWakeLock.acquire();
                }
                XposedBridge.log(TAG + " KeepAliveService 性能模式：WakeLock 常驻持有");
            }
        } catch (Throwable e) {
            fgServiceActive = false;
            XposedBridge.log(TAG + " KeepAliveService.startForeground 失败[" + e.getClass().getSimpleName() + "]:" + e.getMessage());
        }
        return START_STICKY;
    }

    private void scheduleNextAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, KeepAliveService.class);
        intent.putExtra(EXTRA_ALARM_TRIGGER, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        // Android 11+ 使用 getForegroundService，之前版本使用 getService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            alarmPendingIntent = PendingIntent.getForegroundService(this, 1002, intent, flags);
        } else {
            alarmPendingIntent = PendingIntent.getService(this, 1002, intent, flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS, alarmPendingIntent);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS, alarmPendingIntent);
        }
    }

    private void cancelAlarm() {
        if (alarmPendingIntent != null) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.cancel(alarmPendingIntent);
            alarmPendingIntent = null;
        }
    }

    @Override
    public void onDestroy() {
        fgServiceActive = false;
        cancelAlarm();
        if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
            try { serviceWakeLock.release(); } catch (Exception ignored) {}
        }
        serviceWakeLock = null;
        super.onDestroy();
        XposedBridge.log(TAG + " KeepAliveService 已销毁");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
