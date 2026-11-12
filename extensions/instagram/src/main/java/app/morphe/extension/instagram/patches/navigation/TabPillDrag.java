package app.morphe.extension.instagram.patches.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Pick up the highlight behind the selected tab ("Custom selected tab pill") by sliding it sideways (or by holding it),
 * move it along the bar and let go over a tab: that tab is opened. Works on both floating bars, because both draw the
 * same {@link SelectedTabPill.Highlight}.
 * <p>
 * How it works. The tabs are Instagram's own views and take the touches themselves, so the touches are looked at one
 * step before they get there: the window's callback is wrapped (a {@link Proxy} that passes every call on to the
 * real one, and looks at {@code dispatchTouchEvent} first).
 * <ul>
 *   <li>A touch that starts on the highlight is picked up as soon as the finger has moved sideways by the touch
 *       slop (about 8 dp, the same distance that tells a tap from a swipe everywhere in Android), with no waiting.
 *       Holding the finger still on it for about half a second picks it up too. Until then the touch goes on to the
 *       tab as usual, so a tap on the selected tab still works. A touch that moves up or down first is left alone.</li>
 *   <li>When it is picked up the tab is sent a cancel (so it does not click or long-press when the finger lets go),
 *       the phone gives a short tick, and from now on the highlight is drawn under the finger instead of on its tab,
 *       a little bigger, and the rest of the touch is not passed on.</li>
 *   <li>When the finger lets go, the tab under it gets a tap of its own (a touch down and up in its middle, sent
 *       through the bar like a real one). If that is the tab that was already selected, the highlight just slides
 *       back. Otherwise it slides to the new tab and waits there while Instagram opens it.</li>
 * </ul>
 * The wrapping is done only while the setting and a highlight are on screen, and taken off again after that. Any error
 * in here is logged and the touch goes on to the app as if this class were not there.
 */
final class TabPillDrag {
    /** The hold is a little shorter than the system's long press, so it comes before a long press of the tab. */
    private static final int HOLD_SHORTER_BY_MS = 80;
    /** A touch this close to the highlight (dp) counts as on it. */
    private static final float HIT_SLOP_DP = 6f;
    /** The time between the down and the up of the tap on the tab that was let go on. */
    private static final long TAP_MS = 60L;

    /** One controller for each window, so the window's callback is wrapped once. The UI thread only. */
    private static final WeakHashMap<Window, Controller> CONTROLLERS = new WeakHashMap<>();

    private TabPillDrag() {}

    /**
     * Called in every frame with the window, the bar and the highlight that are on screen, or with null for the
     * highlight when there is none (the setting is off, or no highlight is drawn). That is what switches the touch
     * handling on and off.
     * <p>
     * The window is handed in by the caller (it is the activity's own). It cannot be found from the window's root view:
     * that view's context is a {@code DecorContext} whose base is the application, which never leads back to the
     * activity. The bar's own context is only the fallback.
     */
    static void sync(Window window, View bar, SelectedTabPill.Highlight highlight) {
        if (highlight == null && CONTROLLERS.isEmpty()) return;
        if (window == null && bar != null) window = windowOf(bar);
        if (window == null) {
            if (highlight != null) note("no window to wrap the touches of");
            return;
        }
        Controller controller = CONTROLLERS.get(window);
        if (highlight == null || !(bar instanceof ViewGroup)) {
            if (controller != null) controller.deactivate(window);
            return;
        }
        if (controller == null) {
            controller = new Controller();
            CONTROLLERS.put(window, controller);
        }
        controller.activate(window, (ViewGroup) bar, highlight);
    }

    // -------------------------------------------------------------------------------------------------------------
    // What happened, for the debug copy ("Copy navigation bar info")
    // -------------------------------------------------------------------------------------------------------------

    private static volatile String lastEvent = "nothing yet";
    private static volatile String lastDown = "no touch seen yet";
    private static volatile int wraps;
    private static volatile int touches;
    private static volatile int downsOnHighlight;
    private static volatile int holds;
    private static volatile int slides;
    private static volatile int drops;
    private static volatile int opened;

    private static void note(String event) {
        lastEvent = event;
    }

