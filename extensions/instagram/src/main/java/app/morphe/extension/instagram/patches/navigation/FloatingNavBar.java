/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.patches.devFlags.HookFlags;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Turns Instagram's bottom navigation bar into a floating pill that stands on its own over the whole app: a rounded
 * bar that keeps a distance from both sides of the screen and from the bottom edge, with nothing behind it. Instagram's
 * bar frame (its background strip and the line above it) is gone and the screen under it (feed, Reels, search,
 * profile, chats' inbox) runs all the way down behind the pill instead of ending above a reserved strip.
 * <p>
 * Nothing of Instagram's layout is replaced or moved, and nothing is searched for in its code (so no fingerprint can
 * fail on any Instagram version): the bar and the container of the screen are found by their ids
 * ({@code tab_bar}, {@code layout_container_main}), and only a few things are changed, each remembered and put back
 * when the setting is switched off:
 * <ul>
 *   <li>the bar's background: Instagram's own background is no longer drawn, so there is no frame around the pill.
 *       It is kept (not drawn) to read the theme colour from and to give back. The pill is the bar's background and
 *       is drawn behind the tabs;</li>
 *   <li>the bar's left and right padding, so the tabs sit inside the pill and are never cut off at its rounded ends.
 *       The bottom padding is left alone: it is the system inset (gesture bar), and the pill ends right above it;</li>
 *   <li>the thin line above the bar ({@code tab_bar_shadow}) is made invisible, since the pill has its own edge;</li>
 *   <li>the bar takes every tap on a part of it that is not a tab, so it never reaches the page under it (a caption,
 *       a hashtag, a button, the Reel). The bar is made clickable <em>and</em> given a touch listener that takes what
 *       the tabs did not (Instagram turns the bar's clickable off on some tabs and when the app comes back from the
 *       background, and a bar that is not clickable lets taps through to what lies under it); both are checked
 *       again before every frame;</li>
 *   <li>the bottom margin of the screen container (the strip Instagram keeps free for its bar) is taken to 0 while the
 *       bar is shown, so the page lies under the pill. The bar already sits over the container, so it is drawn on
 *       top of the page;</li>
 *   <li>anything else that is left of the old frame is found by what is on the screen and taken away too: a view
 *       that only paints the strip (around the bar, beside it or elsewhere in the window), a margin or padding about
 *       the size of the bar that holds the page above it on some other view than the container, and a page that lies
 *       beside the bar instead of under it (see {@link BarFrameRemover}). When the container is not found by its id
 *       the page is found by where it is.</li>
 * </ul>
 * The pill's shade is taken from the background Instagram gives the bar (a little lighter on a dark bar, a little
 * darker on a light one), so it follows the theme, AMOLED black and the dark Reels bar by itself.
 * <p>
 * The width and the height of the pill follow {@link Pref#floatingNavSideDp()} and {@link Pref#floatingNavGapDp()}: the
 * width by how far the pill keeps from each side of the screen, the height by how much air is left above and below it
 * inside Instagram's own bar. Both are chosen with sliders in the settings (Navigation bar > Floating bar size), which
 * show a live preview built from the same numbers (see {@link FloatingNavSize}); the bar itself picks the new size up
 * within half a second of going back to the app.
 * <p>
 * The highlight behind the selected tab can be a picture of the user's own (Navigation bar > Custom selected tab
 * pill), see {@link SelectedTabPill}. It is drawn by the pill, behind the tabs, and takes the size of the selected
 * tab's cell inside the pill, so it grows and shrinks with the same two sliders: a wider pill gives wider cells, a
 * taller pill gives a taller highlight. When another tab is selected it slides over to it. Instagram's own floating bar
 * gets the same highlight on its own pill (see {@link NativeBarBlur}).
 * <p>
 * Safety: the first time the bar is laid out with the extra padding, the position of its tabs is checked. If the bar
 * does not honour its padding on some Instagram version (the tabs would sit under the pill's ends), the pill is taken
 * off again and left off until the app is restarted, and the reason goes into the layout diagnostics.
 * <p>
 * Instagram's own floating bar (Navigation bar > Instagram's own floating bar) is a bar of its own: the pill steps
 * aside while that setting is on. The same two sliders then give Instagram's bar its width and its height (see
 * {@link NativeBarSize}), once the app has been restarted and the bar is really Instagram's floating one. Switched
 * off, the bar gets the size Instagram gave it.
 * <p>
 * Switched on and off in the settings (Navigation bar > Floating navigation bar). Applies as soon as you go back to the
 * app (no restart); switched off, everything it changed is put back.
 */
public final class FloatingNavBar {
    private static final String MAIN_ACTIVITY_NAME = "InstagramMainActivity";
    private static final String TAB_BAR_ID = "tab_bar";
    private static final String TAB_BAR_SHADOW_ID = "tab_bar_shadow";
    private static final String CONTAINER_ID = "layout_container_main";

