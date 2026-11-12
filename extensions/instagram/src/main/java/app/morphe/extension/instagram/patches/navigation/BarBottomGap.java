/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;

/**
 * Keeps a floating bar (the floating navigation bar's pill, or Instagram's own floating bar) a chosen distance above
 * the bottom of the screen (Navigation bar > Floating bar size > Bottom gap).
 * <p>
 * Only the bar's own bottom margin is changed: the margin Instagram gave it (normally 0) plus the chosen distance.
 * The bar's size, padding and drawing are left alone, so the system inset under the tabs is still there on top of
 * the distance, and the page keeps running down behind the bar to the bottom of the screen.
 * <p>
 * Nothing is searched for in Instagram's code. If Instagram sets the margin again, the new value is taken as its
 * own and the distance is added to it; if it does that on every frame the layout is given back and left alone
 * (until the setting is switched off or the app is restarted), so the two never fight. A bar whose layout
 * parameters have no margins is left alone as well.
 */
final class BarBottomGap {
    /** The margin is changed on this many frames in a row: Instagram sets it back every time, so give up. */
    private static final int MAX_CONSECUTIVE_CHANGES = 12;

    private BarBottomGap() {}

    /** What was done to the bar, to give it back. One per bar. */
    static final class Memory {
        /** The distance is on the bar's margin. */
        boolean applied;
        /** The margin Instagram had (without the distance). */
        int original;
        /** The margin set last, to tell it from one Instagram has set since. */
        int appliedMargin;
        /** Frames in a row in which the margin had to be changed again. */
        int consecutiveChanges;
        /** The margin could not be kept: left alone until {@link #forgetFailures}. */
        boolean gaveUp;

        boolean isApplied() {
            return applied;
        }
    }

    /** Lets a bar that was given up on have another try (the setting was switched on again). */
    static void forgetFailures(Memory memory) {
        memory.gaveUp = false;
        memory.consecutiveChanges = 0;
    }

    /**
     * Puts the bar {@code gapPx} above the bottom of the screen (on top of Instagram's own margin).
     * True when the bar's layout changed, so the frame is laid out again before it is drawn.
     */
    static boolean apply(View bar, int gapPx, Memory memory) {
        if (memory.gaveUp) return false;
        ViewGroup.LayoutParams layout = bar.getLayoutParams();
        if (!(layout instanceof ViewGroup.MarginLayoutParams)) {
            if (gapPx > 0) {
                memory.gaveUp = true;
                Logger.printInfo(() -> "Floating navigation bar: the bottom gap is not applied, the bar's layout "
                        + "parameters have no margins");
            }
            return false;
        }
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) layout;

        // A margin that is not the one set here was set by Instagram: it is the base the distance is added to.
        if (!(memory.applied && params.bottomMargin == memory.appliedMargin)) {
            memory.applied = false;
            memory.original = params.bottomMargin;
        }
        // Nothing asked and nothing set: the layout is not touched at all.
        if (gapPx <= 0 && !memory.applied) {
            memory.consecutiveChanges = 0;
            return false;
        }

        int wanted = memory.original + Math.max(0, gapPx);
        if (params.bottomMargin == wanted) {
            memory.consecutiveChanges = 0;
            memory.appliedMargin = wanted;
            memory.applied = gapPx > 0;
            return false;
        }

        if (++memory.consecutiveChanges > MAX_CONSECUTIVE_CHANGES) {
            // Instagram sets the margin back every frame: give the bar its own margin and stop trying.
            memory.gaveUp = true;
            Logger.printInfo(() -> "Floating navigation bar: Instagram resets the bar's bottom margin on every "
                    + "frame, so the bottom gap is not applied to it");
            restore(bar, memory);
            return true;
        }
        params.bottomMargin = wanted;
        bar.setLayoutParams(params);
        memory.appliedMargin = wanted;
        memory.applied = gapPx > 0;
        return true;
    }

    /** Gives the bar the margin Instagram had. True when the layout changed. */
    static boolean restore(View bar, Memory memory) {
        boolean changed = false;
        ViewGroup.LayoutParams layout = bar.getLayoutParams();
        // Only when the margin is still the one set here: if Instagram has set its own since, it keeps it.
        if (memory.applied && layout instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) layout;
            if (params.bottomMargin == memory.appliedMargin) {
                params.bottomMargin = memory.original;
                bar.setLayoutParams(params);
                changed = true;
            }
        }
        memory.applied = false;
        memory.consecutiveChanges = 0;
        return changed;
    }
}
