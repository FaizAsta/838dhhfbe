/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.settings.preference.fragments.FragmentHook;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Views for the Piko dashboard: a brand card at the top of the root settings page
 * and a two-column grid of category tiles below it.
 */
public final class DashboardStyle {
    /** One hue per category so every tile keeps the same colour wherever it sits in the grid. */
    private static final int CORAL = 0xFFFF6B57;
    private static final int AMBER = 0xFFF5A524;
    private static final int LIME = 0xFF8CC63F;
    private static final int TEAL = 0xFF1FB5A8;
    private static final int SKY = 0xFF3B9EFF;
    private static final int VIOLET = 0xFF8B6CFF;
    private static final int PINK = 0xFFF0508A;
    private static final int[] BRAND_HUES = {CORAL, AMBER, LIME, TEAL, SKY, VIOLET, PINK};

    private static final int INK_ON_LIGHT = 0xFF15171C;
    private static final int PAPER_ON_DARK = 0xFFF2F3F7;

    private DashboardStyle() {
    }

    /** Marks a row of tiles so a recycled list row can be told apart from other preference rows. */
    static final class TileRow extends LinearLayout {
        TileRow(Context context) {
            super(context);
        }
    }

    // ---------- Colours ----------

    private static int hueForKey(String key) {
        if (key == null) {
            return SKY;
        }
        if (key.equals(Constants.PIKO_FRAGMENT_ADS)) return AMBER;
        if (key.equals(Constants.PIKO_FRAGMENT_FILTER_CONTENT)) return PINK;
        if (key.equals(Constants.PIKO_FRAGMENT_GHOST)) return VIOLET;
        if (key.equals(Constants.PIKO_FRAGMENT_DM)) return SKY;
        if (key.equals(Constants.PIKO_FRAGMENT_STORY)) return CORAL;
        if (key.equals(Constants.PIKO_FRAGMENT_LINKS)) return TEAL;
        if (key.equals(Constants.PIKO_FRAGMENT_DISTRACTION_FREE)) return LIME;
        if (key.equals(Constants.PIKO_FRAGMENT_MISC)) return VIOLET;
        if (key.equals(Constants.PIKO_FRAGMENT_DOWNLOAD_MEDIA)) return SKY;
        if (key.equals(Constants.PIKO_FRAGMENT_INSTANTS)) return PINK;
        if (key.equals(Constants.PIKO_FRAGMENT_ACTION_BAR)) return TEAL;
        if (key.equals(Constants.PIKO_FRAGMENT_NAV_BTNS)) return AMBER;
        if (key.equals(Constants.PIKO_FRAGMENT_DEV_OPTIONS)) return VIOLET;
        if (key.equals(Constants.PIKO_FRAGMENT_ABOUT)) return LIME;
        return BRAND_HUES[Math.abs(key.hashCode()) % BRAND_HUES.length];
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int shade(int color, float factor) {
        return Color.argb(
                Color.alpha(color),
                Math.round(Color.red(color) * factor),
                Math.round(Color.green(color) * factor),
                Math.round(Color.blue(color) * factor)
        );
    }

    private static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radiusPx);
        drawable.setColor(color);
        return drawable;
    }

    private static GradientDrawable dot(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private static int dp(Context context, float value) {
        return InstagramPreferenceStyle.dp(context, value);
    }

    // ---------- Hero ----------

    /** Brand card shown above the search bar. */
    public static View createHero(Context context) {
        boolean dark = UI.isDarkMode();
        // The card inverts the page: light card on a dark page, dark card on a light page.
        int fill = dark ? PAPER_ON_DARK : INK_ON_LIGHT;
        int ink = dark ? INK_ON_LIGHT : PAPER_ON_DARK;

        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setPaddingRelative(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 14));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(fill, dp(context, 28)));
        card.setPaddingRelative(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 22));
        wrapper.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Wordmark and the brand palette.
        LinearLayout brandRow = new LinearLayout(context);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(brandRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView wordmark = new TextView(context);
        wordmark.setText("piko");
        wordmark.setTextColor(ink);
        wordmark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
        wordmark.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        wordmark.setLetterSpacing(-0.04f);
        wordmark.setIncludeFontPadding(false);
        wordmark.setSingleLine(true);
        brandRow.addView(wordmark, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        LinearLayout palette = new LinearLayout(context);
        palette.setOrientation(LinearLayout.HORIZONTAL);
        palette.setGravity(Gravity.CENTER_VERTICAL);
        palette.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int index = 0; index < BRAND_HUES.length; index++) {
            View swatch = new View(context);
            swatch.setBackground(dot(BRAND_HUES[index]));
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(
                    dp(context, 9), dp(context, 9)
            );
            if (index > 0) {
                swatchParams.setMarginStart(dp(context, 6));
            }
            palette.addView(swatch, swatchParams);
        }
        brandRow.addView(palette, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView tagline = new TextView(context);
        tagline.setText(str("piko_dashboard_tagline"));
        tagline.setTextColor(withAlpha(ink, 0xB8));
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tagline.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        tagline.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams taglineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        taglineParams.topMargin = dp(context, 8);
        card.addView(tagline, taglineParams);

        addVersionPill(context, card, ink, versionText("piko_patch_version", patchVersion()), 18);
        addVersionPill(context, card, ink, versionText("piko_app_version", appVersion()), 6);

        return wrapper;
    }

    private static Object patchVersion() {
        try {
            return Utils.getPatchesReleaseVersion();
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to read patch version", ex);
            return null;
        }
    }

    private static Object appVersion() {
        try {
            return Utils.getAppVersionName();
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to read app version", ex);
            return null;
        }
    }

    private static CharSequence versionText(String stringKey, Object version) {
        if (version == null || TextUtils.isEmpty(String.valueOf(version))) {
            return null;
        }
        try {
            return String.format(str(stringKey), version);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to format version text", ex);
            return null;
        }
    }

    private static void addVersionPill(
            Context context,
            LinearLayout parent,
            int ink,
            CharSequence text,
            int topMarginDp
    ) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        TextView pill = new TextView(context);
        pill.setText(text);
        pill.setTextColor(withAlpha(ink, 0xD9));
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        pill.setPaddingRelative(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5));
        pill.setBackground(rounded(withAlpha(ink, 0x1F), dp(context, 99)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.START;
        params.topMargin = dp(context, topMarginDp);
        parent.addView(pill, params);
    }

    // ---------- Tiles ----------

    /** A row holding one or two category tiles. A single tile keeps half the row width. */
    static View createTileRow(
            Context context,
            DashboardRowPref.Tile first,
            DashboardRowPref.Tile second
    ) {
        TileRow row = new TileRow(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setPaddingRelative(dp(context, 10), 0, dp(context, 10), 0);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        row.addView(createTile(context, first), tileParams(context));
        if (second != null) {
            row.addView(createTile(context, second), tileParams(context));
        } else {
            View filler = new View(context);
            filler.setVisibility(View.INVISIBLE);
            row.addView(filler, tileParams(context));
        }
        return row;
    }

    private static LinearLayout.LayoutParams tileParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        );
        int margin = dp(context, 6);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private static View createTile(Context context, DashboardRowPref.Tile tile) {
        boolean dark = UI.isDarkMode();
        int radius = dp(context, 22);
        int hue = hueForKey(tile.key);

        FrameLayout tileView = new FrameLayout(context);
        tileView.setBackground(rounded(InstagramPreferenceStyle.searchBackgroundColor(), radius));
        tileView.setForeground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000),
                null,
                rounded(Color.BLACK, radius)
        ));
        tileView.setMinimumHeight(dp(context, 116));
        tileView.setClickable(true);
        tileView.setFocusable(true);
        tileView.setContentDescription(tile.title);
        tileView.setOnClickListener(view -> openCategory(tile.key));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPaddingRelative(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));
        tileView.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout badge = new FrameLayout(context);
        badge.setBackground(rounded(withAlpha(hue, dark ? 0x38 : 0x2E), dp(context, 14)));
        content.addView(badge, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));

        Drawable iconDrawable = loadIcon(tile.iconName);
        if (iconDrawable != null) {
            ImageView icon = new ImageView(context);
            icon.setImageDrawable(iconDrawable);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setColorFilter(new PorterDuffColorFilter(
                    dark ? hue : shade(hue, 0.78f),
                    PorterDuff.Mode.SRC_ATOP
            ));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            badge.addView(icon, new FrameLayout.LayoutParams(
                    dp(context, 24), dp(context, 24), Gravity.CENTER
            ));
        }

        TextView title = new TextView(context);
        title.setText(tile.title);
        title.setTextColor(InstagramPreferenceStyle.primaryTextColor());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(context, 14);
        content.addView(title, titleParams);

        return tileView;
    }

    private static Drawable loadIcon(String iconName) {
        if (iconName == null) {
            return null;
        }
        try {
            return ResourceUtils.getDrawable(iconName);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load dashboard icon " + iconName, ex);
            return null;
        }
    }

    private static void openCategory(String key) {
        try {
            FragmentHook.startFragment(key);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to open settings category " + key, ex);
        }
    }
}
