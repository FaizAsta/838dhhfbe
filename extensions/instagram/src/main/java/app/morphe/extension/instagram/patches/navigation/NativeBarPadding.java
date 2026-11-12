/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.view.View;

import app.morphe.extension.shared.Logger;

/**
 * Takes the side padding off Instagram's own floating bar, so its tabs share the whole pill, as the tabs of the
 * floating navigation bar do.
 * <p>
 * Instagram's bar keeps a padding at its left and right (24 dp on the phone it was seen on) and lays the tabs out in
 * what is left, so the icons crowd toward the middle while the pill (which fills the bar, see {@link NativeBarSize})
 * is as wide as the whole bar: on a 384 dp bar with 24 dp of padding the five icons were 67 dp apart and the first
 * one 58 dp from the edge of the pill, instead of 77 dp apart with the first one 38 dp in. With the side padding
 * gone each tab is a fifth of the pill, and the icon is in the middle of its cell.
 * <p>
 * Only the left and right padding is touched (the top and the bottom are Instagram's: the gesture area under the bar,
 * for one). What Instagram had is remembered in {@link Memory} and put back when the setting is switched off, when the
 * pill stops filling the bar, or when the bar's padding was changed by somebody else in the meantime (that value is
 * not ours to undo). A padding that Instagram sets back is taken off again, and {@link NativeBarSize} gives up on
 * the whole size when that happens on every frame.
 */
final class NativeBarPadding {
    /** Instagram sets the padding back this many times in a row (one frame after the other): stop fighting it. */
    private static final int MAX_RESETS_IN_A_ROW = 6;

    private NativeBarPadding() {}

    /** What Instagram had at the left and the right of the bar, and whether it is taken off now. One per bar. */
    static final class Memory {
        boolean applied;
        int origLeft;
        int origRight;
        /** For the debug copy: what was seen, in px. */
        int seenLeft;
        int seenRight;
        /** Times in a row that Instagram's padding was found back on the bar after it was taken off. */
        int resets;
        /** Instagram sets the padding back on every frame: it is left as Instagram has it until the next try. */
        boolean gaveUp;
    }

    /** The setting was switched off or on again: a padding that was given up on gets another try. */
    static void forgetFailures(Memory memory) {
        memory.gaveUp = false;
        memory.resets = 0;
    }

    /**
     * Takes the bar's side padding off (and remembers it). True when the padding changed, so the bar has to be laid
     * out again before it is drawn. A bar that has no side padding is left alone.
     */
    static boolean apply(View bar, Memory memory) {
        if (memory.gaveUp) return false;
        int left = bar.getPaddingLeft();
        int right = bar.getPaddingRight();
        if (left == 0 && right == 0) {
            memory.resets = 0;
            return false;
        }
        if (memory.applied && ++memory.resets > MAX_RESETS_IN_A_ROW) {
            // It was taken off and has come back every time: the rest of the size is not given up for that.
            memory.gaveUp = true;
            memory.applied = false;
            Logger.printInfo(() -> "Floating navigation bar: Instagram's own bar sets its side padding back on every "
                    + "frame, so it is left as Instagram has it and the icons keep their spacing");
            return false;
        }
        // Instagram's own values: the first time, or a padding it has set again since. These are given back later.
        memory.origLeft = left;
        memory.origRight = right;
        memory.seenLeft = left;
        memory.seenRight = right;
        memory.applied = true;
        bar.setPadding(0, bar.getPaddingTop(), 0, bar.getPaddingBottom());
        return true;
    }

    /**
     * Gives the padding back. Only when it is still the one taken off: a padding that something else has set since is
     * left as it is. True when the padding changed.
     */
    static boolean giveBack(View bar, Memory memory) {
        if (!memory.applied) return false;
        memory.applied = false;
        if (bar.getPaddingLeft() != 0 || bar.getPaddingRight() != 0) return false;
        bar.setPadding(memory.origLeft, bar.getPaddingTop(), memory.origRight, bar.getPaddingBottom());
        return true;
    }

    /** One line for the debug copy. */
    static String describe(Memory memory, float density) {
        if (memory.seenLeft == 0 && memory.seenRight == 0 && !memory.applied) {
            return "side padding: none seen (nothing taken off)";
        }
        return "side padding: " + Math.round(memory.seenLeft / density) + " dp left, "
                + Math.round(memory.seenRight / density) + " dp right on Instagram's bar, "
                + (memory.applied ? "taken off so the tabs share the whole pill" : "given back");
    }
}
