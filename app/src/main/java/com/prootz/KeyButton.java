package com.prootz;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Custom extra-key button with gold/blue theme. */
class KeyButton extends androidx.appcompat.widget.AppCompatButton {

    private boolean mActive = false;

    KeyButton(Context ctx, String label, boolean accent) {
        super(ctx);
        setText(label);
        setTextSize(11f);
        setAllCaps(false);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setPadding(dp(ctx, 3), 0, dp(ctx, 3), 0);
        // Override AppCompatButton's large default min width/height.
        setMinWidth(dp(ctx, 22));
        setMinimumWidth(dp(ctx, 22));
        setMinHeight(0);
        setMinimumHeight(0);
        setTextColor(accent ? Color.parseColor("#FFB300") : Color.parseColor("#42A5F5"));
        setBackground(makeBackground(false, accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(ctx, 2), dp(ctx, 3), dp(ctx, 2), dp(ctx, 3));
        setLayoutParams(lp);
    }

    void setActive(boolean active) {
        mActive = active;
        GradientDrawable bg = (GradientDrawable) getBackground();
        if (active) {
            bg.setColor(Color.parseColor("#1565C0"));
            bg.setStroke(dp(getContext(), 1), Color.parseColor("#42A5F5"));
        } else {
            bg.setColor(Color.parseColor("#162032"));
            bg.setStroke(dp(getContext(), 1), Color.parseColor("#1E3050"));
        }
    }

    boolean isActive() { return mActive; }

    private GradientDrawable makeBackground(boolean active, boolean accent) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(getContext(), 4));
        d.setColor(active ? Color.parseColor("#1565C0") : Color.parseColor("#162032"));
        d.setStroke(dp(getContext(), 1),
            active ? Color.parseColor("#42A5F5") : Color.parseColor("#1E3050"));
        return d;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
