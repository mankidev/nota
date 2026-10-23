package com.nota.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

final class YtStream {
    private YtStream() {
    }

    private static final String PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String VISITOR_URL = "https://www.youtube.com/sw.js_data";

    private static final String CLIENT_NAME = "VISIONOS";
    private static final String CLIENT_VERSION = "1.02";
    static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    private static final int PREFERRED_ITAG = 140;

    private static final int TIMEOUT_MS = 20000;
    private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000;

    private static String visitorData;

    private static final String VISITOR_KEY = "yt_visitor";
    private static final String VISITOR_AT_KEY = "yt_visitor_at";
    private static final long VISITOR_LIFE_MS = 7L * 24 * 60 * 60 * 1000;

    private static final Map<String, Resolved> CACHE = new HashMap<>();

    private static final Map<String, Object> LOOKUPS = new HashMap<>();

    private static class Resolved {
        final String url;
        final long deadline;

        Resolved(String url, long deadline) {
            this.url = url;
            this.deadline = deadline;
        }
    }

    static String url(Context app, String videoId) {
        if (!YtApi.validId(videoId)) return null;
        String hit = remembered(videoId);
        if (hit != null) return hit;
        Object lock;
        synchronized (LOOKUPS) {
            lock = LOOKUPS.get(videoId);
            if (lock == null) LOOKUPS.put(videoId, lock = new Object());
        }
        synchronized (lock) {
            try {
                String waited = remembered(videoId);
                if (waited != null) return waited;
                String url = resolve(app, videoId, true);
                if (url == null) return null;
                synchronized (CACHE) {
                    CACHE.put(videoId, new Resolved(url, deadline(url)));
                }
                return url;
            } finally {
                synchronized (LOOKUPS) {
                    LOOKUPS.remove(videoId);
                }
            }
        }
    }

    private static String remembered(String videoId) {
        synchronized (CACHE) {
            Resolved hit = CACHE.get(videoId);
            return hit != null && System.currentTimeMillis() < hit.deadline ? hit.url : null;
        }
    }

    static void warmSession(Context app) {
        visitor(app);
    }

    private static String resolve(Context app, String videoId, boolean mayRetry) {
        String visitor = visitor(app);
        if (visitor == null) return null;
        String answer;
        try {
            JSONObject client = new JSONObject()
                    .put("clientName", CLIENT_NAME)
                    .put("clientVersion", CLIENT_VERSION)
                    .put("deviceMake", "Apple")
                    .put("deviceModel", "RealityDevice17,1")
                    .put("osName", "visionOS")
                    .put("osVersion", "26.5.23O471")
                    .put("hl", "tr")
                    .put("gl", "TR")
                    .put("visitorData", visitor);
            String body = new JSONObject()
                    .put("context", new JSONObject().put("client", client))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                    .toString();
            answer = Net.post(PLAYER_URL, body, USER_AGENT, TIMEOUT_MS);
        } catch (Exception e) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(answer);
            String status = root.optJSONObject("playabilityStatus") == null
                    ? "" : root.getJSONObject("playabilityStatus").optString("status");
            if (!"OK".equals(status)) {
                if (mayRetry) {
                    forget(app);
                    return resolve(app, videoId, false);
                }
                return null;
            }
            return pickAudio(root.optJSONObject("streamingData"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickAudio(JSONObject streaming) {
        if (streaming == null) return null;
        JSONArray formats = streaming.optJSONArray("adaptiveFormats");
        if (formats == null) return null;
        String best = null;
        int bestBitrate = -1;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", null);
            if (url == null || url.isEmpty()) continue;
            if (!f.optString("mimeType").startsWith("audio/mp4")) continue;
            if (f.optInt("itag") == PREFERRED_ITAG) return url;
            int bitrate = f.optInt("bitrate");
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                best = url;
            }
        }
        return best;
    }

    private static long deadline(String url) {
        long fallback = System.currentTimeMillis() + 60 * 60 * 1000;
        int at = url.indexOf("expire=");
        if (at < 0) return fallback;
        int from = at + "expire=".length();
        int to = from;
        while (to < url.length() && Character.isDigit(url.charAt(to))) to++;
        if (to == from) return fallback;
        try {
            return Long.parseLong(url.substring(from, to)) * 1000 - EXPIRY_MARGIN_MS;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static synchronized String visitor(Context app) {
        if (visitorData != null) return visitorData;
        long kept = Prefs.getLong(app, VISITOR_AT_KEY, 0);
        if (System.currentTimeMillis() - kept < VISITOR_LIFE_MS) {
            String stored = Prefs.getString(app, VISITOR_KEY, null);
            if (stored != null && stored.length() > 0) return visitorData = stored;
        }
        try {
            String body = Net.get(VISITOR_URL, TIMEOUT_MS);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"(Cg[A-Za-z0-9_%-]{20,})\"").matcher(body);
            if (!m.find()) return null;
            visitorData = percentDecode(m.group(1));
            Prefs.setString(app, VISITOR_KEY, visitorData);
            Prefs.setLong(app, VISITOR_AT_KEY, System.currentTimeMillis());
            return visitorData;
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void forget(Context app) {
        visitorData = null;
        Prefs.setLong(app, VISITOR_AT_KEY, 0);
    }

    private static String percentDecode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%' && i + 2 < text.length()) {
                int value = Character.digit(text.charAt(i + 1), 16) * 16
                        + Character.digit(text.charAt(i + 2), 16);
                if (value >= 0) {
                    out.append((char) value);
                    i += 2;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }
}
