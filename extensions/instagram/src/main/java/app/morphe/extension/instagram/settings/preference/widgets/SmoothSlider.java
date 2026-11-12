/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;

/**
 * Makes a {@link SeekBar} glide under the finger instead of jumping from one notch to the next.
 * <p>
 * A plain {@link SeekBar} has as many positions as the setting has values: a slider from 0 to 10 has eleven, so the
 * thumb hops about a thumb-width at a time. Here the bar is given {@link #SUBSTEPS} positions between every two
 * values, so the thumb follows the finger pixel by pixel. The setting itself still has whole values: the value
 * is the one nearest the thumb, the listener is told each time that value changes, and when the finger lets go the
 * thumb eases onto the value it is nearest to.
 * <p>
 * Everything else about the bar is the platform's own (touch, keyboard, accessibility). A keyboard or accessibility
 * step moves by one whole value, as before, and is passed on as a settled change.
 */
final class SmoothSlider implements SeekBar.OnSeekBarChangeListener {
    /** How many positions the thumb has between two neighbouring values. */
    private static final int SUBSTEPS = 100;
    /** How long the thumb takes to ease onto its value after the finger lets go. */
    private static final long SETTLE_MS = 180L;

    /** What the row wants to know. Values are whole steps, 0 to the number of steps given to the slider. */
    abstract static class Listener {
        /** A finger went down on the thumb (or on the track, to move it). */
        void onStart() {}

        /**
         * The value under the thumb changed. {@code dragging} is true while a finger is moving it (a live
         * preview, nothing to save yet) and false for a keyboard or accessibility step (save now).
         */
        abstract void onStep(int step, boolean dragging);

        /** The finger let go: the final value, to be saved. */
        abstract void onSettle(int step);
    }

    private final SeekBar bar;
    private final int steps;
    private final Listener listener;
    private int step;
    private boolean dragging;
    private ValueAnimator glide;
    private int glideTarget = -1;

    /** Gives {@code bar} the values 0 to {@code steps}, with a thumb that glides. */
    SmoothSlider(SeekBar bar, int steps, Listener listener) {
        this.bar = bar;
        this.steps = Math.max(1, steps);
        this.listener = listener;
        bar.setMax(this.steps * SUBSTEPS);
        // One press of an arrow key is one whole value.
        bar.setKeyProgressIncrement(SUBSTEPS);
        bar.setOnSeekBarChangeListener(this);
    }

    SeekBar bar() {
        return bar;
    }

    /** The value the thumb is on. */
    int step() {
        return step;
    }

    /**
     * Puts the thumb on {@code value}; this does not count as the user moving it. A slider that is being dragged
     * is not touched. {@code animate} lets the thumb glide there instead of jumping.
     */
    void set(int value, boolean animate) {
        int target = clamp(value);
        if (dragging) return;
        step = target;
        int position = target * SUBSTEPS;
        // Already on its way there (a row that is drawn again while the thumb settles): leave it.
        if (glide != null && glide.isRunning() && glideTarget == position) return;
        cancelGlide();
        if (bar.getProgress() == position) return;
        if (animate && bar.isAttachedToWindow()) {
            glideTo(position);
        } else {
            bar.setProgress(position);
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        dragging = true;
        // A finger takes over from a thumb that was still settling.
        cancelGlide();
        listener.onStart();
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (!fromUser) return;
        if (dragging) {
            int now = nearest(progress);
            if (now != step) {
                step = now;
                listener.onStep(step, true);
            }
            return;
        }
        // A keyboard or accessibility step moves the slider without a touch, so it never reaches
        // onStopTrackingTouch: it moves by whole values (at least one, in the direction it went) and is saved at once.
        int delta = progress - step * SUBSTEPS;
        if (delta == 0) return;
        int moved = Math.max(1, Math.round(Math.abs(delta) / (float) SUBSTEPS));
        int now = clamp(step + (delta > 0 ? moved : -moved));
        bar.setProgress(now * SUBSTEPS);
        if (now != step) {
            step = now;
            listener.onStep(step, false);
        }
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        dragging = false;
        int now = nearest(bar.getProgress());
        boolean changed = now != step;
        step = now;
        // Start the glide before telling the row: saving can draw the row again, and that must not cut it short.
        glideTo(now * SUBSTEPS);
        if (changed) listener.onStep(step, true);
        listener.onSettle(step);
    }

    private void glideTo(int position) {
        cancelGlide();
        int from = bar.getProgress();
        if (from == position) return;
        glideTarget = position;
        ValueAnimator animator = ValueAnimator.ofInt(from, position);
        animator.setDuration(SETTLE_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            if (!bar.isAttachedToWindow()) {
                a.cancel();
                return;
            }
            // Not from the user, so the listener above ignores it.
            bar.setProgress((Integer) a.getAnimatedValue());
        });
        glide = animator;
        animator.start();
    }

    private void cancelGlide() {
        if (glide != null) {
            glide.cancel();
            glide = null;
        }
        glideTarget = -1;
    }

    private int nearest(int progress) {
        return clamp(Math.round(progress / (float) SUBSTEPS));
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(steps, value));
    }
}
