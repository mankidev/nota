package com.nota.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.nota.R;
import android.app.AlertDialog;
import android.content.DialogInterface;

import com.nota.data.Backup;
import com.nota.data.CacheTrim;
import com.nota.data.Connectivity;
import com.nota.data.CrashLog;
import com.nota.data.DataSaver;
import com.nota.data.Db;
import com.nota.data.Downloads;
import com.nota.data.MediaLibrary;
import com.nota.data.YtApi;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;

public class MainActivity extends Activity {
    public static final int TAB_HOME = 0;
    public static final int TAB_DISCOVER = 1;
    public static final int TAB_LIBRARY = 2;
    public static final int TAB_HISTORY = 3;
    private static final int TAB_COUNT = 4;

    public static final String EXTRA_OPEN = "open";
    public static final String OPEN_EQUALIZER = "equalizer";
    public static final String OPEN_ARTIST = "artist";
    public static final String EXTRA_ARTIST = "artist_name";

    private static final int REQ_AUDIO = 1;
    private static final int REQ_NOTIFY = 2;
    private static final int REQ_EXPORT = 3;
    private static final int REQ_IMPORT = 4;
    private static final int REQ_WRITE = 5;

    private FrameLayout content;
    private View tint;
    private View topbar;
    private TextView titleView;
    private EditText searchField;
    private ImageButton btnBack, btnSearch, btnOverflow, btnSettings;
    private View offlineBar;
    private ValueAnimator offlineFade;
    private Connectivity.Watcher watcher;
    private final View[] tabViews = new View[TAB_COUNT];
    private final Page[] tabPages = new Page[TAB_COUNT];
    private final List<Page> stack = new ArrayList<Page>();
    private int currentTab = TAB_HOME;
    private boolean searching;
    private MiniPlayer mini;

    private static final long SEARCH_DEBOUNCE_MS = 300;
    private static final long OFFLINE_FADE_MS = 220;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = new Runnable() {
        public void run() {
            Page p = current();
            if (p != null && p.root() != null) p.onSearch(searchField.getText().toString());
        }
    };

