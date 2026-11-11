/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

import app.morphe.extension.shared.Logger;

/**
 * Takes away what is still left of Instagram's old bottom bar frame once the bar is a floating pill: the solid strip
 * the pill used to lie on (above it, below it and beside it), with the page ending above it.
 * <p>
 * {@link FloatingNavBar} already stops the bar from drawing Instagram's own background and takes the bottom margin of
 * the screen container ({@code layout_container_main}) to 0. That is enough only when those two are the whole frame.
 * This class deals with the other ways a frame can stay, and it finds them by what is on the screen (where each view
 * sits and how big it is), not by their names, so it does not depend on one Instagram layout:
 * <ol>
 *   <li><b>A view that only paints the strip.</b> A bottom container around the bar (the bar plus the room of the
 *       system inset under it) with a background of its own, a plain view beside the bar that lies in the strip and
 *       has nothing in it (for instance a view that colours the gesture inset), or such a view anywhere else in the
 *       window outside of the page. Their backgrounds are replaced by an empty one (the original is kept and given
 *       back).</li>
 *   <li><b>Space that is kept free for the bar somewhere else than on the container.</b> The page is followed from the
 *       point where it joins the bar's layout down through its single-child frames. The first one that stops short of
 *       the bar's bottom edge is held up either by its own bottom margin or by the bottom padding of the view around
 *       it. When that margin or padding is about the size of the bar, it is taken to 0 (and given back). Margins and
 *       paddings of other sizes are never touched, and nothing is touched while the page already reaches the bottom of
 *       the bar.</li>
 *   <li><b>A page that lies beside the bar instead of under it.</b> When nothing above is what holds the page up, the
 *       page is in a column that puts the bar below it, or in a layout that puts it above the bar. The page is then
 *       let run down to the bar's bottom edge (a negative bottom margin in a column, no "above the bar" rule in a
 *       relative layout), and that is given back as well.</li>
 * </ol>
 * Everything is remembered per view and put back by {@link #putBack}. Nothing here is searched for in Instagram's
 * code, so no fingerprint can fail on any Instagram version. When the page still stops short and nothing could be
 * done, the views around the bar are written to the log (lines that start with "Floating navigation bar:") so the
 * layout can be read from there.
 */
final class BarFrameRemover {
    /** How many wrappers around the bar are looked at (a wrapper is a view around the bar that is only the strip). */
    private static final int MAX_WRAPPERS = 4;
    /** How far the page is followed down through views that have only one shown child. */
    private static final int MAX_FOLLOW = 6;
    /** How long to wait before looking again when the page reaches the bar's bottom, and when it does not and no
     *  margin or padding that fits was found. A margin or padding that was just taken needs no wait. */
    private static final long IDLE_MS = 250L;
    private static final long BLOCKED_MS = 700L;
    /** How often the whole window is searched for views that only paint the strip, and how many views at most. */
    private static final long SCAN_MS = 500L;
    private static final int SCAN_BUDGET = 700;
    /** How often the log gets the layout again while the page still stops short. */
    private static final long REPORT_MS = 8000L;
    /** The room under the bar that can belong to a system inset (gesture bar, three buttons), in dp. */
    private static final int INSET_ROOM_DP = 72;
    /** A strip painter is at least this tall (a thin line or a progress bar is not a strip), in dp. */
    private static final int MIN_STRIP_DP = 8;

    private BarFrameRemover() {}

    /** What was changed, so it can be given back. One per bar. */
    static final class Memory {
        private final ArrayList<Painted> painted = new ArrayList<>();
        private final ArrayList<Freed> freed = new ArrayList<>();
        private final ArrayList<Rule> rules = new ArrayList<>();
        private long nextLookAt;
        private long nextScanAt;
        private long nextReportAt;
        private boolean layoutReported;

        /** True when nothing is changed (and so there is nothing to put back). */
        boolean isEmpty() {
            return painted.isEmpty() && freed.isEmpty() && rules.isEmpty();
        }
    }

