package com.nota.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nota.R;

import java.util.ArrayList;
import java.util.List;

public class Sheet {
    public interface OnPick {
        void picked(int id);
    }

    private static class Item {
        final int id;
        final int icon;
        final CharSequence label;

        Item(int id, int icon, CharSequence label) {
            this.id = id;
            this.icon = icon;
            this.label = label;
        }
    }

    private final Activity host;
    private final List<Item> items = new ArrayList<Item>();
    private View header;

    public Sheet(Activity host) {
        this.host = host;
    }

    public Sheet header(View view) {
        header = view;
        return this;
    }

    public Sheet add(int id, int iconRes, CharSequence label) {
        items.add(new Item(id, iconRes, label));
        return this;
    }

    public Sheet add(int id, int iconRes, int labelRes) {
        return add(id, iconRes, host.getString(labelRes));
    }

    public void show(final OnPick pick) {
        final Dialog dialog = new Dialog(host, R.style.NotaTheme_Dialog);
        LayoutInflater inflater = LayoutInflater.from(host);
        View root = inflater.inflate(R.layout.sheet_menu, null, false);

        FrameLayout headerBox = (FrameLayout) root.findViewById(R.id.sheet_header);
        if (header == null) {
            headerBox.setVisibility(View.GONE);
        } else {
            headerBox.addView(header);
        }

        LinearLayout box = (LinearLayout) root.findViewById(R.id.sheet_items);
        for (Item item : items) {
            final int id = item.id;
            View row = inflater.inflate(R.layout.row_sheet_item, box, false);
            ImageView icon = (ImageView) row.findViewById(R.id.icon);
            icon.setImageResource(item.icon);
            icon.setColorFilter(host.getColor(R.color.text_primary));
            ((TextView) row.findViewById(R.id.label)).setText(item.label);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dialog.dismiss();
                    pick.picked(id);
                }
            });
            box.addView(row);
        }

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(R.style.SheetAnimation);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);
        }
        dialog.show();
    }
}
