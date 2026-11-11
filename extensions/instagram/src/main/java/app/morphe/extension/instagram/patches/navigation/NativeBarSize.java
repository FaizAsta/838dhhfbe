/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Logger;

/**
 * Gives the width and the height chosen in the settings (Navigation bar > Floating bar size) to Instagram's own
 * floating bar, the one that is switched on with "Instagram's own floating bar". The Piko pill draws itself from
 * {@link FloatingNavSize}; Instagram's bar is Instagram's own view, so here the numbers go to its layout instead:
 * <ul>
 *   <li><b>width</b>: the bar is as wide as the preview shows (the screen minus the distance to each side). A bar
 *       that fills its row (match parent) gets side margins, so it keeps its place and its own look; a bar with a
 *       width of its own gets that width and is centered;</li>
 *   <li><b>height</b>: the bar's height above the system inset, as the slider shows it (in dp). Whatever padding
 *       Instagram gives the bar (the system inset under it, for instance) is kept on top of that.</li>
 * </ul>
 * Nothing of Instagram's layout is replaced and nothing is searched for in its code: only the bar's own layout
 * parameters are changed, each remembered in {@link Memory} and put back when the setting is switched off.
 * <p>
 * Instagram's bar was not seen when this was written, so nothing is taken for granted. A few frames after a change
 * the result is looked at: a width that is not the one asked for or a bar that is not in the middle of its row
 * gives the width back and leaves it alone, and a height that is not the one asked for (or that would cut the tabs
 * off, because they have a height of their own) gives the height back and leaves alone that height and every
 * smaller one. The two are judged apart, so a bar whose width can be set but not its height still gets its width.
 * What was given up on is remembered until the setting is switched off or the app is restarted (see
 * {@link #forgetFailures}), and the reason goes into the log with the other "Floating navigation bar" lines.
 */
public final class NativeBarSize {
    /** The bar is checked this many frames after its layout was last changed. */
    private static final int CHECK_AFTER_FRAMES = 3;
    /** A size that is off by more than this (dp) is not the one that was asked for. */
    private static final float TOLERANCE_DP = 3f;
    /** The layout is changed on this many frames in a row: Instagram sets it back every time, so give up. */
    private static final int MAX_CONSECUTIVE_CHANGES = 12;
    /** A size is only given up on after the check found it wrong this many times in a row. */
    private static final int STRIKES_TO_GIVE_UP = 3;

    private static final int NO_GRAVITY_FIELD = Integer.MIN_VALUE;

    /** The bar's structure as first seen (Instagram's own layout), and after the size was applied. For the debug copy. */
    private static volatile String structureBefore;
    private static volatile String structureAfter;

    private NativeBarSize() {}

    /**
     * Everything known about Instagram's own bar, for the "Copy navigation bar info" button in the settings: its
     * structure before and after the size was applied. Null until the bar has been laid out at least once.
     */
    public static String debugReport() {
        String before = structureBefore;
        if (before == null) return null;
        String after = structureAfter;
        String pill = NativeBarBlur.debugShape();
        return "== Instagram's own bar, before the size was applied ==\n" + before
                + "\n\n== After the size was applied ==\n" + (after == null ? "(not checked yet)" : after)
                + "\n\n== Where the pill is drawn ==\n" + (pill == null ? "(not drawn yet)" : pill);
    }

    /** What Instagram had on the bar, what was set on it, and what was given up on. One per bar. */
    static final class Memory {
        // The width: the parameters Instagram had (given back later) and the ones that were set (to tell whether a
        // value is still ours or Instagram has set a new one).
        boolean widthApplied;
        int origWidth;
        int origLeft;
        int origRight;
        int origGravity = NO_GRAVITY_FIELD;
        int appliedWidth;
        int appliedLeft;
        int appliedRight;
        int appliedGravity = NO_GRAVITY_FIELD;
        /** The width the bar should have, in px, to check it by. */
        int expectedWidth;
        /** The width could not be applied on this bar: it is left as Instagram has it. */
        boolean widthFailed;

        // The height of the whole bar (what the slider chose plus its padding), and the part the slider chose.
        boolean heightApplied;
        int origHeight;
        int appliedHeight;
        int appliedContentPx;
        /** Heights up to this (px, without padding) could not be applied. 0: none; MAX_VALUE: no height can. */
        int heightFailedPx;
        /** What each tab had before it was made to fit the bar, to give back: {height px, gravity}. */
        final java.util.WeakHashMap<View, int[]> tabHeights = new java.util.WeakHashMap<>();
        /** The tab height (px) that was set last, to tell it from one Instagram has set since. */
        int appliedTabPx;

