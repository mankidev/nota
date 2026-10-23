package com.nota.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public abstract class Page {
    protected MainActivity host;
    private View root;

    final View attach(MainActivity host, ViewGroup parent) {
        this.host = host;
        if (root == null) {
            root = onCreateView(LayoutInflater.from(host), parent);
        }
        return root;
    }

    public final View root() {
        return root;
    }

    public final boolean visible() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    protected abstract View onCreateView(LayoutInflater inflater, ViewGroup parent);

    public abstract String title();

    public boolean showsBack() {
        return false;
    }

    public boolean hidesTopBar() {
        return false;
    }

    public boolean hasSearch() {
        return false;
    }

    public boolean hasOverflow() {
        return false;
    }

    public boolean hasSettings() {
        return false;
    }

    public void onShow() {
    }

    public void onHide() {
    }

    public void onOnline(boolean online) {
    }

    public boolean onBack() {
        return false;
    }

    public void onOverflow(View anchor) {
    }

    public void onSearch(String query) {
    }

    public void onDestroy() {
    }
}
