package com.nota.data;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class StreamProxy {
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    private static final int HEAD_BYTES = 192 * 1024;
    private static final int CHUNK_BYTES = 1024 * 1024;
    private static final int KEEP = 6;
    private static final String TYPE = "audio/mp4";
    private static final String AHEAD_HEADER = "X-Ahead";
    private static final long QUIET_MS = 1000;
    private static final long HOLD_OFF_MS = 45000;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int MAX_REQUEST_BYTES = 4096;
    private static final int MAX_REQUEST_LINES = 48;
    private static final int MAX_CONNECTIONS = 8;

    private static StreamProxy instance;

    public static synchronized StreamProxy get(Context c) {
        if (instance == null) instance = new StreamProxy(c.getApplicationContext());
        return instance;
    }

    private static final class Fetching {
        final File part;
        final long total;
        volatile long written;
        volatile boolean done;

        Fetching(File part, long total) {
            this.part = part;
            this.total = total;
        }
    }

    private final Context app;
    private final File dir;
    private final Map<String, Fetching> fetching = new HashMap<String, Fetching>();

    private final String token;
    private final AtomicInteger live = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile long feeding;

    private StreamProxy(Context app) {
        this.app = app;
        this.dir = dir(app);
        dir.mkdirs();
        byte[] raw = new byte[12];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        this.token = sb.toString();
        try {
            ServerSocket s = new ServerSocket();
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server = s;
        } catch (IOException e) {
            CrashLog.note(app, "proxy bind failed: " + e);
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                accept();
            }
        }, "nota-proxy");
        t.setDaemon(true);
        t.start();
    }

    public String url(String videoId) {
        ServerSocket s = server;
        if (s == null || !YtApi.validId(videoId)) return null;
        return "http://127.0.0.1:" + s.getLocalPort() + "/" + token + "/" + videoId;
    }

    public boolean cached(String videoId) {
        return cached(app, videoId);
    }

    public static boolean cached(Context c, String videoId) {
        File f = whole(c.getApplicationContext(), videoId);
        return f.isFile() && f.length() > 0;
    }

    private File whole(String videoId) {
        return whole(app, videoId);
    }

    public boolean preload(String videoId) throws IOException {
        if (cached(videoId)) return true;
        String address = url(videoId);
        if (address == null) return false;
        holdOff();
        if (cached(videoId)) return true;
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(180000);
        c.setRequestProperty(AHEAD_HEADER, "1");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            InputStream in = c.getInputStream();
            try {
                byte[] chunk = new byte[65536];
                while (in.read(chunk) > 0) ;
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
        return cached(videoId);
    }

    public boolean preloadHead(String id) throws IOException {
        if (cached(id) || headFile(id) != null) return true;
        String url = YtApi.streamUrl(app, id);
        if (url == null) return false;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", YtStream.USER_AGENT);
        c.setRequestProperty("Range", "bytes=0-" + (HEAD_BYTES - 1));
        File part = fresh(id + "." + System.nanoTime() + ".headpart");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return false;
            }
            long total = totalOf(c);
            if (total <= 0 || total > MAX_BYTES) return false;
            long want = Math.min(HEAD_BYTES, total);
            long written = 0;
            InputStream from = c.getInputStream();
            OutputStream copy = new FileOutputStream(part);
            try {
                byte[] chunk = new byte[65536];
                int n;
                while (written < want && (n = from.read(chunk, 0,
                        (int) Math.min(chunk.length, want - written))) > 0) {
                    copy.write(chunk, 0, n);
                    written += n;
                }
            } finally {
                copy.close();
                from.close();
            }
            DataSaver.get(app).addDownloaded(written);
            if (written != want) return false;
            return part.renameTo(new File(dir, id + "." + total + ".head"));
        } finally {
            c.disconnect();
            part.delete();
        }
    }

    private static long totalOf(HttpURLConnection c) {
        String contentRange = c.getHeaderField("Content-Range");
        if (contentRange != null) {
            int slash = contentRange.indexOf('/');
            if (slash > 0) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return -1;
        }
        return c.getHeaderFieldInt("Content-Length", -1);
    }

    private File headFile(final String id) {
        File[] found = dir.listFiles(new FilenameFilter() {
            public boolean accept(File where, String name) {
                return name.startsWith(id + ".") && name.endsWith(".head");
            }
        });
        return found == null || found.length == 0 ? null : found[0];
    }

    private static long headTotal(File head) {
        String[] parts = head.getName().split("\\.");
        if (parts.length != 3) return -1;
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void holdOff() {
        long until = System.currentTimeMillis() + HOLD_OFF_MS;
        while (System.currentTimeMillis() < until
                && System.currentTimeMillis() - feeding < QUIET_MS) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static File whole(Context app, String videoId) {
        File kept = Downloads.get(app).file(videoId);
        if (kept.isFile() && kept.length() > 0) return kept;
        return new File(dir(app), videoId + ".m4a");
    }

    private File fresh(String name) {
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, name);
    }

    private static File dir(Context app) {
        return new File(app.getCacheDir(), "stream");
    }

    private void accept() {
        while (true) {
            ServerSocket listening = server;
            if (listening == null) return;
            final Socket client;
            try {
                client = listening.accept();
            } catch (IOException e) {
                if (listening.isClosed()) {
                    server = null;
                    return;
                }
                continue;
            }
            if (live.get() >= MAX_CONNECTIONS) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            live.incrementAndGet();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        handle(client);
                    } catch (Exception ignored) {
                    } finally {
                        live.decrementAndGet();
                        try {
                            client.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }, "nota-proxy-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private static String requestLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(96);
        int c = in.read();
        if (c == -1) return null;
        while (c != -1 && c != '\n') {
            if (c != '\r') sb.append((char) c);
            if (sb.length() >= MAX_REQUEST_BYTES) throw new IOException("request too long");
            c = in.read();
        }
        return sb.toString();
    }

    private void handle(Socket client) throws IOException {
        client.setSoTimeout(REQUEST_TIMEOUT_MS);
        InputStream in = new BufferedInputStream(client.getInputStream(), 1024);
        String request = requestLine(in);
        if (request == null) return;

        String[] parts = request.split(" ");
        if (parts.length < 2 || !("GET".equals(parts[0]) || "HEAD".equals(parts[0]))) {
            send(client, "405 Method Not Allowed");
            return;
        }
        String prefix = "/" + token + "/";
        if (!parts[1].startsWith(prefix)) {
            send(client, "403 Forbidden");
            return;
        }
        String id = parts[1].substring(prefix.length());
        if (!YtApi.validId(id)) {
            send(client, "404 Not Found");
            return;
        }

        String range = null;
        boolean ahead = false;
        for (int i = 0; i < MAX_REQUEST_LINES; i++) {
            String line = requestLine(in);
            if (line == null || line.length() == 0) break;
            if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            else if (line.regionMatches(true, 0, AHEAD_HEADER, 0, AHEAD_HEADER.length())) ahead = true;
        }

        boolean body = "GET".equals(parts[0]);
        File file = whole(id);
        if (file.isFile() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
            fromDisk(client, file, range, body);
        } else {
            fromHelper(client, id, range, body, ahead);
        }
    }

    private void fromDisk(Socket client, File file, String range, boolean body)
            throws IOException {
        long length = file.length();
        long start = 0, end = length - 1;
        if (range != null) {
            long[] asked = parseRange(range, length);
            if (asked == null) {
                send(client, "416 Requested Range Not Satisfiable");
                return;
            }
            start = asked[0];
            end = asked[1];
        }
        long count = end - start + 1;
        StringBuilder head = new StringBuilder();
        head.append(range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n");
        head.append("Content-Type: ").append(TYPE).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Content-Length: ").append(count).append("\r\n");
        if (range != null) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        OutputStream out = client.getOutputStream();
        out.write(head.toString().getBytes("UTF-8"));
        if (!body) {
            out.flush();
            return;
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(start);
            byte[] chunk = new byte[65536];
            while (count > 0) {
                int n = raf.read(chunk, 0, (int) Math.min(chunk.length, count));
                if (n <= 0) break;
                out.write(chunk, 0, n);
                count -= n;
            }
            out.flush();
        } finally {
            raf.close();
        }
    }

    private static long[] parseRange(String header, long length) {
        int eq = header.indexOf('=');
        if (eq < 0) return null;
        String spec = header.substring(eq + 1).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            long start = dash == 0 ? -1 : Long.parseLong(spec.substring(0, dash).trim());
            String tail = spec.substring(dash + 1).trim();
            long end = tail.length() == 0 ? -1 : Long.parseLong(tail);
            if (start < 0) {
                if (end <= 0) return null;
                start = Math.max(0, length - end);
                end = length - 1;
            } else if (end < 0 || end >= length) {
                end = length - 1;
            }
            if (start > end || start >= length) return null;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void fromHelper(Socket client, String id, String range, boolean body, boolean ahead)
            throws IOException {
        long began = System.currentTimeMillis();
        String url = YtApi.streamUrl(app, id);
        long resolved = System.currentTimeMillis() - began;
        if (url == null) {
            send(client, "404 Not Found");
            return;
        }
        boolean fromTop = range == null || openEndedFromZero(range);
        if (fromTop && body) {
            Fetching live;
            synchronized (fetching) {
                live = fetching.get(id);
            }
            if (live != null) {
                fromLive(client, live, ahead);
                return;
            }
            File head = headFile(id);
            if (head != null) {
                long total = headTotal(head);
                if (head.length() > 0 && total > head.length()) {
                    fromHead(client, id, head, total, ahead);
                    return;
                }
                head.delete();
            }
        }
        long start = 0;
        long askedEnd = -1;
        if (!fromTop) {
            long[] asked = askedRange(range);
            if (asked == null) {
                send(client, "416 Range Not Satisfiable");
                return;
            }
            start = asked[0];
            askedEnd = asked[1];
        }
        File part = null;
        Fetching live = null;
        boolean complete = false;
        HttpURLConnection c = chunk(url, start);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                send(client, "502 Bad Gateway");
                return;
            }
            long total = totalOf(c);
            if (total <= 0 || total > MAX_BYTES) {
                send(client, "502 Bad Gateway");
                return;
            }
            long end = askedEnd < 0 || askedEnd >= total ? total - 1 : askedEnd;
            if (start > end) {
                send(client, "416 Range Not Satisfiable");
                return;
            }
            long promised = end - start + 1;

            StringBuilder head = new StringBuilder();
            head.append(range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n");
            head.append("Content-Type: ").append(TYPE).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Content-Length: ").append(promised).append("\r\n");
            if (range != null) {
                head.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(total).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            OutputStream out = client.getOutputStream();
            out.write(head.toString().getBytes("UTF-8"));
            if (!body) {
                out.flush();
                return;
            }

            if (fromTop) part = fresh(id + "." + System.nanoTime() + ".part");
            OutputStream copy = part == null ? null : new FileOutputStream(part);
            if (part != null) {
                live = new Fetching(part, total);
                synchronized (fetching) {
                    fetching.put(id, live);
                }
            }
            long written = 0;
            boolean listening = true;
            try {
                byte[] buffer = new byte[65536];
                while (written < promised) {
                    if (c == null) {
                        c = chunk(url, start + written);
                        int next = c.getResponseCode();
                        if (next != HttpURLConnection.HTTP_OK
                                && next != HttpURLConnection.HTTP_PARTIAL) {
                            break;
                        }
                    }
                    long before = written;
                    InputStream from = c.getInputStream();
                    try {
                        int n;
                        while (written < promised && (n = from.read(buffer)) > 0) {
                            if (!ahead) feeding = System.currentTimeMillis();
                            written += n;
                            if (copy != null) copy.write(buffer, 0, n);
                            if (live != null) live.written = written;
                            if (listening) {
                                try {
                                    out.write(buffer, 0, n);
                                } catch (IOException gone) {
                                    listening = false;
                                }
                            }
                            if (!listening && copy == null) break;
                        }
                    } finally {
                        from.close();
                        c.disconnect();
                        c = null;
                    }
                    if (!listening && copy == null) break;
                    if (written == before) break;
                }
                if (listening) out.flush();
            } catch (IOException e) {
                if (listening) throw e;
            } finally {
                if (copy != null) copy.close();
            }
            DataSaver.get(app).addDownloaded(written);
            long spent = System.currentTimeMillis() - began;
            if (spent > 2000 && written * 1000 / spent < 24 * 1024) {
                CrashLog.note(app, "yt slow " + (written / 1024) + "KB in " + spent + "ms");
            }
            complete = written == promised && fromTop;
        } finally {
            if (c != null) c.disconnect();
            if (live != null) {
                live.done = true;
                synchronized (fetching) {
                    fetching.remove(id);
                }
            }
            if (part != null) {
                if (!complete || !part.renameTo(whole(id))) part.delete();
                else prune();
            }
        }
    }

    private HttpURLConnection chunk(String url, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", YtStream.USER_AGENT);
        c.setRequestProperty("Range", "bytes=" + from + "-" + (from + CHUNK_BYTES - 1));
        return c;
    }

    private static long[] askedRange(String header) {
        int eq = header.indexOf('=');
        if (eq < 0) return null;
        String spec = header.substring(eq + 1).trim();
        int dash = spec.indexOf('-');
        if (dash <= 0) return null;
        try {
            long start = Long.parseLong(spec.substring(0, dash).trim());
            String tail = spec.substring(dash + 1).trim();
            return new long[]{start, tail.length() == 0 ? -1 : Long.parseLong(tail)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void fromLive(Socket client, Fetching live, boolean ahead) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: ").append(TYPE).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Content-Length: ").append(live.total).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        OutputStream out = client.getOutputStream();
        out.write(head.toString().getBytes("UTF-8"));
        RandomAccessFile from = new RandomAccessFile(live.part, "r");
        long at = 0;
        try {
            byte[] chunk = new byte[65536];
            while (at < live.total) {
                long have = live.written;
                if (at >= have) {
                    if (live.done) break;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                from.seek(at);
                int n = from.read(chunk, 0, (int) Math.min(chunk.length, have - at));
                if (n <= 0) break;
                if (!ahead) feeding = System.currentTimeMillis();
                out.write(chunk, 0, n);
                at += n;
            }
            out.flush();
        } finally {
            from.close();
        }
    }

    private void fromHead(Socket client, String id, File head, long total, boolean ahead)
            throws IOException {
        String url = YtApi.streamUrl(app, id);
        if (url == null) {
            send(client, "404 Not Found");
            return;
        }
        long headLen = head.length();
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + TYPE + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Content-Length: " + total + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));

        File part = fresh(id + "." + System.nanoTime() + ".part");
        OutputStream copy = new FileOutputStream(part);
        Fetching live = new Fetching(part, total);
        synchronized (fetching) {
            fetching.put(id, live);
        }
        HttpURLConnection c = null;
        boolean complete = false;
        boolean listening = true;
        try {
            RandomAccessFile stored = new RandomAccessFile(head, "r");
            try {
                byte[] chunk = new byte[65536];
                long left = headLen;
                long sent = 0;
                int n;
                while (left > 0 && (n = stored.read(chunk, 0,
                        (int) Math.min(chunk.length, left))) > 0) {
                    copy.write(chunk, 0, n);
                    sent += n;
                    live.written = sent;
                    if (listening) {
                        try {
                            out.write(chunk, 0, n);
                        } catch (IOException gone) {
                            listening = false;
                        }
                    }
                    left -= n;
                }
                if (listening) out.flush();
            } finally {
                stored.close();
            }

            c = chunk(url, headLen);
            if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL || totalOf(c) != total) {
                head.delete();
                return;
            }
            long written = headLen;
            try {
                byte[] buffer = new byte[65536];
                while (written < total) {
                    if (c == null) {
                        c = chunk(url, written);
                        if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) break;
                    }
                    long before = written;
                    InputStream from = c.getInputStream();
                    try {
                        int n;
                        while (written < total && (n = from.read(buffer)) > 0) {
                            if (!ahead) feeding = System.currentTimeMillis();
                            written += n;
                            copy.write(buffer, 0, n);
                            live.written = written;
                            if (listening) {
                                try {
                                    out.write(buffer, 0, n);
                                } catch (IOException gone) {
                                    listening = false;
                                }
                            }
                        }
                    } finally {
                        from.close();
                        c.disconnect();
                        c = null;
                    }
                    if (written == before) break;
                }
                if (listening) out.flush();
            } catch (IOException e) {
                if (listening) throw e;
            }
            DataSaver.get(app).addDownloaded(written - headLen);
            complete = written == total;
        } finally {
            copy.close();
            live.done = true;
            synchronized (fetching) {
                fetching.remove(id);
            }
            if (c != null) c.disconnect();
            if (complete && part.renameTo(whole(id))) {
                head.delete();
                prune();
            } else {
                part.delete();
            }
        }
    }

    private static boolean openEndedFromZero(String header) {
        int eq = header.indexOf('=');
        return eq >= 0 && "0-".equals(header.substring(eq + 1).trim());
    }

    private void send(Socket client, String status) throws IOException {
        client.getOutputStream().write(("HTTP/1.1 " + status + "\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
    }

    private void prune() {
        keepNewest(".m4a");
        keepNewest(".head");
    }

    private void keepNewest(final String suffix) {
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File where, String name) {
                return name.endsWith(suffix);
            }
        });
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = KEEP; i < files.length; i++) files[i].delete();
    }
}
