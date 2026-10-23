package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.nota.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Lyrics {
    public interface Callback {
        void onLyrics(Lyrics lyrics);
    }

    public static class Line {
        public final long timeMs;
        public final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public final List<Line> lines;
    public final boolean synced;
    private Lyrics(List<Line> lines, boolean synced) {
        this.lines = lines;
        this.synced = synced;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final long FRESH_MS = 30L * 24 * 60 * 60 * 1000;

    private static final long LENGTH_SLACK_MS = 3000;

    private static final int MAX_LINES = 2000;

    private static final Pattern STAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern LRC_TAG = Pattern.compile("^\\[[a-zA-Z#]+:.*\\]$");
    private static final Pattern BRACKETED = Pattern.compile("[(\\[][^()\\[\\]]*[)\\]]");
    private static final Pattern FEATURING =
            Pattern.compile("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s.*$");

    public static void load(Context c, final Track t, final Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final Lyrics found = loadBlocking(app, t);
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onLyrics(found);
                    }
                });
            }
        }, "nota-lyrics").start();
    }

    public static Lyrics loadBlocking(Context app, Track t) {
        if (t == null) return empty();
        Lyrics local = sidecar(t);
        if (!local.lines.isEmpty()) return local;
        return fetch(app, t);
    }

    private static Lyrics sidecar(Track t) {
        if (t.data == null || t.isRemote()) return empty();
        int dot = t.data.lastIndexOf('.');
        String base = dot > 0 ? t.data.substring(0, dot) : t.data;
        Lyrics lrc = read(new File(base + ".lrc"));
        if (!lrc.lines.isEmpty()) return lrc;
        return read(new File(base + ".txt"));
    }

    private static Lyrics fetch(Context app, Track t) {
        String artist = strip(t.artist);
        String title = songName(artist, strip(t.title));
        if (title.length() == 0) return empty();
        long seconds = t.durationMs / 1000;

        Lyrics exact = empty();
        if (seconds > 0 && artist.length() > 0) {
            exact = ask(app, t.durationMs, url("get", "artist_name", artist,
                    "track_name", title, "duration", String.valueOf(seconds)));
            if (exact.synced) return exact;
        }
        Lyrics searched = ask(app, t.durationMs,
                url("search", "artist_name", artist, "track_name", title));
        return searched.lines.isEmpty() ? exact : searched;
    }

    private static Lyrics ask(Context app, long durationMs, String url) {
        String body = DiskCache.load(app, "lyrics", url, FRESH_MS, url);
        if (body == null) return empty();
        try {
            return pick(body, durationMs);
        } catch (Exception e) {
            return empty();
        }
    }

    private static String songName(String artist, String title) {
        String prefix = artist + " - ";
        if (artist.length() > 0 && title.length() > prefix.length()
                && title.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return title.substring(prefix.length()).trim();
        }
        return title;
    }

    private static Lyrics pick(String body, long durationMs) throws Exception {
        body = body.trim();
        if (!body.startsWith("[")) return fromRecord(new JSONObject(body));

        JSONArray all = new JSONArray(body);
        JSONObject best = null;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < all.length(); i++) {
            JSONObject record = all.optJSONObject(i);
            if (record == null) continue;
            String timed = field(record, "syncedLyrics");
            if (timed.length() == 0) continue;
            long gap = durationMs <= 0 ? 0
                    : Math.abs((long) (record.optDouble("duration", 0) * 1000) - durationMs);
            if (gap > LENGTH_SLACK_MS) continue;
            if (best == null || gap < bestGap) {
                bestGap = gap;
                best = record;
            }
        }
        return best == null ? empty() : fromRecord(best);
    }

    private static Lyrics fromRecord(JSONObject record) {
        String timed = field(record, "syncedLyrics");
        return parse(timed.length() > 0 ? timed : field(record, "plainLyrics"));
    }

    private static String field(JSONObject o, String name) {
        String s = o.optString(name, "");
        return s == null || s.equals("null") ? "" : s;
    }

    private static String url(String path, String... pairs) {
        StringBuilder sb = new StringBuilder("https://lrclib.net/api/").append(path);
        for (int i = 0; i < pairs.length; i += 2) {
            sb.append(i == 0 ? '?' : '&').append(pairs[i]).append('=').append(encode(pairs[i + 1]));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String strip(String s) {
        if (s == null) return "";
        s = BRACKETED.matcher(s).replaceAll(" ");
        s = FEATURING.matcher(s).replaceAll("");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static Lyrics empty() {
        return new Lyrics(Collections.<Line>emptyList(), false);
    }

    private static Lyrics read(File file) {
        if (!file.isFile() || file.length() > 512 * 1024) return empty();
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String raw;
            while ((raw = reader.readLine()) != null) sb.append(raw).append('\n');
        } catch (Exception e) {
            return empty();
        } finally {
            close(reader);
        }
        return parse(sb.toString());
    }

    private static Lyrics parse(String text) {
        if (text == null || text.length() == 0) return empty();
        List<Line> out = new ArrayList<Line>();
        boolean synced = false;
        for (String raw : text.split("\n")) {
            if (out.size() >= MAX_LINES) break;
            if (LRC_TAG.matcher(raw.trim()).matches()) continue;
            Matcher m = STAMP.matcher(raw);
            List<Long> stamps = new ArrayList<Long>();
            int end = 0;
            while (m.find()) {
                stamps.add(toMs(m));
                end = m.end();
            }
            String body = raw.substring(end).trim();
            if (stamps.isEmpty()) {
                if (body.length() > 0) out.add(new Line(-1, body));
            } else {
                synced = true;
                for (Long s : stamps) {
                    if (out.size() >= MAX_LINES) break;
                    out.add(new Line(s, body));
                }
            }
        }

        if (synced) {
            Collections.sort(out, new Comparator<Line>() {
                public int compare(Line a, Line b) {
                    return Long.compare(a.timeMs, b.timeMs);
                }
            });
        }
        return new Lyrics(out, synced);
    }

    public int indexAt(long positionMs, int near) {
        if (!synced) return -1;
        int i = Math.min(near, lines.size() - 1);
        while (i >= 0 && lines.get(i).timeMs > positionMs) i--;
        while (i + 1 < lines.size() && lines.get(i + 1).timeMs <= positionMs) i++;
        return i;
    }

    private static long toMs(Matcher m) {
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        long frac = 0;
        String f = m.group(3);
        if (f != null) {
            frac = Long.parseLong(f);
            if (f.length() == 1) frac *= 100;
            else if (f.length() == 2) frac *= 10;
        }
        return min * 60000 + sec * 1000 + frac;
    }

    private static void close(BufferedReader r) {
        if (r == null) return;
        try {
            r.close();
        } catch (Exception ignored) {
        }
    }
}
