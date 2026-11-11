/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.RequiresApi;

import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;

/**
 * Opens and closes the Piko settings page with a blur-fade that rises from the bottom of the
 * screen, instead of Instagram's sideways slide.
 * <p>
 * Opening: the page starts below the screen, blurred and see-through, and rises while it fades in
 * and sharpens. Closing is the same in reverse: the page sinks, blurs and fades out. The screen
 * behind is dimmed and blurred while the page is on its way and goes back to normal when it is gone.
 * <p>
 * The page lives in a see-through Instagram screen (see {@link ActivityHook}), so the app behind
 * stays visible while the page moves. The animation runs on the page view itself; the system's own
 * open and close transitions are switched off.
 * <ul>
 *   <li>Android 12+: the page itself is blurred (a render effect), and the screen behind is blurred
 *       as well when the phone allows window blur.</li>
 *   <li>Android 11 and older, or window blur switched off: no blur, but the same rise, fade and dim.</li>
 * </ul>
 */
final class SettingsPageAnimator {

    /** Master switch. When false the settings page opens and closes the way it did in 1.4.1. */
    private static final boolean ENABLED = true;

    private static final int OPEN_MS = 380;
    private static final int CLOSE_MS = 300;
    /** How blurred the page is at the very start of opening and the very end of closing. */
    private static final int PAGE_BLUR_DP = 22;
    /** Blur of the screen behind the page at its strongest. */
    private static final int BEHIND_BLUR_DP = 16;
    /** A light shade of about 15%, same as Prism (0x26 of 0xFF). */
    private static final float BEHIND_DIM = 0x26 / 255f;
    /** The page is fully visible once this part of the opening has passed. */
    private static final float OPEN_FADE_SPAN = 0.5f;
    /** The page starts fading out once this part of the closing has passed. */
    private static final float CLOSE_FADE_START = 0.20f;

    /** Material "emphasized decelerate": quick off the bottom edge, slow settle at the top. */
    private static final PathInterpolator RISE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** Starts gently and speeds up as the page drops away, so the drop stays visible while it fades. */
    private static final AccelerateInterpolator SINK = new AccelerateInterpolator(1.5f);

    static boolean isEnabled() {
        return ENABLED;
    }

    private final Activity activity;
    private final int travelPx;
    private final int pageBlurPx;
    private final int behindBlurPx;
    private final boolean behindBlur;
    private final ColorDrawable behindDim = new ColorDrawable(Color.TRANSPARENT);

    private View page;
    private ValueAnimator animator;
    private boolean closing;
    private boolean menuSheetBehind;
    private boolean behindBlurFlagSet;
    private int lastPageBlur = -1;
    private int lastBehindBlur = -1;

    SettingsPageAnimator(Activity activity) {
        this.activity = activity;
        this.travelPx = activity.getResources().getDisplayMetrics().heightPixels;
        this.pageBlurPx = Math.round(InstagramPreferenceStyle.dp(activity, PAGE_BLUR_DP));
        this.behindBlurPx = Math.round(InstagramPreferenceStyle.dp(activity, BEHIND_BLUR_DP));
        this.behindBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowBlurAvailable(activity);
    }

