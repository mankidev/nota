package com.nota.data;

import android.content.Context;

import com.nota.model.Track;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

public class Prefetch {
    private static final int AHEAD = 2;

    private static Prefetch instance;

    public static synchronized Prefetch get(Context c) {
        if (instance == null) instance = new Prefetch(c.getApplicationContext());
        return instance;
    }

    private static final class Job {
        final Track track;
        final String id;

        Job(Track track, String id) {
            this.track = track;
            this.id = id;
        }

        boolean whole() {
            return track != null;
        }
    }

    private final Context app;
    private final LinkedBlockingDeque<Job> pending = new LinkedBlockingDeque<Job>();

    private final Set<String> asked = new HashSet<String>();
    private final Set<String> inFlight = new HashSet<String>();

    private Prefetch(Context app) {
        this.app = app;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "nota-warm");
        worker.setDaemon(true);
        worker.start();
    }

    public void around(Track next, Track previous, List<Track> upcoming) {
        if (!Connectivity.isOnline(app)) return;
        synchronized (this) {
            for (Iterator<Job> it = pending.iterator(); it.hasNext(); ) {
                Job job = it.next();
                if (!job.whole()) continue;
                it.remove();
                inFlight.remove(job.track.key());
            }
            offer(previous);
            offer(next);
        }
        warm(upcoming);
    }

    private void offer(Track t) {
        if (t == null || !inFlight.add(t.key())) return;
        pending.addFirst(new Job(t, YtApi.videoId(t)));
    }

    private void warm(List<Track> upcoming) {
        int queued = 0;
        for (Track t : upcoming) {
            if (queued >= AHEAD) return;
            String id = YtApi.videoId(t);
            if (id == null) continue;
            queued++;
            if (StreamProxy.get(app).cached(id)) continue;
            synchronized (this) {
                if (inFlight.contains(t.key())) continue;
                if (!asked.add(id)) continue;
                pending.add(new Job(null, id));
            }
        }
    }

    private void loop() {
        while (true) {
            Job job;
            try {
                job = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            if (job.whole()) fetch(job);
            else warmOne(job);
        }
    }

    private void fetch(Job job) {
        try {
            ArtLoader loader = ArtLoader.get(app);
            loader.load(job.track, loader.playerSize());
            Lyrics.loadBlocking(app, job.track);
            if (job.id != null) {
                StreamProxy proxy = StreamProxy.get(app);
                proxy.preloadHead(job.id);
                proxy.preload(job.id);
            }
        } catch (Exception e) {
            CrashLog.note(app, "ready failed: " + e);
        } finally {
            synchronized (this) {
                inFlight.remove(job.track.key());
            }
        }
    }

    private void warmOne(Job job) {
        boolean ready;
        try {
            ready = YtApi.streamUrl(app, job.id) != null;
        } catch (Exception e) {
            CrashLog.note(app, "warm failed: " + e);
            ready = false;
        }
        if (!ready) synchronized (this) {
            asked.remove(job.id);
        }
    }
}
