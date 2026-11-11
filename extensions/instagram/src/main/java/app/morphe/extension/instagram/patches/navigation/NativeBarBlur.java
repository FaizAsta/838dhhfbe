/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;

/**
 * Gives Instagram's own floating bar (the one switched on with "Instagram's own floating bar") a live, see-through
 * blur: the page that runs behind the bar (feed, Reels, search, profile) shows through it, blurred, and moves with
 * it while you scroll. It is the glass look of the iOS bar.
 * <p>
 * How it is done, without searching for anything in Instagram's code: the bar's background is swapped for a
 * {@link Backdrop} drawable. Every time the bar is drawn, the drawable records what is behind the bar (the views drawn
 * before the bar in each of its parents, so nothing that contains the bar) into a {@code RenderNode} with a blur
 * {@code RenderEffect}, and draws that node clipped to the shape of Instagram's own pill, with a translucent tint and
 * a thin edge on top. The recording is live: the node points at those views' own display lists, so the blur follows
 * the page as it scrolls or plays. Instagram's own background is kept (not drawn), to read its colour from and to
 * give back, exactly like the pill of {@link FloatingNavBar} does.
 * <p>
 * The shape of the pill is the one the settings show, not the one Instagram's background happens to paint. Instagram's
 * background is not a pill that follows the bar: on the feed it is an {@code InsetDrawable} that keeps fixed distances
 * from the bar's edges (about 17 dp at the sides, 9 dp above and 16 dp below on the phone this was measured on), so
 * once the bar is made lower or narrower the pill is no longer the size on the sliders and its middle is no longer
 * the middle of the icons; on the Reels tab it is a flat fill of the whole bar, gesture inset included. So the pill is
 * <em>laid out here</em>: as wide as the bar when {@link NativeBarSize} has given the bar the width of the slider, as
 * tall as the slider's height, and always centered on the tabs, so the icons sit in the middle of it. Instagram's
 * background is only measured where its own pill would be when the width could not be applied to the bar.
 * <p>
 * With the blur switched off the pill is still fitted: Instagram's own background is then drawn (clipped to the
 * rounded pill) at the fitted place, so the bar keeps Instagram's colours and gets the size of the sliders.
 * <p>
 * "Custom selected tab pill" is drawn by this drawable as well, on top of the pill (blurred or not) and under the
 * tabs, with the size of the selected tab's cell inside the pill (see {@link SelectedTabPill.Highlight}). It lives
 * here because this is where the pill's rectangle is known, and so it needs the same Android 12 as the rest.
 * <p>
 * Only for Instagram's own bar: {@link FloatingNavBar} calls this only when that bar is really on (its flags were on
 * when the bar was created). The pill of the floating navigation bar is not touched.
 * <p>
 * Limits, so nothing is taken for granted:
 * <ul>
 *   <li>Needs Android 12 (API 31) or newer, where {@code RenderEffect} exists. On older versions the bar is left as
 *       Instagram draws it, and one line says so in the log.</li>
 *   <li>A video that is drawn on a {@code SurfaceView} cannot be recorded by any view: it shows as a hole in the
 *       blur. Videos on a {@code TextureView} blur normally.</li>
 *   <li>If Instagram's bar draws its pill on a child view that is not see-through, the blur lies under it and cannot
 *       be seen. The log line written when the blur is put on tells what the bar's background and children are, so
 *       that case can be told apart.</li>
 *   <li>Anything that goes wrong while recording turns the blur off for that bar (the tint stays) and goes into the
 *       log; it never reaches the app.</li>
 * </ul>
 * The blur is kept live by drawing the bar again whenever the window is drawn for another reason (see
 * {@link #apply}), never on a timer, so nothing is drawn while the screen is still.
 */
final class NativeBarBlur {
    /** {@code RenderEffect} needs Android 12. */
    private static final int MIN_SDK = Build.VERSION_CODES.S;
    /** Blur radius (dp) at 100 % on the blur slider; 50 % (the default) is the 22 dp the blur always had. */
    private static final float MAX_BLUR_RADIUS_DP = 44f;
    private static final float EDGE_DP = 1f;
    /** Instagram sets its background back more than this many times in a second: stop fighting it. */
    private static final int MAX_REPLACEMENTS_PER_SECOND = 30;