    /**
     * Hides the page below the screen and starts opening it once the page has been laid out and
     * drawn for the first time, so the layout work does not eat into the animation.
     * Call right after the page has been set as the content of the activity.
     */
    void prepare(View pageView) {
        this.page = pageView;
        try {
            Window window = activity.getWindow();
            // The window must be see-through, or the app behind could not show while the page moves.
            window.setBackgroundDrawable(behindDim);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            setBarsTransparent();
            // When the Piko menu sheet is still on screen it already dims and blurs the page behind.
            menuSheetBehind = PikoMenuSheet.isShowing();
            render(0f, 0f, 1f, 0f);
            pageView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver observer = pageView.getViewTreeObserver();
                    if (observer.isAlive()) {
                        observer.removeOnPreDrawListener(this);
                    }
                    playOpen();
                    return true;
                }
            });
        } catch (Throwable t) {
            Logger.printException(() -> "Settings page animation could not be prepared", t);
            showAtRest();
        }
    }

    /**
     * Plays the closing animation and finishes the activity when it ends.
     *
     * @return true when the page is closing (started now, or already on its way); false when it
     * cannot be animated, and the caller should finish the activity itself.
     */
    boolean close() {
        if (closing) {
            return true;
        }
        if (page == null || !page.isAttachedToWindow() || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        try {
            closing = true;
            stopAnimator();
            setBarsTransparent();
            // The page covers the whole screen right now, so the backdrop can start at full strength.
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setInterpolator(new LinearInterpolator());
            animator.setDuration(CLOSE_MS);
            animator.addUpdateListener(a -> renderClosing((Float) a.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!cancelled) {
                        finishClose();
                    }
                }
            });
            renderClosing(0f);
            animator.start();
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "Settings page closing animation failed", t);
            closing = false;
            return false;
        }
    }

    /** Stops whatever is running. Call when the activity is destroyed. */
    void release() {
        stopAnimator();
    }

    // ---------- Opening ----------

    private void playOpen() {
        if (activity.isFinishing() || activity.isDestroyed() || closing) {
            return;
        }
        try {
            stopAnimator();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setInterpolator(new LinearInterpolator());
            animator.setDuration(OPEN_MS);
            animator.addUpdateListener(a -> renderOpening((Float) a.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!cancelled) {
                        finishOpen();
                    }
                }
            });
            animator.start();
        } catch (Throwable t) {
            Logger.printException(() -> "Settings page opening animation failed", t);
            showAtRest();
        }
    }

    /** @param t time through the opening, 0 to 1. */
    private void renderOpening(float t) {
        float rise = RISE.getInterpolation(t);
        float fade = 1f - (float) Math.pow(1f - Math.min(1f, t / OPEN_FADE_SPAN), 2);
        float blur = (1f - t) * (1f - t);
        // Opened from the menu sheet, the sheet is already providing the backdrop.
        float backdrop = menuSheetBehind ? 0f : rise;
        render(rise, fade, blur, backdrop);
    }

    /** @param t time through the closing, 0 to 1. */
    private void renderClosing(float t) {
        float sink = SINK.getInterpolation(t);
        float fade = 1f - Math.min(1f, Math.max(0f, (t - CLOSE_FADE_START) / (1f - CLOSE_FADE_START)));
        float blur = 1f - (1f - t) * (1f - t);
        render(1f - sink, fade, blur, 1f - t);
    }

    private void finishOpen() {
        try {
            showAtRest();
            // The page now covers the screen, so a menu sheet left under it can go without a slide.
            PikoMenuSheet.onSettingsPageCovered();
        } catch (Throwable t) {
            Logger.printException(() -> "Settings page opening animation did not finish cleanly", t);
        }
    }

    private void finishClose() {
        try {
            render(0f, 0f, 1f, 0f);
        } catch (Throwable ignored) {
            // The page is being closed anyway.
        }
        activity.finish();
        // The page already moved away on its own, so the system must not animate on top of that.
        activity.overridePendingTransition(0, 0);
    }

    // ---------- Drawing ----------

    /**
     * @param rise     0 = below the screen, 1 = in place.
     * @param fade     0 = invisible, 1 = opaque.
     * @param blur     0 = sharp, 1 = blurred as much as the page ever gets.
     * @param backdrop 0 = screen behind untouched, 1 = dimmed and blurred as much as it gets.
     */
    private void render(float rise, float fade, float blur, float backdrop) {
        page.setTranslationY((1f - clamp(rise)) * travelPx);
        page.setAlpha(clamp(fade));
        setPageBlur(Math.round(pageBlurPx * clamp(blur)));
        behindDim.setColor(Color.argb(Math.round(255f * BEHIND_DIM * clamp(backdrop)), 0, 0, 0));
        setBehindBlur(Math.round(behindBlurPx * clamp(backdrop)));
    }

    /** The finished page: in place, sharp, opaque, with nothing left over from the animation. */
    private void showAtRest() {
        if (page == null) {
            return;
        }
        page.setTranslationY(0f);
        page.setAlpha(1f);
        setPageBlur(0);
        behindDim.setColor(Color.TRANSPARENT);
        setBehindBlur(0);
        InstagramPreferenceStyle.applySystemBarStyle(activity);
    }

    private void setPageBlur(int radiusPx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || radiusPx == lastPageBlur) {
            return;
        }
        lastPageBlur = radiusPx;
        Blur31.setPageBlur(page, radiusPx);
    }

    private void setBehindBlur(int radiusPx) {
        if (!behindBlur || radiusPx == lastBehindBlur) {
            return;
        }
        lastBehindBlur = radiusPx;
        Window window = activity.getWindow();
        if (radiusPx > 0 && !behindBlurFlagSet) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            behindBlurFlagSet = true;
        }
        Blur31.setBehindBlur(window, radiusPx);
        if (radiusPx == 0 && behindBlurFlagSet) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            behindBlurFlagSet = false;
        }
    }

    /** While the page is on the move the bars show what is behind them, not a block of page colour. */
    private void setBarsTransparent() {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static boolean windowBlurAvailable(Activity activity) {
        try {
            return Blur31.windowBlurAvailable(activity);
        } catch (Throwable t) {
            Logger.printException(() -> "Window blur check failed", t);
            return false;
        }
    }

    /** Android 12+ calls, kept apart so older phones never load them. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Blur31 {
        static boolean windowBlurAvailable(Activity activity) {
            WindowManager windowManager = activity.getSystemService(WindowManager.class);
            return windowManager != null && windowManager.isCrossWindowBlurEnabled();
        }

        static void setBehindBlur(Window window, int radiusPx) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.setBlurBehindRadius(radiusPx);
            window.setAttributes(attributes);
        }

        static void setPageBlur(View view, int radiusPx) {
            if (radiusPx <= 0) {
                view.setRenderEffect(null);
            } else {
                view.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
            }
        }
    }
}