    // The distance of the pill from each side of the screen is 24 dp by default (the same as the Reels reply bar, so
    // they line up) and the air above and below it 4 dp (so the page above does not touch it). Both are chosen in the
    // settings (see FloatingNavSize for the defaults and the limits).
    private static final int STROKE_DP = 1;
    private static final long FIND_INTERVAL_MS = 150L;
    private static final long PREF_INTERVAL_MS = 500L;
    /** A frame is held back at most this many times in a row. */
    private static final int MAX_HELD_FRAMES = 2;
    /** The tabs are checked this many frames after the padding was set (when the bar is laid out). */
    private static final int CHECK_AFTER_FRAMES = 3;
    /** After this many errors in a row the bar is left alone for a while (instead of failing on every frame). */
    private static final int MAX_FAILURES_IN_A_ROW = 5;
    private static final long RETRY_AFTER_ERRORS_MS = 5000L;

    private static final WeakHashMap<View, State> WATCHED = new WeakHashMap<>();
    private static boolean started;

    private FloatingNavBar() {}

    private static final class State {
        int tabBarId = -1;
        int shadowId;
        int containerId;
        WeakReference<View> bar = new WeakReference<>(null);
        WeakReference<View> shadow = new WeakReference<>(null);
        WeakReference<View> container = new WeakReference<>(null);
        long lastFind = Long.MIN_VALUE / 2;
        long lastPref = Long.MIN_VALUE / 2;

        boolean enabled;
        /** The size chosen in the settings, in dp. */
        int sideDp = FloatingNavSize.SIDE_DEFAULT_DP;
        int gapDp = FloatingNavSize.GAP_DEFAULT_DP;
        /** The distance of the bar from the bottom of the screen, in dp (see BarBottomGap). */
        int bottomDp = FloatingNavSize.BOTTOM_DEFAULT_DP;
        /** What was done to the bar's bottom margin, to give it back (see BarBottomGap). */
        BarBottomGap.Memory bottomGap = new BarBottomGap.Memory();
        /** The bar did not honour its padding: the pill stays off until the app is restarted. */
        boolean unsupported;

        Pill pill;
        // The left and right padding this class set (-1 when none), and how much of each is the pill's own.
        int appliedLeft = -1;
        int appliedRight = -1;
        int appliedSide;
        int framesSinceApply;
        boolean checked;

        boolean shadowHidden;
        float shadowAlpha = 1f;

        /** "Custom selected tab pill" is switched on in the settings (read with the other settings). */
        boolean tabPill;
        /** The user's picture for the selected tab, or null for the default capsule (read with the settings). */
        Bitmap tabPillImage;
        /** "Slide the highlight sideways, move it to a tab and let go" is switched on (read with the settings). */
        boolean tabPillDrag;
        /** The activity's window, whose touches the drag of the highlight looks at (see TabPillDrag). */
        WeakReference<Window> window = new WeakReference<>(null);

        // The bottom margin of the screen container this class took to 0, and the margin Instagram had there. It is
        // given back when the setting is switched off, once the bar is shown again (while it is hidden the margin
        // is 0 on purpose).
        boolean marginTaken;
        int marginOriginal;

        // What is left of Instagram's old bar frame (painted strips, space kept free for the bar) and was taken
        // away by BarFrameRemover. Looked at again on every frame, and given back when the setting is switched off.
        BarFrameRemover.Memory frame = new BarFrameRemover.Memory();

        // The bar was made clickable by this class, so a tap on its empty parts is taken by the bar and does not
        // reach the page that now lies under it.
        boolean clickTaken;
        /** The bar that has been found at least once: a different one later (or none left) is a new window. */
        boolean barSeen;
        /** This class's touch listener is on the bar. */
        boolean sinkTaken;
        /** Takes the taps of the bar's empty parts while the pill is on (it is inert when the setting is off). */
        View.OnTouchListener sink;
        /** The listener that runs before every frame; kept to be put on the window's observer again. */
        ViewTreeObserver.OnPreDrawListener preDraw;

        int heldFrames;

        // Errors in a row, and the time before which nothing is tried again after too many of them.
        int failures;
        long retryAt;

        /** "Hide gesture line" is switched on in the settings (read with the other settings). */
        boolean hideGestureLine;
        /** The navigation bar is to be hidden (again): the setting was switched on, or the window came back. */
        boolean gestureHidePending;
        /** What was changed to hide the gesture line (see GestureLineHider). */
        GestureLineHider.Memory gesture = new GestureLineHider.Memory();
        /** Asks for the line to be hidden again whenever the window gets the focus back. */
        ViewTreeObserver.OnWindowFocusChangeListener focusListener;

        /** Instagram's own floating bar is switched on in the settings (read with the other settings). */
        boolean nativeSetting;
        /**
         * The flags of Instagram's own floating bar were on when this bar was found, so the bar really is
         * Instagram's floating one. Switching the setting on later changes nothing until the app is restarted.
         */
        boolean nativeBar;
        /** The size given to Instagram's own bar, and what could not be given (see NativeBarSize). */
        NativeBarSize.Memory nativeSize = new NativeBarSize.Memory();
        /** "Blur behind Instagram's own floating bar" is switched on in the settings (read with the others). */
        boolean nativeBlurSetting;
        /** How strong the blur is and how see-through the glass is, 0 to 100 (read with the other settings). */
        int nativeBlurLevel = 50;
        int nativeTransparency = 50;
        /** The blur that is on Instagram's own bar, and what could not be done (see NativeBarBlur). */
        NativeBarBlur.Memory nativeBlur = new NativeBarBlur.Memory();
    }