        int framesSinceApply;
        boolean checked;
        int consecutiveChanges;
        /** Checks in a row that found the width / the height not as asked: one bad frame is not a verdict. */
        int widthStrikes;
        int heightStrikes;
        /** The bar's structure was written to the log (once per bar). */
        boolean described;

        /** True while something of Instagram's layout is changed. */
        boolean isApplied() {
            return widthApplied || heightApplied;
        }
    }

    /**
     * True while the width of the slider is on the bar, so the bar is as wide as the pill should be and the pill can
     * fill it (see {@link NativeBarBlur}).
     */
    static boolean widthApplied(Memory memory) {
        return memory.widthApplied;
    }

    /**
     * The setting was switched off or on again: what could not be applied gets another try. (A new bar gets a new
     * {@link Memory}, so a new window also starts again.)
     */
    static void forgetFailures(Memory memory) {
        memory.widthFailed = false;
        memory.heightFailedPx = 0;
        memory.consecutiveChanges = 0;
        memory.widthStrikes = 0;
        memory.heightStrikes = 0;
    }

    /**
     * Sets the width and the height of the sliders on the bar. {@code sideDp} and {@code gapDp} are the stored
     * values (see {@link FloatingNavSize}), so the numbers are the ones the settings show next to the sliders.
     * True when the bar's layout changed, so the frame is laid out again before it is drawn.
     */
    static boolean apply(View bar, int sideDp, int gapDp, Memory memory) {
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params == null) return false;
        float density = bar.getResources().getDisplayMetrics().density;
        boolean changed = false;

        if (!memory.described && bar.isLaidOut() && bar.getWidth() > 0) {
            memory.described = true;
            final String structure = describeStructure(bar, density);
            structureBefore = describeTree(bar, density);
            Logger.printInfo(() -> "Floating navigation bar: Instagram's own bar structure: " + structure);
        }

        if (!memory.widthFailed) {
            if (applyWidth(bar, params, Math.round(sideDp * density), memory)) changed = true;
        }

        int contentPx = Math.round(FloatingNavSize.pillHeightDp(gapDp) * density);
        if (contentPx > memory.heightFailedPx) {
            if (applyHeight(bar, params, contentPx, memory)) changed = true;
        } else if (memory.heightApplied) {
            // The size was moved to one that could not be applied before: back to Instagram's height.
            if (giveHeightBack(bar, params, memory)) changed = true;
        }

        if (changed) {
            memory.framesSinceApply = 0;
            memory.checked = false;
            memory.consecutiveChanges++;
            if (memory.consecutiveChanges > MAX_CONSECUTIVE_CHANGES) {
                // Instagram puts its own layout back as often as it is changed: stop fighting it.
                memory.widthFailed = true;
                memory.heightFailedPx = Integer.MAX_VALUE;
                giveWidthBack(params, memory);
                giveHeightBack(bar, params, memory);
                memory.consecutiveChanges = 0;
                Logger.printInfo(() -> "Floating navigation bar: Instagram's own bar sets its size back on every "
                        + "frame, so the width and the height of the settings are not applied to it");
            }
            bar.setLayoutParams(params);
            return true;
        }
        memory.consecutiveChanges = 0;

