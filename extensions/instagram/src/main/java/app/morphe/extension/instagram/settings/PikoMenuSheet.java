/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import java.util.List;
import java.util.Locale;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.ResourceType;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * The menu that opens when the Piko settings button (or the launcher shortcut) is pressed.
 * <p>
 * It is a bottom sheet that slides up over the current screen, only as tall as its content,
 * with the page behind it blurred. It holds these cards:
 * <ol>
 *   <li>Piko settings: opens the full settings page.</li>
 *   <li>Developer options: opens Instagram's developer options (the Home long press screen).</li>
 *   <li>Version info: the patch version and the Instagram version (not tappable).</li>
 * </ol>
 * If the cards do not fit the screen the list scrolls inside the sheet.
 */
public final class PikoMenuSheet {

    private static final int HUE_SETTINGS = 0xFF3B9EFF;
    private static final int HUE_DEVELOPERS = 0xFF8B6CFF;
    private static final int HUE_VERSION = 0xFF8CC63F;
    private static final int SLIDE_MS = 220;
    private static final int BADGE_DP = 48;

    /**
     * Opacity of the cards (Piko menu, More profile options, Download options) when the page behind
     * is blurred. 0xFF = solid, 0x00 = invisible. Was 0xCC (80%); lower the number for more transparency.
     */
    private static final int CARD_GLASS_ALPHA = 0x80;

    /**
     * Opacity of the sheet itself (the panel behind the cards) when the page behind is blurred.
     * 0xFF = solid. Was 0xD9 (85%); lower the number for more transparency.
     */
    private static final int SHEET_GLASS_ALPHA = 0x99;

    /** Roundness of the sheet's top corners. Was 24; raise it for rounder corners. */
    private static final int SHEET_TOP_RADIUS_DP = 40;

    /** Corner radius of the cards inside the sheet. Raise it for rounder cards. */
    private static final int CARD_RADIUS_DP = 28;
    /** Blur of the screen behind the sheet when it is fully up. Was 16dp (Prism); lowered for a lighter blur. */
    private static final int BLUR_RADIUS_DP = 7;
    /** Dim over the screen behind: a light shade of about 15%, same as Prism (0x26 of 0xFF), with or without blur. */
    private static final float DIM_WITH_BLUR = 0x26 / 255f;
    private static final float DIM_NO_BLUR = 0x26 / 255f;
    /** The cards scroll inside the sheet once they would be taller than this part of the screen. */
    private static final float MAX_BODY_HEIGHT_FRACTION = 0.72f;
    /** How blurred the sheet itself is at the very start of opening and the very end of closing. */
    private static final int SHEET_BLUR_DP = 16;
    /** The sheet is fully visible once this part of the slide has passed. */
    private static final float SHEET_FADE_SPAN = 0.6f;
    /**
     * After "Piko settings" is tapped the sheet stays until the settings page has risen over it.
     * If the page never reports back in this time, the sheet slides away as it always did.
     */
    private static final int SETTINGS_COVER_TIMEOUT_MS = 1500;

    private static final int BLUR_NONE = 0;
    /** Blurs whatever is behind the sheet's window (other windows too). Needs Android 12+. */
    private static final int BLUR_WINDOW = 1;
    /** Blurs the screen the sheet sits on. Used when window blur is switched off on the phone. */
    private static final int BLUR_SCREEN = 2;

    /** The sheet that is on screen, so a second tap cannot stack a second one. */
    private static SheetBottomDialog.SlideDialog showing;

    private final Activity activity;
    private final Context context;
    private final boolean finishActivityOnClose;
    private final SheetBottomDialog.SlideDialog dialog;
    private final SheetBottomDialog.DraggableLinearLayout panel;
    private LinearLayout body;
    /** Height of the system navigation bar; the cards scroll through it instead of being cut by it. */
    private int navigationInset;
    private final int blurMode;
    private final int blurRadiusPx;
    private final int sheetBlurPx;
    private final float dimAmount;
    private int lastSheetBlur = -1;
    private Runnable settingsFallback;
    /** Null for the Piko menu itself; the cards of another list (see {@link #showOptions}) otherwise. */
    private final List<Option> options;
    private final CharSequence optionsTitle;
    /** Told when the options sheet is gone, however it closed. Null for the Piko menu itself. */
    private final DialogInterface.OnDismissListener optionsDismissListener;

