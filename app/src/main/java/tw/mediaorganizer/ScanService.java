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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class ScanService extends Service {
    public interface ScanListener {
        void onProgress(String status);
        void onPartialResult(Scanner.Result partial);
        void onComplete(Scanner.Result result);
        void onError(String message);
    }

    public static final String CHANNEL_ID = "scan_service_channel";
    public static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_UPDATE = "ACTION_UPDATE";
    public static final String EXTRA_TEXT = "EXTRA_TEXT";

    private static final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private static final Object CACHE_LOCK = new Object();
    private static final AtomicLong currentGeneration = new AtomicLong(0);
    private static volatile long currentScanGeneration = 0;

    private static ScanListener activeListener;
    private static volatile boolean scanning = false;
    private static volatile boolean cancelled = false;
    private static volatile Scanner.Result currentSnapshot;
    private static volatile String currentStatus = "掃描中，可即時查看結果與進行處理…";
    private static volatile long scanGeneration = 0;
    private static Context currentAppContext;

    private PowerManager.WakeLock wakeLock;

    public static synchronized void setListener(ScanListener listener) {
        activeListener = listener;
        if (listener != null && scanning) {
            if (currentStatus != null) {
                listener.onProgress(currentStatus);
            }
            if (currentSnapshot != null) {
                listener.onPartialResult(currentSnapshot);
            }
        }
    }

    public static synchronized void removeListener(ScanListener listener) {
        if (activeListener == listener) {
            activeListener = null;
        }
    }

    public static boolean isScanning() {
        return scanning;
    }

    public static void cancelScan() {
        cancelled = true;
    }

    public static long startScanJob(Context context, boolean isBgScan, boolean faces, int setting, int mediaType, Set<String> filters) {
        if (scanning) return currentScanGeneration;
        scanning = true;
        cancelled = false;
        currentSnapshot = null;
        currentStatus = "掃描中，可即時查看結果與進行處理…";

        final long gen;
        synchronized (CACHE_LOCK) {
            gen = ++scanGeneration;
        }

        Context appContext = context.getApplicationContext();
        currentAppContext = appContext;

        long jobGen = clearCacheAsync(appContext);
        currentScanGeneration = jobGen;

        if (isBgScan) {
            start(appContext, currentStatus);
        }

        scanExecutor.execute(() -> {
            try {
                performClearCache(appContext);
                Scanner scanner = new Scanner(appContext, new Scanner.Progress() {
                    @Override
                    public boolean cancelled() {
                        return cancelled;
                    }

                    @Override
                    public void report(String s) {
                        currentStatus = s;
                        if (isBgScan) {
                            update(appContext, s);
                        }
                        ScanListener l = activeListener;
                        if (l != null) l.onProgress(s);
                    }

                    @Override
                    public void onPartialResult(Scanner.Result partial) {
                        currentSnapshot = partial;
                        ScanListener l = activeListener;
                        if (l != null) l.onPartialResult(partial);
                    }
                }, faces, new int[]{5, 8, 12}[setting], new double[]{.72, .65, .58}[setting], filters, mediaType);

                Scanner.Result scanned = scanner.run();
                currentSnapshot = scanned;
                saveScanCache(appContext, scanned, jobGen);

                ScanListener l = activeListener;
                if (l != null) l.onComplete(scanned);
            } catch (InterruptedException e) {
                if (currentSnapshot != null) {
                    saveScanCache(appContext, currentSnapshot, jobGen);
                }
                ScanListener l = activeListener;
                if (l != null) l.onError("掃描已停止，已呈現部分結果與記錄。");
            } catch (Throwable e) {
                if (currentSnapshot != null) {
                    saveScanCache(appContext, currentSnapshot, jobGen);
                }
                ScanListener l = activeListener;
                if (l != null) l.onError("掃描失敗：" + e.getClass().getSimpleName() + " · " + e.getMessage());
            } finally {
                scanning = false;
                if (isBgScan) {
                    stop(appContext);
                }
            }
        });
        return jobGen;
    }

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), "scan-cache.json");
    }

    public static long getGeneration() {
        return currentGeneration.get();
    }

    private static void performClearCache(Context context) {
        if (context == null) return;
        synchronized (CACHE_LOCK) {
            File directory = new File(context.getCacheDir(), "thumbs");
            File[] files = directory.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            File cache = cacheFile(context);
            if (cache.exists()) {
                cache.delete();
            }
            File tmp = new File(context.getFilesDir(), "scan-cache.tmp");
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    public static long clearCacheAsync(Context context) {
        long gen = currentGeneration.incrementAndGet();
        if (context != null) {
            Context appContext = context.getApplicationContext();
            ioExecutor.execute(() -> performClearCache(appContext));
        }
        return gen;
    }

    public static void saveScanCache(Context context, Scanner.Result res) {
        saveScanCache(context, res, currentGeneration.get());
    }

    public static void saveScanCache(Context context, Scanner.Result res, long generation) {
        if (res == null || context == null) return;
        Context appContext = context.getApplicationContext();
        ioExecutor.execute(() -> {
            synchronized (CACHE_LOCK) {
                if (generation != currentGeneration.get()) {
                    return;
                }
                try {
                    JSONObject root = new JSONObject();
                    JSONArray all = new JSONArray();
                    Map<Scanner.Item, Integer> itemMap = new HashMap<>();
                    synchronized (res) {
                        for (int i = 0; i < res.all.size(); i++) {
                            Scanner.Item item = res.all.get(i);
                            itemMap.put(item, i);
                            JSONObject o = new JSONObject();
                            o.put("uri", item.uri.toString());
                            o.put("name", item.name);
                            o.put("mime", item.mime);
                            o.put("path", item.path);
                            o.put("size", item.size);
                            o.put("duration", item.duration);
                            o.put("video", item.video);
                            o.put("removed", item.removed);
                            if (item.thumb != null) o.put("thumb", item.thumb.getAbsolutePath());
                            all.put(o);
                        }
                        root.put("all", all);
                        root.put("exact", groupsToJson(res.exact, itemMap));
                        root.put("similar", groupsToJson(res.similar, itemMap));
                        root.put("people", groupsToJson(res.people, itemMap));
                        root.put("unreadable", res.unreadable);
                        root.put("faceFailures", res.faceFailures);
                        root.put("faceError", res.faceError);
                    }
                    File tmp = new File(context.getFilesDir(), "scan-cache.tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(root.toString().getBytes("UTF-8"));
                        out.getFD().sync();
                    }
                    File target = cacheFile(context);
                    if (!tmp.renameTo(target)) {
                        if (target.exists()) target.delete();
                        tmp.renameTo(target);
                    }
                } catch (Exception e) {}
            }
        });
    }

    public static void clearCache(Context context) {
        if (context == null) return;
        synchronized (CACHE_LOCK) {
            scanGeneration++;
            currentSnapshot = null;
        }
        ioExecutor.execute(() -> {
            synchronized (CACHE_LOCK) {
                try {
                    File target = cacheFile(context);
                    if (target.exists()) target.delete();
                    File tmp = new File(context.getFilesDir(), "scan-cache.tmp");
                    if (tmp.exists()) tmp.delete();
                } catch (Exception e) {}
            }
        });
    }

    private static JSONArray groupsToJson(List<Scanner.Group> groups, Map<Scanner.Item, Integer> itemMap) throws Exception {
        JSONArray arr = new JSONArray();
        for (Scanner.Group g : groups) {
            JSONObject o = new JSONObject();
            o.put("title", g.title);
            if (g.face != null) o.put("face", g.face.getAbsolutePath());
            JSONArray items = new JSONArray();
            synchronized (g) {
                for (Scanner.Item item : g.items) {
                    Integer idx = itemMap.get(item);
                    if (idx != null) items.put(idx);
                }
            }
            o.put("items", items);
            arr.put(o);
        }
        return arr;
    }

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

    public void onTimeout(int startId) {
        cancelScan();
        if (currentSnapshot != null && currentAppContext != null) {
            saveScanCache(currentAppContext, currentSnapshot, currentScanGeneration);
        }
        releaseWakeLock();
        stopForeground(true);
        stopSelf(startId);
    }

    public void onTimeout(int startId, int fgsType) {
        onTimeout(startId);
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