        if (!memory.checked && memory.isApplied()) {
            memory.framesSinceApply++;
            if (memory.framesSinceApply >= CHECK_AFTER_FRAMES && bar.isLaidOut() && !bar.isLayoutRequested()
                    && bar.getWidth() > 0 && bar.getHeight() > 0) {
                memory.checked = true;
                structureAfter = describeTree(bar, density);
                if (verify(bar, params, density, memory)) {
                    bar.setLayoutParams(params);
                    return true;
                }
            }
        }
        return false;
    }

    /** Gives the bar the width and the height Instagram had. True when the layout changed. */
    static boolean restore(View bar, Memory memory) {
        if (!memory.isApplied()) return false;
        boolean changed = false;
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params != null) {
            // Only what is still ours: a value Instagram has set since is Instagram's.
            if (memory.widthApplied && widthIsOurs(params, memory)) {
                giveWidthBack(params, memory);
                changed = true;
            }
            if (memory.heightApplied && params.height == memory.appliedHeight) {
                giveHeightBack(bar, params, memory);
                changed = true;
            }
            // Tabs that were made to fit are given back even when the bar's own height was changed by Instagram.
            if (!memory.tabHeights.isEmpty() && giveTabsBack(bar, memory)) changed = true;
            if (changed) bar.setLayoutParams(params);
        }
        memory.widthApplied = false;
        memory.heightApplied = false;
        memory.checked = false;
        memory.framesSinceApply = 0;
        memory.consecutiveChanges = 0;
        return changed;
    }

    // ---- width ----

    private static boolean applyWidth(View bar, ViewGroup.LayoutParams params, int sidePx, Memory memory) {
        int screen = bar.getResources().getDisplayMetrics().widthPixels;
        int available = availableWidth(bar, screen);
        int target = targetWidthPx(screen, sidePx, available);
        if (target <= 0) return false;

        if (!(memory.widthApplied && widthIsOurs(params, memory))) {
            // Instagram's own values (the first time, or after Instagram set new ones): these are what is given
            // back later.
            memory.widthApplied = false;
            memory.origWidth = params.width;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                memory.origLeft = ((ViewGroup.MarginLayoutParams) params).leftMargin;
                memory.origRight = ((ViewGroup.MarginLayoutParams) params).rightMargin;
            } else {
                memory.origLeft = 0;
                memory.origRight = 0;
            }
            memory.origGravity = gravityOf(params);
        }

        boolean hasMargins = params instanceof ViewGroup.MarginLayoutParams;
        // A bar that fills its row keeps doing so and gets margins; any other bar gets the width itself.
        boolean byMargins = hasMargins
                && (memory.origWidth == ViewGroup.LayoutParams.MATCH_PARENT || memory.origWidth == 0);
        int wantedWidth;
        int wantedMargin = 0;
        int wantedGravity = memory.origGravity;
        if (byMargins) {
            wantedWidth = memory.origWidth;
            wantedMargin = marginFor(available, target);
        } else {
            wantedWidth = target;
            if (memory.origGravity != NO_GRAVITY_FIELD) wantedGravity = centered(memory.origGravity);
        }
        memory.expectedWidth = target;

        boolean changed = false;
        if (params.width != wantedWidth) {
            params.width = wantedWidth;
            changed = true;
        }
        if (hasMargins) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            if (margins.leftMargin != wantedMargin || margins.rightMargin != wantedMargin) {
                margins.setMargins(wantedMargin, margins.topMargin, wantedMargin, margins.bottomMargin);
                changed = true;
            }
        }
        if (memory.origGravity != NO_GRAVITY_FIELD && gravityOf(params) != wantedGravity) {
            setGravity(params, wantedGravity);
            changed = true;
        }

        memory.appliedWidth = params.width;
        memory.appliedLeft = hasMargins ? ((ViewGroup.MarginLayoutParams) params).leftMargin : 0;
        memory.appliedRight = hasMargins ? ((ViewGroup.MarginLayoutParams) params).rightMargin : 0;
        memory.appliedGravity = gravityOf(params);
        memory.widthApplied = true;
        return changed;
    }

    /** True when the width parameters on the bar are still the ones set here. */
    private static boolean widthIsOurs(ViewGroup.LayoutParams params, Memory memory) {
        if (params.width != memory.appliedWidth) return false;
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            if (margins.leftMargin != memory.appliedLeft || margins.rightMargin != memory.appliedRight) return false;
        }
        return gravityOf(params) == memory.appliedGravity;
    }

    private static void giveWidthBack(ViewGroup.LayoutParams params, Memory memory) {
        if (!memory.widthApplied) return;
        params.width = memory.origWidth;
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMargins(memory.origLeft, margins.topMargin, memory.origRight, margins.bottomMargin);
        }
        if (memory.origGravity != NO_GRAVITY_FIELD) setGravity(params, memory.origGravity);
        memory.widthApplied = false;
    }

    /** The room the bar has across: its row's width without the row's own padding (the screen until it is known). */
    private static int availableWidth(View bar, int screen) {
        if (bar.getParent() instanceof View) {
            View parent = (View) bar.getParent();
            int room = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
            if (room > 0) return room;
        }
        return screen;
    }

    // ---- height ----

    private static boolean applyHeight(View bar, ViewGroup.LayoutParams params, int contentPx, Memory memory) {
        // The padding is Instagram's (the system inset under the bar, for one) and stays: the slider is the height
        // of what is above it.
        int wanted = contentPx + bar.getPaddingTop() + bar.getPaddingBottom();
        if (!(memory.heightApplied && params.height == memory.appliedHeight)) {
            memory.heightApplied = false;
            memory.origHeight = params.height;
        }
        boolean changed = params.height != wanted;
        params.height = wanted;
        memory.appliedHeight = wanted;
        memory.appliedContentPx = contentPx;
        memory.heightApplied = true;
        // The tabs have a height of their own (48 dp) and sit on the bottom padding: in a bar shorter than that they
        // stuck out of the top and were cut off, with the icons off-centre. A tab that is too tall now shrinks to
        // the bar's content, and every tab is centered in it, so its icon (centered in the tab) is centered in the
        // bar. A tab that fits keeps its own height, so what is drawn inside it (a badge, a dot) stays where it was.
        if (fitTabs(bar, contentPx, memory)) changed = true;
        return changed;
    }

    /** Fits every tab (a child with a fixed height) in the bar's content and centers it. True when a tab changed. */
    private static boolean fitTabs(View bar, int contentPx, Memory memory) {
        if (!(bar instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) bar;
        boolean changed = false;
        int tabPx = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp == null || child.getVisibility() == View.GONE) continue;
            int[] original = memory.tabHeights.get(child);
            // Only fixed heights: a tab that matches its bar or wraps its content already follows it.
            if (original == null && lp.height <= 0) continue;
            int gravity = gravityOf(lp);
            if (original == null) {
                original = new int[] {lp.height, gravity};
                memory.tabHeights.put(child, original);
            }
            int wantedHeight = Math.min(original[0], contentPx);
            if (original[0] <= 0) wantedHeight = contentPx;
            tabPx = wantedHeight;
            boolean touched = false;
            if (lp.height != wantedHeight) {
                lp.height = wantedHeight;
                touched = true;
            }
            if (gravity != NO_GRAVITY_FIELD) {
                int base = gravity == -1 ? 0 : gravity & ~Gravity.VERTICAL_GRAVITY_MASK;
                int wantedGravity = base | Gravity.CENTER_VERTICAL;
                if (gravity != wantedGravity) {
                    setGravity(lp, wantedGravity);
                    touched = true;
                }
            }
            if (touched) {
                child.setLayoutParams(lp);
                changed = true;
            }
        }
        memory.appliedTabPx = tabPx;
        return changed;
    }

    /** Gives every tab the height and the gravity it had. True when a tab changed. */
    private static boolean giveTabsBack(View bar, Memory memory) {
        boolean changed = false;
        if (bar instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) bar;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                int[] original = memory.tabHeights.get(child);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (original == null || lp == null) continue;
                boolean touched = false;
                // Only what is still the one set here: a height Instagram has set since is its own.
                if (lp.height == memory.appliedTabPx && lp.height != original[0]) {
                    lp.height = original[0];
                    touched = true;
                }
                if (original[1] != NO_GRAVITY_FIELD && gravityOf(lp) != original[1]) {
                    setGravity(lp, original[1]);
                    touched = true;
                }
                if (touched) {
                    child.setLayoutParams(lp);
                    changed = true;
                }
            }
        }
        memory.tabHeights.clear();
        memory.appliedTabPx = 0;
        return changed;
    }

    private static boolean giveHeightBack(View bar, ViewGroup.LayoutParams params, Memory memory) {
        if (!memory.heightApplied) return false;
        giveTabsBack(bar, memory);
        memory.heightApplied = false;
        // Only when the height is still ours: one Instagram has set since is left alone.
        if (params.height != memory.appliedHeight) return false;
        params.height = memory.origHeight;
        return true;
    }

    // ---- the check ----

    /**
     * Looks at the bar once it has been laid out with the new size. A width or a height that did not come out as
     * asked for is given back and given up on. True when a parameter was given back.
     */
    private static boolean verify(View bar, ViewGroup.LayoutParams params, float density, Memory memory) {
        boolean givenBack = false;
        int tolerance = Math.max(2, Math.round(TOLERANCE_DP * density));

        if (memory.widthApplied) {
            int actual = bar.getWidth();
            boolean wrongWidth = Math.abs(actual - memory.expectedWidth) > tolerance;
            boolean offCenter = false;
            if (bar.getParent() instanceof View) {
                View parent = (View) bar.getParent();
                if (parent.getWidth() > 0) {
                    int center = (bar.getLeft() + bar.getRight()) / 2;
                    int parentCenter = (parent.getPaddingLeft() + parent.getWidth() - parent.getPaddingRight()) / 2;
                    offCenter = Math.abs(center - parentCenter) > tolerance;
                }
            }
            if (wrongWidth || offCenter) {
                memory.widthStrikes++;
                final int expected = memory.expectedWidth;
                final int strikes = memory.widthStrikes;
                final String why = wrongWidth ? "is " + actual + " px wide, not " + expected + " px"
                        : "is not in the middle of its row";
                if (strikes >= STRIKES_TO_GIVE_UP) {
                    memory.widthFailed = true;
                    memory.widthStrikes = 0;
                    giveWidthBack(params, memory);
                    givenBack = true;
                    Logger.printInfo(() -> "Floating navigation bar: Instagram's own bar " + why
                            + "; the width of the settings is not applied to it");
                } else {
                    // Look again a few frames later: Instagram may still have been animating the bar.
                    memory.checked = false;
                    memory.framesSinceApply = 0;
                }
            } else {
                memory.widthStrikes = 0;
            }
        }

        if (memory.heightApplied) {
            int actual = bar.getHeight();
            boolean wrongHeight = Math.abs(actual - memory.appliedHeight) > tolerance;
            if (wrongHeight) {
                memory.heightStrikes++;
                final int asked = memory.appliedHeight;
                final int strikes = memory.heightStrikes;
                if (strikes >= STRIKES_TO_GIVE_UP) {
                    memory.heightFailedPx = Integer.MAX_VALUE;
                    memory.heightStrikes = 0;
                    if (giveHeightBack(bar, params, memory)) givenBack = true;
                    Logger.printInfo(() -> "Floating navigation bar: Instagram's own bar is " + actual
                            + " px tall, not " + asked + " px; the height of the settings is not applied to it");
                } else {
                    memory.checked = false;
                    memory.framesSinceApply = 0;
                }
            } else {
                memory.heightStrikes = 0;
                // Tabs taller than the bar are NOT a reason to give up (they used to be): the user chose this
                // height, so the bar gets it. Only noted in the log, once.
                if (bar instanceof ViewGroup) {
                    int content = actual - bar.getPaddingTop() - bar.getPaddingBottom();
                    ViewGroup group = (ViewGroup) bar;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child.getVisibility() == View.GONE || child.getHeight() <= 0) continue;
                        if (child.getHeight() > content + tolerance) {
                            final int childPx = child.getHeight();
                            final int contentPx = content;
                            Logger.printInfo(() -> "Floating navigation bar: Instagram's own bar has tabs " + childPx
                                    + " px tall in a " + contentPx + " px bar; they may be cut off");
                            break;
                        }
                    }
                }
            }
        }
        return givenBack;
    }

    /** One line for the log: the bar, its layout parameters, its parents and its children. */
    private static String describeStructure(View bar, float density) {
        StringBuilder text = new StringBuilder();
        View view = bar;
        for (int level = 0; view != null && level < 4; level++) {
            if (level > 0) text.append(" <- ");
            text.append(view.getClass().getSimpleName()).append('[')
                    .append(Math.round(view.getWidth() / density)).append('x')
                    .append(Math.round(view.getHeight() / density)).append("dp");
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                text.append(" lp=").append(lp.getClass().getSimpleName()).append(' ')
                        .append(sizeName(lp.width)).append('x').append(sizeName(lp.height));
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    text.append(" margins=").append(m.leftMargin).append(',').append(m.topMargin).append(',')
                            .append(m.rightMargin).append(',').append(m.bottomMargin);
                }
            }
            text.append(" pad=").append(view.getPaddingLeft()).append(',').append(view.getPaddingTop()).append(',')
                    .append(view.getPaddingRight()).append(',').append(view.getPaddingBottom());
            if (view.getBackground() != null) text.append(" bg=").append(view.getBackground().getClass().getSimpleName());
            if (view.getScaleX() != 1f || view.getScaleY() != 1f) {
                text.append(" scale=").append(view.getScaleX()).append(',').append(view.getScaleY());
            }
            text.append(']');
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        if (bar instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) bar;
            text.append(" children: ");
            int count = Math.min(group.getChildCount(), 8);
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                if (i > 0) text.append(", ");
                text.append(child.getClass().getSimpleName()).append('[')
                        .append(Math.round(child.getWidth() / density)).append('x')
                        .append(Math.round(child.getHeight() / density)).append("dp")
                        .append(child.getBackground() == null ? "" : " bg").append(']');
            }
        }
        return text.toString();
    }

    /** A readable tree of the bar and its children (two levels down), with sizes, layout parameters and offsets. */
    private static String describeTree(View bar, float density) {
        StringBuilder text = new StringBuilder();
        text.append("Parents: ").append(describeStructure(bar, density).split(" children: ")[0]).append('\n');
        text.append("Density: ").append(density).append('\n');
        text.append("Tree:\n");
        appendNode(text, bar, density, 0);
        return text.toString();
    }

    private static void appendNode(StringBuilder text, View view, float density, int depth) {
        for (int i = 0; i < depth; i++) text.append("  ");
        text.append(view.getClass().getSimpleName()).append(" [")
                .append(Math.round(view.getLeft() / density)).append(',').append(Math.round(view.getTop() / density))
                .append(' ').append(Math.round(view.getWidth() / density)).append('x')
                .append(Math.round(view.getHeight() / density)).append("dp");
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            text.append(" lp=").append(sizeName(lp.width)).append('x').append(sizeName(lp.height));
            int gravity = gravityOf(lp);
            if (gravity != NO_GRAVITY_FIELD) text.append(" gravity=").append(gravity);
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                text.append(" margins=").append(m.leftMargin).append(',').append(m.topMargin).append(',')
                        .append(m.rightMargin).append(',').append(m.bottomMargin);
            }
        }
        text.append(" pad=").append(view.getPaddingLeft()).append(',').append(view.getPaddingTop()).append(',')
                .append(view.getPaddingRight()).append(',').append(view.getPaddingBottom());
        if (view.getTranslationY() != 0f) text.append(" transY=").append(view.getTranslationY());
        if (view.getScaleY() != 1f) text.append(" scaleY=").append(view.getScaleY());
        if (view.getBackground() != null) text.append(" bg=").append(view.getBackground().getClass().getSimpleName());
        if (view.getVisibility() != View.VISIBLE) text.append(" vis=").append(view.getVisibility());
        if (view instanceof ViewGroup) text.append(" clipChildren=").append(((ViewGroup) view).getClipChildren());
        text.append("]\n");
        if (depth >= 2 || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        int count = Math.min(group.getChildCount(), 8);
        for (int i = 0; i < count; i++) appendNode(text, group.getChildAt(i), density, depth + 1);
    }

    private static String sizeName(int size) {
        if (size == ViewGroup.LayoutParams.MATCH_PARENT) return "match";
        if (size == ViewGroup.LayoutParams.WRAP_CONTENT) return "wrap";
        return String.valueOf(size);
    }

    // ---- numbers (no views, so they can be tried on their own) ----

    /** The bar's width in px: the screen minus the distance to each side, and never more than its row has room for. */
    static int targetWidthPx(int screenPx, int sidePx, int availablePx) {
        return Math.max(0, Math.min(screenPx - 2 * sidePx, availablePx));
    }

    /** The margin on each side that leaves a bar of {@code targetPx} in the middle of {@code availablePx}. */
    static int marginFor(int availablePx, int targetPx) {
        return Math.max(0, (availablePx - targetPx) / 2);
    }

    /** A gravity that keeps its vertical part and is centered across. Unspecified keeps a bar at the top. */
    static int centered(int gravity) {
        if (gravity == -1) return Gravity.CENTER_HORIZONTAL | Gravity.TOP;
        return (gravity & ~(Gravity.HORIZONTAL_GRAVITY_MASK | Gravity.RELATIVE_LAYOUT_DIRECTION))
                | Gravity.CENTER_HORIZONTAL;
    }

    private static int gravityOf(ViewGroup.LayoutParams params) {
        if (params instanceof FrameLayout.LayoutParams) return ((FrameLayout.LayoutParams) params).gravity;
        if (params instanceof LinearLayout.LayoutParams) return ((LinearLayout.LayoutParams) params).gravity;
        return NO_GRAVITY_FIELD;
    }

    private static void setGravity(ViewGroup.LayoutParams params, int gravity) {
        if (params instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) params).gravity = gravity;
        } else if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).gravity = gravity;
        }
    }
}
