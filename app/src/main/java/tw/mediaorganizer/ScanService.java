package tw.mediaorganizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class ScanService extends Service {
    public static final String CHANNEL_ID = "scan_service_channel";
    public static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_UPDATE = "ACTION_UPDATE";
    public static final String EXTRA_TEXT = "EXTRA_TEXT";

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                acquireWakeLock();
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text == null) text = "相簿整理員正在背景掃描中…";
                Notification notification = createNotification(text);
                startForeground(NOTIFICATION_ID, notification);
            } else if (ACTION_UPDATE.equals(action)) {
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text != null) {
                    Notification notification = createNotification(text);
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, notification);
                    }
                }
            } else if (ACTION_STOP.equals(action)) {
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaOrganizer:ScanWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "相簿掃描背景服務",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("背景掃描相簿進度通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        int iconId = getResources().getIdentifier("app_icon", "drawable", getPackageName());
        if (iconId == 0) iconId = android.R.drawable.ic_dialog_info;

        return builder
                .setContentTitle("相簿整理員背景掃描")
                .setContentText(contentText)
                .setSmallIcon(iconId)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    public static void start(Context context, String initialText) {
        Intent intent = new Intent(context, ScanService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TEXT, initialText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void update(Context context, String text) {
        Intent intent = new Intent(context, ScanService.class);
        intent.setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_TEXT, text);
        context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, ScanService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }
}
