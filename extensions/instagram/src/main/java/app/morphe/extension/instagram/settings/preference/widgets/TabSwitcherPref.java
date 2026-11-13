/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A row of buttons in one rounded track, where exactly one button is selected, like the 1 to 5 switch of a
 * lighting app: the selected button has a rounded highlight that glides to the button that is tapped. It is a
 * page switcher: the screen that holds it shows only the rows that belong to the selected button (see
 * {@code ScreenBuilder.buildNavigationSection}). Nothing is saved by this row itself; the owner is told which
 * button was tapped through {@link Listener}.
 */
public class TabSwitcherPref extends Preference {

    /** Told when another button than the selected one is tapped. */
    public interface Listener {
        void onTabSelected(int index);
    }

    private static final int HEIGHT_DP = 44;
    private static final int TRACK_INSET_DP = 4;
    private static final int GLIDE_MS = 220;

    private final CharSequence[] labels;
    private final Listener listener;
    private final TextView[] buttons;
    private int selected;

    private LinearLayout buttonRow;
    private View highlight;

    public TabSwitcherPref(Context context, CharSequence[] labels, int selected, Listener listener) {
        super(context);
        this.labels = labels;
        this.listener = listener;
        this.buttons = new TextView[labels.length];
        this.selected = Math.max(0, Math.min(selected, labels.length - 1));
        // Nothing to store, and the row itself is not tapped: the buttons inside it are.
        setPersistent(false);
        setSelectable(false);
    }

    /** The index of the selected button. */
    public int getSelectedIndex() {
        return selected;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        int page = InstagramPreferenceStyle.backgroundColor() | 0xFF000000;
        boolean dark = Color.luminance(page) < 0.5f;
        int accent = InstagramPreferenceStyle.selectionColor();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(
                InstagramPreferenceStyle.dp(context, 15),
                InstagramPreferenceStyle.dp(context, 10),
                InstagramPreferenceStyle.dp(context, 15),
                InstagramPreferenceStyle.dp(context, 10)
        );
        row.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // The track: a soft pill a little lighter (dark page) or darker (light page) than the page.
        FrameLayout track = new FrameLayout(context);
        int inset = InstagramPreferenceStyle.dp(context, TRACK_INSET_DP);
        track.setPadding(inset, inset, inset, inset);
        GradientDrawable trackBackground = new GradientDrawable();
        trackBackground.setColor(dark ? mix(page, Color.WHITE, 0.10f) : mix(page, Color.BLACK, 0.06f));
        trackBackground.setCornerRadius(InstagramPreferenceStyle.dp(context, 100));
        track.setBackground(trackBackground);
        row.addView(track, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // The highlight behind the selected button: a tint of the accent colour with an accent edge. It is as
        // wide as one button (set once the row is laid out) and glides when another button is selected.
        highlight = new View(context);
        GradientDrawable highlightBackground = new GradientDrawable();
        highlightBackground.setColor((accent & 0x00FFFFFF) | 0x33000000);
        highlightBackground.setStroke(
                Math.max(1, Math.round(1.5f * context.getResources().getDisplayMetrics().density)),
                accent
        );
        highlightBackground.setCornerRadius(InstagramPreferenceStyle.dp(context, 100));
        highlight.setBackground(highlightBackground);
        track.addView(highlight, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        track.addView(buttonRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                InstagramPreferenceStyle.dp(context, HEIGHT_DP)
        ));

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView button = new TextView(context);
            button.setText(labels[i]);
            button.setContentDescription(labels[i]);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setGravity(Gravity.CENTER);
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setPadding(
                    InstagramPreferenceStyle.dp(context, 6),
                    0,
                    InstagramPreferenceStyle.dp(context, 6),
                    0
            );
            button.setClickable(true);
            button.setFocusable(true);
            button.setOnClickListener(v -> select(index));
            buttonRow.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            buttons[i] = button;
        }

        // Once the buttons have their width, the highlight takes the width of one of them and sits on the
        // selected one (without gliding: nothing was moved by the person).
        buttonRow.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (width <= 0 || labels.length == 0) return;
            int buttonWidth = Math.round(width / (float) labels.length);
            ViewGroup.LayoutParams params = highlight.getLayoutParams();
            if (params.width != buttonWidth) {
                params.width = buttonWidth;
                highlight.setLayoutParams(params);
            }
            placeHighlight(false);
        });

        return row;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        showSelection(false);
    }

    private void select(int index) {
        if (index == selected) return;
        selected = index;
        showSelection(true);
        if (listener != null) listener.onTabSelected(index);
    }

    /** Colours the buttons for the selected one and moves the highlight to it. */
    private void showSelection(boolean glide) {
        int selectedColor = InstagramPreferenceStyle.primaryTextColor();
        int otherColor = InstagramPreferenceStyle.secondaryTextColor();
        for (int i = 0; i < buttons.length; i++) {
            TextView button = buttons[i];
            if (button == null) continue;
            boolean on = i == selected;
            button.setSelected(on);
            button.setTextColor(on ? selectedColor : otherColor);
            button.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        placeHighlight(glide);
    }

    private void placeHighlight(boolean glide) {
        if (highlight == null || buttonRow == null) return;
        int width = buttonRow.getWidth();
        if (width <= 0 || labels.length == 0) return;
        float step = width / (float) labels.length;
        // In a right-to-left layout the first button is on the right, so the highlight moves the other way.
        float direction = buttonRow.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1f : 1f;
        float target = direction * step * selected;
        highlight.animate().cancel();
        if (glide) {
            highlight.animate()
                    .translationX(target)
                    .setDuration(GLIDE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            highlight.setTranslationX(target);
        }
    }

    private static int mix(int from, int to, float amount) {
        float keep = 1f - amount;
        return Color.argb(255,
                Math.round(Color.red(from) * keep + Color.red(to) * amount),
                Math.round(Color.green(from) * keep + Color.green(to) * amount),
                Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }
}
