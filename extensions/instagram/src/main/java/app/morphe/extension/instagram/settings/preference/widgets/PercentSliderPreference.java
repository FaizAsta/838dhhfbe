/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.content.Context;
import android.graphics.PorterDuff;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * A slider row from 0 to 100 %, with the title and the value on one line and the description under it. Used for the
 * blur and the transparency of Instagram's own floating bar. The value is read and written through {@link Access},
 * and written when a drag settles (and at once for a keyboard or accessibility step).
 */
public class PercentSliderPreference extends Preference {

    /** Where the value lives. */
    public interface Access {
        int get();

        void set(int percent);

        /** The slider is moving: the value it shows right now, before it is saved (for a live preview). */
        void live(int percent);
    }

    private final Access access;
    private TextView titleView;
    private TextView valueView;
    private TextView summaryView;
    private SeekBar seekBar;
    /** True between a touch going down and coming up, so a drag writes once at the end. */
    private boolean dragging;

    public PercentSliderPreference(Context context, Access access) {
        super(context);
        this.access = access;
        // The value lives in its own setting, written by this row when the slider settles.
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(
                InstagramPreferenceStyle.dp(context, 15),
                InstagramPreferenceStyle.dp(context, 14),
                InstagramPreferenceStyle.dp(context, 15),
                InstagramPreferenceStyle.dp(context, 14)
        );
        row.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.addView(valueView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        summaryView = new TextView(context);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = InstagramPreferenceStyle.dp(context, 2);
        row.addView(summaryView, summaryParams);

        seekBar = new SeekBar(context);
        seekBar.setMax(100);
        seekBar.setPadding(
                seekBar.getPaddingLeft(),
                InstagramPreferenceStyle.dp(context, 10),
                seekBar.getPaddingRight(),
                0
        );
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                showValue(progress);
                access.live(progress);
                // Accessibility actions and keyboard input move the slider without a touch, so they never reach
                // onStopTrackingTouch and have to be written here.
                if (!dragging) access.set(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // A drag writes once it settles, rather than on every notch it passes.
                dragging = false;
                access.set(bar.getProgress());
                showValue(bar.getProgress());
            }
        });
        row.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        return row;
    }

    private void showValue(int percent) {
        if (valueView != null) valueView.setText(percent + "%");
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        boolean enabled = isEnabled();
        int titleColor = enabled
                ? InstagramPreferenceStyle.primaryTextColor()
                : InstagramPreferenceStyle.disabledTextColor();
        int valueColor = enabled
                ? InstagramPreferenceStyle.selectionColor()
                : InstagramPreferenceStyle.disabledTextColor();
        int summaryColor = enabled
                ? InstagramPreferenceStyle.secondaryTextColor()
                : InstagramPreferenceStyle.disabledTextColor();

        if (titleView != null) {
            titleView.setText(getTitle());
            titleView.setTextColor(titleColor);
        }
        if (valueView != null) valueView.setTextColor(valueColor);
        if (summaryView != null) {
            CharSequence summary = getSummary();
            summaryView.setText(summary);
            summaryView.setVisibility(summary == null || summary.length() == 0 ? View.GONE : View.VISIBLE);
            summaryView.setTextColor(summaryColor);
        }
        if (seekBar != null) {
            seekBar.setEnabled(enabled);
            // Never move a slider under a finger that is dragging it.
            if (!dragging) seekBar.setProgress(access.get());
            seekBar.getProgressDrawable().setColorFilter(valueColor, PorterDuff.Mode.SRC_IN);
            if (seekBar.getThumb() != null) {
                seekBar.getThumb().setColorFilter(valueColor, PorterDuff.Mode.SRC_IN);
            }
            showValue(seekBar.getProgress());
        }
        view.setEnabled(enabled);
    }
}
