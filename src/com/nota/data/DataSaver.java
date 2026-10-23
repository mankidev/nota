package com.nota.data;

import android.content.Context;

import java.io.File;

public class DataSaver {
    private static final String KEY_DOWNLOADED = "net_downloaded";
    private static final String KEY_SAVED = "net_saved";
    private static final long FLUSH_EVERY = 64 * 1024;

    private static DataSaver instance;

    public static synchronized DataSaver get(Context c) {
        if (instance == null) instance = new DataSaver(c.getApplicationContext());
        return instance;
    }

    public static DataSaver peek() {
        return instance;
    }

    private final Context app;
    private long downloaded;
    private long saved;
    private long unflushed;

    private DataSaver(Context app) {
        this.app = app;
        downloaded = Prefs.get(app).getLong(KEY_DOWNLOADED, 0);
        saved = Prefs.get(app).getLong(KEY_SAVED, 0);
    }

    public synchronized void addDownloaded(long bytes) {
        if (bytes <= 0) return;
        downloaded += bytes;
        unflushed += bytes;
        if (unflushed >= FLUSH_EVERY) flush();
    }

    public synchronized void addSaved(long bytes) {
        if (bytes <= 0) return;
        saved += bytes;
        unflushed += bytes;
        if (unflushed >= FLUSH_EVERY) flush();
    }

    public synchronized void flush() {
        unflushed = 0;
        Prefs.get(app).edit()
                .putLong(KEY_DOWNLOADED, downloaded)
                .putLong(KEY_SAVED, saved)
                .apply();
    }

    public synchronized long downloadedBytes() {
        return downloaded;
    }

    public synchronized long savedBytes() {
        return saved;
    }

    public synchronized int savedPercent() {
        long total = downloaded + saved;
        return total == 0 ? 0 : (int) (saved * 100 / total);
    }

    public synchronized void reset() {
        downloaded = 0;
        saved = 0;
        flush();
    }

    public static String format(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024f * 1024));
        return String.format("%.2f GB", bytes / (1024f * 1024 * 1024));
    }

    public void hit(File f) {
        addSaved(f.length());
    }
}
