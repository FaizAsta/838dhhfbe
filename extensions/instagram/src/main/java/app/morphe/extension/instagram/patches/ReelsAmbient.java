/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Ambient glow on Reels, like ambient mode on YouTube: when a Reel does not fill the screen (a wide or a square
 * video, which leaves a bar above and a bar below it) the bars are filled with a soft glow in the colours of the video
 * instead of plain black, and the glow follows the video as it plays.
 * <p>
 * How it is done, without searching for anything in Instagram's code (like {@link
 * app.morphe.extension.instagram.patches.navigation.FloatingNavBar}): before every frame of the main screen is drawn
 * the view tree is looked at (not on every frame, a few times a second) for the video of a Reel, a
 * {@code TextureView} or a {@code SurfaceView} that is as wide as the screen and has, among the views around it,
 * one whose id names the Reels screen (Instagram calls Reels "clips"). The page that holds the video (the nearest view
 * around it that is about as big as the screen and is not a list or a pager) is the host. About ten times a second
 * a tiny copy of the video is taken (96 px wide), the part of it that really shows the picture is found (the rest of a
 * video view that is letterboxed is see-through), and, if the picture leaves a bar of at least 12 dp on any side of the
 * host, its colours are averaged into a small grid, softened and drawn, stretched over the whole host and dimmed,
 * as the <em>background</em> of the host. The video and everything else on the page is drawn on top of it, so only the
 * bars show the glow and nothing is covered. Nothing is touched while a Reel fills the screen: the host's own
 * background is only replaced (by a drawable that draws it first, and the glow after it) while there are bars, and
 * is given back as soon as there are none, when the setting is switched off, or when the page goes away.
 * <p>
 * Limits, so nothing is taken for granted:
 * <ul>
 *   <li>Only bars that are <em>outside</em> the picture are filled: the empty part of a letterboxed video view, or the
 *       page around a smaller view. A video that has black bars baked into the file itself (the bars are part of the
 *       picture) is left as it is.</li>
 *   <li>The host's background must be nothing or a plain colour; if it is anything else the page is left alone.</li>
 *   <li>A {@code SurfaceView} video is copied with {@code PixelCopy} (a few times a second, never on the screen's
 *       thread); a video that is protected cannot be copied and shows no glow.</li>
 *   <li>If Instagram sets the background of the host back many times, the page is given up on until the setting is
 *       switched off and on. Anything that goes wrong turns the glow off for a few seconds and goes into the log; it
 *       never reaches the app.</li>
 * </ul>
 * "Copy Reels ambient info" in the settings copies what was found (the video views, the views around them, why a
 * page was or was not taken), to be pasted in a bug report when no glow shows.
 */
public final class ReelsAmbient {
    private static final String MAIN_ACTIVITY_NAME = "InstagramMainActivity";

    /** How often the setting is read, the video looked for, and a copy taken (while there are bars, and while not). */
    private static final long PREF_MS = 500L;
    private static final long FIND_MS = 400L;
    private static final long SAMPLE_BARS_MS = 90L;
    private static final long SAMPLE_IDLE_MS = 500L;

    /** The copy of the video: 96 px wide, as tall as the video's shape asks for (within these limits). */
    private static final int SAMPLE_W = 96;
    private static final int SAMPLE_MIN_H = 24;
    private static final int SAMPLE_MAX_H = 256;
    /** The glow is a grid this big, stretched over the page (a very small picture, so it is soft). */
    private static final int GLOW_W = 16;
    private static final int GLOW_H = 28;
    private static final int BLUR_PASSES = 2;
    /** How far the shown glow moves towards each new picture (0 to 1): the higher, the quicker it follows. */
    private static final float FOLLOW = 0.40f;
    /** How dark the glow is made (black laid over it, 0 to 1), so it stays a mood and not a second picture. */
    private static final float SCRIM = 0.28f;

