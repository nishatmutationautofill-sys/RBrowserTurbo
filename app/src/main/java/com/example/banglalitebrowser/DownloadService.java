package com.example.banglalitebrowser;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.webkit.CookieManager;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fast downloader (IDM-style):
 *  - one tiny probe request finds file size + whether the server supports byte ranges
 *  - the file is cut into chunks that up to 8 parallel connections pull from a shared queue
 *    (a slow connection never holds the others back)
 *  - data is written straight to its final place (no second copy on Android 10+)
 *  - failed chunks resume from the exact byte where they stopped
 * Runs as a foreground service so downloads continue when the app is in the background.
 */
public class DownloadService extends Service {
    static final String ACTION_START = "com.example.banglalitebrowser.DOWNLOAD_START";

    public static final int QUEUED = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELED = 4;

    private static final String CHANNEL = "downloads";
    private static final int NOTIF_ID = 1;
    private static final int MAX_CONN = 8, BUF = 64 * 1024, MAX_RETRY = 5;
    private static final long MIN_MULTI = 1L << 20;                       // below 1 MB one connection is faster

    private static final List<Task> TASKS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger IDS = new AtomicInteger(1);

    // ================= public API used by the UI =================

    public static List<Task> tasks() { return TASKS; }

    public static void remove(Task t) {
        t.cancel();
        TASKS.remove(t);
    }

    public static String describe(Context c, Task t) {
        String state;
        switch (t.status) {
            case DONE:
                state = "সম্পন্ন • " + Formatter.formatShortFileSize(c, t.done.get());
                break;
            case FAILED:
                state = "ব্যর্থ" + (t.error != null ? " • " + t.error : "");
                break;
            case CANCELED:
                state = "বাতিল";
                break;
            case QUEUED:
                state = "অপেক্ষায়…";
                break;
            default:
                long done = t.done.get(), total = t.total;
                state = (total > 0 ? (int) (done * 100 / total) + "% • " : "")
                        + Formatter.formatShortFileSize(c, done)
                        + (total > 0 ? " / " + Formatter.formatShortFileSize(c, total) : "")
                        + " • " + Formatter.formatShortFileSize(c, t.speed) + "/সে.";
        }
        return t.name + "\n" + state;
    }

    public static final class Task {
        public final int id;
        public final String url, userAgent, cookie, referer, mime, name;
        public volatile long total = -1;
        public final AtomicLong done = new AtomicLong();
        public volatile int status = QUEUED;
        public volatile String error;
        public volatile Uri savedUri;
        public volatile long speed;
        volatile boolean canceled, abort;
        long lastBytes, lastTime;                                         // touched only on the main thread
        private final Set<HttpURLConnection> conns = new HashSet<>();

        Task(int id, String url, String userAgent, String cookie, String referer, String mime, String name) {
            this.id = id; this.url = url; this.userAgent = userAgent; this.cookie = cookie;
            this.referer = referer; this.mime = mime; this.name = name;
        }

        void track(HttpURLConnection c) { synchronized (conns) { conns.add(c); } }
        void untrack(HttpURLConnection c) { synchronized (conns) { conns.remove(c); } }

        public void cancel() {
            canceled = true;
            abort = true;
            List<HttpURLConnection> copy;
            synchronized (conns) { copy = new ArrayList<>(conns); }
            for (HttpURLConnection c : copy) {
                try { c.disconnect(); } catch (Exception ignored) { }    // unblocks threads waiting on the network
            }
        }

        boolean stopped() { return canceled || abort; }
    }

    // ================= service =================

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService coordinators = Executors.newFixedThreadPool(3);
    private final ExecutorService segments = Executors.newCachedThreadPool();
    private NotificationManager nm;

    @Override public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ডাউনলোড", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = progressNotification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String url = intent.getStringExtra("url");
            if (url != null) {
                final Task t = new Task(IDS.getAndIncrement(), url, intent.getStringExtra("ua"),
                        intent.getStringExtra("cookie"), intent.getStringExtra("referer"),
                        intent.getStringExtra("mime"), safeName(intent.getStringExtra("name")));
                TASKS.add(t);
                coordinators.execute(() -> run(t));
            }
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    // Android 15 limits dataSync foreground services to ~6 h; stop cleanly instead of crashing.
    public void onTimeout(int startId, int fgsType) { stopSelf(); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        coordinators.shutdownNow();
        segments.shutdownNow();
        super.onDestroy();
    }

