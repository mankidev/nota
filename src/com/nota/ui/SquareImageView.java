package com.nota.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

public class SquareImageView extends ImageView {
    public SquareImageView(Context c) {
        super(c);
    }

    public SquareImageView(Context c, AttributeSet a) {
        super(c, a);
    }

    public SquareImageView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int side = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            side = Math.min(side, MeasureSpec.getSize(heightSpec));
        }
        int square = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
        super.onMeasure(square, square);
    }
}