    /**
     * One card of a list shown with {@link #showOptions}: a round badge (a themed icon, or a short
     * text glyph when there is no icon), a title, an optional second line and an optional chevron.
     */
    public static final class Option {
        final int hue;
        final String drawableName;
        final String glyph;
        final CharSequence title;
        final CharSequence subtitle;
        final boolean chevron;
        final Runnable action;

        public Option(int hue, String drawableName, String glyph, CharSequence title,
                      CharSequence subtitle, boolean chevron, Runnable action) {
            this.hue = hue;
            this.drawableName = drawableName;
            this.glyph = glyph;
            this.title = title;
            this.subtitle = subtitle;
            this.chevron = chevron;
            this.action = action;
        }
    }

    /** True while the menu sheet is on screen. The settings page uses it to avoid a second backdrop. */
    static boolean isShowing() {
        return showing != null && showing.isShowing();
    }

    /**
     * Called when the settings page has finished rising over the screen: the sheet under it
     * can go now, without a slide.
     */
    static void onSettingsPageCovered() {
        SheetBottomDialog.SlideDialog sheet = showing;
        if (sheet != null && sheet.isShowing()) {
            sheet.dismissInstantly();
        }
    }

    /**
     * Opens the menu over the screen that is showing now. If there is no usable screen to
     * put it on, the settings page opens directly, as it did before the menu existed.
     */
    public static void open() {
        Activity activity = Utils.getActivity();
        if (!show(activity, false)) {
            openSettings();
        }
    }

    /**
     * @param finishActivityOnClose true when the host activity exists only to hold the sheet
     *                              (the launcher shortcut), so it should close with it.
     * @return false when the sheet could not be shown.
     */
    static boolean show(Activity activity, boolean finishActivityOnClose) {
        return show(activity, finishActivityOnClose, null, null, null);
    }

    /**
     * Shows a list of cards in the same sheet as the Piko menu (same look, blur and slide).
     * Tapping a card closes the sheet and runs its action.
     *
     * @return false when the sheet could not be shown, so the caller can fall back to its own UI.
     */
    public static boolean showOptions(Context context, CharSequence title, List<Option> options) {
        return showOptions(context, title, options, null);
    }

    /**
     * Same as {@link #showOptions(Context, CharSequence, List)}, and {@code onDismiss} is called once
     * the sheet has left the screen (after a card's action has been started, when one was tapped).
     */
    public static boolean showOptions(Context context, CharSequence title, List<Option> options,
                                      DialogInterface.OnDismissListener onDismiss) {
        if (options == null || options.isEmpty()) {
            return false;
        }
        return show(activityOf(context), false, title, options, onDismiss);
    }

