package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.nota.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class Recommender {
    public interface Callback {
        void onTracks(List<Track> tracks);
    }

    private static final float W_PAIRING = 0.45f;
    private static final float W_MIX = 0.32f;
    private static final float W_SHELF = 0.26f;
    private static final float W_LOVED = 0.20f;
    private static final float W_LAST_RESORT = 0.05f;
    private static final float W_AFFINITY = 0.30f;
    private static final float W_EXPLORE = 0.15f;
    private static final float P_RECENT = 0.5f;
    private static final int TASTE_SEEDS = 6;
    private static final int RECENT_POOL = 12;
    private static final float W_EXPLORE_SHELF = 0.45f;
    private static final float P_STALE = 0.15f;
    private static final String KEY_SHOWN = "taste_shown";
    private static final int SHOWN_MEMORY = 150;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Random RND = new Random();

    public static void nextFor(Context c, final Track seed, List<Track> inQueue, final int want,
                               final Callback cb) {
        final Context app = c.getApplicationContext();
        final Set<String> avoid = new HashSet<String>();
        avoid.add(seed.key());
        if (inQueue != null) for (Track t : inQueue) avoid.add(t.key());

        new Thread(new Runnable() {
            public void run() {
                List<Track> found;
                try {
                    found = build(app, seed, avoid, want);
                } catch (Exception e) {
                    found = new ArrayList<Track>();
                }
                final List<Track> out = found;
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onTracks(out);
                    }
                });
            }
        }, "nota-recommend").start();
    }

    public static void forTaste(Context c, final int want, final Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                List<Track> found;
                try {
                    found = buildTaste(app, want);
                } catch (Exception e) {
                    found = new ArrayList<Track>();
                }
                final List<Track> out = found;
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onTracks(out);
                    }
                });
            }
        }, "nota-taste").start();
    }

    private static class Candidate {
        final Track track;
        float score;

        Candidate(Track track, float score) {
            this.track = track;
            this.score = score;
        }
    }

    private static List<Track> build(Context app, Track seed, Set<String> avoid, int want) {
        Db db = Db.get(app);
        boolean online = Connectivity.isOnline(app);
        Map<String, Candidate> pool = new LinkedHashMap<String, Candidate>();
        collect(app, db, seed, pool, avoid, online);
        if (pool.size() < want) loved(app, db, pool, avoid, online);

        if (pool.isEmpty() && !online) {
            for (Track t : MediaLibrary.get().tracks()) offer(pool, avoid, false, t, W_LAST_RESORT);
        }
        return rank(app, db, pool, want, false, W_EXPLORE, Collections.<String>emptySet());
    }

    private static List<Track> buildTaste(Context app, int want) {
        Db db = Db.get(app);
        boolean online = Connectivity.isOnline(app);
        List<Track> seeds = tasteSeeds(app, db);
        Set<String> avoid = new HashSet<String>();
        for (Track t : seeds) avoid.add(t.key());

        Map<String, Candidate> pool = new LinkedHashMap<String, Candidate>();
        for (Track seed : seeds) collect(app, db, seed, pool, avoid, online);
        Set<String> stale = shown(app);
        stale.addAll(db.heardKeys());
        List<Track> out = rank(app, db, pool, want, true, W_EXPLORE_SHELF, stale);
        remember(app, out);
        return out;
    }

    private static Set<String> shown(Context app) {
        Set<String> out = new HashSet<String>();
        for (String key : Prefs.getString(app, KEY_SHOWN, "").split("\n")) {
            if (key.length() > 0) out.add(key);
        }
        return out;
    }

    private static void remember(Context app, List<Track> picks) {
        if (picks.isEmpty()) return;
        List<String> keys = new ArrayList<String>();
        for (Track t : picks) keys.add(t.key());
        for (String old : Prefs.getString(app, KEY_SHOWN, "").split("\n")) {
            if (keys.size() >= SHOWN_MEMORY) break;
            if (old.length() > 0 && !keys.contains(old)) keys.add(old);
        }
        Prefs.setString(app, KEY_SHOWN, TextUtils.join("\n", keys));
    }

    private static void loved(Context app, Db db, Map<String, Candidate> pool, Set<String> avoid,
                              boolean online) {
        LinkedHashMap<String, Integer> loved = db.topScored(24);
        if (loved.isEmpty()) return;
        float top = Collections.max(loved.values());
        for (Track t : MediaLibrary.resolve(app, new ArrayList<String>(loved.keySet()))) {
            Integer s = loved.get(t.key());
            offer(pool, avoid, online, t, W_LOVED * (s == null ? 0f : s / top));
        }
    }

    private static List<Track> tasteSeeds(Context app, Db db) {
        List<Track> out = new ArrayList<Track>();
        Set<String> artists = new HashSet<String>();
        List<Track> recent = new ArrayList<Track>(MediaLibrary.resolve(
                app, db.recentlyEnjoyed(RECENT_POOL)));
        while (!recent.isEmpty() && out.size() < TASTE_SEEDS) {
            double lean = RND.nextDouble();
            Track t = recent.remove((int) (lean * lean * recent.size()));
            String artist = artistKey(t);
            if (artist != null && !artists.add(artist)) continue;
            out.add(t);
        }
        return out;
    }

    private static void collect(Context app, Db db, Track seed, Map<String, Candidate> pool,
                                Set<String> avoid, boolean online) {
        LinkedHashMap<String, Integer> pairs = db.nextKeys(seed.key(), 12);
        if (!pairs.isEmpty()) {
            float top = Collections.max(pairs.values());
            for (Track t : MediaLibrary.resolve(app, new ArrayList<String>(pairs.keySet()))) {
                Integer w = pairs.get(t.key());
                offer(pool, avoid, online, t, W_PAIRING * (w == null ? 0f : w / top));
            }
        }

        for (Track t : shelf(seed)) offer(pool, avoid, online, t, W_SHELF);

        if (online) {
            List<Track> mix = mixFor(app, seed);
            for (int i = 0; mix != null && i < mix.size(); i++) {
                offer(pool, avoid, true, mix.get(i), W_MIX * (1f - 0.3f * i / mix.size()));
            }
        }
    }

    private static List<Track> rank(Context app, Db db, Map<String, Candidate> pool, int want,
                                    boolean onePerArtist, float explore, Set<String> stale) {
        Map<String, Float> affinity = artistAffinity(app, db);
        Set<String> recent = new HashSet<String>(db.recentKeys(20));
        Set<String> muted = PrefList.MUTED.keys(app);
        Set<String> ignored = PrefList.IGNORED.keys(app);
        boolean lessAi = LessAi.on(app);
        List<Candidate> ranked = new ArrayList<Candidate>(pool.values());
        for (Iterator<Candidate> it = ranked.iterator(); it.hasNext(); ) {
            Track t = it.next().track;
            if (muted.contains(artistKey(t)) || ignored.contains(t.key()) || (lessAi && t.isRemote() && LessAi.looksAi(t))) {
                it.remove();
            }
        }
        for (Candidate c : ranked) {
            Float a = affinity.get(artistKey(c.track));
            float tilt = 1f + W_AFFINITY * (a == null ? 0f : a) + explore * RND.nextFloat();
            if (recent.contains(c.track.key())) tilt -= P_RECENT;
            c.score *= tilt;
            if (stale.contains(c.track.key())) c.score *= P_STALE;
        }
        Collections.sort(ranked, new Comparator<Candidate>() {
            public int compare(Candidate x, Candidate y) {
                return Float.compare(y.score, x.score);
            }
        });
        return spread(ranked, want, onePerArtist);
    }

    private static void offer(Map<String, Candidate> pool, Set<String> avoid, boolean online,
                              Track t, float score) {
        if (t == null || TextUtils.isEmpty(t.title)) return;
        if (!online && t.isRemote()) return;
        String key = t.key();
        if (avoid.contains(key)) return;
        Candidate had = pool.get(key);
        if (had == null) pool.put(key, new Candidate(t, score));
        else if (score > had.score) had.score = score;
    }

    private static List<Track> shelf(Track seed) {
        List<Track> out = new ArrayList<Track>();
        if (seed.type != Track.TYPE_LOCAL) return out;
        String artist = artistKey(seed);
        for (Track t : MediaLibrary.get().tracks()) {
            if (t.key().equals(seed.key())) continue;
            boolean sameArtist = artist != null && artist.equals(artistKey(t));
            boolean sameFolder = seed.folder != null && seed.folder.equals(t.folder);
            if (sameArtist || sameFolder) out.add(t);
        }
        Collections.shuffle(out, RND);
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    private static List<Track> mixFor(Context app, Track seed) {
        String id = YtApi.videoId(seed);
        if (id == null) {
            String term = TextUtils.isEmpty(seed.artist) ? seed.title : seed.artist + " " + seed.title;
            List<Track> hits = YtApi.searchBlocking(app, term);
            if (hits == null || hits.isEmpty()) return null;
            id = YtApi.videoId(hits.get(0));
        }
        return id == null ? null : YtApi.mixBlocking(app, id);
    }

    private static Map<String, Float> artistAffinity(Context app, Db db) {
        Map<String, Float> out = new LinkedHashMap<String, Float>();
        LinkedHashMap<String, Integer> scored = db.topScored(60);
        if (scored.isEmpty()) return out;
        float top = 0f;
        for (Track t : MediaLibrary.resolve(app, new ArrayList<String>(scored.keySet()))) {
            String artist = artistKey(t);
            Integer s = scored.get(t.key());
            if (artist == null || s == null) continue;
            Float had = out.get(artist);
            float total = (had == null ? 0f : had) + s;
            out.put(artist, total);
            if (total > top) top = total;
        }
        if (top > 0f) {
            for (Map.Entry<String, Float> e : out.entrySet()) e.setValue(e.getValue() / top);
        }
        return out;
    }

    private static String artistKey(Track t) {
        return TextUtils.isEmpty(t.artist) ? null : t.artist.toLowerCase(Locale.ROOT).trim();
    }

    private static List<Track> spread(List<Candidate> ranked, int want, boolean onePerArtist) {
        List<Track> out = new ArrayList<Track>(want);
        List<Candidate> parked = new ArrayList<Candidate>();
        Set<String> taken = new HashSet<String>();
        for (Candidate c : ranked) {
            if (out.size() >= want) break;
            String artist = artistKey(c.track);
            boolean repeat = onePerArtist && artist != null && !taken.add(artist);
            if (repeat || crowded(out, c.track)) parked.add(c);
            else out.add(c.track);
        }
        for (Candidate c : parked) {
            if (out.size() >= want) break;
            out.add(c.track);
        }
        return out;
    }

    private static boolean crowded(List<Track> chosen, Track t) {
        String artist = artistKey(t);
        if (artist == null || chosen.size() < 2) return false;
        return artist.equals(artistKey(chosen.get(chosen.size() - 1)))
                && artist.equals(artistKey(chosen.get(chosen.size() - 2)));
    }
}