    /** Where the pill was last drawn, for the debug copy of the bar's structure (see NativeBarSize#debugReport). */
    private static volatile String lastShape;
    /** Which tab the highlight behind the selected tab found, for the same debug copy; null while it is off. */
    private static volatile String lastTabPill;

    private NativeBarBlur() {}

    /**
     * Where the pill is drawn in the bar, and (while "Custom selected tab pill" is on) which tab was found to be
     * selected, or null before the first draw.
     */
    static String debugShape() {
        String shape = lastShape;
        if (shape == null) return null;
        String tab = lastTabPill;
        return tab == null ? shape : shape + "\n" + tab;
    }

    /** The drawable that is on the bar, and what is needed to take it off again. One per bar. */
    static final class Memory {
        Backdrop backdrop;
        /** The bar was drawn again by this class on the last frame: the next one is left alone (see apply). */
        boolean selfInvalidated;
        boolean olderAndroidLogged;
        boolean describedLogged;
        /** The background was swapped for the drawable, and not given back yet. */
        boolean applied;
        /** Instagram put its background back too often: left alone until the setting is switched off and on. */
        boolean gaveUp;
        long replacementsSince;
        int replacements;

        boolean isApplied() {
            return applied;
        }
    }

    /** The highlight behind the selected tab of the bar's current background, or null while none is applied. */
    static SelectedTabPill.Highlight highlight(Memory memory) {
        Backdrop backdrop = memory.applied ? memory.backdrop : null;
        return backdrop == null ? null : backdrop.highlight;
    }

    /** Whether this Android can blur at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= MIN_SDK;
    }

    /** The setting was switched off or on again: a bar that was given up on gets another try. */
    static void forgetFailures(Memory memory) {
        memory.gaveUp = false;
        memory.replacements = 0;
    }

