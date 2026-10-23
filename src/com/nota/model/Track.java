package com.nota.model;

public class Track {
    public static final int TYPE_LOCAL = 0;
    public static final int TYPE_ONLINE = 2;

    public int type = TYPE_LOCAL;
    public long id;
    public String title;
    public String artist;
    public String album;
    public long albumId;
    public long artistId;
    public long durationMs;
    public String data;
    public String artUrl;
    public String folder;
    public int trackNo;
    public int year;
    public long sizeBytes;
    public long dateAddedSec;
    public String sourceId;
    public String artistChannel;

    public boolean isRemote() {
        return type != TYPE_LOCAL;
    }

    public String key() {
        switch (type) {
            case TYPE_ONLINE:
                return "o:" + sourceId;
            default:
                return "l:" + id;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Track && key().equals(((Track) o).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
