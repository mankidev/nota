package com.nota.data;

import android.content.Context;

import com.nota.model.Track;

public class Signals {
    public static final int KIND_SKIP = 0;
    public static final int KIND_PARTIAL = 1;
    public static final int KIND_COMPLETE = 2;
    public static final int KIND_REPLAY = 3;
    public static final int KIND_LIKE = 4;
    public static final int KIND_SEARCH = 5;

    private static final float QUARTER = 0.25f;
    private static final float HALF = 0.5f;
    private static final float FINISHED = 0.9f;

    private static Signals instance;

    public static synchronized Signals get(Context c) {
        if (instance == null) instance = new Signals(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private String previousKey;

    private Signals(Context app) {
        this.app = app;
    }

    public void liked(Track t) {
        if (t != null) Db.get(app).recordEvent(t.key(), KIND_LIKE, 10);
    }

    public void searched(Track t) {
        if (t != null) Db.get(app).recordEvent(t.key(), KIND_SEARCH, 3);
    }

    public void ended(Track track, float heard, Track next) {
        if (track == null) return;
        String key = track.key();
        boolean looped = next != null && key.equals(next.key());

        int kind;
        int score;
        if (heard >= FINISHED) {
            kind = looped ? KIND_REPLAY : KIND_COMPLETE;
            score = looped ? 8 : 5;
        } else if (heard >= HALF) {
            kind = KIND_PARTIAL;
            score = 2;
        } else if (heard >= QUARTER) {
            kind = KIND_PARTIAL;
            score = 1;
        } else {
            kind = KIND_SKIP;
            score = -5;
        }

        final int eventKind = kind;
        final int eventScore = score;
        final String heardKey = key;
        final String pairedWith =
                previousKey != null && !previousKey.equals(key) ? previousKey : null;
        Db.background(new Runnable() {
            public void run() {
                Db db = Db.get(app);
                db.recordEvent(heardKey, eventKind, eventScore);
                if (pairedWith != null) {
                    db.bumpTransition(pairedWith, heardKey, eventScore > 0 ? 1 : -1);
                }
            }
        });
        previousKey = key;
    }
}