    /**
     * Puts the pill of the sliders on the bar (with the blur, when {@code glass} is on) and keeps it up to date.
     * Called before every frame while Instagram's own bar is shown.
     * <ul>
     *   <li>{@code glass}: the blur and the tint are drawn. Off, Instagram's own background is drawn instead, at the
     *       fitted place;</li>
     *   <li>{@code blurPercent} (0 to 100) is how strong the blur is (0 = the page behind shows sharp) and
     *       {@code transparencyPercent} (0 to 100) how see-through the glass is (0 = a solid tint, 100 = no tint);</li>
     *   <li>{@code fillWidth}: the width of the slider was given to the bar (see {@link NativeBarSize}), so the pill is
     *       as wide as the bar. False keeps the width of Instagram's own pill;</li>
     *   <li>{@code pillHeightPx}: the pill's height in px (the slider's), centered on the tabs. 0 keeps the height
     *       and the place of Instagram's own pill;</li>
     *   <li>{@code tabPill} and {@code tabPillImage}: "Custom selected tab pill" is on, and the user's picture for it
     *       (null: the default capsule). The highlight is drawn by the pill, behind the tabs, with the size of the
     *       selected tab's cell inside the pill, so it follows the two sliders (see {@link SelectedTabPill}).</li>
     * </ul>
     */
    static void apply(View bar, Memory memory, boolean glass, int blurPercent, int transparencyPercent,
                      boolean fillWidth, int pillHeightPx, boolean tabPill, Bitmap tabPillImage) {
        if (!supported()) {
            if (!memory.olderAndroidLogged) {
                memory.olderAndroidLogged = true;
                Logger.printInfo(() -> "Floating navigation bar: the pill and the blur of Instagram's own bar need "
                        + "Android 12 or newer; the bar is left as Instagram draws it");
            }
            return;
        }
        if (memory.gaveUp) return;

        float density = bar.getResources().getDisplayMetrics().density;
        Backdrop backdrop = memory.backdrop;
        if (backdrop == null) {
            backdrop = new Backdrop(density);
            memory.backdrop = backdrop;
        }

        Drawable current = bar.getBackground();
        if (current != backdrop) {
            long now = SystemClock.uptimeMillis();
            if (now - memory.replacementsSince > 1000L) {
                memory.replacementsSince = now;
                memory.replacements = 0;
            }
            if (++memory.replacements > MAX_REPLACEMENTS_PER_SECOND) {
                // Instagram sets its own background back as often as it is replaced.
                memory.gaveUp = true;
                restore(bar, memory);
                Logger.printInfo(() -> "Floating navigation bar: Instagram's own bar sets its background back on "
                        + "every frame, so the pill and the blur are not applied to it");
                return;
            }
            // The blur goes on first: setting a background clears the callback of the one it replaces, and the old
            // background must report its changes to the blur from now on.
            bar.setBackground(backdrop);
            backdrop.setBase(current);
            memory.applied = true;
            if (!memory.describedLogged) {
                memory.describedLogged = true;
                final String text = describe(bar, current);
                Logger.printInfo(() -> "Floating navigation bar: pill put on Instagram's own bar. " + text);
            }
        }

        boolean night = (bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        backdrop.update(bar, bar.getPaddingBottom(), night, glass, blurPercent, transparencyPercent, fillWidth,
                pillHeightPx);
        // The highlight behind the selected tab. The tab is looked at in every frame (a tab changing its selected
        // state does not redraw the bar's background by itself); the bar is drawn again only when it changed.
        backdrop.updateTabPill(bar, tabPill, tabPillImage);

        // Without the glass nothing behind the bar is recorded, so there is nothing to keep live.
        if (!glass) {
            memory.selfInvalidated = false;
            return;
        }

        // Keep it live. This runs before a frame that is going to be drawn anyway (the page scrolled, a video
        // moved on), so drawing the bar in that same frame costs nothing extra. The frame that this call causes is
        // left alone, so the bar never asks for a frame of its own over and over: nothing is drawn while the
        // screen is still.
        if (memory.selfInvalidated) {
            memory.selfInvalidated = false;
        } else {
            memory.selfInvalidated = true;
            bar.invalidate();
        }
    }

    /** Gives the bar the background Instagram had. */
    static void restore(View bar, Memory memory) {
        Backdrop backdrop = memory.backdrop;
        memory.backdrop = null;
        memory.applied = false;
        memory.selfInvalidated = false;
        if (backdrop == null) return;
        Drawable base = backdrop.releaseBase();
        // Only when the bar still wears the blur: if Instagram has set its own background since, it keeps it.
        if (bar != null && bar.getBackground() == backdrop) {
            bar.setBackground(base);
            bar.invalidate();
        }
        backdrop.release();
    }

    /** One line about the bar for the log: its background, and its children's backgrounds. */
    private static String describe(View bar, Drawable background) {
        StringBuilder text = new StringBuilder("Background: ")
                .append(background == null ? "none" : background.getClass().getSimpleName());
        if (bar instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) bar;
            text.append("; children: ");
            int count = Math.min(group.getChildCount(), 8);
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                if (i > 0) text.append(", ");
                text.append(child.getClass().getSimpleName()).append(child.getBackground() == null ? "" : "(bg)");
            }
        }
        return text.toString();
    }

    // ---- the drawable ----

    /**
     * The background of the bar while the blur is on: the page behind the bar, blurred and clipped to the bar's
     * rounded shape, a translucent tint and a thin edge. Instagram's own background is held, not drawn. Everything
     * that needs Android 12 is in here, and an instance is only made on Android 12 or newer.
     */
    private static final class Backdrop extends Drawable implements Drawable.Callback {
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF ring = new RectF();
        private final Path clip = new Path();
        private final int[] where = new int[2];

        private Drawable base;
        private WeakReference<View> bar = new WeakReference<>(null);
        /** The painted area of Instagram's own background (its pill), in this drawable's coordinates; null if unknown. */
        private Rect baseShape;
        /**
         * How far Instagram's own pill keeps from the edges of its drawable (left, top, right, bottom, px), as it
         * painted when it was measured. Zero for a flat background that fills all of it. Used to draw Instagram's
         * background at the fitted place (see drawOwn).
         */
        private final int[] baseInsets = new int[4];
        /** The glass (blur and tint) is drawn; false draws Instagram's own background at the fitted place. */
        private boolean glass = true;
        /** The pill is as wide as the bar (the width of the slider was given to the bar). */
        private boolean fillWidth;
        /** The height of the pill in px, centered on the tabs; 0 keeps Instagram's own pill. */
        private int pillHeightPx;
        private int shapeW = -1;
        private int shapeH = -1;
        private Drawable shapeOf;
        private boolean loggedDrawn;
        private boolean loggedShape;
        private final RectF shownRect = new RectF();
        private int bottomInset;
        private boolean dark = true;
        private boolean night = true;
        private int lastUnder = Integer.MIN_VALUE;
        private boolean baseSampled;
        private int sampledColor;
        private float radius;

        private RenderNode node;
        private float nodeRadiusPx = -1f;
        private boolean drawing;
        private boolean blurFailed;
        private int blurPercent = 50;
        private int transparencyPercent = 50;
        /** The highlight behind the selected tab ("Custom selected tab pill"), drawn on top of the pill. */
        private final SelectedTabPill.Highlight highlight;

        Backdrop(float density) {
            this.density = density;
            highlight = new SelectedTabPill.Highlight(density);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, EDGE_DP * density));
            fill.setStyle(Paint.Style.FILL);
        }

        /**
         * Takes "Custom selected tab pill" (on or off, and the user's picture) and looks at which tab of the bar is
         * selected. The bar is drawn again only when something changed (see {@link SelectedTabPill.Highlight}).
         */
        void updateTabPill(View barView, boolean on, Bitmap picture) {
            if (!(barView instanceof ViewGroup)) return;
            if (highlight.update((ViewGroup) barView, on, picture)) {
                lastTabPill = highlight.report();
                invalidateSelf();
            }
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
            lastUnder = Integer.MIN_VALUE;
            if (drawable != null) drawable.setCallback(this);
            invalidateSelf();
        }

        /** Lets go of Instagram's background (it is given back to the bar) and returns it. */
        Drawable releaseBase() {
            Drawable released = base;
            if (released != null && released.getCallback() == this) released.setCallback(null);
            base = null;
            return released;
        }

        /** Lets go of the recorded page. */
        void release() {
            RenderNode old = node;
            node = null;
            if (old != null) old.discardDisplayList();
            bar.clear();
        }

        /** Takes the bar, the page and the shade (from what Instagram's background has under it). */
        void update(View barView, int bottomInset, boolean night, boolean glass, int blurPercent,
                    int transparencyPercent, boolean fillWidth, int pillHeightPx) {
            // Runs before every frame: a new reference is made only when the view changed.
            if (bar.get() != barView) bar = new WeakReference<>(barView);
            blurPercent = Math.max(0, Math.min(100, blurPercent));
            transparencyPercent = Math.max(0, Math.min(100, transparencyPercent));
            pillHeightPx = Math.max(0, pillHeightPx);
            boolean moved = this.bottomInset != bottomInset || this.night != night
                    || this.blurPercent != blurPercent || this.transparencyPercent != transparencyPercent
                    || this.glass != glass || this.fillWidth != fillWidth || this.pillHeightPx != pillHeightPx;
            this.bottomInset = bottomInset;
            this.night = night;
            this.glass = glass;
            this.blurPercent = blurPercent;
            this.transparencyPercent = transparencyPercent;
            this.fillWidth = fillWidth;
            this.pillHeightPx = pillHeightPx;

            int under = colorUnder();
            if (!moved && under == lastUnder) return;
            lastUnder = under;
            boolean known = Color.alpha(under) >= 0x60;
            dark = known ? Color.luminance(under) < 0.5f : night;
            int shade = known ? FloatingNavBar.pillFill(under, dark) : (dark ? 0xFF1E2226 : 0xFFEFEFF1);
            // 0 % transparency is a solid tint, 100 % is none: the page behind shows through as it is.
            int alpha = Math.round(255f * (100 - transparencyPercent) / 100f);
            fill.setColor((shade & 0x00FFFFFF) | (alpha << 24));
            edge.setColor(FloatingNavBar.pillEdge(dark));
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
            // Any other drawable: its colour is read in measureBase, in the middle of what it paints (0 until then).
            return sampledColor;
        }

        /**
         * Finds where Instagram's own background really paints (its pill), by drawing it once into a small picture
         * and taking the box of what is painted. The bar view is taller than its visible pill (the system inset
         * under it, the room around it), so the bar's own bounds are not the pill. Done again only when the bar's
         * size or its background changes.
         */
        private void measureBase(Rect bounds) {
            Drawable source = base;
            int w = bounds.width();
            int h = bounds.height();
            if (source == shapeOf && w == shapeW && h == shapeH) return;
            shapeOf = source;
            shapeW = w;
            shapeH = h;
            baseShape = null;
            sampledColor = 0;
            java.util.Arrays.fill(baseInsets, 0);
            if (source == null || w < 16 || h < 16 || (long) w * h > 4_000_000L) return;
            Drawable.Callback callback = source.getCallback();
            Bitmap bitmap = null;
            try {
                // Drawing it must not ask this drawable (or the bar) to draw again.
                source.setCallback(null);
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                source.setBounds(0, 0, w, h);
                source.draw(new Canvas(bitmap));
                int[] pixels = new int[w * h];
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
                int minX = w, minY = h, maxX = -1, maxY = -1;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if ((pixels[y * w + x] >>> 24) >= 0x40) {
                            if (x < minX) minX = x;
                            if (x > maxX) maxX = x;
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                    }
                }
                if (maxX - minX >= 8 * density && maxY - minY >= 8 * density) {
                    baseShape = new Rect(minX + bounds.left, minY + bounds.top, maxX + 1 + bounds.left,
                            maxY + 1 + bounds.top);
                    baseInsets[0] = minX;
                    baseInsets[1] = minY;
                    baseInsets[2] = w - (maxX + 1);
                    baseInsets[3] = h - (maxY + 1);
                    sampledColor = pixels[((minY + maxY) / 2) * w + (minX + maxX) / 2];
                }
            } catch (Throwable ignored) {
                // Instagram's own drawable can refuse a size (a gradient with no radius, for one): the shape is then
                // not known and the fallback is used. Its old bounds are NOT put back: this drawable is never
                // drawn by the bar while the blur is on, and an empty rectangle is exactly what such a drawable
                // refuses.
                baseShape = null;
            } finally {
                try {
                    source.setCallback(callback);
                } catch (Throwable ignored) {
                }
                if (bitmap != null) bitmap.recycle();
            }
        }

        /**
         * The rounded shape of the pill in the drawable's own coordinates, and false when there is not enough room
         * for one.
         * <ul>
         *   <li>across: all of the bar's width when the width of the slider was given to the bar (the bar is then
         *       exactly as wide as the pill should be), otherwise where Instagram's own background paints its pill;</li>
         *   <li>up and down: {@link #pillHeightPx} high and centered on the tabs, so the icons are in the middle of
         *       the pill whatever Instagram's own background does. Without a height (or before the tabs have been
         *       laid out) where Instagram's own background paints its pill, or all of the bar above the system
         *       inset when that cannot be told.</li>
         * </ul>
         */
        private boolean shape(Rect bounds, boolean measure) {
            // Measuring draws Instagram's drawable, so it is only done from draw(), never while the bounds or the
            // outline are being changed.
            if (measure) measureBase(bounds);

            float left = bounds.left;
            float right = bounds.right;
            float top = bounds.top;
            float bottom = bounds.bottom - bottomInset;
            if (bottom - top < 8f * density) bottom = bounds.bottom;
            if (baseShape != null) {
                if (!fillWidth) {
                    left = baseShape.left;
                    right = baseShape.right;
                }
                top = baseShape.top;
                bottom = baseShape.bottom;
            }

            if (pillHeightPx > 0) {
                float centre = tabsCentre();
                if (!Float.isNaN(centre)) {
                    float height = Math.min(pillHeightPx, bounds.height());
                    top = centre - height / 2f;
                    bottom = centre + height / 2f;
                    // Never outside the bar: it could not be drawn there.
                    if (top < bounds.top) {
                        top = bounds.top;
                        bottom = top + height;
                    }
                    if (bottom > bounds.bottom) {
                        bottom = bounds.bottom;
                        top = bottom - height;
                    }
                }
            }

            if (right - left < 8f * density || bottom - top < 8f * density) return false;
            rect.set(left, top, right, bottom);
            float pill = Math.min(rect.width(), rect.height()) / 2f;
            radius = pill;
            if (base instanceof GradientDrawable) {
                // Instagram's own corner radius when it is smaller than a full pill.
                float own = ((GradientDrawable) base).getCornerRadius();
                if (own > 0f && own < pill) radius = own;
            }
            return true;
        }

        /**
         * The middle of the tabs, up and down, in the bar's coordinates: halfway between the top of the highest
         * tab and the bottom of the lowest one. An icon is centered in its tab, so a pill centered here has the icons
         * in its middle. NaN when the bar is gone or has no tab that has been laid out.
         */
        private float tabsCentre() {
            View view = bar.get();
            if (!(view instanceof ViewGroup)) return Float.NaN;
            ViewGroup group = (ViewGroup) view;
            int top = Integer.MAX_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) {
                    continue;
                }
                top = Math.min(top, child.getTop());
                bottom = Math.max(bottom, child.getBottom());
            }
            if (top == Integer.MAX_VALUE) return Float.NaN;
            return (top + bottom) / 2f;
        }

        @Override
        public void draw(Canvas canvas) {
            if (!shape(getBounds(), true)) return;
            clip.rewind();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            if (!rect.equals(shownRect)) {
                // Where the pill is now, for the debug copy of the bar (and once for the log).
                shownRect.set(rect);
                lastShape = describeShape();
                if (!loggedShape) {
                    loggedShape = true;
                    Logger.printInfo(() -> "Floating navigation bar: " + lastShape);
                }
            }

            if (!glass) {
                drawOwn(canvas);
                drawTabPill(canvas);
                return;
            }

            if (!blurFailed && !drawing && canvas.isHardwareAccelerated()) {
                View barView = bar.get();
                if (barView != null && barView.isAttachedToWindow()) {
                    drawing = true;
                    int count = canvas.save();
                    try {
                        drawBlur(canvas, barView);
                    } catch (Throwable t) {
                        // The tint is still drawn; the blur is given up on for this bar.
                        blurFailed = true;
                        Logger.printException(() -> "NativeBarBlur: the blur could not be drawn", t);
                    } finally {
                        canvas.restoreToCount(count);
                        drawing = false;
                    }
                }
            }

            canvas.drawRoundRect(rect, radius, radius, fill);
            float half = edge.getStrokeWidth() / 2f;
            ring.set(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half);
            canvas.drawRoundRect(ring, Math.max(0f, radius - half), Math.max(0f, radius - half), edge);
            drawTabPill(canvas);
        }

        /**
         * The highlight behind the selected tab, on top of the pill and under the tabs' icons (the bar draws its tabs
         * after its background). It has the size of the selected tab's cell inside the pill, so it follows the width
         * and the height of the sliders. Asks for another frame while it slides to a new tab.
         */
        private void drawTabPill(Canvas canvas) {
            if (highlight.draw(canvas, rect.left, rect.right, rect.top, rect.bottom, dark)) invalidateSelf();
        }

        /** Where the pill is in the bar and why, in dp, on one line. */
        private String describeShape() {
            Rect bounds = getBounds();
            float centre = tabsCentre();
            return "Pill in the bar: [" + dp(rect.left) + "," + dp(rect.top) + " " + dp(rect.width()) + "x"
                    + dp(rect.height()) + "dp] of a " + dp(bounds.width()) + "x" + dp(bounds.height())
                    + "dp bar, tabs centre y=" + (Float.isNaN(centre) ? "?" : String.valueOf(dp(centre)))
                    + "dp, Instagram's own pill insets l/t/r/b=" + dp(baseInsets[0]) + "/" + dp(baseInsets[1]) + "/"
                    + dp(baseInsets[2]) + "/" + dp(baseInsets[3]) + "dp, fillWidth=" + fillWidth + ", glass=" + glass;
        }

        private int dp(float px) {
            return Math.round(px / density);
        }

        /**
         * Draws Instagram's own background at the fitted place, for the bar without the glass: its own pill is made
         * to fall on the rectangle of {@link #shape} (the drawable is given bounds that are that rectangle plus the
         * distances its pill keeps from its edges), and what it paints is clipped to the rounded shape, so a flat
         * background (Reels) is a pill too.
         */
        private void drawOwn(Canvas canvas) {
            Drawable source = base;
            if (source == null) return;
            Drawable.Callback callback = source.getCallback();
            int count = canvas.save();
            try {
                // Drawing it must not ask this drawable (or the bar) to draw again.
                source.setCallback(null);
                canvas.clipPath(clip);
                source.setBounds(Math.round(rect.left) - baseInsets[0], Math.round(rect.top) - baseInsets[1],
                        Math.round(rect.right) + baseInsets[2], Math.round(rect.bottom) + baseInsets[3]);
                source.draw(canvas);
            } catch (Throwable ignored) {
                // Instagram's own drawable can refuse a size: nothing is drawn for this frame.
            } finally {
                canvas.restoreToCount(count);
                try {
                    source.setCallback(callback);
                } catch (Throwable ignored) {
                }
            }
        }

        /**
         * Records what is drawn behind the bar into the blur node and draws it, clipped to the bar's shape. What is
         * behind the bar is every view that is drawn before the bar's branch in each of the bar's parents (the page,
         * whatever the container is called and wherever it sits), so nothing that contains the bar is ever recorded
         * (the bar would be recorded into its own blur). The node points at those views' own display lists, so it
         * shows what they show now.
         */
        private void drawBlur(Canvas canvas, View barView) {
            float blurPx = MAX_BLUR_RADIUS_DP * density * blurPercent / 100f;
            // Under half a pixel there is nothing to blur: the recorded page is drawn as it is.
            if (blurPx < 0.5f) blurPx = 0f;
            int pad = (int) Math.ceil(blurPx * 2f);
            int width = (int) Math.ceil(rect.width()) + 2 * pad;
            int height = (int) Math.ceil(rect.height()) + 2 * pad;

            barView.getLocationOnScreen(where);
            // Where the node's top left corner is on the screen.
            float originX = where[0] + rect.left - pad;
            float originY = where[1] + rect.top - pad;

            RenderNode record = node;
            if (record == null) {
                record = new RenderNode("PikoNativeBarBlur");
                record.setClipToBounds(true);
                node = record;
                nodeRadiusPx = -1f;
            }
            record.setPosition(0, 0, width, height);
            if (nodeRadiusPx != blurPx) {
                nodeRadiusPx = blurPx;
                // CLAMP: the edges of the recorded area repeat instead of fading to black.
                record.setRenderEffect(blurPx > 0f
                        ? RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                        : null);
            }
            int recorded = 0;
            Canvas recording = record.beginRecording(width, height);
            try {
                View child = barView;
                for (int level = 0; level < 16 && child.getParent() instanceof ViewGroup; level++) {
                    ViewGroup parent = (ViewGroup) child.getParent();
                    int index = parent.indexOfChild(child);
                    for (int i = 0; i < index && i < 96; i++) {
                        View behind = parent.getChildAt(i);
                        if (behind.getVisibility() != View.VISIBLE || behind.getWidth() <= 0
                                || behind.getHeight() <= 0) {
                            continue;
                        }
                        behind.getLocationOnScreen(where);
                        int save = recording.save();
                        recording.translate(where[0] - originX, where[1] - originY);
                        behind.draw(recording);
                        recording.restoreToCount(save);
                        recorded++;
                    }
                    child = parent;
                }
            } finally {
                record.endRecording();
            }
            if (!loggedDrawn) {
                loggedDrawn = true;
                final int count = recorded;
                Logger.printInfo(() -> "Floating navigation bar: blur recorded " + count + " view(s) behind "
                        + "Instagram's own bar");
            }

            canvas.clipPath(clip);
            canvas.translate(rect.left - pad, rect.top - pad);
            canvas.drawRenderNode(record);
        }

        @Override
        public void getOutline(Outline outline) {
            Rect bounds = getBounds();
            if (!shape(bounds, false)) {
                outline.setEmpty();
                return;
            }
            outline.setRoundRect(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right),
                    Math.round(rect.bottom), radius);
            // The shape clips and is hit-tested, but casts no shadow under a see-through bar.
            outline.setAlpha(0f);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            invalidateSelf();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }
}