    /** Called when the app starts. */
    public static void start() {
        try {
            synchronized (FloatingNavBar.class) {
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
            Logger.printException(() -> "FloatingNavBar.start failed", t);
        }
    }

    /** The app's Application, or null when it is not available yet. */
    private static Application application() {
        try {
            Context context = Utils.getContext();
            if (context != null && context.getApplicationContext() instanceof Application) {
                return (Application) context.getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        try {
            // The app context may not be set yet when the app has only just started.
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
            final Window activityWindow = activity.getWindow();
            final View decor = activityWindow.getDecorView();
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
            state.window = new WeakReference<>(activityWindow);
            if (state.preDraw == null) {
                // The decor is held weakly: the state is the value of a map that is keyed by the decor.
                final WeakReference<View> decorRef = new WeakReference<>(decor);
                // Runs before every frame is drawn, so a bar that was just changed is never seen in its old shape.
                state.preDraw = () -> {
                    View current = decorRef.get();
                    return current == null || !tick(current, state);
                };
            }
            // On every return to the app (not only the first time): the window can get a new observer when the
            // app was in the background, and a listener on the old one would never run again, so the bar would
            // keep whatever Instagram set while the app was away. Removing a listener that is not there is a no-op.
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (state.focusListener == null) {
                // The system brings its bars back after a dialog, the keyboard or another app: hide them again.
                state.focusListener = hasFocus -> {
                    if (hasFocus) state.gestureHidePending = true;
                };
            }
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(state.preDraw);
                observer.addOnPreDrawListener(state.preDraw);
                observer.removeOnWindowFocusChangeListener(state.focusListener);
                observer.addOnWindowFocusChangeListener(state.focusListener);
            }
            state.gestureHidePending = true;
            // Look for the bar again at once and read the settings again.
            state.lastFind = Long.MIN_VALUE / 2;
            state.lastPref = Long.MIN_VALUE / 2;
            decor.invalidate();
        } catch (Throwable t) {
            Logger.printException(() -> "FloatingNavBar.watch failed", t);
        }
    }

    /**
     * Returns true when the bar's layout was changed, so the frame is laid out again before it is drawn. An error
     * never leaves this method: after a few in a row the bar is left alone for a few seconds, so a problem on one
     * Instagram version cannot repeat on every frame (with a log line each time) or reach the app.
     */
    private static boolean tick(View decor, State state) {
        try {
            if (!decor.isAttachedToWindow()) return false;
            if (SystemClock.uptimeMillis() < state.retryAt) return false;
            boolean relayout = tickBar(decor, state);
            gestureLine(decor, state);
            state.failures = 0;
            return relayout;
        } catch (Throwable t) {
            state.failures++;
            if (state.failures >= MAX_FAILURES_IN_A_ROW) {
                state.failures = 0;
                state.retryAt = SystemClock.uptimeMillis() + RETRY_AFTER_ERRORS_MS;
            }
            Logger.printException(() -> "FloatingNavBar.tick failed", t);
            return false;
        }
    }

    /** The work of one frame for the bar itself. True when the bar's layout was changed. */
    private static boolean tickBar(View decor, State state) {
        long now = SystemClock.uptimeMillis();
        if (now - state.lastPref >= PREF_INTERVAL_MS) {
            state.lastPref = now;
            state.sideDp = Pref.floatingNavSideDp();
            state.gapDp = Pref.floatingNavGapDp();
            state.bottomDp = Pref.floatingNavBottomDp();
            state.tabPill = Pref.customTabPill();
            state.tabPillDrag = Pref.tabPillDrag();
            // Read from storage only when the picture was replaced or deleted (see SelectedTabPill.image).
            state.tabPillImage = state.tabPill ? SelectedTabPill.image(decor.getContext()) : null;
            boolean hideLine = Pref.hideGestureLine();
            if (hideLine != state.hideGestureLine) {
                state.hideGestureLine = hideLine;
                if (hideLine) state.gestureHidePending = true;
            }
            // Instagram's own floating bar (a separate setting) is a bar of its own: this one steps aside.
            boolean nativeSetting = Pref.nativeFloatingNav();
            boolean wanted = Pref.floatingNavBar() && !nativeSetting;
            if (wanted != state.enabled) {
                state.enabled = wanted;
                // Switching it on again gives the pill another try.
                if (wanted) state.unsupported = false;
                BarBottomGap.forgetFailures(state.bottomGap);
            }
            if (nativeSetting != state.nativeSetting) {
                state.nativeSetting = nativeSetting;
                // Switched off, or on again: a size that Instagram's bar did not take gets another try.
                NativeBarSize.forgetFailures(state.nativeSize);
                BarBottomGap.forgetFailures(state.bottomGap);
            }
            state.nativeBlurLevel = Pref.nativeFloatingNavBlurLevel();
            state.nativeTransparency = Pref.nativeFloatingNavTransparency();
            boolean nativeBlurSetting = Pref.nativeFloatingNavBlur();
            if (nativeBlurSetting != state.nativeBlurSetting) {
                state.nativeBlurSetting = nativeBlurSetting;
                // Switched off, or on again: a bar that the blur was given up on gets another try.
                NativeBarBlur.forgetFailures(state.nativeBlur);
            }
        }

        // Instagram's own floating bar (when that is what is on) gets the size of the sliders, and gets the size
        // Instagram chose back when its setting is switched off. Independent of the pill, which is off while
        // Instagram's bar is on.
        boolean nativeChanged = nativeSize(decor, state, now);
        // The distance between the bottom of the screen and whichever floating bar is on (the pill or Instagram's).
        if (bottomGap(decor, state, now)) nativeChanged = true;
        // The blur behind Instagram's own bar. It only changes how the bar's background is drawn, never its layout, so
        // it does not hold the frame back.
        nativeBlur(decor, state, now);
        // The drag of the highlight (slide sideways or hold), for whichever bar is on screen.
        tabPillDrag(state);

        if (!state.enabled || state.unsupported) {
            boolean relayout = nativeChanged;
            if (state.pill != null || state.shadowHidden || state.appliedLeft >= 0 || state.marginTaken
                    || state.clickTaken || !state.frame.isEmpty()) {
                if (restore(state)) relayout = true;
            }
            return hold(state, relayout);
        }

        View bar = findBar(decor, state, now);
        if (bar == null) return hold(state, nativeChanged);
        // Hidden (a chat covers it): nothing to draw. What was set stays until the bar is shown again.
        if (bar.getVisibility() != View.VISIBLE) {
            state.heldFrames = 0;
            return nativeChanged;
        }
        return hold(state, apply(bar, state) || nativeChanged);
    }

    /**
     * Hides the system's gesture line while a floating bar is on and the setting asks for it, keeps the bar where it
     * was, and gives everything back when either is switched off (see {@link GestureLineHider}).
     */
    private static void gestureLine(View decor, State state) {
        GestureLineHider.Memory memory = state.gesture;
        // Only a floating bar floats over the page: the stock bar has its own background under the line.
        boolean floating = (state.enabled && !state.unsupported) || (state.nativeSetting && state.nativeBar);
        View bar = state.bar.get();
        if (!(state.hideGestureLine && floating)) {
            if (memory.isActive()) {
                GestureLineHider.putBarBack(bar, memory);
                GestureLineHider.show(decor, memory);
            }
            state.gestureHidePending = false;
            return;
        }
        if (state.gestureHidePending || !memory.hidden) {
            // Not done yet when the window cannot be asked: tried again on the next frame.
            state.gestureHidePending = !GestureLineHider.hide(decor, memory);
        }
        if (memory.hidden && bar != null && bar.isAttachedToWindow() && bar.getVisibility() == View.VISIBLE) {
            GestureLineHider.keepBarUp(decor, bar, memory);
        }
    }

    /** Holds the frame back (true) when the layout changed, but never more than MAX_HELD_FRAMES in a row. */
    private static boolean hold(State state, boolean relayout) {
        if (!relayout) {
            state.heldFrames = 0;
            return false;
        }
        if (state.heldFrames >= MAX_HELD_FRAMES) return false;
        state.heldFrames++;
        return true;
    }

    /**
     * Gives Instagram's own floating bar the width and the height of the sliders (see {@link NativeBarSize}) while
     * its setting is on, and gives it the size Instagram chose when the setting is switched off. True when the bar's
     * layout changed.
     */
    private static boolean nativeSize(View decor, State state, long now) {
        NativeBarSize.Memory memory = state.nativeSize;
        boolean wanted = state.nativeSetting;
        if (!wanted && !memory.isApplied()) return false;
        View bar = wanted ? findBar(decor, state, now) : state.bar.get();
        if (bar == null || !bar.isAttachedToWindow()) return false;
        // Only a bar that Instagram itself makes floating: its flags are read when the bar is created, so right after
        // the setting is switched on (and before the restart) the bar is still the stock one and is left alone.
        if (!wanted || !state.nativeBar) return NativeBarSize.restore(bar, memory);
        // Hidden (a chat covers it): nothing to size. What was set stays until the bar is shown again.
        if (bar.getVisibility() != View.VISIBLE) return false;
        return NativeBarSize.apply(bar, state.sideDp, state.gapDp, memory);
    }

    /**
     * Keeps the bar the chosen distance above the bottom of the screen (see {@link BarBottomGap}) while a floating
     * bar is on: the pill of the floating navigation bar, or Instagram's own floating bar. Gives the bar its own
     * margin back when both are switched off. True when the bar's layout changed.
     */
    private static boolean bottomGap(View decor, State state, long now) {
        BarBottomGap.Memory memory = state.bottomGap;
        boolean floating = (state.enabled && !state.unsupported) || (state.nativeSetting && state.nativeBar);
        if (!floating && !memory.isApplied()) return false;
        View bar = floating ? findBar(decor, state, now) : state.bar.get();
        if (bar == null || !bar.isAttachedToWindow()) return false;
        if (!floating) return BarBottomGap.restore(bar, memory);
        // Hidden (a chat covers it): nothing to move. What was set stays until the bar is shown again.
        if (bar.getVisibility() != View.VISIBLE) return false;
        float density = bar.getResources().getDisplayMetrics().density;
        return BarBottomGap.apply(bar, Math.round(state.bottomDp * density), memory);
    }

    /**
     * Lays out the pill of Instagram's own floating bar (see {@link NativeBarBlur}) while "Instagram's own floating
     * bar" is on: as wide and as tall as the sliders say and centered on the icons, with the live blur on it while
     * "Blur behind Instagram's own floating bar" is on as well, and with Instagram's own background (fitted the same
     * way) when it is off. The highlight behind the selected tab ("Custom selected tab pill") is drawn by that pill as
     * well. Gives the bar its own background back when the setting is switched off. Only for a bar that Instagram
     * itself makes floating (decided when the bar is created, like the size): the stock bar and the pill are never
     * touched.
     */
    private static void nativeBlur(View decor, State state, long now) {
        NativeBarBlur.Memory memory = state.nativeBlur;
        boolean wanted = state.nativeSetting;
        if (!wanted && !memory.isApplied()) return;
        View bar = wanted ? findBar(decor, state, now) : state.bar.get();
        if (bar == null || !bar.isAttachedToWindow()) return;
        if (!wanted || !state.nativeBar) {
            NativeBarBlur.restore(bar, memory);
            return;
        }
        // Hidden (a chat covers it): nothing to draw. What was set stays until the bar is shown again.
        if (bar.getVisibility() != View.VISIBLE) return;
        float density = bar.getResources().getDisplayMetrics().density;
        // The slider's height, the same one the bar's own height is made from (see NativeBarSize).
        int pillHeightPx = Math.round(FloatingNavSize.pillHeightDp(state.gapDp) * density);
        NativeBarBlur.apply(bar, memory, state.nativeBlurSetting, state.nativeBlurLevel, state.nativeTransparency,
                NativeBarSize.widthApplied(state.nativeSize), pillHeightPx, state.tabPill, state.tabPillImage);
    }

    /**
     * Lets "slide the highlight sideways (or hold it), move it to a tab and let go" work (see {@link TabPillDrag}): hands it the
     * bar and the highlight that is on screen, or nothing when the setting is off or there is no highlight, which
     * switches the touch handling off again. It only reads what the frame already found.
     */
    private static void tabPillDrag(State state) {
        SelectedTabPill.Highlight highlight = null;
        View bar = state.bar.get();
        if (state.tabPill && state.tabPillDrag && bar != null) {
            if (state.enabled && !state.unsupported) {
                // The pill of the floating navigation bar.
                if (state.pill != null && bar.getBackground() == state.pill) highlight = state.pill.highlight();
            } else if (state.nativeSetting && state.nativeBar) {
                // The background that draws the pill of Instagram's own floating bar.
                highlight = NativeBarBlur.highlight(state.nativeBlur);
            }
        }
        TabPillDrag.sync(state.window.get(), bar, highlight);
    }

    /** Finds the bar (and the line above it). Looks again when the one it knew is gone. */
    private static View findBar(View decor, State state, long now) {
        View bar = state.bar.get();
        if (bar != null && bar.isAttachedToWindow()) return bar;
        if (now - state.lastFind < FIND_INTERVAL_MS) return null;
        state.lastFind = now;

        if (state.tabBarId == -1) {
            state.tabBarId = decor.getResources().getIdentifier(TAB_BAR_ID, "id", decor.getContext().getPackageName());
            state.shadowId = decor.getResources().getIdentifier(TAB_BAR_SHADOW_ID, "id",
                    decor.getContext().getPackageName());
            state.containerId = decor.getResources().getIdentifier(CONTAINER_ID, "id",
                    decor.getContext().getPackageName());
        }
        if (state.tabBarId == 0) return null;
        View found = decor.findViewById(state.tabBarId);
        if (found == null) return null;
        if (state.barSeen && bar != found) {
            // A new window, a new bar: what was set on the old one is gone with it. This also holds when the old
            // bar was already collected (then it reads as null): a bar that was seen and is not this one.
            state.pill = null;
            state.appliedLeft = -1;
            state.appliedRight = -1;
            state.checked = false;
            state.shadowHidden = false;
            state.marginTaken = false;
            state.clickTaken = false;
            state.sinkTaken = false;
            state.frame = new BarFrameRemover.Memory();
            state.nativeSize = new NativeBarSize.Memory();
            state.bottomGap = new BarBottomGap.Memory();
            state.nativeBlur = new NativeBarBlur.Memory();
            // The old bar's translation is gone with it. Whether the navigation bar is hidden belongs to the window.
            state.gesture.shifted = false;
        }
        if (!state.barSeen || bar != found) {
            // Whether this bar is Instagram's own floating one is decided when it is created, by the flags that
            // were on at that time: they are looked at now, once per bar.
            state.nativeBar = HookFlags.nativeFloatingNavActive();
        }
        state.barSeen = true;
        state.bar = new WeakReference<>(found);
        state.shadow = new WeakReference<>(state.shadowId == 0 ? null : decor.findViewById(state.shadowId));
        state.container = new WeakReference<>(state.containerId == 0 ? null : decor.findViewById(state.containerId));
        return found;
    }

    /** Puts the pill on the bar. True when the layout changed. */
    private static boolean apply(View bar, State state) {
        boolean relayout = false;
        float density = bar.getResources().getDisplayMetrics().density;
        int side = Math.round(state.sideDp * density);
        int gap = Math.round(state.gapDp * density);

        // 1. The pill is the bar's background, and Instagram's own background is held under it (not drawn). Whatever
        // the bar has now that is not the pill is Instagram's (it may set a new one, for instance when the theme or
        // the tab changes).
        if (state.pill == null) state.pill = new Pill(density);
        Pill pill = state.pill;
        Drawable background = bar.getBackground();
        if (background != pill) {
            // The pill goes on first: setting a background clears the callback of the one it replaces, and the old
            // background must report its changes to the pill from now on.
            bar.setBackground(pill);
            pill.setBase(background);
        }
        boolean night = (bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        pill.update(side, gap, bar.getPaddingBottom(), night);
        // The highlight behind the selected tab: on or off, which picture, and which tab is selected right now. The
        // tab is looked at in every frame, because a tab changing its selected state does not redraw the bar's
        // background by itself; the pill asks for a redraw only when the tab (or its position) changed.
        pill.updateTabPill(bar, state.tabPill, state.tabPillImage);
        // Tell the settings' preview how tall Instagram's bar really is (above the system inset), so the preview
        // shows the same proportions as the bar.
        if (bar.isLaidOut() && bar.getHeight() > 0) {
            FloatingNavSize.measured((bar.getHeight() - bar.getPaddingBottom()) / density,
                    bar.getPaddingBottom() / density);
        }

        // 2. Room at both ends for the rounded ends of the pill. Instagram's own horizontal padding (normally 0)
        // is kept and the pill's is added to it.
        int left = bar.getPaddingLeft();
        int right = bar.getPaddingRight();
        // The padding is still the one this class set: only a change of the width in the settings is left to do.
        boolean ours = state.appliedLeft >= 0 && left == state.appliedLeft && right == state.appliedRight;
        if (!ours || state.appliedSide != side) {
            int baseLeft = ours ? left - state.appliedSide : left;
            int baseRight = ours ? right - state.appliedSide : right;
            int wantedLeft = baseLeft + side;
            int wantedRight = baseRight + side;
            bar.setPadding(wantedLeft, bar.getPaddingTop(), wantedRight, bar.getPaddingBottom());
            state.appliedLeft = wantedLeft;
            state.appliedRight = wantedRight;
            state.appliedSide = side;
            state.framesSinceApply = 0;
            state.checked = false;
            relayout = true;
        }

        // 3. The thin line above the bar.
        View shadow = state.shadow.get();
        if (shadow != null && shadow.getAlpha() != 0f) {
            if (!state.shadowHidden) {
                state.shadowAlpha = shadow.getAlpha();
                state.shadowHidden = true;
            }
            shadow.setAlpha(0f);
        }

        // 3a. The page lies under the bar now, so a tap that lands on the bar but not on a tab (between the icons,
        // around the pill) must stop at the bar. Tabs still get their own taps first.
        // - Clickable, checked before every frame and not only once: Instagram turns it off again (on the Reels
        //   tab, and when the app comes back from the background) and a bar that is not clickable lets every tap
        //   through to the page under it. A bar that is clickable by Instagram's own choice is not counted as ours.
        if (!bar.isClickable()) {
            bar.setClickable(true);
            state.clickTaken = true;
        }
        // - A touch listener as well, which does not depend on the clickable flag: it takes the touches that no
        //   tab took. It is put on once per bar and stays inert (takes nothing) when the setting is off.
        if (!state.sinkTaken) {
            if (state.sink == null) {
                state.sink = (v, event) -> state.enabled && !state.unsupported;
            }
            bar.setOnTouchListener(state.sink);
            state.sinkTaken = true;
        }

        // 3b. The strip Instagram keeps free under the page for its bar: the page runs under the pill instead.
        View page = pageOf(bar, state);
        if (takeMargin(state)) relayout = true;

        // 3c. Whatever else is left of the old frame: a view that only paints the strip (around the bar, beside it
        // or elsewhere in the window), space kept free for the bar on some other view than the container, or a page
        // that lies beside the bar instead of under it (see BarFrameRemover). Looks at what is on the screen, so it
        // does nothing when the page already runs down behind the pill.
        BarFrameRemover.clearPaint(bar, page, state.frame);
        // Not in a frame where the layout was just changed above: the sizes it reads are the old ones until it runs.
        if (!relayout && BarFrameRemover.freeSpace(bar, page, state.frame)) relayout = true;

        // 4. Once the bar has been laid out with the padding, make sure the tabs really sit inside it.
        if (!state.checked && !relayout) {
            state.framesSinceApply++;
            if (state.framesSinceApply >= CHECK_AFTER_FRAMES && bar.isLaidOut() && !bar.isLayoutRequested()
                    && bar.getWidth() > 0) {
                state.checked = true;
                if (!tabsInside(bar)) {
                    state.unsupported = true;
                    Logger.printInfo(() -> "Floating navigation bar taken off: the bar does not keep its tabs inside "
                            + "its padding on this Instagram version");
                    return restore(state);
                }
            }
        }
        return relayout;
    }

    /**
     * The view that holds the page: the screen container by its id, or, when that id is not in this Instagram
     * version (or the view is gone), the big view beside the bar that is found by where it is. Null when none.
     */
    private static View pageOf(View bar, State state) {
        View container = state.container.get();
        if (container != null && container.isAttachedToWindow()) return container;
        View found = null;
        if (state.containerId != 0) found = bar.getRootView().findViewById(state.containerId);
        if (found == null) found = BarFrameRemover.findPage(bar);
        state.container = new WeakReference<>(found);
        return found;
    }

    /**
     * Takes the bottom margin of the screen container to 0 while the bar is shown. When Instagram sets a margin
     * again (a new screen, the bar shown again after a chat), the new value is remembered and taken away again.
     * True when the margin was changed, so the frame is laid out again before it is drawn.
     */
    private static boolean takeMargin(State state) {
        View container = state.container.get();
        if (container == null || !container.isAttachedToWindow()) return false;
        if (!(container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return false;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) container.getLayoutParams();
        if (params.bottomMargin <= 0) return false;
        state.marginOriginal = params.bottomMargin;
        state.marginTaken = true;
        params.bottomMargin = 0;
        container.setLayoutParams(params);
        return true;
    }

    /** Gives the container its strip back (when the bar is shown). True when the margin was changed. */
    private static boolean giveMarginBack(State state) {
        if (!state.marginTaken) return false;
        View container = state.container.get();
        View bar = state.bar.get();
        if (container == null || !container.isAttachedToWindow()
                || !(container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            state.marginTaken = false;
            return false;
        }
        // A hidden bar (a chat covers it) has a container without a strip on purpose: wait until it is shown.
        if (bar == null || bar.getVisibility() != View.VISIBLE) return false;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) container.getLayoutParams();
        state.marginTaken = false;
        // When Instagram has set a margin itself in the meantime, it is left as it is.
        if (params.bottomMargin != 0 || state.marginOriginal <= 0) return false;
        params.bottomMargin = state.marginOriginal;
        container.setLayoutParams(params);
        return true;
    }

    /** True when every shown child of the bar lies inside the bar's padding (the padding is honoured). */
    private static boolean tabsInside(View bar) {
        if (!(bar instanceof ViewGroup)) return true;
        ViewGroup group = (ViewGroup) bar;
        int minLeft = Integer.MAX_VALUE;
        int maxRight = Integer.MIN_VALUE;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE || child.getWidth() <= 0) continue;
            minLeft = Math.min(minLeft, child.getLeft());
            maxRight = Math.max(maxRight, child.getRight());
        }
        if (minLeft == Integer.MAX_VALUE) return true;
        float slack = 2f * bar.getResources().getDisplayMetrics().density;
        return minLeft >= bar.getPaddingLeft() - slack && maxRight <= bar.getWidth() - bar.getPaddingRight() + slack;
    }

    /**
     * The pill's fill for a bar of colour {@code under}: a little lighter on a dark bar, a little darker on a light
     * one. Also used by the settings' preview so it has the same shade as the bar.
     */
    public static int pillFill(int under, boolean dark) {
        int shade = dark ? Pill.mix(under, Color.WHITE, 0.11f) : Pill.mix(under, Color.BLACK, 0.055f);
        return shade | 0xFF000000;
    }

    /** The pill's thin edge (a translucent line), for the preview as well as the bar. */
    public static int pillEdge(boolean dark) {
        return dark ? 0x26FFFFFF : 0x1A000000;
    }

    /** Gives the bar back what it had. True when the layout changed. */
    private static boolean restore(State state) {
        boolean relayout = false;
        View bar = state.bar.get();
        Pill pill = state.pill;
        if (bar != null && pill != null) {
            Drawable base = pill.releaseBase();
            // Only when the bar still wears the pill: if Instagram has set its own background since, it keeps it.
            if (bar.getBackground() == pill) bar.setBackground(base);
        }
        if (bar != null && state.appliedLeft >= 0) {
            if (bar.getPaddingLeft() == state.appliedLeft && bar.getPaddingRight() == state.appliedRight) {
                bar.setPadding(bar.getPaddingLeft() - state.appliedSide, bar.getPaddingTop(),
                        bar.getPaddingRight() - state.appliedSide, bar.getPaddingBottom());
                relayout = true;
            }
        }
        View shadow = state.shadow.get();
        if (shadow != null && state.shadowHidden) shadow.setAlpha(state.shadowAlpha);
        state.shadowHidden = false;
        // The frame's own changes first: one of them can be a margin on the container that has to be back at what
        // Instagram had (0 while the pill is on) before the container's own strip is given back.
        if (BarFrameRemover.putBack(state.frame, bar != null && bar.getVisibility() == View.VISIBLE)) {
            relayout = true;
        }
        if (giveMarginBack(state)) relayout = true;
        if (bar != null && state.clickTaken) bar.setClickable(false);
        state.clickTaken = false;
        state.pill = null;
        state.appliedLeft = -1;
        state.appliedRight = -1;
        state.checked = false;
        return relayout;
    }

    /**
     * The background of the bar: only the pill. Instagram's own background (if any) is held, not drawn. It is the
     * bar's background, so it is drawn behind the tabs and moves with the bar.
     */
    private static final class Pill extends Drawable implements Drawable.Callback {
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lift = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF ring = new RectF();
        /** The highlight behind the selected tab (see SelectedTabPill): which tab, the slide, the drag, the drawing. */
        private final SelectedTabPill.Highlight highlight;

        private Drawable base;
        private int side;
        private int gap;
        private int bottomInset;
        private boolean dark = true;
        private int baseColor = Integer.MIN_VALUE;
        private boolean baseSampled;
        private int sampledColor;

        Pill(float density) {
            this.density = density;
            highlight = new SelectedTabPill.Highlight(density);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, STROKE_DP * density));
            lift.setStyle(Paint.Style.FILL);
            lift.setColor(Color.BLACK);
        }

        void setBase(Drawable drawable) {
            if (base == drawable) {
                // The bar took the callback when Instagram set the same background again.
                if (drawable != null) drawable.setCallback(this);
                return;
            }
            if (base != null && base.getCallback() == this) base.setCallback(null);
            base = drawable;
            baseSampled = false;
            baseColor = Integer.MIN_VALUE;
            if (drawable != null) drawable.setCallback(this);
            invalidateSelf();
        }

        /**
         * The highlight behind the selected tab: on or off, which picture, and which tab is selected right now. The
         * tab is looked at in every frame, because a tab changing its selected state does not redraw the bar's
         * background by itself; the pill asks for a redraw only when the tab (or its position) changed.
         */
        void updateTabPill(View barView, boolean on, Bitmap image) {
            if (!(barView instanceof ViewGroup)) return;
            if (highlight.update((ViewGroup) barView, on, image)) invalidateSelf();
        }

        /** The highlight, for the drag (see TabPillDrag). */
        SelectedTabPill.Highlight highlight() {
            return highlight;
        }

        /** Lets go of Instagram's background (it is given back to the bar) and returns it. */
        Drawable releaseBase() {
            Drawable released = base;
            if (released != null && released.getCallback() == this) released.setCallback(null);
            base = null;
            return released;
        }

        /** Takes the size of the pill's surroundings and the shade from what is under it. */
        void update(int side, int gap, int bottomInset, boolean nightFallback) {
            boolean moved = this.side != side || this.gap != gap || this.bottomInset != bottomInset;
            this.side = side;
            this.gap = gap;
            this.bottomInset = bottomInset;

            int under = colorUnder();
            if (!moved && under == baseColor) return;
            baseColor = under;
            boolean known = Color.alpha(under) >= 0x60;
            dark = known ? Color.luminance(under) < 0.5f : nightFallback;
            int shade = known ? pillFill(under, dark) : (dark ? 0xFF1E2226 : 0xFFEFEFF1);
            fill.setColor(shade | 0xFF000000);
            edge.setColor(pillEdge(dark));
            invalidateSelf();
        }

        /** The colour of Instagram's background (0 when it has none or cannot be read). */
        private int colorUnder() {
            if (base == null) return 0;
            if (base instanceof ColorDrawable) {
                ColorDrawable color = (ColorDrawable) base;
                int c = color.getColor();
                int alpha = Math.round(Color.alpha(c) * (color.getAlpha() / 255f));
                return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
            }
            // Any other drawable: read it once, in the middle of a tiny picture of it.
            if (!baseSampled) {
                baseSampled = true;
                sampledColor = 0;
                try {
                    Rect old = base.copyBounds();
                    Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
                    base.setBounds(0, 0, 8, 8);
                    base.draw(new Canvas(bitmap));
                    sampledColor = bitmap.getPixel(4, 4);
                    base.setBounds(old);
                    bitmap.recycle();
                } catch (RuntimeException ignored) {
                    sampledColor = 0;
                }
            }
            return sampledColor;
        }

        static int mix(int from, int to, float amount) {
            float keep = 1f - amount;
            return Color.argb(255,
                    Math.round(Color.red(from) * keep + Color.red(to) * amount),
                    Math.round(Color.green(from) * keep + Color.green(to) * amount),
                    Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            // Instagram's own background (the frame around the bar) is not drawn: the pill stands on its own over
            // the page. It is only kept to read the theme colour from and to be given back.
            float left = bounds.left + side;
            float right = bounds.right - side;
            // The tabs are centered in the bar above its system inset, and so is the pill: the same air on both sides.
            float top = bounds.top + gap;
            float bottom = bounds.bottom - bottomInset - gap;
            if (right - left < 8f || bottom - top < 8f) return;
            float radius = (bottom - top) / 2f;
            rect.set(left, top, right, bottom);

            if (!dark) {
                // A soft lift under the pill on a light bar: three faint rings, the widest the faintest.
                for (int i = 3; i >= 1; i--) {
                    float grow = i * 1.5f * density;
                    ring.set(left - grow, top, right + grow, bottom + grow + density);
                    lift.setAlpha(8 + (4 - i) * 6);
                    canvas.drawRoundRect(ring, radius + grow, radius + grow, lift);
                }
            }
            canvas.drawRoundRect(rect, radius, radius, fill);
            float half = edge.getStrokeWidth() / 2f;
            ring.set(left + half, top + half, right - half, bottom - half);
            canvas.drawRoundRect(ring, radius - half, radius - half, edge);

            // The highlight behind the selected tab, on top of the pill and under the tabs' icons. Another frame
            // while it slides.
            if (highlight.draw(canvas, left, right, top, bottom, dark)) invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }
}
