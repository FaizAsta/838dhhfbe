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
 * Press and hold the highlight behind the selected tab ("Custom selected tab pill"), move it along the bar and let go
 * over a tab: that tab is opened. Works on both floating bars, because both draw the same
 * {@link SelectedTabPill.Highlight}.
 * <p>
 * How it works. The tabs are Instagram's own views and take the touches themselves, so the touches are looked at one
 * step before they get there: the window's callback is wrapped (a {@link Proxy} that passes every call on to the
 * real one, and looks at {@code dispatchTouchEvent} first).
 * <ul>
 *   <li>A touch that starts on the highlight and stays there for about half a second (not moving more than the touch
 *       slop) is a "press and hold". Until then the touch goes on to the tab as usual, so a tap on the selected tab
 *       still works.</li>
 *   <li>At the hold the tab is sent a cancel (so it does not click or long-press when the finger lets go), the phone
 *       gives a short buzz, and the highlight is picked up: from now on it is drawn under the finger instead of on
 *       its tab, a little bigger, and the rest of the touch is not passed on.</li>
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
     * Called in every frame with the bar and the highlight that are on screen, or with null for the highlight when
     * there is none (the setting is off, or no highlight is drawn). That is what switches the touch handling on and
     * off.
     */
    static void sync(View decor, View bar, SelectedTabPill.Highlight highlight) {
        if (highlight == null && CONTROLLERS.isEmpty()) return;
        Window window = windowOf(decor);
        if (window == null) return;
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

    private static Window windowOf(View decor) {
        Context context = decor.getContext();
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

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final int[] location = new int[2];
        private final Runnable holdTimer = this::onHold;

        private WeakReference<ViewGroup> bar = new WeakReference<>(null);
        private WeakReference<SelectedTabPill.Highlight> highlight = new WeakReference<>(null);

        /** The wrapper that is in the window now (null while none), and the callback it passes everything on to. */
        private Window.Callback wrapper;
        private Window.Callback original;

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
            if (wrapper == null) install(window);
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
            try {
                int action = ev.getActionMasked();
                if (state == DRAGGING) {
                    if (action != MotionEvent.ACTION_DOWN) return drag(ev, action);
                    // A new touch while one was being dragged: the old one lost its end. Let it go, start over.
                    abort();
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelPending();
                    if (ev.getPointerCount() == 1 && hitHighlight(ev)) startPending(ev);
                } else if (state == PENDING) {
                    if (action == MotionEvent.ACTION_MOVE) {
                        if (Math.abs(ev.getRawX() - downX) > slop || Math.abs(ev.getRawY() - downY) > slop) {
                            cancelPending();
                        } else {
                            fingerX = ev.getRawX();
                        }
                    } else {
                        // Let go, cancelled, or a second finger: not a hold.
                        cancelPending();
                    }
                }
            } catch (Throwable t) {
                Logger.printException(() -> "TabPillDrag.onTouch failed", t);
                reset();
            }
            return target.dispatchTouchEvent(ev);
        }

        private boolean hitHighlight(MotionEvent ev) {
            ViewGroup barView = bar.get();
            SelectedTabPill.Highlight h = highlight.get();
            if (barView == null || h == null || !barView.isAttachedToWindow() || !barView.isShown()) return false;
            barView.getLocationOnScreen(location);
            float density = barView.getResources().getDisplayMetrics().density;
            return h.hit(ev.getRawX() - location[0], ev.getRawY() - location[1], HIT_SLOP_DP * density);
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
                ViewGroup barView = bar.get();
                SelectedTabPill.Highlight h = highlight.get();
                MotionEvent down = downEvent;
                downEvent = null;
                state = IDLE;
                if (barView == null || h == null || down == null) {
                    if (down != null) down.recycle();
                    return;
                }
                barView.getLocationOnScreen(location);
                if (!h.beginDrag(barView, fingerX - location[0])) {
                    down.recycle();
                    return;
                }
                state = DRAGGING;
                // The tab under the finger was told that a touch started: tell it (and everything above it) that the
                // touch is over, so it does not click or long-press when the finger lets go.
                MotionEvent cancel = MotionEvent.obtain(down);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                try {
                    if (original != null) original.dispatchTouchEvent(cancel);
                } finally {
                    cancel.recycle();
                    down.recycle();
                }
                barView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                barView.invalidate();
            } catch (Throwable t) {
                Logger.printException(() -> "TabPillDrag.onHold failed", t);
                reset();
            }
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
                h.endDrag();
                barView.invalidate();
                return;
            }
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