    /** A bar must be at least this tall (dp) to be filled. */
    private static final float MIN_BAR_DP = 12f;
    /** The video's view is at least this share of the screen's width; the host at least this share of its size. */
    private static final float MIN_VIDEO_WIDTH_SHARE = 0.5f;
    private static final float MIN_HOST_WIDTH_SHARE = 0.95f;
    private static final float MIN_HOST_HEIGHT_SHARE = 0.70f;
    /** At least this share of the video's view must be on the screen. */
    private static final float MIN_VISIBLE_SHARE = 0.25f;
    /** Bars gone for this many copies in a row: the glow is taken off. */
    private static final int NO_BARS_BEFORE_REMOVAL = 3;

    private static final int MAX_NODES = 4000;
    private static final int MAX_ENTRIES = 3;
    private static final int MAX_BACKGROUND_RESETS = 30;
    private static final int MAX_FAILURES_IN_A_ROW = 5;
    private static final long FAILURE_PAUSE_MS = 10_000L;
    private static final long SUMMARY_MS = 1500L;

    /** The names of the ids that were already looked up (a name is looked up once per id). */
    private static final HashMap<Integer, String> NAMES = new HashMap<>();
    private static final String NO_NAME = "";

    private static final WeakHashMap<View, State> WATCHED = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean started;
    /** What the last look found, for "Copy Reels ambient info". */
    private static volatile String lastSummary;

    private ReelsAmbient() {}

    /** One for each main screen that is watched. */
    private static final class State {
        ViewTreeObserver.OnPreDrawListener preDraw;
        boolean enabled;
        long lastPref = Long.MIN_VALUE / 2;
        long lastFind = Long.MIN_VALUE / 2;
        long lastReport = Long.MIN_VALUE / 2;
        long retryAt;
        int failures;
        final ArrayList<Entry> entries = new ArrayList<>();
    }

    /** One video that has a page to put a glow behind. */
    private static final class Entry {
        final WeakReference<View> video;
        final WeakReference<ViewGroup> host;
        Glow glow;
        long lastSample;
        int noBarsInARow;
        int backgroundResets;
        boolean gaveUp;
        boolean copying;
        Bitmap sample;
        int[] pixels;
        final int[] target = new int[GLOW_W * GLOW_H];
        /** What the last copy showed, for the report. */
        String status = "not copied yet";

        Entry(View video, ViewGroup host) {
            this.video = new WeakReference<>(video);
            this.host = new WeakReference<>(host);
        }

        /** The bitmap to copy the video into: 96 px wide and as tall as the video's shape. */
        Bitmap bitmapFor(View view) {
            int w = Math.max(1, view.getWidth());
            int h = Math.max(1, view.getHeight());
            int sh = Math.max(SAMPLE_MIN_H, Math.min(SAMPLE_MAX_H, Math.round(SAMPLE_W * h / (float) w)));
            if (sample == null || sample.getHeight() != sh) {
                sample = Bitmap.createBitmap(SAMPLE_W, sh, Bitmap.Config.ARGB_8888);
                pixels = new int[SAMPLE_W * sh];
            }
            return sample;
        }
    }

