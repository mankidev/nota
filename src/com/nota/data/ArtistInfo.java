package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ArtistInfo {
    private static final String KEY = "artist_info:";
    private static final int TIMEOUT_MS = 8000;
    private static final int MIN_SCORE = 90;
    private static final String CHANNEL_URL = "https://music.youtube.com/channel/";
    private static final long PACE_MS = 1100;
    private static long lastAsked;

    public interface Callback {
        void onInfo(String line);
    }

    public static void lookup(final Context c, final String artist, final String channel,
                              final Callback cb) {
        final Context app = c.getApplicationContext();
        final String key = KEY + cacheKey(artist, channel);
        String cached = Prefs.getString(app, key, null);
        if (cached != null) {
            cb.onInfo(cached);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                String line;
                try {
                    line = fetch(artist, channel);
                } catch (Exception e) {
                    return;
                }
                Prefs.setString(app, key, line);
                final String result = line;
                main.post(new Runnable() {
                    public void run() {
                        cb.onInfo(result);
                    }
                });
            }
        }, "nota-artist-info").start();
    }

    static String cacheKey(String artist, String channel) {
        return channel != null ? channel : artist.toLowerCase(Locale.ROOT);
    }

    private static final Map<String, JSONObject> RECORDS = new HashMap<String, JSONObject>();

    static synchronized JSONObject record(String artist, String channel) throws Exception {
        String key = cacheKey(artist, channel);
        if (RECORDS.containsKey(key)) return RECORDS.get(key);
        String mbid = channel == null ? null : byChannel(channel);
        boolean guessed = mbid == null;
        if (guessed) {
            JSONObject a = search(artist);
            mbid = a == null ? null : a.optString("id", null);
        }
        JSONObject found = null;
        if (mbid != null && mbid.matches("[0-9a-f-]{36}")) {
            found = new JSONObject(ask("https://musicbrainz.org/ws/2/artist/" + mbid
                    + "?fmt=json&inc=aliases+url-rels"));
            if (guessed && channel != null && ownsOtherChannel(found, channel)) found = null;
        }
        RECORDS.put(key, found);
        return found;
    }

    private static String byChannel(String channel) throws Exception {
        String json;
        try {
            json = ask("https://musicbrainz.org/ws/2/url?fmt=json&inc=artist-rels&resource="
                    + URLEncoder.encode(CHANNEL_URL + channel, "UTF-8"));
        } catch (Net.NotFound e) {
            return null;
        }
        JSONArray rels = new JSONObject(json).optJSONArray("relations");
        String mbid = null;
        for (int i = 0; rels != null && i < rels.length(); i++) {
            JSONObject a = rels.getJSONObject(i).optJSONObject("artist");
            String id = a == null ? null : a.optString("id", null);
            if (id == null) continue;
            if (mbid != null && !mbid.equals(id)) return null;
            mbid = id;
        }
        return mbid;
    }

    private static String ask(String url) throws Exception {
        long wait = lastAsked + PACE_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        try {
            return Net.get(url, TIMEOUT_MS);
        } finally {
            lastAsked = System.currentTimeMillis();
        }
    }

    private static boolean ownsOtherChannel(JSONObject record, String channel) {
        JSONArray rels = record.optJSONArray("relations");
        boolean other = false;
        for (int i = 0; rels != null && i < rels.length(); i++) {
            JSONObject rel = rels.optJSONObject(i);
            if (rel == null || !"youtube music".equals(rel.optString("type"))) continue;
            JSONObject url = rel.optJSONObject("url");
            String resource = url == null ? "" : url.optString("resource", "");
            if (resource.equals(CHANNEL_URL + channel)) return false;
            if (resource.startsWith(CHANNEL_URL)) other = true;
        }
        return other;
    }

    private static JSONObject search(String artist) throws Exception {
        String term = artist.replaceAll("[\"\\\\]", " ").trim();
        if (term.length() == 0) return null;
        String url = "https://musicbrainz.org/ws/2/artist?fmt=json&limit=1&query="
                + URLEncoder.encode("artist:\"" + term + "\"", "UTF-8");
        JSONArray found = new JSONObject(ask(url)).optJSONArray("artists");
        if (found == null || found.length() == 0) return null;
        JSONObject a = found.getJSONObject(0);
        return a.optInt("score") < MIN_SCORE ? null : a;
    }

    private static String fetch(String artist, String channel) throws Exception {
        JSONObject a = record(artist, channel);
        if (a == null) return "";

        StringBuilder out = new StringBuilder();
        String name = a.optString("name", artist);
        if (!isLatin(name)) {
            String latin = latinAlias(a.optJSONArray("aliases"));
            if (latin == null) latin = readable(a.optString("sort-name", ""));
            if (latin != null && latin.length() > 0) out.append(latin);
        }
        String legal = aliasOfType(a.optJSONArray("aliases"), "Legal name");
        if (legal != null && !legal.equalsIgnoreCase(name) && !legal.equalsIgnoreCase(artist)) {
            if (out.length() > 0) out.append(" · ");
            out.append(legal);
        }
        String about = a.optString("disambiguation", "");
        if (about.length() > 0) {
            if (out.length() > 0) out.append(" · ");
            out.append(about);
        }
        return out.toString();
    }

    private static String readable(String sortName) {
        int comma = sortName.indexOf(", ");
        return comma < 0 ? sortName
                : sortName.substring(comma + 2) + " " + sortName.substring(0, comma);
    }

    private static String latinAlias(JSONArray aliases) {
        if (aliases == null) return null;
        String fallback = null;
        for (int i = 0; i < aliases.length(); i++) {
            JSONObject al = aliases.optJSONObject(i);
            if (al == null) continue;
            String locale = al.optString("locale", "");
            String value = al.optString("name", "");
            if (value.length() == 0 || !isLatin(value)) continue;
            if (locale.startsWith("en") && al.optBoolean("primary")) return value;
            if (fallback == null && (locale.startsWith("en") || locale.contains("Latn"))) {
                fallback = value;
            }
        }
        return fallback;
    }

    private static String aliasOfType(JSONArray aliases, String type) {
        if (aliases == null) return null;
        for (int i = 0; i < aliases.length(); i++) {
            JSONObject al = aliases.optJSONObject(i);
            if (al != null && type.equals(al.optString("type"))) return al.optString("name");
        }
        return null;
    }

    private static boolean isLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x2E7F) return false;
        }
        return true;
    }
}