    private final MediaLibrary.Listener libraryReady = new MediaLibrary.Listener() {
        public void onLibraryChanged() {
            Playback.get(MainActivity.this).restore();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.chosen(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashLog.install(this);
        setContentView(R.layout.activity_main);
        settleSession();
        CacheTrim.run(this);

        content = (FrameLayout) findViewById(R.id.content);
        tint = findViewById(R.id.tint);
        runUnderStatusBar();
        topbar = findViewById(R.id.topbar);
        titleView = (TextView) findViewById(R.id.title);
        searchField = (EditText) findViewById(R.id.search_field);
        btnBack = (ImageButton) findViewById(R.id.btn_back);
        btnSearch = (ImageButton) findViewById(R.id.btn_search);
        btnOverflow = (ImageButton) findViewById(R.id.btn_overflow);
        btnSettings = (ImageButton) findViewById(R.id.btn_settings);
        offlineBar = findViewById(R.id.offline_bar);
        watcher = new Connectivity.Watcher(this, new Connectivity.Listener() {
            public void onOnline(boolean online) {
                bindOffline(online);
                tellPages(online);
            }
        });
        bindOffline(watcher.online());

        setupTab(R.id.tab_home, TAB_HOME, R.drawable.ic_home, R.string.tab_home);
        setupTab(R.id.tab_discover, TAB_DISCOVER, R.drawable.ic_search, R.string.tab_discover);
        setupTab(R.id.tab_library, TAB_LIBRARY, R.drawable.ic_library, R.string.tab_library);
        setupTab(R.id.tab_history, TAB_HISTORY, R.drawable.ic_timer, R.string.tab_history);

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressed();
            }
        });
        btnSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (searching) exitSearch();
                else enterSearch();
            }
        });
        btnOverflow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                current().onOverflow(v);
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                push(new SettingsPage());
            }
        });
        searchField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchHandler.removeCallbacks(searchRunnable);
                searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS);
            }

            public void afterTextChanged(Editable s) {
            }
        });

        DataSaver.get(this);
        mini = new MiniPlayer(this, (FrameLayout) findViewById(R.id.mini_holder));
        selectTab(TAB_HOME);

        MediaLibrary.get().addListener(libraryReady);
        if (hasAudioPermission(this)) {
            MediaLibrary.get().load(this, false);
            if (MediaLibrary.get().isLoaded()) Playback.get(this).restore();
        } else {
            requestAudioPermission();
            Playback.get(this).restore();
        }
        requestNotificationPermission();
        showPendingCrash();
        handleOpenIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
    }

    private void handleOpenIntent(Intent intent) {
        if (intent == null) return;
        String open = intent.getStringExtra(EXTRA_OPEN);
        if (OPEN_EQUALIZER.equals(open)) {
            intent.removeExtra(EXTRA_OPEN);
            selectTab(TAB_LIBRARY);
            push(new SettingsPage());
            push(new EqualizerPage());
        } else if (OPEN_ARTIST.equals(open)) {
            intent.removeExtra(EXTRA_OPEN);
            String name = intent.getStringExtra(EXTRA_ARTIST);
            if (name == null || name.length() == 0) return;
            Track cur = Playback.get(this).current();
            selectTab(TAB_HOME);
            push(new ArtistPage(name, cur != null && name.equals(cur.artist) ? cur : null));
        }
    }

    private void setupTab(int viewId, final int index, int iconRes, int labelRes) {
        View v = findViewById(viewId);
        tabViews[index] = v;
        ImageView icon = (ImageView) v.findViewById(R.id.tab_icon);
        icon.setImageResource(iconRes);
        icon.setContentDescription(getString(labelRes));
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if (currentTab == index && stack.size() > 1) {
                    while (stack.size() > 1) popNoRefresh();
                    showCurrent();
                } else {
                    selectTab(index);
                }
            }
        });
    }

    private Page createTab(int index) {
        switch (index) {
            case TAB_DISCOVER:
                return new DiscoverPage();
            case TAB_LIBRARY:
                return new LibraryPage();
            case TAB_HISTORY:
                return new HistoryPage();
            default:
                return new HomePage();
        }
    }

    public void selectTab(int index) {
        while (stack.size() > 1) popNoRefresh();
        if (!stack.isEmpty()) stack.get(0).onHide();
        exitSearch();
        if (tabPages[index] == null) tabPages[index] = createTab(index);
        stack.clear();
        stack.add(tabPages[index]);
        currentTab = index;
        for (int i = 0; i < tabViews.length; i++) {
            View v = tabViews[i];
            v.setSelected(i == index);
            ImageView icon = (ImageView) v.findViewById(R.id.tab_icon);
            icon.setColorFilter(getColor(i == index ? R.color.accent : R.color.text_secondary));
        }
        showCurrent();
    }

    public void push(Page page) {
        current().onHide();
        exitSearch();
        stack.add(page);
        showCurrent();
    }

    private void popNoRefresh() {
        Page p = stack.remove(stack.size() - 1);
        p.onHide();
        if (p.root() != null) content.removeView(p.root());
        p.onDestroy();
    }

    public boolean pop() {
        if (stack.size() <= 1) return false;
        popNoRefresh();
        showCurrent();
        return true;
    }

    private Page current() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private void showCurrent() {
        Page p = current();
        View v = p.attach(this, content);
        if (v.getParent() == null) {
            content.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            c.setVisibility(c == v ? View.VISIBLE : View.GONE);
        }
        p.onShow();
        updateTopBar();
    }

    public void setTint(Drawable wash) {
        setTint(wash, getResources().getDimensionPixelSize(R.dimen.wash_height));
    }

    public void setHeaderTint(int colour) {
        if (colour == 0) {
            setTint(null);
            return;
        }
        int inset = findViewById(R.id.column).getPaddingTop();
        if (inset <= 0) inset = Ui.dp(this, 24);
        int head = inset + getResources().getDimensionPixelSize(R.dimen.topbar_height);
        int fade = getResources().getDimensionPixelSize(R.dimen.wash_fade);
        int rgb = colour & 0xFFFFFF;
        setTint(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000 | rgb, rgb}), head + fade);
    }

    private void setTint(Drawable wash, int heightPx) {
        tint.getLayoutParams().height = heightPx;
        tint.setTranslationY(0);
        tint.requestLayout();
        tint.setBackground(wash);
    }

    public void setTintScroll(int scrollY) {
        tint.setTranslationY(-scrollY);
    }

    private void runUnderStatusBar() {
        getWindow().setStatusBarColor(0);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        final View column = findViewById(R.id.column);
        column.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
                return insets;
            }
        });
    }

    private void updateTopBar() {
        Page p = current();
        topbar.setVisibility(p.hidesTopBar() ? View.GONE : View.VISIBLE);
        titleView.setText(p.title());
        btnBack.setVisibility(p.showsBack() || stack.size() > 1 ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(p.hasSearch() ? View.VISIBLE : View.GONE);
        btnOverflow.setVisibility(p.hasOverflow() ? View.VISIBLE : View.GONE);
        btnSettings.setVisibility(p.hasSettings() ? View.VISIBLE : View.GONE);
    }

    private void enterSearch() {
        searching = true;
        titleView.setVisibility(View.GONE);
        searchField.setVisibility(View.VISIBLE);
        searchField.setText("");
        searchField.requestFocus();
        btnSearch.setImageResource(R.drawable.ic_close);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
    }

    public void openDiscoverSearch() {
        selectTab(TAB_DISCOVER);
        enterSearch();
    }

    public void searchFor(String term) {
        if (!searching) enterSearch();
        searchField.setText(term);
        searchField.setSelection(term.length());
        searchField.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    private void exitSearch() {
        searchHandler.removeCallbacks(searchRunnable);
        if (searching) {
            Page p = current();
            if (p != null && p.root() != null) p.onSearch("");
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
        }
        searching = false;
        searchField.setVisibility(View.GONE);
        searchField.setText("");
        titleView.setVisibility(View.VISIBLE);
        btnSearch.setImageResource(R.drawable.ic_search);
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            exitSearch();
            return;
        }
        if (current().onBack()) return;
        if (pop()) return;
        if (currentTab != TAB_HOME) {
            selectTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    private void bindOffline(boolean online) {
        boolean showing = offlineBar.getVisibility() == View.VISIBLE;
        if (online != showing) return;
        if (offlineFade != null) offlineFade.cancel();

        final int full = getResources().getDimensionPixelSize(R.dimen.offline_bar_height);
        final boolean leaving = online;
        if (!leaving) {
            offlineBar.setAlpha(0f);
            offlineBar.setVisibility(View.VISIBLE);
        }
        offlineFade = ValueAnimator.ofFloat(leaving ? 1f : 0f, leaving ? 0f : 1f);
        offlineFade.setDuration(OFFLINE_FADE_MS);
        offlineFade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float shown = (Float) a.getAnimatedValue();
                offlineBar.setAlpha(shown);
                ViewGroup.LayoutParams lp = offlineBar.getLayoutParams();
                lp.height = Math.round(full * shown);
                offlineBar.setLayoutParams(lp);
            }
        });
        offlineFade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (leaving) offlineBar.setVisibility(View.GONE);
            }
        });
        offlineFade.start();
    }

    private void tellPages(boolean online) {
        for (int i = 1; i < stack.size(); i++) stack.get(i).onOnline(online);
        for (Page p : tabPages) {
            if (p != null) p.onOnline(online);
        }
    }

    private void settleSession() {
        final Context app = getApplicationContext();
        if (!Connectivity.isOnline(app)) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                YtApi.warmSession(app);
            }
        }, "nota-session");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mini != null) mini.onResume();
        if (watcher != null) bindOffline(Connectivity.isOnline(this));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mini != null) mini.onPause();
        DataSaver.get(this).flush();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        searchHandler.removeCallbacks(searchRunnable);
        MediaLibrary.get().removeListener(libraryReady);
        if (offlineFade != null) offlineFade.cancel();
        if (watcher != null) watcher.stop();
        if (mini != null) mini.onDestroy();
        for (int i = 1; i < stack.size(); i++) stack.get(i).onDestroy();
        for (Page p : tabPages) {
            if (p != null) p.onDestroy();
        }
    }

    public void refreshTopBar() {
        updateTopBar();
    }

    public void refreshPlaylists() {
        for (Page p : stack) {
            if (p instanceof PlaylistsPage) ((PlaylistsPage) p).refresh();
        }
    }

    private void showPendingCrash() {
        final String report = CrashLog.exportPending(this);
        if (!CrashLog.fatal(report)) return;
        new AlertDialog.Builder(this, R.style.NotaTheme_Dialog)
                .setTitle(R.string.crash_title)
                .setMessage(report)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.crash_share, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, report), null));
                    }
                })
                .show();
    }

    public void exportBackup() {
        String name = "nota-" + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date())
                + ".json";
        pickDocument(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, name), REQ_EXPORT);
    }

    public void importBackup() {
        pickDocument(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), REQ_IMPORT);
    }

    private void pickDocument(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final Context app = getApplicationContext();
        if (request == REQ_EXPORT) {
            Db.background(new Runnable() {
                public void run() {
                    boolean ok;
                    try {
                        OutputStream out = app.getContentResolver().openOutputStream(uri, "wt");
                        try {
                            Backup.write(app, out);
                        } finally {
                            out.close();
                        }
                        ok = true;
                    } catch (Exception e) {
                        ok = false;
                    }
                    final boolean saved = ok;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(app, saved ? R.string.backup_saved
                                    : R.string.backup_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        } else if (request == REQ_IMPORT) {
            Db.background(new Runnable() {
                public void run() {
                    Backup.Result r;
                    try {
                        InputStream in = app.getContentResolver().openInputStream(uri);
                        try {
                            r = Backup.read(app, in);
                        } finally {
                            in.close();
                        }
                    } catch (Exception e) {
                        r = null;
                    }
                    final Backup.Result done = r;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            onImported(done);
                        }
                    });
                }
            });
        }
    }

    private void onImported(Backup.Result r) {
        if (r == null) {
            Toast.makeText(this, R.string.backup_unreadable, Toast.LENGTH_LONG).show();
            return;
        }
        String text = getString(R.string.backup_restored, r.favorites, r.plays, r.playlists);
        if (r.missingLocal > 0) {
            text += "\n" + getString(R.string.backup_missing_local, r.missingLocal);
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        if (!isFinishing() && !isDestroyed()) recreate();
    }

    public static String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasAudioPermission(Context c) {
        return c.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    public void requestAudioPermission() {
        requestPermissions(new String[]{audioPermission()}, REQ_AUDIO);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    void askMusicWrite() {
        if (Build.VERSION.SDK_INT >= 29 || checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_WRITE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            Downloads.get(this).setToMusic(true);
        }
        if (code == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                MediaLibrary.get().load(this, true);
            }
            for (Page p : tabPages) {
                if (p instanceof LibraryPage) ((LibraryPage) p).refresh();
            }
        }
    }
}