    /** Runs every second on the main thread: refreshes speeds + notification, stops the service when idle. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            int active = 0;
            for (Task t : TASKS) {
                if (t.status == QUEUED || t.status == RUNNING) {
                    active++;
                    if (t.status == RUNNING) {
                        long d = t.done.get(), dt = now - t.lastTime;
                        if (t.lastTime > 0 && dt > 0) {
                            long inst = (d - t.lastBytes) * 1000 / dt;
                            t.speed = t.speed == 0 ? inst : (t.speed + inst) / 2;   // light smoothing
                        }
                        t.lastBytes = d;
                        t.lastTime = now;
                    }
                }
            }
            if (active == 0) {
                stopForeground(true);
                stopSelf();
                return;
            }
            nm.notify(NOTIF_ID, progressNotification());
            handler.postDelayed(this, 1000);
        }
    };

    // ================= notifications =================

    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    }

    private PendingIntent downloadsIntent() {
        Intent i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification progressNotification() {
        int active = 0;
        long sumDone = 0, sumTotal = 0, speed = 0;
        boolean unknown = false;
        Task first = null;
        for (Task t : TASKS) {
            if (t.status == QUEUED || t.status == RUNNING) {
                active++;
                if (first == null) first = t;
                sumDone += t.done.get();
                if (t.total > 0) sumTotal += t.total; else unknown = true;
                speed += t.speed;
            }
        }
        Notification.Builder b = builder().setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(downloadsIntent());
        if (active == 0 || first == null) {
            b.setContentTitle("Rishat Browser").setContentText("ডাউনলোড প্রস্তুত হচ্ছে…").setProgress(0, 0, true);
        } else {
            boolean known = sumTotal > 0 && !unknown;
            int pct = sumTotal > 0 ? (int) (sumDone * 100 / sumTotal) : 0;
            String text = (known ? pct + "% • " : "") + Formatter.formatShortFileSize(this, sumDone)
                    + (sumTotal > 0 ? " / " + Formatter.formatShortFileSize(this, sumTotal) : "")
                    + " • " + Formatter.formatShortFileSize(this, speed) + "/সে.";
            b.setContentTitle(active == 1 ? first.name : active + "টি ডাউনলোড চলছে").setContentText(text);
            if (known) b.setProgress(100, pct, false); else b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void notifyDone(Task t, boolean ok) {
        Notification.Builder b = builder()
                .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(t.name).setContentText(ok ? "ডাউনলোড সম্পন্ন" : "ডাউনলোড ব্যর্থ")
                .setAutoCancel(true).setContentIntent(downloadsIntent());
        nm.notify(1000 + t.id, b.build());
    }

    // ================= download pipeline =================

    private void run(final Task t) {
        if (t.canceled) { t.status = CANCELED; handler.post(tick); return; }
        t.status = RUNNING;
        Target g = null;
        try {
            Probe p = probe(t);
            t.total = p.total;
            g = openTarget(t);
            if (p.ranges && p.total >= MIN_MULTI) {
                int n = (int) Math.max(2L, Math.min((long) MAX_CONN, p.total / (2L << 20)));
                try {
                    multi(t, p.url, g, p.total, n);
                } catch (NoRangeException e) {                           // server lied about ranges: start over, 1 connection
                    t.abort = false;
                    t.done.set(0);
                    single(t, p.url, g, null);
                }
                if (!t.canceled && t.done.get() != p.total) throw new IOException("ফাইল অসম্পূর্ণ");
            } else {
                single(t, p.url, g, p.conn);
            }
            if (t.canceled) throw new IOException("canceled");
            finish(t, g);
            g = null;
            t.status = DONE;
            notifyDone(t, true);
        } catch (Exception e) {
            if (g != null) discard(g);
            if (t.canceled) {
                t.status = CANCELED;
            } else {
                t.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                t.status = FAILED;
                notifyDone(t, false);
            }
        } finally {
            handler.post(tick);
        }
    }

    // ---- step 1: probe ----

    private static final class Probe {
        String url;
        long total = -1;
        boolean ranges;
        HttpURLConnection conn;                                          // only set when the server answered 200 (reused for single mode)
    }

    private static final class NoRangeException extends IOException {
        NoRangeException() { super("range not supported"); }
    }

    private Probe probe(Task t) throws IOException {
        String cur = t.url;
        HttpURLConnection c = null;
        int code = 0;
        for (int hop = 0; hop < 8; hop++) {                              // follow redirects ourselves (also http -> https)
            c = open(t, cur, "bytes=0-0", false);
            code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                t.untrack(c);
                c.disconnect();
                if (loc == null) throw new IOException("রিডাইরেক্ট ত্রুটি");
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            break;
        }
        if (c == null) throw new IOException("সংযোগ ব্যর্থ");

        Probe p = new Probe();
        p.url = cur;
        if (code == 206) {
            String cr = c.getHeaderField("Content-Range");              // "bytes 0-0/12345"
            int slash = cr == null ? -1 : cr.lastIndexOf('/');
            if (slash > 0) {
                try { p.total = Long.parseLong(cr.substring(slash + 1).trim()); p.ranges = true; }
                catch (NumberFormatException ignored) { }
            }
            drainAndClose(c);
            t.untrack(c);
        } else if (code == 200) {
            p.total = c.getContentLengthLong();                          // server ignores Range: stream this same response
            p.conn = c;
        } else if (code == 416) {
            t.untrack(c);
            c.disconnect();                                              // e.g. empty file
        } else {
            t.untrack(c);
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return p;
    }

    private static void drainAndClose(HttpURLConnection c) {
        try (InputStream in = c.getInputStream()) {
            byte[] tmp = new byte[64];
            while (in.read(tmp) > 0) { /* 1 byte body */ }
        } catch (IOException ignored) { }
    }

    private String cookieFor(Task t, String url) {
        if (url.equals(t.url)) return t.cookie;
        try { return CookieManager.getInstance().getCookie(url); } catch (Throwable e) { return null; }
    }

    private HttpURLConnection open(Task t, String url, String range, boolean follow) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(follow);
        c.setRequestProperty("Accept-Encoding", "identity");             // never let gzip change byte offsets/lengths
        if (t.userAgent != null) c.setRequestProperty("User-Agent", t.userAgent);
        String ck = cookieFor(t, url);
        if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        if (t.referer != null && !t.referer.isEmpty()) c.setRequestProperty("Referer", t.referer);
        if (range != null) c.setRequestProperty("Range", range);
        t.track(c);
        return c;
    }

    // ---- step 2: fetch ----

    private void multi(final Task t, final String url, final Target g, final long total, int n) throws Exception {
        final long cs = Math.max(512L << 10, Math.min(4L << 20, total / (n * 4L)));
        final int chunks = (int) ((total + cs - 1) / cs);
        final AtomicInteger next = new AtomicInteger();
        final AtomicReference<Exception> err = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(segments.submit(() -> {
                try {
                    int k;
                    while (!t.stopped() && (k = next.getAndIncrement()) < chunks) {
                        long start = k * cs;
                        long end = Math.min(total, start + cs) - 1;
                        fetchChunk(t, url, g, start, end);
                    }
                } catch (Exception ex) {
                    err.compareAndSet(null, ex);
                    t.abort = true;                                      // tell the other workers to stop
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        Exception e = err.get();
        if (e != null) throw e;
    }

    private void fetchChunk(Task t, String url, Target g, long start, long end) throws IOException {
        byte[] buf = new byte[BUF];
        long pos = start;
        int failures = 0;
        while (pos <= end) {
            if (t.stopped()) throw new IOException("canceled");
            HttpURLConnection c = null;
            boolean clean = false;
            try {
                c = open(t, url, "bytes=" + pos + "-" + end, true);
                int code = c.getResponseCode();
                if (code == 200) throw new NoRangeException();
                if (code != 206) throw new IOException("HTTP " + code);
                InputStream in = c.getInputStream();
                int n;
                while (pos <= end && (n = in.read(buf, 0, (int) Math.min(buf.length, end - pos + 1))) > 0) {
                    g.write(buf, n, pos);
                    pos += n;
                    t.done.addAndGet(n);
                    failures = 0;
                    if (t.stopped()) throw new IOException("canceled");
                }
                if (pos <= end) throw new IOException("সংযোগ মাঝপথে বন্ধ");
                in.close();                                              // fully read: connection goes back to the keep-alive pool
                clean = true;
            } catch (NoRangeException e) {
                throw e;
            } catch (IOException e) {
                if (t.stopped() || ++failures > MAX_RETRY) throw e;
                try { Thread.sleep(failures * 700L); } catch (InterruptedException ie) { throw new IOException("canceled"); }
            } finally {
                if (c != null) {
                    t.untrack(c);
                    if (!clean) c.disconnect();
                }
            }
        }
    }

    private void single(Task t, String url, Target g, HttpURLConnection c) throws IOException {
        try {
            if (c == null) {
                c = open(t, url, null, true);
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                if (t.total <= 0) t.total = c.getContentLengthLong();
            }
            InputStream in = c.getInputStream();
            byte[] buf = new byte[BUF];
            long pos = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (t.stopped()) throw new IOException("canceled");
                g.write(buf, n, pos);
                pos += n;
                t.done.addAndGet(n);
            }
            in.close();
            if (t.total > 0 && pos != t.total) throw new IOException("ফাইল অসম্পূর্ণ");
        } finally {
            if (c != null) {
                t.untrack(c);
                c.disconnect();
            }
        }
    }

    // ================= output file =================

    /** Positional writer: many threads can write different offsets of the same file safely. */
    private static final class Target {
        FileChannel ch;
        Closeable owner;
        File file;        // temp file (when not written directly to MediaStore)
        Uri uri;          // pending MediaStore entry (Android 10+)

        void write(byte[] b, int n, long pos) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(b, 0, n);
            long p = pos;
            while (bb.hasRemaining()) p += ch.write(bb, p);
        }

        void close() {
            try { if (ch != null) ch.close(); } catch (IOException ignored) { }
            try { if (owner != null) owner.close(); } catch (IOException ignored) { }
        }
    }

    private Target openTarget(Task t) throws IOException {
        Target g = new Target();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, t.name);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeOf(t));
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    g.uri = uri;
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw");
                    if (pfd != null) {
                        ParcelFileDescriptor.AutoCloseOutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
                        g.owner = os;
                        g.ch = os.getChannel();
                        return g;
                    }
                }
            } catch (Exception ignored) {
                // fall through to the temp-file path below
            }
            if (g.uri != null) {
                try { getContentResolver().delete(g.uri, null, null); } catch (Exception ignored) { }
                g.uri = null;
            }
        }
        File base = getExternalFilesDir(null);
        File dir = new File(base != null ? base : getFilesDir(), "dl");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        g.file = new File(dir, "d" + t.id + ".part");
        RandomAccessFile raf = new RandomAccessFile(g.file, "rw");
        raf.setLength(t.total > 0 ? t.total : 0);                        // reserves space up-front: "disk full" shows immediately
        g.owner = raf;
        g.ch = raf.getChannel();
        return g;
    }

    private void finish(Task t, Target g) throws IOException {
        g.close();
        if (Build.VERSION.SDK_INT >= 29) finishModern(t, g); else finishLegacy(t, g);
    }

    private void finishModern(Task t, Target g) throws IOException {
        ContentResolver cr = getContentResolver();
        if (g.uri == null) {                                             // fallback path: copy the temp file into Downloads
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, t.name);
            v.put(MediaStore.Downloads.MIME_TYPE, mimeOf(t));
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Downloads ফোল্ডারে সংরক্ষণ করা যায়নি");
            g.uri = uri;
            try (InputStream in = new FileInputStream(g.file); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("ফাইল লেখা যায়নি");
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            //noinspection ResultOfMethodCallIgnored
            g.file.delete();
            g.file = null;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(g.uri, done, null, null);
        t.savedUri = g.uri;
    }

    @SuppressWarnings("deprecation")
    private void finishLegacy(Task t, Target g) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = uniqueFile(dir, t.name);
        if (!g.file.renameTo(dest)) {                                    // different volume: copy instead
            try (InputStream in = new FileInputStream(g.file); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            //noinspection ResultOfMethodCallIgnored
            g.file.delete();
        }
        try {                                                            // make it appear in the system Downloads app
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.addCompletedDownload(dest.getName(), "Rishat Browser", true, mimeOf(t), dest.getAbsolutePath(), dest.length(), false);
        } catch (Exception ignored) { }
    }

    private void discard(Target g) {
        g.close();
        if (g.file != null) {
            //noinspection ResultOfMethodCallIgnored
            g.file.delete();
        }
        if (g.uri != null) {
            try { getContentResolver().delete(g.uri, null, null); } catch (Exception ignored) { }
        }
    }

    // ================= helpers =================

    private static String mimeOf(Task t) {
        return t.mime == null || t.mime.isEmpty() ? "application/octet-stream" : t.mime;
    }

    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "download";
        String n = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return n.isEmpty() ? "download" : n;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }
}
