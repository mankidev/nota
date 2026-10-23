package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

public class ArtistPhoto {
    private static final String KEY = "artist_photo:";
    private static final int TIMEOUT_MS = 8000;

    public interface Callback {
        void onUrl(String url);
    }

    public static void lookup(Context c, final String artist, final String channel,
                              final int width, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String key = KEY + ArtistInfo.cacheKey(artist, channel);
        String cached = Prefs.getString(app, key, null);
        if (cached != null) {
            cb.onUrl(cached.length() == 0 ? null : filePath(cached, width));
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final String file;
                try {
                    file = fetch(artist, channel);
                } catch (Exception e) {
                    return;
                }
                Prefs.setString(app, key, file);
                if (file.length() == 0) return;
                main.post(new Runnable() {
                    public void run() {
                        cb.onUrl(filePath(file, width));
                    }
                });
            }
        }, "nota-artist-photo").start();
    }

    private static String fetch(String artist, String channel) throws Exception {
        JSONObject found = ArtistInfo.record(artist, channel);
        if (found == null) return "";
        String entity = wikidataId(found);
        if (entity == null) return "";
        String file = image(entity);
        return file == null ? "" : file;
    }

    static String wikidataId(JSONObject record) {
        JSONArray rels = record.optJSONArray("relations");
        if (rels == null) return null;
        for (int i = 0; i < rels.length(); i++) {
            JSONObject rel = rels.optJSONObject(i);
            if (rel == null || !"wikidata".equals(rel.optString("type"))) continue;
            JSONObject url = rel.optJSONObject("url");
            String resource = url == null ? "" : url.optString("resource", "");
            String id = resource.substring(resource.lastIndexOf('/') + 1);
            if (id.matches("Q[0-9]+")) return id;
        }
        return null;
    }

    private static String image(String entity) throws Exception {
        String json = Net.get("https://www.wikidata.org/w/api.php?action=wbgetclaims"
                + "&format=json&property=P18&entity=" + entity, TIMEOUT_MS);
        JSONObject claims = new JSONObject(json).optJSONObject("claims");
        JSONArray p18 = claims == null ? null : claims.optJSONArray("P18");
        JSONObject first = p18 == null || p18.length() == 0 ? null : p18.optJSONObject(0);
        JSONObject snak = first == null ? null : first.optJSONObject("mainsnak");
        JSONObject value = snak == null ? null : snak.optJSONObject("datavalue");
        String file = value == null ? null : value.optString("value", "");
        return file == null || file.length() == 0 ? null : file;
    }

    private static String filePath(String file, int width) {
        try {
            return "https://commons.wikimedia.org/wiki/Special:FilePath/"
                    + URLEncoder.encode(file, "UTF-8").replace("+", "%20")
                    + "?width=" + width;
        } catch (Exception e) {
            return null;
        }
    }
}
