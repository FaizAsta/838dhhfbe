/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import app.morphe.extension.shared.Logger;

/**
 * Hides the thin line at the very bottom of the screen while a floating bar is on.
 * <p>
 * That line is not part of Instagram: it is the gesture handle of Android's gesture navigation, which the system
 * draws on top of every app. With the stock bar it sits on the bar's solid background and nobody notices it; with a
 * floating bar the page runs under it, so it floats over the picture. An app cannot switch the handle off alone. The
 * only thing it can do is hide the system navigation bar, which is what this class does (the handle goes with it).
 * <p>
 * What that costs, and why it is a switch that is off by default: with the navigation bar hidden, a swipe up from the
 * bottom edge first brings the handle back for a moment (it hides again by itself) and only the next swipe goes Home
 * or opens the recent apps. Three-button navigation works the same way (the buttons come back with a swipe).
 * <p>
 * The system inset under the bar is 0 while the navigation bar is hidden, so Instagram would lay the floating bar
 * lower, against the edge of the screen. The bar is moved up again by the distance its tabs really sank, so they
 * stay exactly where they were. That distance is measured on the screen (where the tabs end, before and after the
 * navigation bar was hidden) and is never more than the inset the system took away, so it needs nothing of
 * Instagram's layout. It is 0 when the tabs did not sink: when Instagram does not reserve the inset under the bar in
 * the first place (for instance with "Remove empty bottom space" on), nothing is moved, so the strip that setting
 * removes does not come back. Only the bar's translation is changed, never its layout, and it is given back when
 * the setting is switched off.
 * <p>
 * Nothing is searched for in Instagram's code, so no fingerprint can fail on any Instagram version.
 */
final class GestureLineHider {
    private GestureLineHider() {}

    /** What was changed, so it can be given back. One per window. */
    static final class Memory {
        /** The navigation bar was hidden by this class and not shown again yet. */
        boolean hidden;
        /** The way the system brings hidden bars back before this class changed it (Android 11 and later). */
        boolean behaviorTaken;
        int behavior;
        /** The window's system UI flags before this class changed them (Android 10 and earlier). */
        int legacyFlags;

        /** The bar is moved up by this class: the bar's own translation, and the one this class set last. */
        boolean shifted;
        float baseTranslation;
        float setTranslation;
        /**
         * Where the tabs ended while the navigation bar was shown: their distance (px) from the bottom of the window.
         * Measured on every frame in which the bar is shown, so it is the last known place of the tabs and is kept
         * while the bar is hidden. -1 until the bar has been seen laid out with the navigation bar shown.
         */
        int shownGap = -1;

        /** True while something is changed (and so there is something to give back). */
        boolean isActive() {
            return hidden || shifted;
        }
    }

    /**
     * Hides the system navigation bar (and with it the gesture line) of the window. True when it was done (or was
     * already), false when the window cannot be asked yet (it is tried again later).
     */
    @SuppressWarnings("deprecation")
    static boolean hide(View decor, Memory memory) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller == null) return false;
                if (!memory.behaviorTaken) {
                    memory.behavior = controller.getSystemBarsBehavior();
                    memory.behaviorTaken = true;
                }
                // The bars come back for a moment on a swipe from the edge and hide again by themselves.
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.navigationBars());
            } else {
                int flags = decor.getSystemUiVisibility();
                if (!memory.hidden) memory.legacyFlags = flags;
                int wanted = flags | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                if (wanted != flags) decor.setSystemUiVisibility(wanted);
            }
            memory.hidden = true;
            return true;
        } catch (RuntimeException e) {
            Logger.printException(() -> "GestureLineHider.hide failed", e);
            return false;
        }
    }

    /** Shows the navigation bar again, the way it was before {@link #hide}. */
    @SuppressWarnings("deprecation")
    static void show(View decor, Memory memory) {
        if (!memory.hidden) return;
        memory.hidden = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.navigationBars());
                    if (memory.behaviorTaken) controller.setSystemBarsBehavior(memory.behavior);
                }
                memory.behaviorTaken = false;
            } else {
                decor.setSystemUiVisibility(memory.legacyFlags);
            }
        } catch (RuntimeException e) {
            Logger.printException(() -> "GestureLineHider.show failed", e);
        }
    }

    /**
     * Keeps the bar where it was while the navigation bar is hidden: moves it up by the distance its tabs sank
     * because the hidden bar no longer takes its inset. Run before every frame; changes nothing but the translation
     * of the bar.
     * <p>
     * The inset the system took away ({@link #reservePx}) is only the most the bar can have sunk. How far it really
     * sank depends on whether Instagram reserved that inset under the bar at all, so it is measured: the tabs' place
     * while the bar was shown (see {@link Memory#shownGap}) against their place now. With "Remove empty bottom
     * space" on Instagram may reserve nothing there, the tabs do not sink, and moving the bar up by the whole inset
     * would bring back the empty strip that setting removes.
     */
    static void keepBarUp(View decor, View bar, Memory memory) {
        float current = bar.getTranslationY();
        // A translation that is not the one set here was set by Instagram (an animation, for one): it is the base.
        if (!memory.shifted || current != memory.setTranslation) memory.baseTranslation = current;
        int reserve = reservePx(decor);
        int lift = reserve;
        if (bar.isLaidOut() && bar.getHeight() > 0 && decor.getHeight() > 0) {
            int gap = tabsGapPx(decor, bar);
            if (reserve == 0) {
                // The navigation bar is shown (or was not hidden yet): this is where the tabs belong.
                memory.shownGap = gap;
            } else if (memory.shownGap >= 0) {
                // The navigation bar is hidden: only what the tabs really sank, and never more than the inset lost.
                lift = Math.max(0, Math.min(reserve, memory.shownGap - gap));
            }
            // Without a place from before (the window was hidden from the start): the whole inset, as before.
        }
        float wanted = memory.baseTranslation - lift;
        if (current != wanted) bar.setTranslationY(wanted);
        memory.setTranslation = wanted;
        memory.shifted = true;
    }

    /**
     * The distance (px) from the bottom of the bar's tabs (the bar above its own bottom padding, which is where the
     * system inset goes) to the bottom of the window. The bar's translation is left out, so moving the bar up here
     * does not change the number.
     */
    private static int tabsGapPx(View decor, View bar) {
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        float top = at[1] - bar.getTranslationY();
        float tabsBottom = top + bar.getHeight() - bar.getPaddingBottom();
        return Math.round(decor.getHeight() - tabsBottom);
    }

    /** Gives the bar its own translation back (only when it still has the one set here). */
    static void putBarBack(View bar, Memory memory) {
        if (!memory.shifted) return;
        memory.shifted = false;
        if (bar != null && bar.getTranslationY() == memory.setTranslation) {
            bar.setTranslationY(memory.baseTranslation);
        }
    }

    /**
     * The height of the navigation bar's inset that is missing now because the bar is hidden: what it takes when it
     * is shown, minus what it takes now. 0 while the bar is shown, and 0 when the window's insets are not known.
     */
    @SuppressWarnings("deprecation")
    private static int reservePx(View decor) {
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return 0;
        int missing;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int full = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
            int now = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            missing = full - now;
        } else {
            // The stable inset does not change when the bar is hidden; the window's own inset does (and also
            // grows with the keyboard, which only makes this smaller, never negative).
            missing = insets.getStableInsetBottom() - insets.getSystemWindowInsetBottom();
        }
        // A bar that is hidden by the system (not asked by us) or an absurd value is not worth following.
        int limit = Math.round(96f * decor.getResources().getDisplayMetrics().density);
        return Math.max(0, Math.min(missing, limit));
    }
}