    /** Called when the app starts. */
    public static void start() {
        try {
            synchronized (ReelsAmbient.class) {
                if (started) return;
                Application application = application();
                if (application == null) return;
                started = true;
                application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity activity) {
                        if (isMain(activity)) watch(activity);
                    }
                    @Override public void onActivityPaused(Activity a) {}
                    @Override public void onActivityCreated(Activity a, Bundle b) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {}
                });
            }
        } catch (Throwable t) {
            Logger.printException(() -> "ReelsAmbient.start failed", t);
        }
    }

    private static Application application() {
        try {
            Context context = Utils.getContext();
            if (context != null && context.getApplicationContext() instanceof Application) {
                return (Application) context.getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app instanceof Application) return (Application) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isMain(Activity activity) {
        return activity != null && activity.getClass().getName().endsWith(MAIN_ACTIVITY_NAME);
    }

    private static void watch(Activity activity) {
        try {
            if (activity.getWindow() == null) return;
            final Window window = activity.getWindow();
            final View decor = window.getDecorView();
            final State state;
            synchronized (WATCHED) {
                State known = WATCHED.get(decor);
                if (known != null) {
                    state = known;
                } else {
                    state = new State();
                    WATCHED.put(decor, state);
                }
            }
            if (state.preDraw == null) {
                final WeakReference<View> decorRef = new WeakReference<>(decor);
                // Runs before every frame is drawn; it never stops a frame.
                state.preDraw = () -> {
                    View current = decorRef.get();
                    if (current != null) tick(current, state);
                    return true;
                };
            }
            // On every return to the app: the window can get a new observer while the app was away.
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(state.preDraw);
                observer.addOnPreDrawListener(state.preDraw);
            }
            state.lastPref = Long.MIN_VALUE / 2;
            state.lastFind = Long.MIN_VALUE / 2;
        } catch (Throwable t) {
            Logger.printException(() -> "ReelsAmbient.watch failed", t);
        }
    }

    /**
     * Runs before every frame. An error never leaves this method: after a few in a row the glow is taken off and left
     * alone for a few seconds, so a problem on one Instagram version cannot repeat on every frame or reach the app.
     */
    private static void tick(View decor, State state) {
        long now = SystemClock.uptimeMillis();
        try {
            if (!decor.isAttachedToWindow()) return;
            if (now < state.retryAt) return;
            if (now - state.lastPref >= PREF_MS) {
                state.lastPref = now;
                boolean on = Pref.reelsAmbient();
                if (!on && (state.enabled || !state.entries.isEmpty())) release(state);
                state.enabled = on;
            }
            if (!state.enabled) return;
            if (now - state.lastFind >= FIND_MS) {
                state.lastFind = now;
                find(decor, state, now);
            }
            for (int i = state.entries.size() - 1; i >= 0; i--) {
                if (!step(state.entries.get(i), now)) state.entries.remove(i);
            }
            state.failures = 0;
        } catch (Throwable t) {
            state.failures++;
            final int failures = state.failures;
            Logger.printException(() -> "ReelsAmbient.tick failed (" + failures + " in a row)", t);
            if (state.failures >= MAX_FAILURES_IN_A_ROW) {
                state.retryAt = now + FAILURE_PAUSE_MS;
                state.failures = 0;
                try {
                    release(state);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Finding the video and its page
    // ------------------------------------------------------------------------------------------------------------

    private static void find(View decor, State state, long now) {
        int decorW = decor.getWidth();
        int decorH = decor.getHeight();
        if (decorW <= 0 || decorH <= 0) return;

        boolean report = now - state.lastReport >= SUMMARY_MS;
        StringBuilder summary = report ? new StringBuilder() : null;
        ArrayList<Entry> seen = new ArrayList<>();

        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(decor);
        int nodes = 0;
        int videos = 0;
        while (!pending.isEmpty() && nodes < MAX_NODES) {
            View view = pending.poll();
            nodes++;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child != null) pending.add(child);
                }
            }
            if (!(view instanceof TextureView) && !(view instanceof SurfaceView)) continue;
            videos++;
            Entry entry = consider(view, state, decorW, decorH, seen, summary);
            if (entry != null) seen.add(entry);
        }

        // Pages that are gone (or no longer qualify): give their background back.
        for (int i = state.entries.size() - 1; i >= 0; i--) {
            Entry entry = state.entries.get(i);
            if (!seen.contains(entry)) {
                dispose(entry);
                state.entries.remove(i);
            }
        }
        if (report) {
            state.lastReport = now;
            final int total = videos;
            final int walked = nodes;
            ReelsAmbient.lastSummary = "Reels ambient: " + total + " video view(s) among " + walked
                    + " views" + (pending.isEmpty() ? "" : " (stopped early)") + "\n" + summary;
        }
    }

    /**
     * Looks at one video view. Returns the entry for it (a known one, or a new one) when it is a Reel's video with a
     * page to put a glow behind, or null; the reason goes into {@code summary} when there is one.
     */
    private static Entry consider(View video, State state, int decorW, int decorH, ArrayList<Entry> seen,
                                  StringBuilder summary) {
        String type = video instanceof TextureView ? "TextureView" : "SurfaceView";
        StringBuilder chain = summary != null ? new StringBuilder() : null;
        String line = type + " " + video.getWidth() + "x" + video.getHeight();
        String reason = null;

        if (!video.isShown()) reason = "not shown";
        else if (video.getWidth() < decorW * MIN_VIDEO_WIDTH_SHARE) reason = "too narrow";
        else if (!enoughVisible(video)) reason = "hardly on screen";

        boolean reels = AmbientMath.isReelsName(nameOf(video));
        ViewGroup host = null;
        boolean stopped = false;
        String hostNote = null;
        int depth = 0;
        for (Object p = video.getParent(); p instanceof ViewGroup && depth < 60; p = ((View) p).getParent(), depth++) {
            ViewGroup group = (ViewGroup) p;
            String name = nameOf(group);
            if (AmbientMath.isReelsName(name)) reels = true;
            if (chain != null && depth < 14) {
                chain.append(" < ").append(name.isEmpty() ? group.getClass().getSimpleName() : name);
            }
            if (host != null || stopped) continue;
            if (isScroller(group)) {
                stopped = true;
                hostNote = "stopped at a list/pager (" + group.getClass().getSimpleName() + ")";
            } else if (group.getWidth() >= decorW * MIN_HOST_WIDTH_SHARE
                    && group.getHeight() >= decorH * MIN_HOST_HEIGHT_SHARE
                    && group.getHeight() <= decorH * 1.02f) {
                // The first view around the video that is as big as a page is the page: if it cannot be used, the
                // video is left alone (a bigger view further up would put the glow behind much more than a page).
                Drawable background = group.getBackground();
                stopped = true;
                if (!(group.getParent() instanceof View)) {
                    hostNote = "the page-sized view is the window itself";
                } else if (background == null || background instanceof ColorDrawable || background instanceof Glow) {
                    host = group;
                } else {
                    hostNote = "page has a " + background.getClass().getSimpleName() + " background";
                }
            }
        }
        if (reason == null && !reels) reason = "no Reels id around it";
        if (reason == null && host == null) reason = hostNote != null ? hostNote : "no page-sized view around it";

        Entry entry = null;
        if (reason == null) {
            for (Entry known : state.entries) {
                if (known.video.get() == video) {
                    entry = known;
                    break;
                }
            }
            if (entry == null) {
                if (seen.size() >= MAX_ENTRIES) {
                    reason = "too many videos";
                } else {
                    entry = new Entry(video, host);
                    state.entries.add(entry);
                }
            } else if (entry.host.get() != host) {
                // The page around the video changed: start again for this video.
                dispose(entry);
                state.entries.remove(entry);
                entry = new Entry(video, host);
                state.entries.add(entry);
            }
        }
        if (summary != null) {
            summary.append("- ").append(line).append(": ")
                    .append(reason == null ? "TAKEN (" + entry.status + (entry.gaveUp ? ", gave up" : "") + ")" : reason)
                    .append("\n    around:").append(chain).append("\n");
        }
        return entry;
    }

    private static boolean enoughVisible(View view) {
        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return false;
        long area = (long) view.getWidth() * view.getHeight();
        if (area <= 0) return false;
        return (long) visible.width() * visible.height() >= area * MIN_VISIBLE_SHARE;
    }

    private static boolean isScroller(View view) {
        for (Class<?> c = view.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (AmbientMath.isScrollerName(c.getName())) return true;
        }
        return false;
    }

    /** The name of the view's id (like "clips_viewer_view_pager"), or "" when it has none. */
    private static String nameOf(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return NO_NAME;
        String known = NAMES.get(id);
        if (known != null) return known;
        String name = NO_NAME;
        try {
            name = view.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
        }
        if (NAMES.size() > 2000) NAMES.clear();
        NAMES.put(id, name == null ? NO_NAME : name);
        return name == null ? NO_NAME : name;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Copying the video and drawing the glow
    // ------------------------------------------------------------------------------------------------------------

    /** One look at one page, when it is due. Returns false when the entry is finished and was removed from use. */
    private static boolean step(Entry entry, long now) {
        View video = entry.video.get();
        ViewGroup host = entry.host.get();
        if (video == null || host == null || !video.isAttachedToWindow() || !host.isAttachedToWindow()) {
            dispose(entry);
            return false;
        }
        if (entry.gaveUp) return true;
        keepBackground(entry, host);
        if (entry.gaveUp) return true;

        long due = entry.glow != null ? SAMPLE_BARS_MS : SAMPLE_IDLE_MS;
        if (now - entry.lastSample < due) return true;
        if (!video.isShown()) {
            entry.status = "video not shown";
            removeGlow(entry, host);
            return true;
        }
        entry.lastSample = now;
        if (video instanceof TextureView) {
            TextureView texture = (TextureView) video;
            if (!texture.isAvailable()) {
                entry.status = "texture not available";
                return true;
            }
            Bitmap bitmap = entry.bitmapFor(texture);
            if (texture.getBitmap(bitmap) == null) {
                entry.status = "texture gave no picture";
                return true;
            }
            process(entry, video, host);
        } else if (video instanceof SurfaceView) {
            copySurface(entry, (SurfaceView) video);
        }
        return true;
    }

    private static void copySurface(Entry entry, SurfaceView surface) {
        if (entry.copying) return;
        if (!surface.getHolder().getSurface().isValid()) {
            entry.status = "surface not valid";
            return;
        }
        Bitmap bitmap = entry.bitmapFor(surface);
        entry.copying = true;
        try {
            PixelCopy.request(surface, bitmap, result -> {
                entry.copying = false;
                try {
                    View video = entry.video.get();
                    ViewGroup host = entry.host.get();
                    if (result != PixelCopy.SUCCESS) {
                        entry.status = "PixelCopy failed (" + result + ")";
                    } else if (video != null && host != null && !entry.gaveUp) {
                        process(entry, video, host);
                    }
                } catch (Throwable t) {
                    Logger.printException(() -> "ReelsAmbient: processing a surface copy failed", t);
                }
            }, MAIN);
        } catch (Throwable t) {
            entry.copying = false;
            entry.status = "PixelCopy could not start: " + t.getClass().getSimpleName();
        }
    }

    /** The copy of the video is in {@code entry.sample}: find the picture in it, and put the glow on or take it off. */
    private static void process(Entry entry, View video, ViewGroup host) {
        Bitmap bitmap = entry.sample;
        if (bitmap == null || entry.pixels == null) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        bitmap.getPixels(entry.pixels, 0, w, 0, 0, w, h);

        int[] rect = AmbientMath.contentRect(entry.pixels, w, h);
        if (rect == null) {
            // Nothing is drawn yet (a video that has not started): leave things as they are.
            entry.status = "frame is empty";
            return;
        }

        // Where the picture is on the host, in the host's pixels (the video's view is stretched to the copy).
        int[] viewAt = new int[2];
        int[] hostAt = new int[2];
        video.getLocationInWindow(viewAt);
        host.getLocationInWindow(hostAt);
        float scaleX = video.getWidth() / (float) w;
        float scaleY = video.getHeight() / (float) h;
        float left = viewAt[0] - hostAt[0] + rect[0] * scaleX;
        float top = viewAt[1] - hostAt[1] + rect[1] * scaleY;
        float right = viewAt[0] - hostAt[0] + rect[2] * scaleX;
        float bottom = viewAt[1] - hostAt[1] + rect[3] * scaleY;
        float minBar = MIN_BAR_DP * host.getResources().getDisplayMetrics().density;
        boolean bars = AmbientMath.barsVisible(host.getWidth(), host.getHeight(), left, top, right, bottom, minBar);

        int[] black = AmbientMath.blackEdgeRows(entry.pixels, w, rect);
        entry.status = "picture " + Math.round(left) + "," + Math.round(top) + " to " + Math.round(right) + ","
                + Math.round(bottom) + " on a " + host.getWidth() + "x" + host.getHeight() + " page; "
                + (bars ? "bars" : "no bars") + "; black rows in the picture: " + black[0] + " above, "
                + black[1] + " below";

        if (!bars) {
            entry.noBarsInARow++;
            if (entry.noBarsInARow >= NO_BARS_BEFORE_REMOVAL) removeGlow(entry, host);
            return;
        }
        entry.noBarsInARow = 0;

        AmbientMath.grid(entry.pixels, w, rect, GLOW_W, GLOW_H, entry.target);
        AmbientMath.blur(entry.target, GLOW_W, GLOW_H, BLUR_PASSES);
        if (entry.glow == null) {
            Glow glow = new Glow(host.getBackground());
            host.setBackground(glow);
            entry.glow = glow;
        }
        entry.glow.show(entry.target);
    }

    /** Instagram can set the page's background again: put the glow back, a limited number of times. */
    private static void keepBackground(Entry entry, ViewGroup host) {
        Glow glow = entry.glow;
        if (glow == null || host.getBackground() == glow) return;
        entry.backgroundResets++;
        Drawable now = host.getBackground();
        if (entry.backgroundResets > MAX_BACKGROUND_RESETS || !(now == null || now instanceof ColorDrawable)) {
            entry.gaveUp = true;
            entry.status = "gave up: Instagram keeps setting the page's background";
            entry.glow = null;
            Logger.printInfo(() -> "ReelsAmbient: gave up on a page, its background keeps being set");
            return;
        }
        Glow again = new Glow(now);
        again.copyFrom(glow);
        host.setBackground(again);
        entry.glow = again;
    }

    private static void removeGlow(Entry entry, ViewGroup host) {
        Glow glow = entry.glow;
        entry.glow = null;
        entry.noBarsInARow = 0;
        if (glow != null && host.getBackground() == glow) host.setBackground(glow.original);
    }

    /** Gives the page's background back (when the glow is still the page's background). */
    private static void dispose(Entry entry) {
        ViewGroup host = entry.host.get();
        if (host != null) removeGlow(entry, host);
        entry.glow = null;
    }

    private static void release(State state) {
        for (Entry entry : state.entries) dispose(entry);
        state.entries.clear();
    }

    /** What the last look found, or null before the first look. */
    public static String debugReport() {
        String report = lastSummary;
        if (report == null) return null;
        return "Piko Reels ambient\nVersion of Android: " + android.os.Build.VERSION.SDK_INT
                + "\nSetting on: " + Pref.reelsAmbient() + "\n" + report;
    }

    // ------------------------------------------------------------------------------------------------------------
    // The glow
    // ------------------------------------------------------------------------------------------------------------

    /**
     * The background of a page while it has bars: the page's own background first (a plain colour, or nothing), then
     * the glow: a small grid of colours, stretched over the whole page and dimmed. The video is drawn over it.
     */
    private static final class Glow extends Drawable {
        final Drawable original;
        private final Bitmap image = Bitmap.createBitmap(GLOW_W, GLOW_H, Bitmap.Config.ARGB_8888);
        private final int[] shown = new int[GLOW_W * GLOW_H];
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint scrim = new Paint();
        private boolean hasImage;

        Glow(Drawable original) {
            this.original = original;
            scrim.setColor(Color.argb(Math.round(255f * SCRIM), 0, 0, 0));
        }

        /** The glow of the one this replaces, so a background set again by Instagram does not make the glow jump. */
        void copyFrom(Glow other) {
            if (!other.hasImage) return;
            System.arraycopy(other.shown, 0, shown, 0, shown.length);
            hasImage = true;
            image.setPixels(shown, 0, GLOW_W, 0, 0, GLOW_W, GLOW_H);
        }

        /** The glow follows {@code target}; the first time it simply is {@code target}. */
        void show(int[] target) {
            if (!hasImage) {
                System.arraycopy(target, 0, shown, 0, shown.length);
                hasImage = true;
            } else {
                AmbientMath.follow(shown, target, FOLLOW);
            }
            image.setPixels(shown, 0, GLOW_W, 0, 0, GLOW_W, GLOW_H);
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            if (original != null) original.setBounds(bounds);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (original != null) original.draw(canvas);
            if (!hasImage || bounds.isEmpty()) return;
            canvas.drawBitmap(image, null, bounds, paint);
            canvas.drawRect(bounds, scrim);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
