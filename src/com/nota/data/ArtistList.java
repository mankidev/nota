package com.nota.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ArtistList {
    public static final ArtistList FOLLOWS = new ArtistList("followed_artists");
    public static final ArtistList MUTED = new ArtistList("muted_artists");

    private final String key;

    private ArtistList(String key) {
        this.key = key;
    }

    public List<String> all(Context c) {
        List<String> out = new ArrayList<String>();
        for (String line : Prefs.getString(c, key, "").split("\n")) {
            if (line.length() > 0) out.add(line);
        }
        return out;
    }

    public Set<String> keys(Context c) {
        Set<String> out = new HashSet<String>();
        for (String a : all(c)) out.add(a.toLowerCase(Locale.ROOT).trim());
        return out;
    }

    public boolean has(Context c, String artist) {
        return artist != null && keys(c).contains(artist.toLowerCase(Locale.ROOT).trim());
    }

    public boolean toggle(Context c, String artist) {
        List<String> kept = new ArrayList<String>();
        boolean removed = false;
        for (String a : all(c)) {
            if (a.equalsIgnoreCase(artist)) removed = true;
            else kept.add(a);
        }
        if (!removed) kept.add(0, artist);
        save(c, kept);
        return !removed;
    }

    public void addAll(Context c, List<String> artists) {
        List<String> kept = all(c);
        for (String artist : artists) {
            boolean known = false;
            for (String a : kept) if (a.equalsIgnoreCase(artist)) known = true;
            if (!known) kept.add(artist);
        }
        save(c, kept);
    }

    private void save(Context c, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String a : names) sb.append(a).append('\n');
        Prefs.setString(c, key, sb.toString());
    }
}
