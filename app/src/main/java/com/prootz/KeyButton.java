package com.prootz;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;

import com.google.android.material.color.MaterialColors;

/** Custom extra-key button, themed via Material 3 color attrs. */
class KeyButton extends androidx.appcompat.widget.AppCompatButton {

    private boolean mActive = false;
    private final boolean mAccent;

    KeyButton(Context ctx, String label, boolean accent) {
        super(ctx);
        mAccent = accent;
        setText(label);
        setTextSize(11f);
        setAllCaps(false);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setPadding(dp(ctx, 3), 0, dp(ctx, 3), 0);
        setMinWidth(dp(ctx, 22));
        setMinimumWidth(dp(ctx, 22));
        setMinHeight(0);
        setMinimumHeight(0);
        setTextColor(MaterialColors.getColor(this,
            mAccent ? com.google.android.material.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorSecondary,
            accent ? Color.parseColor("#FFB300") : Color.parseColor("#42A5F5")));
        setBackground(makeBackground(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(ctx, 2), dp(ctx, 3), dp(ctx, 2), dp(ctx, 3));
        setLayoutParams(lp);
    }

    void setActive(boolean active) {
        mActive = active;
        GradientDrawable bg = (GradientDrawable) getBackground();
        bg.setColor(MaterialColors.getColor(this,
            active ? com.google.android.material.R.attr.colorSecondaryContainer
                   : com.google.android.material.R.attr.colorSurfaceContainer,
            active ? Color.parseColor("#1565C0") : Color.parseColor("#162032")));
        bg.setStroke(dp(getContext(), 1), MaterialColors.getColor(this,
            active ? com.google.android.material.R.attr.colorSecondary
                   : com.google.android.material.R.attr.colorOutlineVariant,
            active ? Color.parseColor("#42A5F5") : Color.parseColor("#1E3050")));
    }

    boolean isActive() { return mActive; }

    private GradientDrawable makeBackground(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(getContext(), 8));
        d.setColor(MaterialColors.getColor(this,
            active ? com.google.android.material.R.attr.colorSecondaryContainer
                   : com.google.android.material.R.attr.colorSurfaceContainer,
            active ? Color.parseColor("#1565C0") : Color.parseColor("#162032")));
        d.setStroke(dp(getContext(), 1), MaterialColors.getColor(this,
            active ? com.google.android.material.R.attr.colorSecondary
                   : com.google.android.material.R.attr.colorOutlineVariant,
            active ? Color.parseColor("#42A5F5") : Color.parseColor("#1E3050")));
        return d;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