    /**
     * What this class has done so far, for the debug copy: whether the window's touches are being looked at, how many
     * touches came through, how many started on the highlight, how many holds picked it up, and the last thing that
     * happened. It says where the press-and-hold stops when it does not work.
     */
    static String status() {
        return "touches looked at: " + touches
                + "\nwindow callbacks wrapped: " + wraps
                + "\ntouches that started on the highlight: " + downsOnHighlight
                + "\npicked up by sliding sideways: " + slides
                + "\nholds that picked it up: " + holds
                + "\ndrops: " + drops + " (a tab other than the selected one: " + opened + ")"
                + "\nlast touch down: " + lastDown
                + "\nlast event: " + lastEvent;
    }

    /** The activity's window, from the context a view was made with; null when that never leads to an activity. */
    private static Window windowOf(View view) {
        Context context = view.getContext();
        for (int depth = 0; depth < 8 && context != null; depth++) {
            if (context instanceof Activity) return ((Activity) context).getWindow();
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static final class Controller {
        private static final int IDLE = 0;
        /** A touch started on the highlight: waiting to see whether it is held. */
        private static final int PENDING = 1;
        private static final int DRAGGING = 2;
        private static final int MAX_REWRAPS = 3;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final int[] location = new int[2];
        private final Runnable holdTimer = this::onHold;

        private WeakReference<ViewGroup> bar = new WeakReference<>(null);
        private WeakReference<SelectedTabPill.Highlight> highlight = new WeakReference<>(null);

        /** The wrapper that is in the window now (null while none), and the callback it passes everything on to. */
        private Window.Callback wrapper;
        private Window.Callback original;

        /** How often the callback was wrapped again after something else had set one (at most MAX_REWRAPS). */
        private int rewraps;
        /** A touch is being passed on (or the cancel is being sent) from here: wrappers deeper in the chain skip it. */
        private boolean busy;

        private int state = IDLE;
        private float downX;
        private float downY;
        /** Where the finger is (screen px) as far as it is known while waiting for the hold. */
        private float fingerX;
        private int slop;
        private long holdMs;
        private MotionEvent downEvent;

        // ---------------------------------------------------------------------------------------------------------
        // Switching on and off
        // ---------------------------------------------------------------------------------------------------------

        void activate(Window window, ViewGroup barView, SelectedTabPill.Highlight highlightView) {
            if (bar.get() != barView) bar = new WeakReference<>(barView);
            if (highlight.get() != highlightView) highlight = new WeakReference<>(highlightView);
            if (wrapper == null) {
                install(window);
            } else if (window.getCallback() != wrapper && rewraps < MAX_REWRAPS) {
                // Something set another callback on the window since: either it wrapped this one (which then still
                // gets the touches) or it replaced it (which then does not). Wrapping again covers both; a touch that
                // reaches two wrappers is only looked at by the outer one (see busy).
                rewraps++;
                install(window);
            }
        }

        void deactivate(Window window) {
            abort();
            bar = new WeakReference<>(null);
            highlight = new WeakReference<>(null);
            // Taken off again when it is still the outermost one. If something wrapped it since, it stays and passes
            // everything on (nothing is looked at while there is no bar and no highlight).
            if (wrapper != null && window.getCallback() == wrapper) {
                window.setCallback(original);
                wrapper = null;
                original = null;
            }
        }

        private void install(Window window) {
            Window.Callback current = window.getCallback();
            if (current == null) return;
            final Window.Callback target = current;
            InvocationHandler invocation = (proxy, method, args) -> invoke(target, proxy, method, args);
            Window.Callback made = (Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[] {Window.Callback.class}, invocation);
            window.setCallback(made);
            wrapper = made;
            original = target;
            wraps++;
            note("wrapped the window's touches");
        }

        /** Every call on the window's callback comes here: all but the touches are passed on untouched. */
        private Object invoke(Window.Callback target, Object self, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(name)) return self == args[0];
                if ("hashCode".equals(name)) return System.identityHashCode(self);
                return "TabPillDrag callback of " + target;
            }
            if ("dispatchTouchEvent".equals(name) && args != null && args.length == 1
                    && args[0] instanceof MotionEvent) {
                return onTouch((MotionEvent) args[0], target);
            }
            // The window lost the focus (a dialog, another app): a drag in the middle of that is let go.
            if ("onWindowFocusChanged".equals(name) && args != null && args.length == 1
                    && Boolean.FALSE.equals(args[0])) {
                abort();
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }

        // ---------------------------------------------------------------------------------------------------------
        // The touch
        // ---------------------------------------------------------------------------------------------------------

        /** Looks at a touch, then (unless it is being dragged) passes it on. */
        private boolean onTouch(MotionEvent ev, Window.Callback target) {
            if (busy) return target.dispatchTouchEvent(ev);
            touches++;
            try {
                int action = ev.getActionMasked();
                if (state == DRAGGING) {
                    if (action != MotionEvent.ACTION_DOWN) return drag(ev, action);
                    // A new touch while one was being dragged: the old one lost its end. Let it go, start over.
                    abort();
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelPending();
                    if (ev.getPointerCount() == 1 && hitHighlight(ev)) {
                        downsOnHighlight++;
                        startPending(ev);
                    }
                } else if (state == PENDING) {
                    if (action == MotionEvent.ACTION_MOVE) {
                        float dx = ev.getRawX() - downX;
                        float dy = ev.getRawY() - downY;
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                            // Slid sideways: the highlight is picked up now, and goes where the finger is. This move is
                            // not passed on (the tab was sent a cancel).
                            if (pickUp(downX, ev.getRawX(), HapticFeedbackConstants.CLOCK_TICK)) {
                                slides++;
                                note("picked up by sliding sideways");
                                return true;
                            }
                        } else if (Math.abs(dy) > slop) {
                            note("moved up or down: left alone");
                            cancelPending();
                        } else {
                            fingerX = ev.getRawX();
                        }
                    } else {
                        // Let go, cancelled, or a second finger: not a drag.
                        cancelPending();
                    }
                }
            } catch (Throwable t) {
                Logger.printException(() -> "TabPillDrag.onTouch failed", t);
                note("onTouch failed: " + t);
                reset();
            }
            busy = true;
            try {
                return target.dispatchTouchEvent(ev);
            } finally {
                busy = false;
            }
        }

        private boolean hitHighlight(MotionEvent ev) {
            ViewGroup barView = bar.get();
            SelectedTabPill.Highlight h = highlight.get();
            if (barView == null || h == null || !barView.isAttachedToWindow() || !barView.isShown()) {
                lastDown = "no bar on screen";
                return false;
            }
            barView.getLocationOnScreen(location);
            float density = barView.getResources().getDisplayMetrics().density;
            float x = ev.getRawX() - location[0];
            float y = ev.getRawY() - location[1];
            boolean hit = h.hit(x, y, HIT_SLOP_DP * density);
            lastDown = (hit ? "on" : "missed") + " the highlight: the finger at " + Math.round(x) + "," + Math.round(y)
                    + " in the bar, " + h.describeShown();
            return hit;
        }

        private void startPending(MotionEvent ev) {
            ViewGroup barView = bar.get();
            if (barView == null) return;
            slop = ViewConfiguration.get(barView.getContext()).getScaledTouchSlop();
            holdMs = Math.max(150L, ViewConfiguration.getLongPressTimeout() - HOLD_SHORTER_BY_MS);
            downX = ev.getRawX();
            downY = ev.getRawY();
            fingerX = downX;
            downEvent = MotionEvent.obtain(ev);
            state = PENDING;
            handler.postDelayed(holdTimer, holdMs);
        }

        private void cancelPending() {
            handler.removeCallbacks(holdTimer);
            if (state == PENDING) state = IDLE;
            if (downEvent != null) {
                downEvent.recycle();
                downEvent = null;
            }
        }

        /** The finger has stayed on the highlight: pick it up. */
        private void onHold() {
            try {
                if (state != PENDING) return;
                if (pickUp(fingerX, fingerX, HapticFeedbackConstants.LONG_PRESS)) {
                    holds++;
                    note("picked up by holding");
                }
            } catch (Throwable t) {
                Logger.printException(() -> "TabPillDrag.onHold failed", t);
                note("onHold failed: " + t);
                reset();
            }
        }

        /**
         * Picks the highlight up. The finger took hold at {@code grabRawX} (the highlight is held there, so it does not
         * jump) and is at {@code fingerRawX} now (screen px). False when it could not be picked up, and the touch goes on
         * to the app as it was.
         */
        private boolean pickUp(float grabRawX, float fingerRawX, int haptic) {
            handler.removeCallbacks(holdTimer);
            ViewGroup barView = bar.get();
            SelectedTabPill.Highlight h = highlight.get();
            MotionEvent down = downEvent;
            downEvent = null;
            state = IDLE;
            if (barView == null || h == null || down == null) {
                if (down != null) down.recycle();
                return false;
            }
            barView.getLocationOnScreen(location);
            if (!h.beginDrag(barView, grabRawX - location[0])) {
                note("the highlight could not be picked up (" + h.describeShown() + ")");
                down.recycle();
                return false;
            }
            h.dragTo(barView, fingerRawX - location[0]);
            state = DRAGGING;
            // The tab under the finger was told that a touch started: tell it (and everything above it) that the touch
            // is over, so it does not click or long-press when the finger lets go.
            MotionEvent cancel = MotionEvent.obtain(down);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            busy = true;
            try {
                if (original != null) original.dispatchTouchEvent(cancel);
            } finally {
                busy = false;
                cancel.recycle();
                down.recycle();
            }
            barView.performHapticFeedback(haptic);
            barView.invalidate();
            return true;
        }

        /** The rest of a touch that is dragging the highlight: followed here, not passed on. */
        private boolean drag(MotionEvent ev, int action) {
            switch (action) {
                case MotionEvent.ACTION_MOVE:
                    ViewGroup barView = bar.get();
                    SelectedTabPill.Highlight h = highlight.get();
                    if (barView == null || h == null) {
                        abort();
                        break;
                    }
                    barView.getLocationOnScreen(location);
                    h.dragTo(barView, ev.getRawX() - location[0]);
                    barView.invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    finish(ev);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    finish(null);
                    break;
                default:
                    // Another finger going down or up: ignored while one is dragging.
                    break;
            }
            return true;
        }

        /** The finger let go ({@code up}), or the touch was cancelled (null). */
        private void finish(MotionEvent up) {
            state = IDLE;
            ViewGroup barView = bar.get();
            SelectedTabPill.Highlight h = highlight.get();
            if (barView == null || h == null) return;
            if (up == null) {
                h.endDrag();
                barView.invalidate();
                return;
            }
            barView.getLocationOnScreen(location);
            int target = tabAt(barView, up.getRawX() - location[0]);
            View tab = target < 0 ? null : barView.getChildAt(target);
            if (tab == null || target == h.selectedIndex()) {
                // Let go over the tab it came from: nothing to open, the highlight settles back.
                drops++;
                note("let go over the tab it came from");
                h.endDrag();
                barView.invalidate();
                return;
            }
            drops++;
            opened++;
            note("dropped on tab " + target + " (the selected one was " + h.selectedIndex() + ")");
            h.dropOn(target, tab.getLeft(), tab.getRight());
            barView.invalidate();
            tap(barView, tab);
        }

        /**
         * The tab that is under {@code x} (the bar's coordinates) as an index among the bar's children; the nearest one
         * when the finger is past the ends of the row. -1 when there is no tab.
         */
        private static int tabAt(ViewGroup barView, float x) {
            int nearest = -1;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < barView.getChildCount(); i++) {
                View child = barView.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0) continue;
                if (x >= child.getLeft() && x < child.getRight()) return i;
                float distance = Math.abs(x - (child.getLeft() + child.getRight()) / 2f);
                if (distance < best) {
                    best = distance;
                    nearest = i;
                }
            }
            return nearest;
        }

        /**
         * Taps {@code tab}: a touch down and (a moment later) up in its middle, sent through the bar like a real one, so
         * it works whether the tab or the bar takes the touch, and however Instagram listens for it (a click or a
         * touch). When nothing takes the touch down, the tab is clicked directly.
         */
        private void tap(ViewGroup barView, View tab) {
            final float x = tab.getLeft() + tab.getWidth() / 2f;
            final float y = tab.getTop() + tab.getHeight() / 2f;
            final long downTime = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            boolean handled;
            try {
                handled = barView.dispatchTouchEvent(down);
            } finally {
                down.recycle();
            }
            if (!handled) {
                tab.performClick();
                return;
            }
            handler.postDelayed(() -> {
                try {
                    MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP, x, y, 0);
                    up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    try {
                        barView.dispatchTouchEvent(up);
                    } finally {
                        up.recycle();
                    }
                } catch (Throwable t) {
                    Logger.printException(() -> "TabPillDrag: the tap on the tab failed", t);
                }
            }, TAP_MS);
        }

        /** Lets go of whatever is going on: a wait for the hold is dropped, a drag ends where it is. */
        private void abort() {
            cancelPending();
            if (state == DRAGGING) finish(null);
            state = IDLE;
        }

        /** After an error: back to the start, and the highlight is put down. */
        private void reset() {
            try {
                cancelPending();
                SelectedTabPill.Highlight h = highlight.get();
                ViewGroup barView = bar.get();
                if (h != null) h.endDrag();
                if (barView != null) barView.invalidate();
            } catch (Throwable ignored) {
            }
            state = IDLE;
        }
    }
}