    private static Activity activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                break;
            }
            current = base;
        }
        return Utils.getActivity();
    }

    private static boolean show(Activity activity, boolean finishActivityOnClose,
                                CharSequence optionsTitle, List<Option> options,
                                DialogInterface.OnDismissListener optionsDismissListener) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return false;
            }
            if (showing != null && showing.isShowing()) {
                return true;
            }
            PikoMenuSheet sheet = new PikoMenuSheet(activity, finishActivityOnClose, optionsTitle, options, optionsDismissListener);
            showing = sheet.dialog;
            sheet.dialog.show();
            return true;
        } catch (Throwable t) {
            showing = null;
            Logger.printException(() -> "PikoMenuSheet show failure", t);
            return false;
        }
    }

    private static void openSettings() {
        ActivityHook.startPikoActivity(Constants.PIKO_FRAGMENT_SETTINGS, "piko_title_settings");
    }

    private PikoMenuSheet(Activity activity, boolean finishActivityOnClose,
                          CharSequence optionsTitle, List<Option> options,
                          DialogInterface.OnDismissListener optionsDismissListener) {
        this.optionsDismissListener = optionsDismissListener;
        this.options = options;
        this.optionsTitle = optionsTitle;
        this.activity = activity;
        this.finishActivityOnClose = finishActivityOnClose;
        this.context = InstagramPreferenceStyle.dialogContext(activity);
        this.blurMode = chooseBlurMode(activity, finishActivityOnClose);
        this.blurRadiusPx = dp(BLUR_RADIUS_DP);
        this.sheetBlurPx = dp(SHEET_BLUR_DP);
        this.dimAmount = blurMode == BLUR_NONE ? DIM_NO_BLUR : DIM_WITH_BLUR;

        int sheetColor = InstagramPreferenceStyle.backgroundColor();
        panel = SheetBottomDialog.createMainLayout(context, sheetColor);
        // Full width, rounded only at the top, and only as tall as its content.
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        GradientDrawable sheetBackground = new GradientDrawable();
        // A little see-through when the page behind is blurred, so it reads as glass.
        sheetBackground.setColor(isGlass() ? withAlpha(sheetColor, SHEET_GLASS_ALPHA) : sheetColor);
        float radius = dp(SHEET_TOP_RADIUS_DP);
        sheetBackground.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(sheetBackground);
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets navigation = insets.getInsets(WindowInsets.Type.navigationBars());
                left = navigation.left;
                right = navigation.right;
                bottom = navigation.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            // No bottom padding on the sheet: the scroll area runs down to the screen edge and the
            // cards scroll through the navigation bar area, so nothing is cut off by a hard edge.
            view.setPadding(left, 0, right, 0);
            if (navigationInset != bottom) {
                navigationInset = bottom;
                applyBodyPadding();
            }
            return insets;
        });

        dialog = SheetBottomDialog.createSlideDialog(context, panel, SLIDE_MS);
        panel.setDialog(dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            if (blurMode == BLUR_WINDOW) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            }
            makeNavigationBarTransparent(window, sheetColor);
        }
        // Dim and blur follow the slide: they grow while the sheet comes up and fade as it leaves.
        // The sheet itself rises out of a blur and fades in, and sinks into a blur and fades out.
        onSlideProgress(0f);
        dialog.setProgressListener(this::onSlideProgress);

        dialog.setOnDismissListener(d -> {
            if (this.settingsFallback != null) {
                this.body.removeCallbacks(this.settingsFallback);
                this.settingsFallback = null;
            }
            clearBackdrop();
            if (showing == dialog) {
                showing = null;
            }
            if (this.finishActivityOnClose && !this.activity.isFinishing()) {
                this.activity.finish();
                this.activity.overridePendingTransition(0, 0);
            }
            if (this.optionsDismissListener != null) {
                try {
                    this.optionsDismissListener.onDismiss(d);
                } catch (Throwable t) {
                    Logger.printException(() -> "Sheet dismiss listener failed", t);
                }
            }
        });
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        applyBodyPadding();
        // As tall as the cards inside it, so the sheet hugs its content. Only when there are more
        // cards than the screen can hold does it stop growing and scroll.
        CappedScrollView scroll = new CappedScrollView(context, MAX_BODY_HEIGHT_FRACTION);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(body, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        showMain();
    }

    /** Padding of the card list; the bottom part makes room for the navigation bar. */
    private void applyBodyPadding() {
        if (body != null) {
            body.setPadding(dp(16), dp(14), dp(16), dp(18) + navigationInset);
        }
    }

    // ---------- Navigation bar ----------

    /**
     * The sheet is drawn right to the bottom edge, under the system navigation bar, and the bar
     * itself has no colour of its own. Without this the dialog theme paints a dark (or grey, in
     * light mode) strip under the sheet. The sheet's own padding already keeps the cards clear
     * of the bar.
     */
    private static void makeNavigationBarTransparent(Window window, int sheetColor) {
        try {
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false);
            }
            boolean lightSheet = isLightColor(sheetColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            lightSheet ? android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                View decor = window.getDecorView();
                int flags = decor.getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = lightSheet
                            ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                            : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to make the sheet's navigation bar transparent", t);
        }
    }

    private static boolean isLightColor(int color) {
        return 299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color) > 128000;
    }

    // ---------- Blur and dim ----------

    private static int chooseBlurMode(Activity activity, boolean hostIsSheetOnly) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return BLUR_NONE;
        }
        try {
            if (Blur31.windowBlurAvailable(activity)) {
                return BLUR_WINDOW;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Window blur check failed", t);
        }
        // From the shortcut the host screen is empty, so blurring it would show nothing.
        return hostIsSheetOnly ? BLUR_NONE : BLUR_SCREEN;
    }

    private boolean isGlass() {
        return blurMode != BLUR_NONE;
    }

    /** @param shown 0 = off screen, 1 = fully in place. */
    private void onSlideProgress(float shown) {
        applyBackdrop(shown);
        applySheetEffect(shown);
    }

    /** The sheet fades in while it rises and sharpens as it settles; the same in reverse when it leaves. */
    private void applySheetEffect(float shown) {
        float amount = Math.max(0f, Math.min(1f, shown));
        try {
            float fade = Math.min(1f, amount / SHEET_FADE_SPAN);
            panel.setAlpha(fade);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int radius = Math.round(sheetBlurPx * (1f - amount) * (1f - amount));
                if (radius != lastSheetBlur) {
                    lastSheetBlur = radius;
                    Blur31.setViewBlur(panel, radius);
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to update the sheet blur", t);
        }
    }

    /** @param shown 0 = no dim or blur, 1 = full. */
    private void applyBackdrop(float shown) {
        float amount = Math.max(0f, Math.min(1f, shown));
        try {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setDimAmount(dimAmount * amount);
            }
            int radius = Math.round(blurRadiusPx * amount);
            if (blurMode == BLUR_WINDOW && window != null) {
                Blur31.setWindowBlur(window, radius);
            } else if (blurMode == BLUR_SCREEN) {
                Blur31.setScreenBlur(activity, radius);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to update the sheet backdrop", t);
        }
    }

    private void clearBackdrop() {
        if (blurMode == BLUR_SCREEN) {
            try {
                Blur31.setScreenBlur(activity, 0);
            } catch (Throwable t) {
                Logger.printException(() -> "Failed to clear the screen blur", t);
            }
        }
    }

    /** Android 12+ blur calls, kept apart so older phones never load them. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Blur31 {
        static boolean windowBlurAvailable(Activity activity) {
            WindowManager windowManager = activity.getSystemService(WindowManager.class);
            return windowManager != null && windowManager.isCrossWindowBlurEnabled();
        }

        static void setWindowBlur(Window window, int radiusPx) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.setBlurBehindRadius(radiusPx);
            window.setAttributes(attributes);
        }

        static void setViewBlur(View view, int radiusPx) {
            if (radiusPx <= 0) {
                view.setRenderEffect(null);
            } else {
                view.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
            }
        }

        static void setScreenBlur(Activity activity, int radiusPx) {
            View decor = activity.getWindow().getDecorView();
            if (radiusPx <= 0) {
                decor.setRenderEffect(null);
            } else {
                decor.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
            }
        }
    }

    // ---------- Pages ----------

    private void showMain() {
        body.removeAllViews();
        if (options != null) {
            showOptionCards();
            return;
        }

        body.addView(
                card(
                        iconBadge(HUE_SETTINGS, UI.DRAWABLE_GEAR_ICON, null),
                        str("piko_title_settings"),
                        str("piko_menu_settings_desc"),
                        true,
                        v -> launchFromCard(PikoMenuSheet::openSettings)
                ),
                cardParams(0)
        );

        if (SettingsStatus.enableDeveloperOptions && SharedPref.getBooleanPref(Settings.DEVELOPER_OPTIONS)) {
            body.addView(
                    card(
                            iconBadge(HUE_DEVELOPERS, null, "</>"),
                            str("piko_menu_developers"),
                            str("piko_menu_developers_desc"),
                            true,
                            v -> openDeveloperOptions()
                    ),
                    cardParams(10)
            );
        }

        body.addView(versionCard(), cardParams(10));
        fadeIn();
    }

    /** The cards of {@link #showOptions}: a title, then one card per option. */
    private void showOptionCards() {
        if (!TextUtils.isEmpty(optionsTitle)) {
            TextView heading = text(optionsTitle, 22, true, InstagramPreferenceStyle.primaryTextColor());
            heading.setPadding(dp(6), dp(2), dp(6), dp(4));
            body.addView(heading, cardParams(0));
        }
        for (Option option : options) {
            final Runnable action = option.action;
            LinearLayout card = card(
                    iconBadge(option.hue, option.drawableName, option.glyph),
                    option.title,
                    option.subtitle,
                    option.chevron,
                    true,
                    v -> {
                        dialog.dismiss();
                        try {
                            if (action != null) {
                                action.run();
                            }
                        } catch (Throwable t) {
                            Logger.printException(() -> "Sheet option failed", t);
                        }
                    }
            );
            body.addView(card, cardParams(10));
        }
        fadeIn();
    }

    /**
     * Opens a page from a card. With the page animation on, the sheet stays until the page has
     * risen over it (see {@link #holdUntilSettingsCovers()}); otherwise it just slides away.
     */
    private void launchFromCard(Runnable launch) {
        if (settingsFallback != null) {
            return; // A page is already on its way.
        }
        launch.run();
        if (SettingsPageAnimator.isEnabled()) {
            holdUntilSettingsCovers();
        } else {
            dialog.dismiss();
        }
    }

    /**
     * The settings page rises over the sheet, so the sheet stays where it is and is removed by
     * {@link #onSettingsPageCovered()} once the page is in place. If that never happens (the page
     * could not open) the sheet slides away after a moment, as it did before.
     */
    private void holdUntilSettingsCovers() {
        if (settingsFallback != null) {
            return;
        }
        settingsFallback = () -> {
            settingsFallback = null;
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        };
        body.postDelayed(settingsFallback, SETTINGS_COVER_TIMEOUT_MS);
    }

    /**
     * Opens Instagram's developer options by firing the Home tab's long press, the same thing that
     * happens when you hold the Home button. The override makes it work even when Home long press
     * is set to open Piko settings.
     */
    private void openDeveloperOptions() {
        View home = null;
        try {
            View decor = activity.getWindow().getDecorView();
            home = decor.findViewById(ResourceUtils.getIdentifier(ResourceType.ID, "feed_tab"));
            if (home == null) {
                Activity top = Utils.getActivity();
                if (top != null && top != activity) {
                    home = top.getWindow().getDecorView()
                            .findViewById(ResourceUtils.getIdentifier(ResourceType.ID, "feed_tab"));
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to find the Home tab", t);
        }
        if (home == null) {
            Utils.showToastShort(str("piko_developer_options_unavailable"));
            return;
        }
        final View target = home;
        dialog.dismiss();
        target.post(() -> {
            try {
                Pref.setForceDeveloperOptions(true);
                if (!target.performLongClick()) {
                    Utils.showToastShort(str("piko_developer_options_unavailable"));
                }
            } catch (Throwable t) {
                Logger.printException(() -> "Failed to open developer options", t);
            } finally {
                Pref.setForceDeveloperOptions(false);
            }
        });
    }

    // ---------- Cards ----------

    /** A rounded card: badge, title and subtitle, and a chevron when it can be tapped. */
    private LinearLayout card(View badge, CharSequence title, CharSequence subtitle,
                              boolean chevron, View.OnClickListener onClick) {
        return card(badge, title, subtitle, chevron, false, onClick);
    }

    /** @param oneLineSubtitle true to cut a long second line to one line with an ellipsis. */
    private LinearLayout card(View badge, CharSequence title, CharSequence subtitle,
                              boolean chevron, boolean oneLineSubtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(cardBackground(onClick != null));
        if (onClick != null) {
            row.setOnClickListener(onClick);
        }

        row.addView(badge, new LinearLayout.LayoutParams(dp(BADGE_DP), dp(BADGE_DP)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMarginStart(dp(14));
        texts.addView(text(title, 17, true, InstagramPreferenceStyle.primaryTextColor()));
        if (!TextUtils.isEmpty(subtitle)) {
            TextView sub = text(subtitle, 14, false, InstagramPreferenceStyle.secondaryTextColor());
            sub.setPadding(0, dp(2), 0, 0);
            if (oneLineSubtitle) {
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
            }
            texts.addView(sub);
        }
        row.addView(texts, textsParams);

        if (chevron) {
            ImageView arrow = new ImageView(context);
            boolean rtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL;
            UI.setThemedIcon(arrow, rtl ? UI.DRAWABLE_CHEVRON_RIGHT_RTL : UI.DRAWABLE_CHEVRON_RIGHT,
                    "igds_color_secondary_text");
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(20), dp(20));
            arrowParams.setMarginStart(dp(8));
            row.addView(arrow, arrowParams);
        }
        return row;
    }

    /** Third card: the patch version and the Instagram version, one under the other. */
    private View versionCard() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(cardBackground(false));

        row.addView(iconBadge(HUE_VERSION, UI.DRAWABLE_INFO_ICON, null),
                new LinearLayout.LayoutParams(dp(BADGE_DP), dp(BADGE_DP)));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMarginStart(dp(14));

        TextView patch = text(versionLine("piko_patch_version", patchVersion()), 16, true,
                InstagramPreferenceStyle.primaryTextColor());
        patch.setSingleLine(true);
        patch.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(patch);

        TextView instagram = text(versionLine("piko_instagram_version", instagramVersion()), 16, false,
                InstagramPreferenceStyle.primaryTextColor());
        instagram.setSingleLine(true);
        instagram.setEllipsize(TextUtils.TruncateAt.END);
        instagram.setPadding(0, dp(3), 0, 0);
        texts.addView(instagram);

        row.addView(texts, textsParams);
        return row;
    }

    private static String patchVersion() {
        try {
            return String.valueOf(Utils.getPatchesReleaseVersion());
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to read patch version", t);
            return "?";
        }
    }

    private static String instagramVersion() {
        try {
            return String.valueOf(Utils.getAppVersionName());
        } catch (Throwable t) {
            Logger.printException(() -> "Failed to read Instagram version", t);
            return "?";
        }
    }

    private static String versionLine(String stringKey, String version) {
        return str(stringKey, version);
    }

    // ---------- Small view helpers ----------

    /** A round tinted badge holding either a themed icon or a short text glyph. */
    private View iconBadge(int hue, String drawableName, String glyph) {
        LinearLayout badge = new LinearLayout(context);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(withAlpha(hue, 0x2E));
        badge.setBackground(circle);

        if (drawableName != null) {
            ImageView icon = new ImageView(context);
            try {
                Drawable drawable = ResourceUtils.getDrawable(drawableName);
                icon.setImageDrawable(drawable);
                icon.setColorFilter(new PorterDuffColorFilter(hue, PorterDuff.Mode.SRC_ATOP));
            } catch (Throwable t) {
                Logger.printException(() -> "Failed to load sheet icon " + drawableName, t);
            }
            badge.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        } else if (glyph != null) {
            TextView mark = text(glyph, 15, true, hue);
            mark.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            mark.setIncludeFontPadding(false);
            badge.addView(mark);
        }
        return badge;
    }

    private TextView text(CharSequence value, int sp, boolean bold, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (bold) {
            view.setTypeface(view.getTypeface(), Typeface.BOLD);
        }
        return view;
    }

    /** Full-width card at its natural height, with a gap above it. */
    private LinearLayout.LayoutParams cardParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    /** Card fill with a hairline edge; see-through when the page behind is blurred. */
    private Drawable cardBackground(boolean clickable) {
        int fill = InstagramPreferenceStyle.searchBackgroundColor();
        if (isGlass()) {
            fill = withAlpha(fill, CARD_GLASS_ALPHA);
        }
        GradientDrawable shape = rounded(fill, dp(CARD_RADIUS_DP));
        shape.setStroke(dp(1), withAlpha(InstagramPreferenceStyle.primaryTextColor(), 0x1F));
        if (!clickable) {
            return shape;
        }
        return new RippleDrawable(
                ColorStateList.valueOf(withAlpha(InstagramPreferenceStyle.primaryTextColor(), 0x24)),
                shape,
                rounded(Color.WHITE, dp(CARD_RADIUS_DP))
        );
    }

    private void fadeIn() {
        body.setAlpha(0f);
        body.animate().alpha(1f).setDuration(140).start();
    }

    private static String initial(String name) {
        if (TextUtils.isEmpty(name)) {
            return "?";
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radiusPx);
        drawable.setColor(color);
        return drawable;
    }

    private static Drawable ripple(int fill, float radiusPx) {
        int rippleColor = withAlpha(InstagramPreferenceStyle.primaryTextColor(), 0x24);
        return new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                rounded(fill, radiusPx),
                rounded(Color.WHITE, radiusPx)
        );
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dp(float value) {
        return InstagramPreferenceStyle.dp(context, value);
    }

    /** A scroll view that wraps its content but never grows past a part of the screen. */
    private static final class CappedScrollView extends ScrollView {
        private final float maxFraction;

        CappedScrollView(Context context, float maxFraction) {
            super(context);
            this.maxFraction = maxFraction;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * maxFraction);
            int mode = View.MeasureSpec.getMode(heightMeasureSpec);
            int size = View.MeasureSpec.getSize(heightMeasureSpec);
            int cap = mode == View.MeasureSpec.UNSPECIFIED ? max : Math.min(max, size);
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
        }
    }
}
