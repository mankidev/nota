package com.nota.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.text.Layout;
import android.view.Menu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.ArtistBio;
import com.nota.data.ArtistInfo;
import com.nota.data.ArtistLinks;
import com.nota.data.ArtistPhoto;
import com.nota.data.Db;
import com.nota.data.PrefList;
import com.nota.data.YtApi;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ArtistPage extends Page implements Playback.Listener {
    private static final int LIMIT = 50;
    private static final int BANNER_FADE_MS = 320;
    private static final int BIO_LINES = 4;

    private final String name;
    private final Track face;

    private TrackAdapter adapter;
    private ListView list;
    private View header;
    private Button follow;
    private ProgressBar progress;
    private boolean searched;
    private String channel;
    private boolean lookedUp;
    private boolean bannerUp;
    private Drawable wash;

    public ArtistPage(String name, Track face) {
        this.name = name;
        this.face = face;
        this.channel = face == null ? null : face.artistChannel;
    }

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_list, parent, false);
        list = (ListView) root.findViewById(R.id.list);
        progress = (ProgressBar) root.findViewById(R.id.progress);
        root.findViewById(R.id.empty_box).setVisibility(View.GONE);
        header = inflater.inflate(R.layout.header_artist, list, false);
        list.addHeaderView(header, null, false);

        adapter = new TrackAdapter(host, new ArrayList<Track>());
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                int index = pos - list.getHeaderViewsCount();
                if (index >= 0) Playback.get(host).play(adapter.items(), index);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                int index = pos - list.getHeaderViewsCount();
                if (index < 0) return false;
                TrackMenu.show(host, view, adapter.getItem(index), 0, null);
                return true;
            }
        });
        header.findViewById(R.id.btn_play).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!adapter.items().isEmpty()) Playback.get(host).play(adapter.items(), 0);
            }
        });
        header.findViewById(R.id.btn_shuffle).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Playback.get(host).shuffleAll(adapter.items());
            }
        });
        follow = (Button) header.findViewById(R.id.btn_follow);
        follow.setVisibility(View.VISIBLE);
        bindFollow(PrefList.FOLLOWS.has(host, name));
        follow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bindFollow(PrefList.FOLLOWS.toggle(host, name));
            }
        });

        Playback.get(host).addListener(this);
        show(Db.get(host).onlineByArtist(name, channel, LIMIT));
        bindListened();
        if (channel != null) lookUp();
        loadArtist();
        return root;
    }

    private void bindFollow(boolean following) {
        follow.setText(following ? R.string.following : R.string.follow);
        follow.setBackgroundResource(following ? R.drawable.bg_button_on : R.drawable.bg_button);
        follow.setTextColor(following ? 0xFF000000 : host.getColor(R.color.accent));
    }

    private void bindListened() {
        TextView line = (TextView) header.findViewById(R.id.listened);
        long minutes = Db.get(host).listenedMs(name, channel) / 60000;
        if (minutes < 1) {
            line.setVisibility(View.GONE);
            return;
        }
        line.setText(minutes < 60
                ? host.getString(R.string.listened_m, minutes)
                : host.getString(R.string.listened_hm, minutes / 60, minutes % 60));
        line.setVisibility(View.VISIBLE);
    }

    private void lookUp() {
        if (lookedUp) return;
        lookedUp = true;
        loadInfo();
        loadLinks();
        loadBio();
    }

    private void loadInfo() {
        final TextView note = (TextView) header.findViewById(R.id.note);
        ArtistInfo.lookup(host, name, channel, new ArtistInfo.Callback() {
            public void onInfo(String line) {
                if (adapter == null || line.length() == 0) return;
                note.setText(line);
                note.setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadLinks() {
        final View box = header.findViewById(R.id.links_box);
        final LinearLayout row = (LinearLayout) header.findViewById(R.id.links);
        ArtistLinks.lookup(host, name, channel, new ArtistLinks.Callback() {
            public void onLinks(List<ArtistLinks.Link> links) {
                if (adapter == null || links.isEmpty()) return;
                row.removeAllViews();
                for (ArtistLinks.Link link : links) row.addView(chip(row, link));
                box.setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadBio() {
        final TextView bio = (TextView) header.findViewById(R.id.bio);
        final TextView more = (TextView) header.findViewById(R.id.bio_more);
        View.OnClickListener open = new View.OnClickListener() {
            public void onClick(View v) {
                bio.setMaxLines(Integer.MAX_VALUE);
                more.setVisibility(View.GONE);
            }
        };
        bio.setOnClickListener(open);
        more.setOnClickListener(open);
        ArtistBio.lookup(host, name, channel, new ArtistBio.Callback() {
            public void onBio(String text) {
                if (adapter == null || text.length() == 0) return;
                bio.setText(text);
                bio.setVisibility(View.VISIBLE);
                bio.post(new Runnable() {
                    public void run() {
                        Layout laid = bio.getLayout();
                        if (adapter == null || laid == null) return;
                        if (laid.getLineCount() < BIO_LINES
                                || laid.getEllipsisCount(BIO_LINES - 1) == 0) return;
                        more.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private View chip(LinearLayout row, final ArtistLinks.Link link) {
        View view = LayoutInflater.from(host).inflate(R.layout.item_link, row, false);
        ((ImageView) view.findViewById(R.id.icon)).setImageResource(icon(link.label));
        TextView label = (TextView) view.findViewById(R.id.label);
        label.setText(link.label);
        label.setPaintFlags(label.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        view.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent go = new Intent(Intent.ACTION_VIEW, Uri.parse(link.url));
                if (go.resolveActivity(host.getPackageManager()) != null) host.startActivity(go);
            }
        });
        return view;
    }

    private static int icon(String label) {
        if (label.equals("Spotify")) return R.drawable.ic_link_spotify;
        if (label.equals("YouTube")) return R.drawable.ic_link_youtube;
        if (label.equals("YouTube Music")) return R.drawable.ic_link_youtubemusic;
        if (label.equals("Instagram")) return R.drawable.ic_link_instagram;
        if (label.equals("X")) return R.drawable.ic_link_x;
        if (label.equals("Facebook")) return R.drawable.ic_link_facebook;
        if (label.equals("Wikipedia")) return R.drawable.ic_link_wikipedia;
        if (label.equals("Apple Music")) return R.drawable.ic_link_applemusic;
        if (label.equals("Deezer")) return R.drawable.ic_link_deezer;
        if (label.equals("Tidal")) return R.drawable.ic_link_tidal;
        if (label.equals("SoundCloud")) return R.drawable.ic_link_soundcloud;
        if (label.equals("Bandcamp")) return R.drawable.ic_link_bandcamp;
        if (label.equals("TikTok")) return R.drawable.ic_link_tiktok;
        if (label.equals("VK")) return R.drawable.ic_link_vk;
        return R.drawable.ic_link_site;
    }

    @Override
    public String title() {
        return "";
    }

    @Override
    public boolean showsBack() {
        return true;
    }

    @Override
    public boolean hasOverflow() {
        return true;
    }

    @Override
    public void onOverflow(View anchor) {
        final boolean muted = PrefList.MUTED.has(host, name);
        PopupMenu menu = new PopupMenu(host, anchor);
        menu.getMenu().add(Menu.NONE, 1, 0, muted ? R.string.unmute_artist : R.string.mute_artist);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                boolean now = PrefList.MUTED.toggle(host, name);
                Toast.makeText(host, host.getString(
                        now ? R.string.muted_artist : R.string.unmuted_artist, name),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        menu.show();
    }

    private void loadArtist() {
        if (searched) return;
        searched = true;
        progress.setVisibility(View.VISIBLE);
        String videoId = face == null ? null : YtApi.videoId(face);
        YtApi.artist(host, name, channel, videoId, new YtApi.ArtistCallback() {
            public void onArtist(final YtApi.Artist found) {
                if (adapter == null) return;
                if (found.channel != null) channel = found.channel;
                lookUp();
                showBanner(found.banner);
                if (channel == null || found.songs == null || found.songs.isEmpty()) {
                    bindListened();
                    searchCatalogue();
                    return;
                }
                final String owner = channel;
                Db.background(new Runnable() {
                    public void run() {
                        Db db = Db.get(host);
                        db.claim(owner, found.songs);
                        final List<Track> known = db.onlineByArtist(name, owner, LIMIT);
                        host.runOnUiThread(new Runnable() {
                            public void run() {
                                if (adapter == null) return;
                                progress.setVisibility(View.GONE);
                                show(merge(known, found.songs));
                                bindListened();
                            }
                        });
                    }
                });
            }
        });
    }

    private static List<Track> merge(List<Track> known, List<Track> found) {
        List<Track> merged = new ArrayList<Track>(known);
        Set<String> seen = new HashSet<String>();
        for (Track t : merged) seen.add(t.key());
        for (Track t : found) if (seen.add(t.key())) merged.add(t);
        return merged;
    }

    private void searchCatalogue() {
        YtApi.search(host, name, new YtApi.TrackCallback() {
            public void onTracks(List<Track> found) {
                if (adapter == null) return;
                progress.setVisibility(View.GONE);
                List<Track> theirs = new ArrayList<Track>();
                for (Track t : found) {
                    if (channel == null || channel.equals(t.artistChannel)) theirs.add(t);
                }
                show(merge(channel == null ? adapter.items()
                        : Db.get(host).onlineByArtist(name, channel, LIMIT), theirs));
            }

            public void onError() {
                if (adapter == null) return;
                progress.setVisibility(View.GONE);
            }
        });
    }

    private void show(List<Track> items) {
        adapter.setItems(items);
        Track cur = Playback.get(host).current();
        adapter.setActiveKey(cur == null ? null : cur.key());
        bindHeader(items);
    }

    private void bindHeader(List<Track> items) {
        ((TextView) header.findViewById(R.id.title)).setText(name);
        Track cover = face != null ? face : items.isEmpty() ? null : items.get(0);
        loadTint(cover);
    }

    private void showBanner(String url) {
        final int w = Math.min(host.getResources().getDisplayMetrics().widthPixels, 540);
        final int h = w * host.getResources().getDimensionPixelSize(R.dimen.wash_height)
                / host.getResources().getDisplayMetrics().widthPixels;
        if (url != null) {
            loadBanner(url, w, h);
            return;
        }
        ArtistPhoto.lookup(host, name, channel, w, new ArtistPhoto.Callback() {
            public void onUrl(String photo) {
                loadBanner(photo, w, h);
            }
        });
    }

    private void loadBanner(String url, final int w, final int h) {
        if (adapter == null || url == null) return;
        ArtLoader.get(host).loadWide(url, w, h, new ArtLoader.BitmapCallback() {
            public void onBitmap(Bitmap bmp) {
                if (adapter == null || !visible()) return;
                bannerUp = true;
                dissolveTo(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(host.getResources(), Ui.band(bmp, w, h)),
                        Ui.scrim(host)}));
            }
        });
    }

    private void loadTint(final Track cover) {
        if (bannerUp) return;
        if (cover == null) {
            paint(null);
            return;
        }
        Ui.coverColor(host, cover, new Ui.ColorCallback() {
            public void onColor(int colour) {
                if (adapter == null || bannerUp || !visible()) return;
                if (colour == 0) {
                    paint(null);
                    return;
                }
                int rgb = colour & 0xFFFFFF;
                paint(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x73000000 | rgb, 0x2E000000 | rgb, rgb & 0xFFFFFF}));
            }
        });
    }

    private void paint(Drawable drawable) {
        wash = drawable;
        host.setTint(drawable);
    }

    private void dissolveTo(Drawable next) {
        Drawable from = wash != null ? wash : new ColorDrawable(0);
        TransitionDrawable crossing = new TransitionDrawable(new Drawable[]{from, next});
        crossing.setCrossFadeEnabled(true);
        wash = next;
        host.setTint(crossing);
        crossing.startTransition(BANNER_FADE_MS);
    }

    @Override
    public void onShow() {
        host.setTint(wash);
    }

    @Override
    public void onHide() {
        host.setTint(null);
    }

    public void onTrackChanged(Track track) {
        if (adapter != null) adapter.setActiveKey(track == null ? null : track.key());
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }

    @Override
    public void onDestroy() {
        Playback.get(host).removeListener(this);
        adapter = null;
    }
}
