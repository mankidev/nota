package com.nota.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

public class LockScrollView extends ScrollView {
    private boolean locked;

    public LockScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setLocked(boolean value) {
        locked = value;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return !locked && super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return !locked && super.onTouchEvent(event);
    }
}
