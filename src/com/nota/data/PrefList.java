package com.nota.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PrefList {
    public static final PrefList FOLLOWS = new PrefList("followed_artists", true);
    public static final PrefList MUTED = new PrefList("muted_artists", true);
    public static final PrefList IGNORED = new PrefList("ignored_tracks", false);

    private final String key;
    private final boolean folded;

    private PrefList(String key, boolean folded) {
        this.key = key;
        this.folded = folded;
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
        for (String a : all(c)) out.add(fold(a));
        return out;
    }

    public boolean has(Context c, String name) {
        return name != null && keys(c).contains(fold(name));
    }

    private String fold(String name) {
        return folded ? name.toLowerCase(Locale.ROOT).trim() : name;
    }

    private boolean same(String a, String b) {
        return folded ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    public boolean toggle(Context c, String name) {
        List<String> kept = new ArrayList<String>();
        boolean removed = false;
        for (String a : all(c)) {
            if (same(a, name)) removed = true;
            else kept.add(a);
        }
        if (!removed) kept.add(0, name);
        save(c, kept);
        return !removed;
    }

    public void addAll(Context c, List<String> names) {
        List<String> kept = all(c);
        for (String name : names) {
            boolean known = false;
            for (String a : kept) if (same(a, name)) known = true;
            if (!known) kept.add(name);
        }
        save(c, kept);
    }

    private void save(Context c, List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String a : names) sb.append(a).append('\n');
        Prefs.setString(c, key, sb.toString());
    }
}
