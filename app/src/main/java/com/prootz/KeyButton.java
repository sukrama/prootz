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
        setTextSize(9f);
        setAllCaps(false);
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0);
        setTextColor(accent ? Color.parseColor("#FFB300") : Color.parseColor("#42A5F5"));
        setBackground(makeBackground(false, accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(ctx, 1), dp(ctx, 2), dp(ctx, 1), dp(ctx, 2));
        setLayoutParams(lp);
        setMinWidth(dp(ctx, 26));
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
        d.setCornerRadius(dp(getContext(), 6));
        d.setColor(active ? Color.parseColor("#1565C0") : Color.parseColor("#162032"));
        d.setStroke(dp(getContext(), 1),
            active ? Color.parseColor("#42A5F5") : Color.parseColor("#1E3050"));
        return d;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
