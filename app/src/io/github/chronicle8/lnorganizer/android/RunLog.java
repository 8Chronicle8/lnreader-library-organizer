package io.github.chronicle8.lnorganizer.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The app's run record (Rule 17): one line per event, flushed as written, in the
 * app's private files. Kept to about 1 MB by moving the old file aside once.
 */
final class RunLog {
    private static final long MAX_BYTES = 1L << 20;
    private static RunLog instance;

    private final File file;
    private final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private PrintWriter out;

    private RunLog(File file) {
        this.file = file;
    }

    static synchronized RunLog get(Context context) {
        if (instance == null) {
            File dir = new File(context.getFilesDir(), "logs");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
            instance = new RunLog(new File(dir, "organizer.log"));
            instance.line("start", "app " + version(context) + ", Android " + android.os.Build.VERSION.RELEASE
                    + " (API " + android.os.Build.VERSION.SDK_INT + "), " + android.os.Build.MANUFACTURER + " "
                    + android.os.Build.MODEL);
        }
        return instance;
    }

    static String version(Context context) {
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : legacyVersionCode(p);
            return p.versionName + " (" + code + ")";
        } catch (PackageManager.NameNotFoundException e) {
            // Our own package is always installed; this cannot happen while the app runs.
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static long legacyVersionCode(PackageInfo p) {
        return p.versionCode;
    }

    synchronized void line(String event, String detail) {
        try {
            if (out == null) open();
            out.println(time.format(new Date()) + " [" + Thread.currentThread().getName() + "] " + event + ": " + detail);
            out.flush();
            if (file.length() > MAX_BYTES) rotate();
        } catch (IOException e) {
            // Logging must not take the app down; the failure goes to the system log instead.
            android.util.Log.e("RunLog", "cannot write " + file, e);
        }
    }

    void error(String event, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        line(event, sw.toString());
    }

    /** The last part of the log, for sharing. */
    synchronized String tail(int maxChars) throws IOException {
        if (out != null) out.flush();
        long len = file.length();
        long start = Math.max(0, len - maxChars);
        byte[] b = new byte[(int) (len - start)];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(b);
        }
        return new String(b, StandardCharsets.UTF_8);
    }

    private void open() throws IOException {
        out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
    }

    private void rotate() throws IOException {
        out.close();
        out = null;
        File old = new File(file.getParentFile(), "organizer.old.log");
        if (old.exists() && !old.delete()) throw new IOException("cannot delete " + old);
        if (!file.renameTo(old)) throw new IOException("cannot rename " + file);
        open();
    }
}
