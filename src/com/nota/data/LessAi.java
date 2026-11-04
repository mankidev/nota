package com.nota.data;

import android.content.Context;
import android.text.TextUtils;

import com.nota.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class LessAi {
    public static final String KEY = "less_ai";

    private static final Pattern MARK = Pattern.compile(
            "\\b(ai|a\\.i\\.)[\\s-]+(cover|covers|version|song|songs|music|remix|generated|voice|vocals?|made)\\b"
                    + "|\\b(cover|version|remix|voice|sung|made)\\s+(by|with)?\\s*(an\\s+)?ai\\b"
                    + "|[(\\[]\\s*ai\\s*[)\\]]"
                    + "|#ai\\b"
                    + "|\\bsuno\\b|\\budio\\b"
                    + "|\\bai[\\s-]generated\\b|generative ai|(created|made|produced) (with|using|by) ai\\b"
                    + "|artificial intelligence"
                    + "|yapay zek[aâ]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final String LIST_URL =
            "https://raw.githubusercontent.com/CappyT/ammit/main/extension/data/blocklist.json";
    private static final String LIST_FILE = "ai_artists.txt";
    private static final long LIST_FRESH_MS = 7L * 24 * 60 * 60 * 1000;
    private static final Pattern SPLIT = Pattern.compile(
            "\\s*(,|&|\\bve\\b|\\band\\b|\\bx\\b|\\bfeat\\.?|\\bft\\.?)\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DISCLOSED = Pattern.compile(
            "\\bsuno\\b|\\budio\\b|generative ai|artificial intelligence"
                    + "|\\bai[\\s-](generated|assisted|disclaimer|tools?|music|artist|vocals?)\\b"
                    + "|(created|made|produced) (with|using|by) ai\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String VERDICT_FILE = "ai_verdicts.txt";
    private static final long VERDICT_FRESH_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int SYNTHETIC_SCORE = 5;

    private static Map<String, long[]> verdicts;
    private static volatile Set<String> listed;
    private static boolean loading;
    private static long checkedAt;

    public static boolean on(Context c) {
        boolean on = Prefs.getBool(c, KEY, false);
        if (on) prepare(c);
        return on;
    }

    public static void set(Context c, boolean value) {
        Prefs.setBool(c, KEY, value);
        if (value) prepare(c);
    }

    public static boolean looksAi(Track t) {
        return matches(t.title) || matches(t.artist) || matches(t.album) || listed(t);
    }

    private static boolean listed(Track t) {
        Set<String> set = listed;
        if (set == null) return false;
        if (t.artistChannel != null && set.contains(t.artistChannel)) return true;
        if (t.artistChannel != null && knownSynthetic(t.artistChannel)) return true;
        if (TextUtils.isEmpty(t.artist)) return false;
        for (String name : SPLIT.split(t.artist)) {
            String key = nameKey(name);
            if (key.length() > 0 && set.contains(key)) return true;
        }
        return false;
    }

    private static synchronized long[] verdict(Context c, String channel) {
        if (verdicts == null) {
            verdicts = new HashMap<String, long[]>();
            File f = new File(c.getApplicationContext().getFilesDir(), VERDICT_FILE);
            try {
                if (f.exists()) {
                    for (String line : read(f)) {
                        String[] part = line.split("\t");
                        if (part.length != 3) continue;
                        verdicts.put(part[0], new long[]{Long.parseLong(part[1]), Long.parseLong(part[2])});
                    }
                }
            } catch (Exception ignored) {
            }
        }
        long[] v = verdicts.get(channel);
        return v != null && System.currentTimeMillis() - v[1] < VERDICT_FRESH_MS ? v : null;
    }

    private static synchronized boolean knownSynthetic(String channel) {
        long[] v = verdicts == null ? null : verdicts.get(channel);
        return v != null && v[0] == 1;
    }

    public static boolean judged(Context c, String channel) {
        return verdict(c, channel) != null;
    }

    public static boolean synthetic(Context c, String channel) {
        long[] known = verdict(c, channel);
        if (known != null) return known[0] == 1;
        YtApi.Footprint f = YtApi.footprintBlocking(c, channel);
        if (f == null) return false;
        boolean ai = score(f) >= SYNTHETIC_SCORE;
        remember(c, channel, ai);
        return ai;
    }

    private static synchronized void remember(Context c, String channel, boolean ai) {
        long now = System.currentTimeMillis();
        verdicts.put(channel, new long[]{ai ? 1 : 0, now});
        File f = new File(c.getApplicationContext().getFilesDir(), VERDICT_FILE);
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            try {
                out.write(channel + "\t" + (ai ? 1 : 0) + "\t" + now + "\n");
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    static int score(YtApi.Footprint f) {
        if (f.live) return 0;
        int score = 0;
        if (DISCLOSED.matcher(f.description).find()) score += 4;
        int dated = f.years.size();
        int first = Integer.MAX_VALUE;
        int last = 0;
        for (int y : f.years) {
            first = Math.min(first, y);
            last = Math.max(last, y);
        }
        int year = Calendar.getInstance().get(Calendar.YEAR);
        if (f.subscribers < 300000 && dated >= 3 && first >= year - 1 && f.releases >= 5) score += 4;
        if (f.releases >= 20 && f.subscribers < 20000) score += 2;
        if (dated >= 3 && f.releases / ((last - first + 1) * 12f) >= 1.5f) score += 2;
        if (f.description.trim().length() == 0) score += 1;
        return score;
    }

    private static String nameKey(String name) {
        return "n:" + name.toLowerCase(Locale.ROOT).trim();
    }

    private static synchronized void prepare(Context c) {
        long now = System.currentTimeMillis();
        if (loading || (listed != null && now - checkedAt < 60 * 60 * 1000)) return;
        checkedAt = now;
        File file = new File(c.getApplicationContext().getFilesDir(), LIST_FILE);
        boolean fresh = file.exists()
                && System.currentTimeMillis() - file.lastModified() < LIST_FRESH_MS;
        if (listed != null && fresh) return;
        loading = true;
        final File target = file;
        final boolean refresh = !fresh;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (listed == null && target.exists()) listed = new HashSet<String>(read(target));
                    if (refresh) {
                        Set<String> got = download();
                        if (got != null) {
                            write(target, got);
                            listed = got;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    synchronized (LessAi.class) {
                        loading = false;
                    }
                }
            }
        }, "nota-ai-list").start();
    }

    private static Set<String> download() throws Exception {
        JSONArray artists = new JSONObject(Net.get(LIST_URL, 20000)).getJSONArray("artists");
        Set<String> out = new HashSet<String>(artists.length() * 2);
        for (int i = 0; i < artists.length(); i++) {
            JSONObject a = artists.getJSONObject(i);
            String channel = a.optString("channelId", "");
            String name = a.isNull("name") ? "" : a.optString("name", "");
            if (channel.startsWith("UC")) out.add(channel);
            else if (name.trim().length() > 0) out.add(nameKey(name));
        }
        return out.size() > 1000 ? out : null;
    }

    private static List<String> read(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = in.readLine()) != null) if (line.length() > 0) out.add(line);
        } finally {
            in.close();
        }
        return out;
    }

    private static void write(File f, Set<String> keys) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        Writer out = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            for (String k : keys) out.write(k + "\n");
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new Exception("rename failed");
    }

    private static boolean matches(String s) {
        return s != null && MARK.matcher(s).find();
    }

    public static List<Track> filter(Context c, List<Track> tracks) {
        if (tracks == null || !on(c)) return tracks;
        List<Track> out = new ArrayList<Track>(tracks.size());
        for (Track t : tracks) if (!t.isRemote() || !looksAi(t)) out.add(t);
        return out;
    }
}
