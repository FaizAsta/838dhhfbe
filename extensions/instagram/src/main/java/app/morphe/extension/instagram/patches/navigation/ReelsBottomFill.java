/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * Makes the picture of a Reel run down behind Instagram's own floating bar, when Instagram keeps the Reels page above
 * the bar and leaves a dark strip under it.
 * <p>
 * Why this is not done with the layout (as {@link BarFrameRemover} does for the other screens): what holds the Reels
 * page up is the same thing that holds its caption, its buttons and its progress bar up, so taking it away would
 * slide all of them down behind the bar. This leaves the layout alone. It only draws the <em>video</em> larger: the
 * video view of the Reel is scaled up from its top edge (a uniform zoom, so the picture is not stretched, and about
 * a tenth of its width is cut at the sides, like the "fill the screen" crop Instagram does on a tall phone) until it
 * reaches the bottom of the window. Everything else on the page stays where Instagram put it, above the bar.
 * <p>
 * What it does, step by step, on what is on the screen (no search in Instagram's code):
 * <ol>
 *   <li>Every 0.4 s the window is searched for video views ({@code TextureView}, {@code SurfaceView}) as wide as the
 *       screen that have a Reels id ("clips") on them or around them.</li>
 *   <li>Before every frame, for each of them: the views around it are followed up until one reaches the bottom of
 *       the window. The last one before that is the Reels page; the room between its bottom and the bottom of the
 *       window is the strip. Nothing is done unless the page is as wide as the screen and most of its height, the
 *       video fills the page, the strip is at least 8 dp and not much more than the bar itself, and the zoom would be
 *       at most 25 %.</li>
 *   <li>The views from the video's parent up to the one that reaches the bottom are told not to clip what they draw
 *       (so the bigger picture can be seen), and the video is scaled about its top middle point.</li>
 *   <li>A picture that does not fill the video view (a wide video with bars above and below it) is left alone: once a
 *       second a tiny copy of a {@code TextureView} is looked at, and when its top or bottom rows are see-through it is
 *       a letterboxed video.</li>
 * </ol>
 * If Instagram scales the video itself (the comments sheet, a gesture), this class steps aside until the video is at
 * its normal size again. Everything it changed (the scale, the pivot, the clip flags) is given back by
 * {@link #putBack} when the setting, Instagram's bar or the bar's visibility goes.
 * <p>
 * It also writes down what it saw (the views around each video, why it did or did not zoom it, and which views lie
 * over the strip) for "Copy navigation bar info" ({@link Memory#report}).
 */
final class ReelsBottomFill {
    private static final long FIND_MS = 400L;
    private static final long CHECK_MS = 1000L;
    private static final long REPORT_MS = 1500L;
    private static final int MAX_NODES = 2500;
    private static final int MAX_ENTRIES = 6;
    private static final int MAX_UP = 40;
    /** The video is at least this share of the screen's width, and of its page's height. */
    private static final float FILLS = 0.9f;
    /** The page is at least this share of the window's height. */
    private static final float MIN_PAGE_HEIGHT = 0.6f;
    private static final float MAX_SCALE = 1.25f;
    private static final float EPS = 0.002f;
    private static final int MIN_STRIP_DP = 8;
    /** The strip may be this much taller than the bar's own room at the bottom, in dp. */
    private static final int STRIP_SLACK_DP = 24;
    private static final int REACH_DP = 3;
    /** The size of the tiny copy of a video that is looked at for bars, and how many rows at each end. */
    private static final int LB_W = 16;
    private static final int LB_H = 32;
    private static final int LB_ROWS = 2;
    private static final int MAX_PROBE_LINES = 24;
    private static final int MAX_PROBE_NODES = 1500;
    private static final String NO_NAME = "";

    private static final HashMap<Integer, String> NAMES = new HashMap<>();

    private ReelsBottomFill() {}

    /** What was changed, so it can be given back, and what was seen. One per window. */
    static final class Memory {
        private final ArrayList<Entry> entries = new ArrayList<>();
        private long nextFindAt;
        private long nextReportAt;
        private int lastNodes;
        private int lastVideos;
        private String report = "Reels fill: not looked yet";

        /** True when nothing is changed (and so there is nothing to put back). */
        boolean isEmpty() {
            return entries.isEmpty();
        }

        /** What the last look found, for "Copy navigation bar info". */
        String report() {
            return report;
        }
    }

    private static final class Clip {
        final WeakReference<ViewGroup> group;
        final boolean clipChildren;
        final boolean clipToPadding;

        Clip(ViewGroup group, boolean clipChildren, boolean clipToPadding) {
            this.group = new WeakReference<>(group);
            this.clipChildren = clipChildren;
            this.clipToPadding = clipToPadding;
        }
    }

    private static final class Entry {
        final WeakReference<View> video;
        final ArrayList<Clip> clips = new ArrayList<>();
        /** The scale this class put on the video (1 = nothing). */
        float applied = 1f;
        boolean foreign;
        boolean letterboxed;
        long nextCheckAt;
        /** Why the video is not zoomed right now (null while it is). */
        String why = "not looked at yet";
        int strip;
        float scale = 1f;

        Entry(View video) {
            this.video = new WeakReference<>(video);
        }
    }

    /** What is needed for one video: the views to stop clipping (the video's parent up to the one that reaches the
     *  bottom of the window), the scale, and the strip in px. */
    private static final class Plan {
        final ArrayList<ViewGroup> chain;
        final float scale;
        final int strip;

        Plan(ArrayList<ViewGroup> chain, float scale, int strip) {
            this.chain = chain;
            this.scale = scale;
            this.strip = strip;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Every frame
    // ------------------------------------------------------------------------------------------------------------

    /** Called before every frame while Instagram's own bar is shown and "Edge-to-edge Reels" is on. */
    static void apply(View decor, View bar, Memory memory, long now) {
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0 || !bar.isLaidOut()) return;
        float density = decor.getResources().getDisplayMetrics().density;

        if (now >= memory.nextFindAt) {
            memory.nextFindAt = now + FIND_MS;
            discover(decor, bar, memory, width);
        }

        for (Iterator<Entry> it = memory.entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            View video = entry.video.get();
            if (video == null || !video.isAttachedToWindow()) {
                undo(entry);
                it.remove();
                continue;
            }
            Plan plan = plan(entry, video, bar, width, height, density, now);
            if (plan == null) {
                undo(entry);
            } else {
                put(entry, video, plan);
            }
        }

        if (now >= memory.nextReportAt) {
            memory.nextReportAt = now + REPORT_MS;
            memory.report = buildReport(decor, bar, memory, width, height, density);
        }
    }

    /** Gives back everything that was changed (the scale, the pivot, the clip flags). */
    static void putBack(Memory memory) {
        for (Entry entry : memory.entries) undo(entry);
        memory.entries.clear();
        memory.nextFindAt = 0L;
        memory.report = "Reels fill: given back (the setting or the bar is off, or the bar is hidden)";
    }

    // ------------------------------------------------------------------------------------------------------------
    // Finding the videos
    // ------------------------------------------------------------------------------------------------------------

    private static void discover(View decor, View bar, Memory memory, int width) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(decor);
        int nodes = 0;
        int videos = 0;
        while (!pending.isEmpty() && nodes < MAX_NODES) {
            View view = pending.poll();
            nodes++;
            if (view == bar) continue;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child != null && child.getVisibility() != View.GONE) pending.add(child);
                }
            }
            if (!(view instanceof TextureView) && !(view instanceof SurfaceView)) continue;
            if (view.getWidth() < width * FILLS || !isReels(view)) continue;
            videos++;
            if (known(memory, view) == null && memory.entries.size() < MAX_ENTRIES) {
                memory.entries.add(new Entry(view));
            }
        }
        memory.lastNodes = nodes;
        memory.lastVideos = videos;
    }

    private static Entry known(Memory memory, View video) {
        for (Entry entry : memory.entries) {
            if (entry.video.get() == video) return entry;
        }
        return null;
    }

    /** A Reels id on the view or on a view around it (Instagram calls Reels "clips"). */
    private static boolean isReels(View view) {
        if (isReelsName(nameOf(view))) return true;
        int depth = 0;
        for (ViewParent p = view.getParent(); p instanceof View && depth < 60; p = p.getParent(), depth++) {
            if (isReelsName(nameOf((View) p))) return true;
        }
        return false;
    }

    private static boolean isReelsName(String name) {
        if (name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("stor")) return false;
        return n.contains("clips") || n.contains("reel");
    }

    /** The name of the view's id (like "clips_viewer_view_pager"), or "" when it has none. */
    private static String nameOf(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return NO_NAME;
        String known = NAMES.get(id);
        if (known != null) return known;
        String name = NO_NAME;
        try {
            String entry = view.getResources().getResourceEntryName(id);
            if (entry != null) name = entry;
        } catch (Throwable ignored) {
            // A view id that has no name.
        }
        if (NAMES.size() > 2000) NAMES.clear();
        NAMES.put(id, name);
        return name;
    }

    // ------------------------------------------------------------------------------------------------------------
    // What is needed for one video
    // ------------------------------------------------------------------------------------------------------------

    private static Plan fail(Entry entry, String why) {
        entry.why = why;
        entry.scale = 1f;
        entry.strip = 0;
        return null;
    }

    private static Plan plan(Entry entry, View video, View bar, int width, int height, float density, long now) {
        if (!video.isShown()) return fail(entry, "the video is not shown");
        if (video.getWidth() < width * FILLS || video.getHeight() <= 0) return fail(entry, "narrower than the screen");
        int[] at = new int[2];
        int reach = Math.round(REACH_DP * density);
        // With the scale this class sets (pivot at the top) the top of the video does not move.
        video.getLocationInWindow(at);
        if (at[1] + video.getHeight() >= height - reach) return fail(entry, "the video already reaches the bottom");

        View outer = video;
        ArrayList<ViewGroup> chain = new ArrayList<>();
        boolean reached = false;
        ViewParent up = video.getParent();
        for (int i = 0; i < MAX_UP && up instanceof ViewGroup; i++) {
            ViewGroup group = (ViewGroup) up;
            group.getLocationInWindow(at);
            chain.add(group);
            if (at[1] + group.getHeight() >= height - reach) {
                reached = true;
                break;
            }
            outer = group;
            up = group.getParent();
        }
        if (!reached) return fail(entry, "no view around the video reaches the bottom of the window");

        // The page is the last view on the way up that still stops short of the bottom.
        outer.getLocationInWindow(at);
        int pageBottom = at[1] + outer.getHeight();
        if (outer.getWidth() < width * FILLS || outer.getHeight() < height * MIN_PAGE_HEIGHT) {
            return fail(entry, "the page around the video is small (" + outer.getWidth() + "x" + outer.getHeight() + ")");
        }
        if (video.getHeight() < outer.getHeight() * FILLS) return fail(entry, "the video does not fill its page");
        int strip = height - pageBottom;
        bar.getLocationInWindow(at);
        int room = height - at[1];
        if (strip < Math.round(MIN_STRIP_DP * density)) return fail(entry, "the strip is thin (" + strip + " px)");
        if (strip > room + Math.round(STRIP_SLACK_DP * density)) {
            return fail(entry, "the strip (" + strip + " px) is taller than the bar's room (" + room + " px)");
        }
        float scale = (video.getHeight() + strip) / (float) video.getHeight();
        if (scale > MAX_SCALE) return fail(entry, "the zoom would be " + Math.round((scale - 1f) * 100f) + " %");

        // A letterboxed picture is left alone. Only looked at while the video is on the screen, once a second.
        if (now >= entry.nextCheckAt && onScreen(video, height)) {
            entry.nextCheckAt = now + CHECK_MS;
            entry.letterboxed = looksLetterboxed(video);
        }
        if (entry.letterboxed) return fail(entry, "the picture has bars above or below it (letterboxed)");

        entry.why = null;
        entry.scale = scale;
        entry.strip = strip;
        return new Plan(chain, scale, strip);
    }

    private static boolean onScreen(View video, int height) {
        Rect visible = new Rect();
        return video.getGlobalVisibleRect(visible) && visible.height() >= height * 0.5f;
    }

    /** True when the top or the bottom rows of a tiny copy of a {@code TextureView} are see-through (a letterboxed
     *  video). A {@code SurfaceView} cannot be copied cheaply: it counts as filling its view. */
    private static boolean looksLetterboxed(View video) {
        if (!(video instanceof TextureView)) return false;
        try {
            TextureView texture = (TextureView) video;
            if (!texture.isAvailable()) return false;
            Bitmap copy = texture.getBitmap(LB_W, LB_H);
            if (copy == null) return false;
            try {
                long top = 0;
                long bottom = 0;
                for (int y = 0; y < LB_ROWS; y++) {
                    for (int x = 0; x < LB_W; x++) {
                        top += Color.alpha(copy.getPixel(x, y));
                        bottom += Color.alpha(copy.getPixel(x, LB_H - 1 - y));
                    }
                }
                long count = (long) LB_ROWS * LB_W;
                return top / count < 64 || bottom / count < 64;
            } finally {
                copy.recycle();
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Putting the zoom on, and taking it off
    // ------------------------------------------------------------------------------------------------------------

    private static void put(Entry entry, View video, Plan plan) {
        // Nothing around the video may clip the bigger picture.
        for (ViewGroup group : plan.chain) {
            boolean clipChildren = group.getClipChildren();
            boolean clipPadding = group.getClipToPadding() && group.getPaddingBottom() > 0;
            if (!clipChildren && !clipPadding) continue;
            if (clipOf(entry, group) == null) {
                entry.clips.add(new Clip(group, clipChildren, group.getClipToPadding()));
            }
            if (clipChildren) group.setClipChildren(false);
            if (clipPadding) group.setClipToPadding(false);
        }

        float x = video.getScaleX();
        float y = video.getScaleY();
        boolean plain = Math.abs(x - 1f) < EPS && Math.abs(y - 1f) < EPS;
        boolean ours = entry.applied != 1f && Math.abs(x - entry.applied) < EPS && Math.abs(y - entry.applied) < EPS;
        if (plain || ours) {
            entry.foreign = false;
            float pivotX = video.getWidth() / 2f;
            if (plain || Math.abs(entry.applied - plan.scale) > EPS || video.getPivotY() != 0f
                    || Math.abs(video.getPivotX() - pivotX) > 1f) {
                video.setPivotX(pivotX);
                video.setPivotY(0f);
                video.setScaleX(plan.scale);
                video.setScaleY(plan.scale);
            }
            if (entry.applied == 1f) {
                final float scale = plan.scale;
                final int strip = plan.strip;
                final int clips = entry.clips.size();
                Logger.printInfo(() -> "Edge-to-edge Reels: a Reel's video zoomed " + scale + "x to reach the bottom ("
                        + strip + " px strip, " + clips + " view(s) no longer clip)");
            }
            entry.applied = plan.scale;
        } else {
            // Instagram scales this video itself right now (the comments sheet, a gesture): left alone.
            entry.applied = 1f;
            entry.foreign = true;
        }
    }

    private static Clip clipOf(Entry entry, ViewGroup group) {
        for (Clip clip : entry.clips) {
            if (clip.group.get() == group) return clip;
        }
        return null;
    }

    private static void undo(Entry entry) {
        View video = entry.video.get();
        if (video != null && entry.applied != 1f
                && Math.abs(video.getScaleX() - entry.applied) < EPS
                && Math.abs(video.getScaleY() - entry.applied) < EPS) {
            video.setScaleX(1f);
            video.setScaleY(1f);
            if (Build.VERSION.SDK_INT >= 28) {
                video.resetPivot();
            } else {
                video.setPivotX(video.getWidth() / 2f);
                video.setPivotY(video.getHeight() / 2f);
            }
        }
        entry.applied = 1f;
        for (Clip clip : entry.clips) {
            ViewGroup group = clip.group.get();
            if (group == null) continue;
            if (clip.clipChildren) group.setClipChildren(true);
            if (clip.clipToPadding) group.setClipToPadding(true);
        }
        entry.clips.clear();
    }

    // ------------------------------------------------------------------------------------------------------------
    // What was seen, for "Copy navigation bar info"
    // ------------------------------------------------------------------------------------------------------------

    private static String buildReport(View decor, View bar, Memory memory, int width, int height, float density) {
        StringBuilder text = new StringBuilder("Reels fill: ").append(memory.lastVideos)
                .append(" Reels video view(s) among ").append(memory.lastNodes).append(" views (window ")
                .append(width).append("x").append(height).append(" px, density ").append(density).append(")");
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        text.append("\n  bar: top ").append(at[1]).append(" px, ").append(bar.getHeight()).append(" px tall");
        int probeY = -1;
        for (Entry entry : memory.entries) {
            View video = entry.video.get();
            if (video == null) continue;
            text.append("\n  - ").append(video.getClass().getSimpleName()).append(" ").append(video.getWidth())
                    .append("x").append(video.getHeight()).append(" ").append(nameOf(video)).append(": ");
            if (entry.why == null) {
                text.append("ZOOMED x").append(entry.scale).append(" (strip ").append(entry.strip).append(" px)");
            } else {
                text.append(entry.why);
            }
            if (entry.foreign) text.append(" [Instagram scales it itself right now]");
            video.getLocationInWindow(at);
            text.append("\n      video y=").append(at[1]).append("..").append(at[1] + video.getHeight());
            ViewParent up = video.getParent();
            for (int i = 0; i < 14 && up instanceof ViewGroup; i++) {
                ViewGroup group = (ViewGroup) up;
                group.getLocationInWindow(at);
                text.append("\n      around ").append(i).append(": ").append(describe(group))
                        .append(" y=").append(at[1]).append("..").append(at[1] + group.getHeight())
                        .append(" clipChildren=").append(group.getClipChildren())
                        .append(" clipToPadding=").append(group.getClipToPadding())
                        .append(" pb=").append(group.getPaddingBottom());
                if (at[1] + group.getHeight() >= height - 2) break;
                up = group.getParent();
            }
            if (probeY < 0 && entry.strip > 0) probeY = height - entry.strip / 2;
        }
        if (probeY < 0) probeY = height - Math.round(24f * density);
        text.append("\n  views over the point x=").append(width / 2).append(" y=").append(probeY)
                .append(" (the middle of the strip), from the back to the front:");
        StringBuilder lines = new StringBuilder();
        probe(decor, bar, width / 2, probeY, 0, lines, new int[]{MAX_PROBE_NODES, 0});
        text.append(lines.length() == 0 ? "\n      (none)" : lines);
        return text.toString();
    }

    private static void probe(View view, View bar, int x, int y, int depth, StringBuilder out, int[] counters) {
        if (counters[0]-- <= 0 || counters[1] >= MAX_PROBE_LINES || view.getVisibility() != View.VISIBLE) return;
        int[] at = new int[2];
        view.getLocationInWindow(at);
        if (x < at[0] || x >= at[0] + view.getWidth() || y < at[1] || y >= at[1] + view.getHeight()) return;
        counters[1]++;
        out.append("\n      ");
        for (int i = 0; i < depth && i < 20; i++) out.append(' ');
        out.append(describe(view)).append(" y=").append(at[1]).append("..").append(at[1] + view.getHeight());
        Drawable background = view.getBackground();
        if (background != null) out.append(" bg=").append(background.getClass().getSimpleName());
        if (view.getAlpha() != 1f) out.append(" alpha=").append(view.getAlpha());
        if (view == bar) out.append(" (the bar)");
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != null) probe(child, bar, x, y, depth + 1, out, counters);
        }
    }

    /** A short name of a view for the report: its class and its id name when it has one. */
    private static String describe(View view) {
        String name = nameOf(view);
        return view.getClass().getSimpleName() + (name.isEmpty() ? "" : "#" + name) + " "
                + view.getWidth() + "x" + view.getHeight();
    }
}
