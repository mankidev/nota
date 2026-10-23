package com.nota.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Connectivity;
import com.nota.data.Db;
import com.nota.data.PrefList;
import com.nota.data.LessAi;
import com.nota.data.MediaLibrary;
import com.nota.data.Recommender;
import com.nota.data.YtApi;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class HomePage extends Page implements MediaLibrary.Listener, Playback.Listener {
    private static final int SHELF = 5;
    private static final int FOR_YOU_PAGES = 3;
    private static final int SLIDER = 12;
    private static final int MOOD = 20;
    private static final int MOOD_ARTISTS = 3;
    private static final int MIXES = 8;
    private static final int FRESH_ARTISTS = 3;
    private static final int TASTE = 5;
    private static final long FOR_YOU_FRESH_MS = 2L * 60 * 60 * 1000;

    private LinearLayout sections;
    private ScrollView scroll;
    private View empty;

    private final long mixDraw = new Random().nextLong();

    private List<Track> forYou = new ArrayList<Track>();
    private String askedFor;
    private long askedAt;
    private boolean asking;

    private List<Track> fresh = new ArrayList<Track>();
    private String freshFor;
    private String askingFresh;

    private String mood;
    private String moodShown;
    private String asked;
    private List<Track> moodTracks = new ArrayList<Track>();
    private int builtAt = -1;
    private String playedFrom;
    private boolean lessAiAt;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_home, parent, false);
        sections = (LinearLayout) root.findViewById(R.id.sections);
        empty = root.findViewById(R.id.empty);
        root.findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.openDiscoverSearch();
            }
        });
        scroll = (ScrollView) root.findViewById(R.id.home_scroll);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                host.setTintScroll(y);
            }
        });
        MediaLibrary.get().addListener(this);
        Playback.get(host).addListener(this);
        refresh();
        loadTint();
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.tab_home);
    }

    @Override
    public boolean hidesTopBar() {
        return true;
    }

    @Override
    public void onShow() {
        Db db = Db.get(host);
        if (LessAi.on(host) != lessAiAt) {
            forgetAnswers();
            refresh();
        } else if (db.revision() != builtAt || !listenedTo(db).equals(playedFrom)) {
            refresh();
        }
        loadTint();
    }

    @Override
    public void onHide() {
        host.setTint(null);
    }

    @Override
    public void onOnline(boolean online) {
        if (online) {
            if (moodTracks.isEmpty()) moodShown = null;
            if (fresh.isEmpty()) freshFor = null;
            if (forYou.isEmpty()) askedFor = null;
        }
        refresh();
    }

    private void forgetAnswers() {
        moodShown = null;
        freshFor = null;
        askedFor = null;
    }

    public void onLibraryChanged() {
        refresh();
    }

    public void onTrackChanged(Track track) {
        loadTint();
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }

    private void loadTint() {
        if (!visible()) return;
        final Track t = Playback.get(host).current();
        if (t == null) {
            paint(0);
            return;
        }
        Ui.coverColor(host, t, new Ui.ColorCallback() {
            public void onColor(int colour) {
                if (sections == null || !visible() || Playback.get(host).current() != t) return;
                paint(colour);
            }
        });
    }

    private void paint(int colour) {
        host.setHeaderTint(colour);
        if (scroll != null) host.setTintScroll(scroll.getScrollY());
    }

    private String listenedTo(Db db) {
        return db.mostPlayedKeys(SLIDER) + "|" + db.recentKeys(SHELF);
    }

    private void refresh() {
        if (sections == null) return;
        sections.removeAllViews();
        Db db = Db.get(host);
        builtAt = db.revision();
        playedFrom = listenedTo(db);
        lessAiAt = LessAi.on(host);

        boolean online = Connectivity.isOnline(host);
        if (online) addMoods();
        int beforeShelves = sections.getChildCount();

        if (online && mood != null) {
            addMood();
        } else {
            askForYou(db);
            List<Track> faces = online ? artistFaces(db) : new ArrayList<Track>();
            if (online) {
                askForFresh(faces, db);
                addShelf(host.getString(R.string.new_releases), fresh, 1, null);
            }

            List<Track> played = MediaLibrary.resolve(host, db.mostPlayedKeys(SLIDER));
            addSlider(R.string.on_repeat, played,
                    TrackListPage.mostPlayed(host.getString(R.string.on_repeat)));

            addPagedShelf(host.getString(R.string.for_you),
                    MediaLibrary.playableOnly(host, forYou), SHELF, FOR_YOU_PAGES);

            if (online) {
                List<Track> seedPool = new ArrayList<Track>(played);
                seedPool.addAll(forYou);
                addMixes(host.getString(R.string.mixes_for_you), seedPool);
            }

            addArtists(faces);

            addShelf(host.getString(R.string.recently_played),
                    MediaLibrary.resolve(host, db.recentKeys(SHELF)), SHELF,
                    TrackListPage.recent(host.getString(R.string.recently_played)));
            addShelf(host.getString(R.string.favorites),
                    MediaLibrary.resolve(host, db.favoriteKeys()), SHELF,
                    TrackListPage.favorites(host.getString(R.string.favorites)));
        }

        empty.setVisibility(sections.getChildCount() == beforeShelves ? View.VISIBLE : View.GONE);
    }

    private void addMoods() {
        View box = LayoutInflater.from(host).inflate(
                R.layout.item_home_moods, sections, false);
        LinearLayout row = (LinearLayout) box.findViewById(R.id.mood_row);
        if (!LessAi.on(host)) addLessAiChip(row);
        for (String name : host.getResources().getStringArray(R.array.mood_names)) {
            final String picked = name;
            TextView chip = (TextView) LayoutInflater.from(host).inflate(
                    R.layout.item_mood_chip, row, false);
            chip.setText(picked);
            chip.setSelected(picked.equals(mood));
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mood = picked.equals(mood) ? null : picked;
                    refresh();
                }
            });
            row.addView(chip);
        }
        sections.addView(box);
    }

    private void addLessAiChip(final LinearLayout row) {
        final TextView chip = (TextView) LayoutInflater.from(host).inflate(
                R.layout.item_mood_chip, row, false);
        chip.setText(R.string.less_ai);
        chip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                chip.setOnClickListener(null);
                chip.setSelected(true);
                chip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0);
                chip.setCompoundDrawablePadding(Ui.dp(host, 6));
                chip.setCompoundDrawableTintList(chip.getTextColors());
                LessAi.set(host, true);
                slideAway(row, chip);
            }
        });
        row.addView(chip);
    }

    private void slideAway(final LinearLayout row, final TextView chip) {
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) chip.getLayoutParams();
        final int width = chip.getWidth();
        final int margin = lp.getMarginEnd();
        ValueAnimator collapse = ValueAnimator.ofFloat(1f, 0f);
        collapse.setDuration(260);
        collapse.setStartDelay(420);
        collapse.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                chip.setTranslationX(-(width + margin) * (1f - f));
                chip.setAlpha(f);
                lp.width = Math.round(width * f);
                lp.setMarginEnd(Math.round(margin * f));
                chip.setLayoutParams(lp);
            }
        });
        collapse.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator a) {
                row.removeView(chip);
                forgetAnswers();
                refresh();
            }
        });
        collapse.start();
    }

    private void addMood() {
        if (!mood.equals(moodShown)) {
            askForMood();
            sections.addView(LayoutInflater.from(host).inflate(
                    R.layout.item_home_skeleton, sections, false));
            return;
        }
        if (moodTracks.isEmpty()) {
            addNote(R.string.empty_search);
            return;
        }
        addMixes(host.getString(R.string.mood_playlists), moodTracks);
        addShelf(mood, moodTracks, MOOD, null);
    }

    private void askForMood() {
        final String want = mood;
        if (want.equals(asked)) return;
        asked = want;
        YtApi.mood(host, want, want + " " + host.getString(R.string.mood_query), moodArtists(),
                new YtApi.TrackCallback() {
                    public void onTracks(List<Track> tracks) {
                        deliverMood(want, tracks);
                    }

                    public void onError() {
                        deliverMood(want, new ArrayList<Track>());
                    }
                });
    }

    private List<String> moodArtists() {
        Db db = Db.get(host);
        List<Track> sources = new ArrayList<Track>(db.topArtists(MOOD_ARTISTS));
        sources.addAll(artistFaces(db));
        List<String> names = new ArrayList<String>();
        Set<String> seen = new HashSet<String>(PrefList.MUTED.keys(host));
        for (Track t : sources) {
            if (TextUtils.isEmpty(t.artist)) continue;
            if (!seen.add(t.artist.toLowerCase(Locale.ROOT))) continue;
            names.add(t.artist);
            if (names.size() == MOOD_ARTISTS) break;
        }
        return names;
    }

    private void deliverMood(String want, List<Track> tracks) {
        if (sections == null) return;
        if (want.equals(asked)) asked = null;
        if (!want.equals(mood)) return;
        moodTracks = tracks;
        moodShown = want;
        refresh();
    }

    private void addNote(int textRes) {
        TextView note = (TextView) LayoutInflater.from(host).inflate(
                R.layout.item_home_note, sections, false);
        note.setText(textRes);
        sections.addView(note);
    }

    private void askForFresh(List<Track> faces, Db db) {
        final List<String> who = freshArtists(faces, db);
        if (who.isEmpty()) return;
        final String asking = TextUtils.join("\n", who);
        if (asking.equals(freshFor) || asking.equals(askingFresh)) return;
        askingFresh = asking;
        YtApi.latestReleases(host, who, new YtApi.TrackCallback() {
            public void onTracks(List<Track> tracks) {
                deliverFresh(asking, tracks);
            }

            public void onError() {
                deliverFresh(asking, new ArrayList<Track>());
            }
        });
    }

    private List<String> freshArtists(List<Track> faces, Db db) {
        List<String> who = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        List<Track> sources = new ArrayList<Track>(db.topArtists(1));
        sources.addAll(faces);
        for (Track t : sources) {
            if (TextUtils.isEmpty(t.artist)) continue;
            if (!seen.add(t.artist.toLowerCase(Locale.ROOT))) continue;
            who.add(t.artist);
            if (who.size() == FRESH_ARTISTS) break;
        }
        return who;
    }

    private void deliverFresh(String asking, List<Track> tracks) {
        if (sections == null) return;
        if (asking.equals(askingFresh)) askingFresh = null;
        fresh = tracks;
        freshFor = asking;
        refresh();
    }

    private void askForYou(Db db) {
        String taste = TextUtils.join(",", db.recentlyEnjoyed(TASTE));
        boolean old = System.currentTimeMillis() - askedAt > FOR_YOU_FRESH_MS;
        if (asking || taste.length() == 0 || (taste.equals(askedFor) && !old)) return;
        asking = true;
        askedFor = taste;
        askedAt = System.currentTimeMillis();
        Recommender.forTaste(host, SHELF * FOR_YOU_PAGES, new Recommender.Callback() {
            public void onTracks(List<Track> tracks) {
                asking = false;
                if (tracks.isEmpty() || sections == null) return;
                forYou = tracks;
                refresh();
            }
        });
    }

    private void addSlider(int titleRes, List<Track> tracks, final Page seeAll) {
        if (tracks.isEmpty()) return;
        final List<Track> shown = new ArrayList<Track>(
                tracks.subList(0, Math.min(SLIDER, tracks.size())));
        View slider = LayoutInflater.from(host).inflate(
                R.layout.item_home_slider, sections, false);
        TextView label = (TextView) slider.findViewById(R.id.section_title);
        label.setText(titleRes);
        if (seeAll == null) {
            label.setBackground(null);
        } else {
            label.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(seeAll);
                }
            });
        }

        LinearLayout row = (LinearLayout) slider.findViewById(R.id.slider_row);
        ArtLoader loader = ArtLoader.get(host);
        int artPx = host.getResources().getDimensionPixelSize(R.dimen.card);
        float radius = host.getResources().getDimension(R.dimen.art_radius);
        for (int i = 0; i < shown.size(); i++) {
            final int index = i;
            Track t = shown.get(i);
            View card = LayoutInflater.from(host).inflate(R.layout.item_cover_card, row, false);
            ImageView art = (ImageView) card.findViewById(R.id.art);
            Ui.round(art, radius);
            loader.bind(art, t, artPx, R.drawable.ic_note, Ui.dp(host, 34));
            ((TextView) card.findViewById(R.id.title)).setText(t.title);
            ((TextView) card.findViewById(R.id.subtitle)).setText(Ui.artistOr(host, t.artist));
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Playback.get(host).playSingle(shown.get(index));
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    TrackMenu.show(host, v, shown.get(index), 0, null);
                    return true;
                }
            });
            row.addView(card);
        }
        sections.addView(slider);
    }

    private void addMixes(CharSequence title, List<Track> pool) {
        List<Track> candidates = new ArrayList<Track>();
        Set<String> seen = new HashSet<String>();
        for (Track t : pool) {
            if (!YtApi.isYouTube(t)) continue;
            String who = TextUtils.isEmpty(t.artist) ? t.title : t.artist;
            if (seen.add(who.toLowerCase(Locale.ROOT))) candidates.add(t);
        }
        Collections.shuffle(candidates, new Random(mixDraw));
        List<Track> seeds = candidates.size() > MIXES
                ? new ArrayList<Track>(candidates.subList(0, MIXES)) : candidates;
        if (seeds.isEmpty()) return;

        View slider = LayoutInflater.from(host).inflate(
                R.layout.item_home_slider, sections, false);
        TextView label = (TextView) slider.findViewById(R.id.section_title);
        label.setText(title);
        label.setBackground(null);

        LinearLayout row = (LinearLayout) slider.findViewById(R.id.slider_row);
        ArtLoader loader = ArtLoader.get(host);
        int artPx = host.getResources().getDimensionPixelSize(R.dimen.card);
        float radius = host.getResources().getDimension(R.dimen.art_radius);
        for (final Track seed : seeds) {
            final String name = host.getString(R.string.mix_of, Ui.artistOr(host, seed.artist));
            View card = LayoutInflater.from(host).inflate(R.layout.item_cover_card, row, false);
            ImageView art = (ImageView) card.findViewById(R.id.art);
            Ui.round(art, radius);
            loader.bind(art, seed, artPx, R.drawable.ic_playlist, Ui.dp(host, 34));
            Collage.bindMix(art, seed, artPx);
            ((TextView) card.findViewById(R.id.title)).setText(name);
            ((TextView) card.findViewById(R.id.subtitle)).setText(seed.title);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(TrackListPage.forMix(seed, name));
                }
            });
            row.addView(card);
        }
        sections.addView(slider);
    }

    private List<Track> artistFaces(Db db) {
        List<Track> faces = new ArrayList<Track>();
        Set<String> seen = new HashSet<String>(PrefList.MUTED.keys(host));
        List<String> follows = PrefList.FOLLOWS.all(host);
        Map<String, Track> known = db.topByArtists(follows);
        for (String name : follows) {
            Track face = known.get(name);
            if (face != null && seen.add(name.toLowerCase(Locale.ROOT))) faces.add(face);
        }
        Set<String> shown = new HashSet<String>();
        for (Track t : db.topArtists(SLIDER)) {
            if (t.artist == null) continue;
            String name = t.artist.toLowerCase(Locale.ROOT);
            if (seen.contains(name)) continue;
            if (shown.add(t.artistChannel != null ? t.artistChannel : name)) faces.add(t);
        }
        return faces;
    }

    private void addArtists(List<Track> faces) {
        if (faces.isEmpty()) return;
        View slider = LayoutInflater.from(host).inflate(
                R.layout.item_home_slider, sections, false);
        TextView label = (TextView) slider.findViewById(R.id.section_title);
        label.setText(R.string.loved_artists);
        label.setBackground(null);

        LinearLayout row = (LinearLayout) slider.findViewById(R.id.slider_row);
        ArtLoader loader = ArtLoader.get(host);
        int artPx = host.getResources().getDimensionPixelSize(R.dimen.card);
        for (final Track face : faces) {
            View card = LayoutInflater.from(host).inflate(R.layout.item_artist_card, row, false);
            ImageView art = (ImageView) card.findViewById(R.id.art);
            Ui.round(art, artPx / 2f);
            loader.bind(art, face, artPx, R.drawable.ic_artist, Ui.dp(host, 34));
            ((TextView) card.findViewById(R.id.title)).setText(face.artist);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(new ArtistPage(face.artist, face));
                }
            });
            row.addView(card);
        }
        sections.addView(slider);
    }

    private void addShelf(CharSequence title, final List<Track> tracks, int limit,
                          final Page seeAll) {
        if (tracks.isEmpty()) return;
        final List<Track> shown = tracks.subList(0, Math.min(limit, tracks.size()));
        View shelf = LayoutInflater.from(host).inflate(
                R.layout.item_home_section, sections, false);
        TextView label = (TextView) shelf.findViewById(R.id.section_title);
        label.setText(title);
        if (seeAll == null) {
            label.setBackground(null);
        } else {
            label.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(seeAll);
                }
            });
        }

        LinearLayout rows = (LinearLayout) shelf.findViewById(R.id.section_rows);
        TrackAdapter adapter = new TrackAdapter(host, shown);
        for (int i = 0; i < shown.size(); i++) {
            rows.addView(trackRow(adapter, shown, i, rows));
        }
        sections.addView(shelf);
    }

    private void addPagedShelf(CharSequence title, List<Track> tracks, int rows, int pages) {
        if (tracks.isEmpty()) return;
        final List<Track> shown = tracks.subList(0, Math.min(rows * pages, tracks.size()));
        View shelf = LayoutInflater.from(host).inflate(
                R.layout.item_home_pager, sections, false);
        ((TextView) shelf.findViewById(R.id.section_title)).setText(title);

        LinearLayout strip = (LinearLayout) shelf.findViewById(R.id.pager_row);
        TrackAdapter adapter = new TrackAdapter(host, shown);
        for (int start = 0; start < shown.size(); start += rows) {
            LinearLayout page = new LinearLayout(host);
            page.setOrientation(LinearLayout.VERTICAL);
            for (int i = start; i < Math.min(start + rows, shown.size()); i++) {
                page.addView(trackRow(adapter, shown, i, page));
            }
            strip.addView(page);
        }
        sections.addView(shelf);
    }

    private View trackRow(TrackAdapter adapter, final List<Track> shown, final int index,
                          ViewGroup parent) {
        View row = adapter.getView(index, null, parent);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Playback.get(host).playSingle(shown.get(index));
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                TrackMenu.show(host, v, shown.get(index), 0, null);
                return true;
            }
        });
        return row;
    }

    @Override
    public void onDestroy() {
        MediaLibrary.get().removeListener(this);
        Playback.get(host).removeListener(this);
        sections = null;
    }
}
