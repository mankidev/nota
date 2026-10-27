package com.nota.data;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

public class CrashLog {
    private static final String FATAL = "FATAL ";

    private static final Pattern ADDRESS = Pattern.compile("(https?://[^/\\s]+)[^\\s]*");

    private static boolean installed;

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    write(app, thread, error);
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), "crash.txt");
    }

    public static String exportPending(Context context) {
        File f = file(context);
        if (!f.exists() || f.length() == 0) return null;
        String body = read(f);
        if (body == null) return null;
        if (Diag.LOGS && Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME,
                    "nota-crash-" + System.currentTimeMillis() + ".txt");
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            try {
                Uri uri = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream out = context.getContentResolver().openOutputStream(uri);
                    out.write(body.getBytes("UTF-8"));
                    out.close();
                }
            } catch (Exception ignored) {
            }
        }
        f.delete();
        return body;
    }

    public static void note(Context context, String text) {
        if (!Diag.LOGS) return;
        try {
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " " + withoutAddresses(text) + "\n";
            FileOutputStream out =
                    new FileOutputStream(file(context.getApplicationContext()), true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean fatal(String report) {
        return report != null && report.contains(FATAL);
    }

    private static String withoutAddresses(String text) {
        if (text == null) return "";
        return ADDRESS.matcher(text).replaceAll("$1/...");
    }

    private static String read(File f) {
        RandomAccessFile in = null;
        try {
            in = new RandomAccessFile(f, "r");
            byte[] raw = new byte[(int) in.length()];
            in.readFully(raw);
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void write(Context app, Thread thread, Throwable error) throws Exception {
        StringWriter sw = new StringWriter();
        sw.write(FATAL);
        sw.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        sw.write("\nthread: " + thread.getName() + "\n");
        error.printStackTrace(new PrintWriter(sw));
        sw.write("\n----\n");

        File f = file(app);
        FileOutputStream out = new FileOutputStream(f, true);
        try {
            out.write(withoutAddresses(sw.toString()).getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
