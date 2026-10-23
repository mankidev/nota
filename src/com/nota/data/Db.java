package com.nota.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import com.nota.model.Track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Db extends SQLiteOpenHelper {
    public static class Playlist {
        public long id;
        public String name;
        public int count;
        public long updated;
    }

    private static final String NAME = "nota.db";
    private static final int VERSION = 6;

    private static Db instance;

    private Set<String> favorites;

    private static final ExecutorService writes = Executors.newSingleThreadExecutor();

    public static void background(Runnable task) {
        writes.execute(task);
    }

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private final Context app;

    private Db(Context c) {
        super(c, NAME, null, VERSION);
        app = c;
    }

    private boolean paused() {
        return HistoryPause.on(app);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "created INTEGER NOT NULL,"
                + "updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE playlist_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "playlist_id INTEGER NOT NULL,"
                + "track_key TEXT NOT NULL,"
                + "pos INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_items ON playlist_items(playlist_id, pos)");
        db.execSQL("CREATE TABLE favorites ("
                + "track_key TEXT PRIMARY KEY,"
                + "added INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE history ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "track_key TEXT NOT NULL,"
                + "played_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE play_counts ("
                + "track_key TEXT PRIMARY KEY,"
                + "plays INTEGER NOT NULL,"
                + "last_at INTEGER NOT NULL,"
                + "heard_ms INTEGER NOT NULL DEFAULT 0)");
        createOnlineTracks(db);
        createSignals(db);
        createTimeIndexes(db);
        addArtistChannel(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from < 2) createOnlineTracks(db);
        if (from < 3) createSignals(db);
        if (from < 4) createHeard(db);
        if (from < 5) createTimeIndexes(db);
        if (from < 6) addArtistChannel(db);
    }

    private static void addArtistChannel(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE online_tracks ADD COLUMN artist_channel TEXT");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_online_channel ON online_tracks(artist_channel)");
    }

    private static void createTimeIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_at ON history(played_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_at ON events(at)");
    }

    private static void createHeard(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE play_counts ADD COLUMN heard_ms INTEGER NOT NULL DEFAULT 0");
        db.execSQL("UPDATE play_counts SET heard_ms = plays * COALESCE("
                + "(SELECT duration FROM online_tracks WHERE source_id = SUBSTR(track_key, 3)), 0) "
                + "WHERE track_key LIKE 'o:%'");
    }

    private static void createSignals(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "track_key TEXT NOT NULL,"
                + "kind INTEGER NOT NULL,"
                + "score INTEGER NOT NULL,"
                + "at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events ON events(track_key)");
        db.execSQL("CREATE TABLE IF NOT EXISTS transitions ("
                + "from_key TEXT NOT NULL,"
                + "to_key TEXT NOT NULL,"
                + "weight INTEGER NOT NULL,"
                + "at INTEGER NOT NULL,"
                + "PRIMARY KEY (from_key, to_key))");
    }

    private static void createOnlineTracks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS online_tracks ("
                + "source_id TEXT PRIMARY KEY,"
                + "title TEXT,"
                + "artist TEXT,"
                + "album TEXT,"
                + "url TEXT,"
                + "art TEXT,"
                + "duration INTEGER NOT NULL DEFAULT 0,"
                + "track_no INTEGER NOT NULL DEFAULT 0,"
                + "year INTEGER NOT NULL DEFAULT 0,"
                + "size INTEGER NOT NULL DEFAULT 0,"
                + "added INTEGER NOT NULL DEFAULT 0)");
    }

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<Playlist>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT p.id, p.name, p.updated, COUNT(i.id) FROM playlists p "
                        + "LEFT JOIN playlist_items i ON i.playlist_id = p.id "
                        + "GROUP BY p.id ORDER BY p.updated DESC", null);
        try {
            while (c.moveToNext()) {
                Playlist p = new Playlist();
                p.id = c.getLong(0);
                p.name = c.getString(1);
                p.updated = c.getLong(2);
                p.count = c.getInt(3);
                out.add(p);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public long createPlaylist(String name) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", now);
        v.put("updated", now);
        return getWritableDatabase().insert("playlists", null, v);
    }

    public void renamePlaylist(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("playlists", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deletePlaylist(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] arg = {String.valueOf(id)};
        db.delete("playlist_items", "playlist_id=?", arg);
        db.delete("playlists", "id=?", arg);
    }

    public List<String> playlistKeys(long id) {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().query("playlist_items", new String[]{"track_key"},
                "playlist_id=?", new String[]{String.valueOf(id)}, null, null, "pos ASC");
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public void addToPlaylist(long id, List<Track> tracks) {
        SQLiteDatabase db = getWritableDatabase();
        int pos = nextPos(db, id);
        db.beginTransaction();
        try {
            for (Track t : tracks) {
                ContentValues v = new ContentValues();
                v.put("playlist_id", id);
                v.put("track_key", t.key());
                v.put("pos", pos++);
                db.insert("playlist_items", null, v);
            }
            ContentValues u = new ContentValues();
            u.put("updated", System.currentTimeMillis());
            db.update("playlists", u, "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private int nextPos(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT IFNULL(MAX(pos), -1) + 1 FROM playlist_items "
                + "WHERE playlist_id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void removeFromPlaylist(long id, String trackKey) {
        getWritableDatabase().delete("playlist_items", "playlist_id=? AND track_key=?",
                new String[]{String.valueOf(id), trackKey});
    }

    private synchronized Set<String> favorites() {
        if (favorites == null) favorites = new HashSet<String>(favoriteKeys());
        return favorites;
    }

    private int revision;

    public int revision() {
        return revision;
    }

    public synchronized void reloaded() {
        favorites = null;
        revision++;
    }

    public int favoriteCount() {
        return favorites().size();
    }

    public boolean isFavorite(String key) {
        return favorites().contains(key);
    }

    public boolean toggleFavorite(Track track) {
        if (track.type == Track.TYPE_ONLINE) saveOnline(track);
        return toggleFavorite(track.key());
    }

    public boolean toggleFavorite(String key) {
        revision++;
        if (favorites().remove(key)) {
            getWritableDatabase().delete("favorites", "track_key=?", new String[]{key});
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("track_key", key);
        v.put("added", System.currentTimeMillis());
        getWritableDatabase().insert("favorites", null, v);
        favorites().add(key);
        return true;
    }

    public List<String> favoriteKeys() {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().query("favorites", new String[]{"track_key"},
                null, null, null, null, "added DESC");
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public void recordPlay(Track track) {
        if (track.type == Track.TYPE_ONLINE) saveOnline(track);
        if (paused()) return;
        String key = track.key();
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        ContentValues h = new ContentValues();
        h.put("track_key", key);
        h.put("played_at", now);
        db.insert("history", null, h);
        SQLiteStatement st = db.compileStatement(
                "UPDATE play_counts SET plays = plays + 1, last_at = ? WHERE track_key = ?");
        st.bindLong(1, now);
        st.bindString(2, key);
        int rows = st.executeUpdateDelete();
        st.close();
        if (rows == 0) {
            ContentValues p = new ContentValues();
            p.put("track_key", key);
            p.put("plays", 1);
            p.put("last_at", now);
            db.insertWithOnConflict("play_counts", null, p, SQLiteDatabase.CONFLICT_IGNORE);
        }
        db.execSQL("DELETE FROM history WHERE id NOT IN "
                + "(SELECT id FROM history ORDER BY played_at DESC LIMIT 500)");
    }

    public void addListened(String trackKey, long ms) {
        if (ms <= 0 || paused()) return;
        SQLiteStatement st = getWritableDatabase().compileStatement(
                "UPDATE play_counts SET heard_ms = heard_ms + ? WHERE track_key = ?");
        st.bindLong(1, ms);
        st.bindString(2, trackKey);
        try {
            st.executeUpdateDelete();
        } finally {
            st.close();
        }
    }

    public List<String> recentKeys(int limit) {
        return new ArrayList<String>(recentPlays(limit).keySet());
    }

    public LinkedHashMap<String, Long> recentPlays(int limit) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<String, Long>();
        Cursor c = getReadableDatabase().query("history",
                new String[]{"track_key", "played_at"},
                null, null, null, null, "played_at DESC", String.valueOf(limit * 4));
        try {
            while (c.moveToNext() && out.size() < limit) {
                String key = c.getString(0);
                if (!out.containsKey(key)) out.put(key, c.getLong(1));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public List<String> mostPlayedKeys(int limit) {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().query("play_counts", new String[]{"track_key"},
                "plays > 1", null, null, null, "plays DESC, last_at DESC", String.valueOf(limit));
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public Set<String> heardKeys() {
        Set<String> out = new HashSet<String>();
        Cursor c = getReadableDatabase().query("play_counts", new String[]{"track_key"},
                null, null, null, null, null);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public void clearHistory() {
        revision++;
        SQLiteDatabase db = getWritableDatabase();
        db.delete("history", null, null);
        db.delete("play_counts", null, null);
        db.delete("events", null, null);
        db.delete("transitions", null, null);
    }

    public void recordEvent(String trackKey, int kind, int score) {
        if (paused()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("track_key", trackKey);
        v.put("kind", kind);
        v.put("score", score);
        v.put("at", System.currentTimeMillis());
        db.insert("events", null, v);
        db.execSQL("DELETE FROM events WHERE id NOT IN "
                + "(SELECT id FROM events ORDER BY at DESC LIMIT 2000)");
    }

    public void bumpTransition(String fromKey, String toKey, int delta) {
        if (paused()) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        SQLiteStatement st = db.compileStatement("UPDATE transitions SET weight = weight + ?, "
                + "at = ? WHERE from_key = ? AND to_key = ?");
        st.bindLong(1, delta);
        st.bindLong(2, now);
        st.bindString(3, fromKey);
        st.bindString(4, toKey);
        int rows = st.executeUpdateDelete();
        st.close();
        if (rows == 0) {
            ContentValues v = new ContentValues();
            v.put("from_key", fromKey);
            v.put("to_key", toKey);
            v.put("weight", delta);
            v.put("at", now);
            db.insertWithOnConflict("transitions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    public int eventCount() {
        return count("SELECT COUNT(*) FROM events");
    }

    public int transitionCount() {
        return count("SELECT COUNT(*) FROM transitions");
    }

    private int count(String sql) {
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public List<String> topScoredKeys(int limit) {
        return new ArrayList<String>(topScored(limit).keySet());
    }

    public LinkedHashMap<String, Integer> topScored(int limit) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT track_key, SUM(score) AS total FROM events GROUP BY track_key "
                        + "HAVING total > 0 ORDER BY total DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        } finally {
            c.close();
        }
        return out;
    }

    public List<String> recentlyEnjoyed(int limit) {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT track_key FROM events GROUP BY track_key "
                        + "HAVING MAX(CASE WHEN score > 0 THEN at END) >= MAX(at) "
                        + "ORDER BY MAX(at) DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public LinkedHashMap<String, Integer> nextKeys(String fromKey, int limit) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT to_key, weight FROM transitions WHERE from_key = ? AND weight > 0 "
                        + "ORDER BY weight DESC, at DESC LIMIT ?",
                new String[]{fromKey, String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
        } finally {
            c.close();
        }
        return out;
    }

    public void saveOnline(Track t) {
        ContentValues v = new ContentValues();
        v.put("source_id", t.sourceId);
        v.put("title", t.title);
        v.put("artist", t.artist);
        v.put("album", t.album);
        v.put("url", t.data);
        v.put("art", t.artUrl);
        v.put("duration", t.durationMs);
        v.put("track_no", t.trackNo);
        v.put("year", t.year);
        v.put("size", t.sizeBytes);
        v.put("added", System.currentTimeMillis());
        v.put("artist_channel", t.artistChannel != null ? t.artistChannel
                : storedChannel(t.sourceId));
        getWritableDatabase().insertWithOnConflict("online_tracks", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    private String storedChannel(String sourceId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT artist_channel FROM online_tracks WHERE source_id = ?",
                new String[]{sourceId});
        try {
            return c.moveToFirst() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    public void claim(String channel, Collection<Track> tracks) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Track t : tracks) {
                if (!channel.equals(t.artistChannel)) continue;
                db.execSQL("UPDATE online_tracks SET artist_channel = ? "
                        + "WHERE source_id = ? AND artist_channel IS NULL",
                        new Object[]{channel, t.sourceId});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Track> topArtists(int limit) {
        List<Track> faces = new ArrayList<Track>();
        List<Long> totals = new ArrayList<Long>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT t.*, MAX(p.plays) AS peak, SUM(p.plays) AS total "
                        + "FROM play_counts p JOIN online_tracks t "
                        + "ON t.source_id = SUBSTR(p.track_key, 3) "
                        + "WHERE p.track_key LIKE 'o:%' "
                        + "AND t.artist IS NOT NULL AND t.artist <> '' "
                        + "GROUP BY LOWER(t.artist), COALESCE(t.artist_channel, '') "
                        + "ORDER BY total DESC, peak DESC LIMIT ?",
                new String[]{String.valueOf(limit * 3)});
        try {
            while (c.moveToNext()) {
                faces.add(readOnline(c));
                totals.add(c.getLong(c.getColumnIndexOrThrow("total")));
            }
        } finally {
            c.close();
        }
        Map<String, Integer> named = new HashMap<String, Integer>();
        Set<String> shared = new HashSet<String>();
        for (int i = 0; i < faces.size(); i++) {
            if (faces.get(i).artistChannel == null) continue;
            String name = faces.get(i).artist.toLowerCase(Locale.ROOT);
            if (named.put(name, i) != null) shared.add(name);
        }
        for (int i = 0; i < faces.size(); i++) {
            if (faces.get(i).artistChannel != null) continue;
            String name = faces.get(i).artist.toLowerCase(Locale.ROOT);
            Integer owner = shared.contains(name) ? null : named.get(name);
            if (owner == null) continue;
            totals.set(owner, totals.get(owner) + totals.get(i));
            totals.set(i, -1L);
        }
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < faces.size(); i++) if (totals.get(i) >= 0) order.add(i);
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return Long.compare(totals.get(b), totals.get(a));
            }
        });
        List<Track> out = new ArrayList<Track>();
        for (int i : order) {
            if (out.size() >= limit) break;
            out.add(faces.get(i));
        }
        return out;
    }

    public long listenedMs(String artist, String channel) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(p.heard_ms) FROM play_counts p JOIN online_tracks t "
                        + "ON t.source_id = SUBSTR(p.track_key, 3) "
                        + "WHERE p.track_key LIKE 'o:%' AND " + artistIs(channel),
                new String[]{channel != null ? channel : artist});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    public List<Track> onlineByArtist(String artist, String channel, int limit) {
        List<Track> out = new ArrayList<Track>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT t.*, COALESCE(p.plays, 0) AS plays FROM online_tracks t "
                        + "LEFT JOIN play_counts p ON p.track_key = 'o:' || t.source_id "
                        + "WHERE " + artistIs(channel) + " ORDER BY plays DESC LIMIT ?",
                new String[]{channel != null ? channel : artist, String.valueOf(limit)});
        try {
            while (c.moveToNext()) out.add(readOnline(c));
        } finally {
            c.close();
        }
        return out;
    }

    public Map<String, Track> topByArtists(List<String> names) {
        Map<String, Track> out = new HashMap<String, Track>();
        for (int from = 0; from < names.size(); from += 500) {
            List<String> part = names.subList(from, Math.min(names.size(), from + 500));
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < part.size(); i++) rows.append(i == 0 ? "(?)" : ",(?)");
            Cursor c = getReadableDatabase().rawQuery(
                    "WITH n(wanted) AS (VALUES " + rows + ") "
                            + "SELECT n.wanted, t.*, COALESCE(p.plays, 0) AS plays FROM n "
                            + "JOIN online_tracks t ON LOWER(t.artist) = LOWER(n.wanted) "
                            + "LEFT JOIN play_counts p ON p.track_key = 'o:' || t.source_id "
                            + "ORDER BY plays DESC",
                    part.toArray(new String[0]));
            try {
                while (c.moveToNext()) {
                    String wanted = c.getString(0);
                    if (!out.containsKey(wanted)) out.put(wanted, readOnline(c));
                }
            } finally {
                c.close();
            }
        }
        return out;
    }

    private static String artistIs(String channel) {
        return channel != null ? "t.artist_channel = ?" : "LOWER(t.artist) = LOWER(?)";
    }

    public Map<String, Track> online(Collection<String> sourceIds) {
        Map<String, Track> out = new HashMap<String, Track>();
        if (sourceIds.isEmpty()) return out;
        StringBuilder holes = new StringBuilder();
        String[] args = new String[sourceIds.size()];
        int i = 0;
        for (String id : sourceIds) {
            if (i > 0) holes.append(',');
            holes.append('?');
            args[i++] = id;
        }
        Cursor c = getReadableDatabase().query("online_tracks", null,
                "source_id IN (" + holes + ")", args, null, null, null);
        try {
            while (c.moveToNext()) {
                Track t = readOnline(c);
                out.put(t.sourceId, t);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static Track readOnline(Cursor c) {
        Track t = new Track();
        t.type = Track.TYPE_ONLINE;
        t.sourceId = c.getString(c.getColumnIndexOrThrow("source_id"));
        t.title = c.getString(c.getColumnIndexOrThrow("title"));
        t.artist = c.getString(c.getColumnIndexOrThrow("artist"));
        t.album = c.getString(c.getColumnIndexOrThrow("album"));
        t.data = c.getString(c.getColumnIndexOrThrow("url"));
        t.artUrl = c.getString(c.getColumnIndexOrThrow("art"));
        t.durationMs = c.getLong(c.getColumnIndexOrThrow("duration"));
        t.trackNo = c.getInt(c.getColumnIndexOrThrow("track_no"));
        t.year = c.getInt(c.getColumnIndexOrThrow("year"));
        t.sizeBytes = c.getLong(c.getColumnIndexOrThrow("size"));
        t.artistChannel = c.getString(c.getColumnIndexOrThrow("artist_channel"));
        return t;
    }
}