    private static final class Painted {
        final WeakReference<View> view;
        Drawable original;

        Painted(View view, Drawable original) {
            this.view = new WeakReference<>(view);
            this.original = original;
        }
    }

    private static final class Freed {
        final WeakReference<View> view;
        /** True for the bottom margin, false for the bottom padding. */
        final boolean margin;
        /** The value Instagram had. */
        int original;
        /** The value this class put there (0, or a negative margin that lets the page run under the bar). */
        int applied;

        Freed(View view, boolean margin, int original, int applied) {
            this.view = new WeakReference<>(view);
            this.margin = margin;
            this.original = original;
            this.applied = applied;
        }
    }

    /** The "above the bar" rule of a page in a relative layout, taken off so the page runs down to the bottom. */
    private static final class Rule {
        final WeakReference<View> view;
        final int anchor;
        final boolean hadAlignBottom;

        Rule(View view, int anchor, boolean hadAlignBottom) {
            this.view = new WeakReference<>(view);
            this.anchor = anchor;
            this.hadAlignBottom = hadAlignBottom;
        }
    }

    /** A background that draws nothing. It is what a strip painter wears while the pill is on. */
    private static final class Empty extends Drawable {
        @Override public void draw(Canvas canvas) {}
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSPARENT; }
    }

    // ------------------------------------------------------------------------------------------------------------
    // 1. Views that only paint the strip
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Stops the views that paint the strip around the bar. Does not change any layout. {@code page} is the screen
     * container (or the view that was taken for it): it is never touched, and the search of the whole window leaves
     * it out; without a page that search is not done.
     */
    static void clearPaint(View bar, View page, Memory memory) {
        int height = bar.getHeight();
        if (height <= 0 || bar.getWidth() <= 0 || !bar.isAttachedToWindow()) return;
        float density = bar.getResources().getDisplayMetrics().density;
        int slack = Math.round(6f * density);
        int reach = height + Math.round(INSET_ROOM_DP * density);
        int minHeight = Math.round(MIN_STRIP_DP * density);
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        int top = at[1];
        int bottom = top + height;
        int wide = Math.round(bar.getWidth() * 0.9f);
        // The strip runs down to the bottom of the window, also when the bar stops above it (the system inset).
        int stripBottom = Math.max(bottom, bar.getRootView().getHeight());

        if (!memory.layoutReported && bar.isLaidOut()) {
            memory.layoutReported = true;
            reportLayout(bar, page);
        }

        View inner = bar;
        ViewParent up = bar.getParent();
        for (int level = 0; level <= MAX_WRAPPERS && up instanceof ViewGroup; level++) {
            ViewGroup group = (ViewGroup) up;
            // A wrapper: only the strip (the bar and the room under it), not the window's own root.
            boolean wrapper = isStrip(group, bottom, reach, slack);
            if (wrapper) blank(group, memory);
            paintersBeside(group, inner, wide, top, stripBottom, reach, minHeight, slack, memory);
            if (!wrapper) break;
            inner = group;
            up = group.getParent();
        }

        long now = SystemClock.uptimeMillis();
        if (page != null && now >= memory.nextScanAt) {
            memory.nextScanAt = now + SCAN_MS;
            scan(bar.getRootView(), bar, page, top, stripBottom, wide, reach, minHeight, slack, memory,
                    new int[]{SCAN_BUDGET});
        }
    }

    /** A view around the bar that is no taller than the bar and its inset room, reaches down to the bar's bottom, and
     *  is not the window's own root. Its own background is the strip. */
    private static boolean isStrip(ViewGroup group, int barBottom, int reach, int slack) {
        int height = group.getHeight();
        if (height <= 0 || height > reach || !(group.getParent() instanceof ViewGroup)) return false;
        int[] at = new int[2];
        group.getLocationInWindow(at);
        return at[1] + height >= barBottom - slack;
    }

    /** Blanks the views of {@code group} (other than {@code except}) that are nothing but a painted strip. */
    private static void paintersBeside(ViewGroup group, View except, int wide, int top, int stripBottom, int reach,
                                       int minHeight, int slack, Memory memory) {
        int[] at = new int[2];
        for (int i = 0; i < group.getChildCount(); i++) {
            View sibling = group.getChildAt(i);
            if (sibling == except || sibling.getVisibility() != View.VISIBLE || sibling.getAlpha() == 0f) continue;
            if (!paintsOnly(sibling)) continue;
            int height = sibling.getHeight();
            if (sibling.getWidth() < wide || height < minHeight || height > reach) continue;
            sibling.getLocationInWindow(at);
            if (at[1] < top - slack || at[1] + height > stripBottom + slack) continue;
            blank(sibling, memory);
        }
    }

    /** Looks through the window (outside of the page and the bar) for views that only paint the strip. */
    private static void scan(View view, View bar, View page, int top, int stripBottom, int wide, int reach,
                             int minHeight, int slack, Memory memory, int[] budget) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        int[] at = new int[2];
        for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
            View child = group.getChildAt(i);
            budget[0]--;
            if (child == bar || child == page) continue;
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() == 0f) continue;
            if (paintsOnly(child)) {
                int height = child.getHeight();
                if (child.getWidth() >= wide && height >= minHeight && height <= reach) {
                    child.getLocationInWindow(at);
                    if (at[1] >= top - slack && at[1] + height <= stripBottom + slack) blank(child, memory);
                }
                continue;
            }
            scan(child, bar, page, top, stripBottom, wide, reach, minHeight, slack, memory, budget);
        }
    }

    /** A view with a background of its own and nothing in it: it is there only to paint. Text, pictures and
     *  progress bars are content, even when they have a background. */
    private static boolean paintsOnly(View view) {
        Drawable background = view.getBackground();
        if (background == null || background instanceof Empty) return false;
        if (view instanceof TextView || view instanceof ImageView || view instanceof ProgressBar) return false;
        return !hasShownChild(view);
    }

    private static boolean hasShownChild(View view) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() == View.VISIBLE) return true;
        }
        return false;
    }

    private static void blank(View view, Memory memory) {
        Drawable current = view.getBackground();
        if (current == null || current instanceof Empty) return;
        Painted known = null;
        for (Painted entry : memory.painted) {
            if (entry.view.get() == view) {
                known = entry;
                break;
            }
        }
        if (known == null) {
            memory.painted.add(new Painted(view, current));
            Logger.printInfo(() -> "Floating navigation bar: background of " + describe(view) + " taken off");
        } else {
            // Instagram set a new background after ours: that one is the one to give back.
            known.original = current;
        }
        view.setBackground(new Empty());
    }

    // ------------------------------------------------------------------------------------------------------------
    // 2. Space kept free for the bar, and a page that lies beside the bar
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Looks for what keeps the page above the bar's bottom edge and takes it away: a margin or padding about the size
     * of the bar goes to 0, and a page that lies beside the bar is let run down under it.
     * True when one was changed, so the frame is laid out again before it is drawn.
     */
    static boolean freeSpace(View bar, View main, Memory memory) {
        long now = SystemClock.uptimeMillis();
        if (now < memory.nextLookAt) return false;
        if (main == null || !main.isAttachedToWindow() || !bar.isAttachedToWindow() || !bar.isLaidOut()
                || bar.getHeight() <= 0) {
            return false;
        }
        // A layout is waiting to run: the sizes read now are the old ones.
        if (main.isLayoutRequested() || bar.isLayoutRequested()) return false;
        ArrayList<View> path = pagePath(bar, main);
        if (path == null || path.isEmpty()) {
            memory.nextLookAt = now + BLOCKED_MS;
            return false;
        }

        float density = bar.getResources().getDisplayMetrics().density;
        int tolerance = Math.round(2f * density);
        int barHeight = bar.getHeight();
        // A margin or padding is "about the size of the bar" when it is between these.
        int low = Math.round(barHeight * 0.4f);
        int high = barHeight + Math.round(24f * density);
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        int barBottom = at[1] + barHeight;

        for (int i = 0; i < path.size(); i++) {
            View node = path.get(i);
            // Not laid out yet: its size cannot be trusted. Look again soon.
            if (!node.isLaidOut() || node.getHeight() <= 0) {
                memory.nextLookAt = now + 100L;
                return false;
            }
            node.getLocationInWindow(at);
            int missing = barBottom - (at[1] + node.getHeight());
            if (missing <= tolerance) continue;

            // The first view on the way down that stops short of the bar's bottom edge. Something holds it up.
            boolean freed = false;
            ViewGroup.LayoutParams params = node.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                if (margins.bottomMargin >= low && margins.bottomMargin <= high) {
                    remember(memory, node, true, margins.bottomMargin, 0);
                    margins.bottomMargin = 0;
                    node.setLayoutParams(margins);
                    freed = true;
                }
            }
            // The first view of the path is a child of the layout that also holds the bar: that layout's padding
            // belongs to the bar as well, so it is left alone. Further down, the view above is the page's own.
            if (!freed && i > 0) {
                View above = path.get(i - 1);
                int padding = above.getPaddingBottom();
                if (padding >= low && padding <= high) {
                    remember(memory, above, false, padding, 0);
                    above.setPadding(above.getPaddingLeft(), above.getPaddingTop(), above.getPaddingRight(), 0);
                    freed = true;
                }
            }
            // Nothing holds the page up: it lies beside the bar (in a column or above it in a relative layout).
            if (!freed) freed = runUnderBar(node, missing, memory);
            if (!freed) reportShortPage(path, node, missing, memory, now);
            memory.nextLookAt = freed ? 0L : now + BLOCKED_MS;
            return freed;
        }
        // The page reaches the bottom of the bar: nothing to do for now.
        memory.nextLookAt = now + IDLE_MS;
        return false;
    }

    /**
     * Lets a page that lies beside the bar run down under it. Only for the two layouts where that is certain: a
     * column in which the page takes the room that is left (the bar below it then lies over the page's bottom with a
     * negative margin on the page), and a relative layout that puts the page above the bar (the rule is taken off and
     * the page goes to the bottom). Anything else is left alone.
     */
    private static boolean runUnderBar(View node, int missing, Memory memory) {
        ViewParent parent = node.getParent();
        ViewGroup.LayoutParams params = node.getLayoutParams();
        if (parent instanceof LinearLayout && params instanceof LinearLayout.LayoutParams) {
            LinearLayout column = (LinearLayout) parent;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
            if (column.getOrientation() == LinearLayout.VERTICAL && lp.weight > 0f && lp.height == 0) {
                int applied = lp.bottomMargin - missing;
                remember(memory, node, true, lp.bottomMargin, applied);
                lp.bottomMargin = applied;
                node.setLayoutParams(lp);
                return true;
            }
        }
        if (parent instanceof RelativeLayout && params instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) params;
            int anchor = lp.getRule(RelativeLayout.ABOVE);
            if (anchor != 0 && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                rememberRule(memory, node, anchor, lp.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0);
                lp.removeRule(RelativeLayout.ABOVE);
                lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                node.setLayoutParams(lp);
                return true;
            }
        }
        return false;
    }

    /**
     * The page from where it joins the bar's layout down to its last single-child frame, top to bottom. Null when
     * the page contains the bar or is not in the bar's window.
     */
    private static ArrayList<View> pagePath(View bar, View main) {
        ArrayList<ViewParent> aroundBar = new ArrayList<>();
        for (ViewParent p = bar.getParent(); p != null && aroundBar.size() < 48; p = p.getParent()) {
            aroundBar.add(p);
        }
        if (aroundBar.contains(main)) return null;

        ArrayList<View> up = new ArrayList<>();
        View node = main;
        boolean joined = false;
        for (int i = 0; i < 48 && node != null; i++) {
            up.add(node);
            ViewParent parent = node.getParent();
            if (parent == null) break;
            if (aroundBar.contains(parent)) {
                joined = true;
                break;
            }
            node = parent instanceof View ? (View) parent : null;
        }
        if (!joined) return null;

        ArrayList<View> path = new ArrayList<>(up.size() + MAX_FOLLOW);
        for (int i = up.size() - 1; i >= 0; i--) path.add(up.get(i));
        View current = main;
        for (int i = 0; i < MAX_FOLLOW; i++) {
            View only = onlyShownChild(current);
            if (only == null) break;
            path.add(only);
            current = only;
        }
        return path;
    }

    private static View onlyShownChild(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        View found = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            if (found != null) return null;
            found = child;
        }
        return found;
    }

    /**
     * The view that holds the page, found by where it is when its id is not there: the biggest view beside the bar
     * (or beside the layouts around it) that is nearly as wide as the window and takes at least 40 % of its height.
     * Null when there is none.
     */
    static View findPage(View bar) {
        View root = bar.getRootView();
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return null;
        View child = bar;
        ViewParent up = bar.getParent();
        for (int level = 0; level < 8 && up instanceof ViewGroup; level++) {
            ViewGroup group = (ViewGroup) up;
            View best = null;
            long bestArea = 0;
            for (int i = 0; i < group.getChildCount(); i++) {
                View sibling = group.getChildAt(i);
                if (sibling == child || sibling.getVisibility() != View.VISIBLE) continue;
                if (sibling.getWidth() < width * 0.9f || sibling.getHeight() < height * 0.4f) continue;
                long area = (long) sibling.getWidth() * sibling.getHeight();
                if (area > bestArea) {
                    best = sibling;
                    bestArea = area;
                }
            }
            if (best != null) return best;
            child = group;
            up = group.getParent();
        }
        return null;
    }

    /**
     * Remembers a bottom margin or padding that is about to be changed. When the view is already known and its value
     * is not the one this class put there, Instagram has set a new one: that is the one to give back.
     */
    private static void remember(Memory memory, View view, boolean margin, int original, int applied) {
        for (Freed entry : memory.freed) {
            if (entry.view.get() == view && entry.margin == margin) {
                if (original != entry.applied) entry.original = original;
                entry.applied = applied;
                return;
            }
        }
        memory.freed.add(new Freed(view, margin, original, applied));
        Logger.printInfo(() -> "Floating navigation bar: bottom " + (margin ? "margin" : "padding") + " of "
                + describe(view) + " (" + original + " px) taken to " + applied + " px");
    }

    private static void rememberRule(Memory memory, View view, int anchor, boolean hadAlignBottom) {
        for (Rule entry : memory.rules) {
            if (entry.view.get() == view) return;
        }
        memory.rules.add(new Rule(view, anchor, hadAlignBottom));
        Logger.printInfo(() -> "Floating navigation bar: " + describe(view)
                + " no longer placed above the bar, it runs down to the bottom");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Giving it all back
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Gives back what was taken. The backgrounds go back at once. The margins, paddings and rules go back only while
     * the bar is shown (while a chat hides the bar, 0 is what Instagram wants there); until then they stay
     * remembered. True when a layout changed.
     */
    static boolean putBack(Memory memory, boolean barShown) {
        for (Painted entry : memory.painted) {
            View view = entry.view.get();
            // Only when the view still wears the empty background: a background Instagram set since stays.
            if (view != null && view.getBackground() instanceof Empty) view.setBackground(entry.original);
        }
        memory.painted.clear();
        memory.nextLookAt = 0L;
        memory.nextScanAt = 0L;
        if (!barShown) return false;

        boolean relayout = false;
        for (Iterator<Freed> it = memory.freed.iterator(); it.hasNext(); ) {
            Freed entry = it.next();
            it.remove();
            View view = entry.view.get();
            if (view == null || entry.original == entry.applied) continue;
            if (entry.margin) {
                ViewGroup.LayoutParams params = view.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    // When Instagram has set a margin itself in the meantime, it is left as it is.
                    if (margins.bottomMargin == entry.applied) {
                        margins.bottomMargin = entry.original;
                        view.setLayoutParams(margins);
                        relayout = true;
                    }
                }
            } else if (view.getPaddingBottom() == entry.applied) {
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), entry.original);
                relayout = true;
            }
        }
        for (Iterator<Rule> it = memory.rules.iterator(); it.hasNext(); ) {
            Rule entry = it.next();
            it.remove();
            View view = entry.view.get();
            if (view == null) continue;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) params;
                // Only while the rule is still off: a rule Instagram set since stays.
                if (lp.getRule(RelativeLayout.ABOVE) == 0) {
                    lp.addRule(RelativeLayout.ABOVE, entry.anchor);
                    if (!entry.hadAlignBottom) lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                    view.setLayoutParams(lp);
                    relayout = true;
                }
            }
        }
        return relayout;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Log
    // ------------------------------------------------------------------------------------------------------------

    /** The layout around the bar, once per bar, so a strip that stays can be read from the log. */
    private static void reportLayout(View bar, View page) {
        StringBuilder text = new StringBuilder("Floating navigation bar: layout around the bar");
        text.append("\n  bar: ").append(describeFull(bar));
        text.append("\n  page: ").append(page == null ? "not found" : describeFull(page));
        ViewParent up = bar.getParent();
        for (int level = 0; level < 6 && up instanceof ViewGroup; level++) {
            ViewGroup group = (ViewGroup) up;
            text.append("\n  around ").append(level).append(": ").append(describeFull(group));
            for (int i = 0; i < group.getChildCount() && i < 12; i++) {
                text.append("\n      child: ").append(describeFull(group.getChildAt(i)));
            }
            up = group.getParent();
        }
        final String message = text.toString();
        Logger.printInfo(() -> message);
    }

    /** The page stops short of the bar and nothing could be done: the path of the page, at most every few seconds. */
    private static void reportShortPage(ArrayList<View> path, View node, int missing, Memory memory, long now) {
        if (now < memory.nextReportAt) return;
        memory.nextReportAt = now + REPORT_MS;
        StringBuilder text = new StringBuilder("Floating navigation bar: the page stops ").append(missing)
                .append(" px above the bottom of the bar and nothing could be taken away. First view that stops")
                .append(" short: ").append(describeFull(node)).append("\n  path of the page:");
        for (View view : path) text.append("\n      ").append(describeFull(view));
        final String message = text.toString();
        Logger.printInfo(() -> message);
    }

    /** A short name of a view for the log: its class, its id name (when it has one) and its size. */
    private static String describe(View view) {
        String name = view.getClass().getSimpleName();
        try {
            if (view.getId() != View.NO_ID) {
                name += "#" + view.getResources().getResourceEntryName(view.getId());
            }
        } catch (RuntimeException ignored) {
            // A view id that has no name.
        }
        return name + " " + view.getWidth() + "x" + view.getHeight();
    }

    /** {@link #describe} plus where the view sits and what holds it up: layout, margin, padding, background. */
    private static String describeFull(View view) {
        StringBuilder text = new StringBuilder(describe(view));
        text.append(" y=").append(view.getTop()).append("..").append(view.getBottom());
        ViewParent parent = view.getParent();
        if (parent != null) text.append(" in=").append(parent.getClass().getSimpleName());
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            text.append(" lp=").append(params.getClass().getName().replaceAll("^.*[.$]", ""));
            if (params instanceof ViewGroup.MarginLayoutParams) {
                text.append(" mb=").append(((ViewGroup.MarginLayoutParams) params).bottomMargin);
            }
        }
        text.append(" pb=").append(view.getPaddingBottom());
        Drawable background = view.getBackground();
        if (background != null) text.append(" bg=").append(background.getClass().getSimpleName());
        if (view.getVisibility() != View.VISIBLE) text.append(" vis=").append(view.getVisibility());
        return text.toString();
    }
}
