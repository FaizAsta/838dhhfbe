/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;

import app.morphe.extension.shared.Logger;

/**
 * The glass of Instagram's own floating bar (see {@link NativeBarBlur}), drawn for the preview on the settings screen:
 * the same drop shadow, the same blur, the same liquid glass shader ({@link LiquidGlassEffect}), the same tint, the
 * same opacity and the same corner radius, from the same numbers ({@link LiquidGlassLook}). The only difference is
 * what is behind the pill: not the real page but a picture that the preview draws (a {@link Page}).
 * <p>
 * Needs Android 12 ({@code RenderEffect}); the liquid glass shader needs Android 13, below that the plain blur is
 * drawn, as on the real bar. The settings screen only makes one of these on Android 12 or newer.
 */
public final class LiquidGlassPreview {
    /** Paints what is behind the glass, in the coordinates of the canvas that was given to {@link #draw}. */
    public interface Page {
        void draw(Canvas canvas);
    }

    /** The same scale as the real bar's blur slider (see NativeBarBlur). */
    private static final float MAX_BLUR_RADIUS_DP = 44f;

    private final float density;
    private final RenderNode node = new RenderNode("PikoPreviewGlass");
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF ring = new RectF();

    // What the render effect of the node was made from, so it is made again only when one of these changes.
    private float effectBlurPx = -1f;
    private boolean effectGlassy;
    private LiquidGlassLook effectLook;
    private float effectWidth = -1f;
    private float effectHeight = -1f;
    private float effectRadius = -1f;
    private int effectPad = -1;
    /** The shader could not be built on this phone: the plain blur is drawn instead (until liquid is switched off). */
    private boolean liquidFailed;

    public LiquidGlassPreview(float density) {
        this.density = density;
        node.setClipToBounds(true);
        fill.setStyle(Paint.Style.FILL);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, density));
    }

    /** True where the liquid glass shader runs (Android 13 or newer). */
    public static boolean liquidSupported() {
        return LiquidGlassEffect.supported();
    }

    /**
     * The corner radius of the pill, in px: a full pill, or with liquid glass on the corner radius slider's share of
     * it (100 % is a full pill, 0 % is square), as on the real bar.
     */
    public static float radius(RectF pill, boolean liquid, LiquidGlassLook look) {
        float half = Math.min(pill.width(), pill.height()) / 2f;
        return liquid && look != null ? half * look.cornerPart() : half;
    }

    /**
     * Draws the glass pill: its shadow, the blurred (and, with {@code liquid}, bent) picture behind it, the tint
     * and the thin edge. What goes on top of the pill (the highlight behind the selected tab, the icons) is drawn by
     * the caller afterwards, as the real bar draws its tabs after its background.
     *
     * @param pill                the pill, in the canvas's coordinates
     * @param radius              the corner radius (see {@link #radius})
     * @param blurPercent         0 to 100, the blur slider
     * @param transparencyPercent 0 to 100: 0 is a solid tint, 100 is no tint
     * @param liquid              the liquid glass look is on
     * @param look                the liquid glass numbers (ignored unless {@code liquid})
     * @param shade               the colour of the tint (alpha ignored)
     * @param edgeColor           the colour of the thin edge
     * @param page                what is behind the pill
     */
    public void draw(Canvas canvas, RectF pill, float radius, int blurPercent, int transparencyPercent,
                     boolean liquid, LiquidGlassLook look, int shade, int edgeColor, Page page) {
        if (look == null) look = LiquidGlassLook.DEFAULT;
        if (!liquid) liquidFailed = false;
        clip.rewind();
        clip.addRoundRect(pill, radius, radius, Path.Direction.CW);

        // Liquid glass: the opacity fades the whole glass (and its shadow), so they are drawn into one layer.
        int layer = -1;
        if (liquid) {
            float spread = look.shadowSpreadPx(density);
            if (look.opacity < 100) {
                float m = spread * 1.5f + 2f * density;
                layer = canvas.saveLayerAlpha(pill.left - m, pill.top - m, pill.right + m, pill.bottom + m,
                        Math.round(255f * look.opacityPart()));
            }
            drawShadow(canvas, pill, radius, look, spread);
        }

        if (canvas.isHardwareAccelerated()) {
            int count = canvas.save();
            try {
                drawBlur(canvas, pill, radius, blurPercent, liquid, look, page);
            } catch (Throwable t) {
                // The tint is still drawn.
                Logger.printException(() -> "LiquidGlassPreview: the glass could not be drawn", t);
            } finally {
                canvas.restoreToCount(count);
            }
        }

        // 0 % transparency is a solid tint, 100 % is none: the page behind shows through as it is.
        int alpha = Math.round(255f * (100 - Math.max(0, Math.min(100, transparencyPercent))) / 100f);
        fill.setColor((shade & 0x00FFFFFF) | (alpha << 24));
        canvas.drawRoundRect(pill, radius, radius, fill);

        edge.setColor(edgeColor);
        float half = edge.getStrokeWidth() / 2f;
        ring.set(pill.left + half, pill.top + half, pill.right - half, pill.bottom - half);
        canvas.drawRoundRect(ring, Math.max(0f, radius - half), Math.max(0f, radius - half), edge);
        if (layer >= 0) canvas.restoreToCount(layer);
    }

    /** The soft drop shadow around the pill, with the pill itself cut out so it never shows through the glass. */
    private void drawShadow(Canvas canvas, RectF pill, float radius, LiquidGlassLook look, float spread) {
        float alpha = look.shadowAlpha();
        if (alpha <= 0f || spread < 1f) return;
        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setShadowLayer(spread, 0f, spread * 0.3f, Color.argb(Math.round(255f * alpha), 0, 0, 0));
        int save = canvas.save();
        try {
            canvas.clipOutPath(clip);
            canvas.drawRoundRect(pill, radius, radius, shadowPaint);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Records the page into the blur node (with the pill's top left corner {@code pad} px in from the node's) and
     * draws the node clipped to the pill. The same as the real bar's {@code drawBlur}, with the page drawn by the
     * preview instead of the views behind the bar.
     */
    private void drawBlur(Canvas canvas, RectF pill, float radius, int blurPercent, boolean liquid,
                          LiquidGlassLook look, Page page) {
        float blurPx = MAX_BLUR_RADIUS_DP * density * Math.max(0, Math.min(100, blurPercent)) / 100f;
        // Under half a pixel there is nothing to blur: the recorded page is drawn as it is.
        if (blurPx < 0.5f) blurPx = 0f;
        boolean glassy = liquid && !liquidFailed && LiquidGlassEffect.supported();
        // Room all around the pill for the blur to read (the glass shader itself never looks outside the pill).
        int pad = (int) Math.ceil(blurPx * 2f);
        int width = (int) Math.ceil(pill.width()) + 2 * pad;
        int height = (int) Math.ceil(pill.height()) + 2 * pad;

        node.setPosition(0, 0, width, height);
        if (effectBlurPx != blurPx || effectGlassy != glassy || !look.equals(effectLook)
                || effectWidth != pill.width() || effectHeight != pill.height() || effectRadius != radius
                || effectPad != pad) {
            effectBlurPx = blurPx;
            effectGlassy = glassy;
            effectLook = look;
            effectWidth = pill.width();
            effectHeight = pill.height();
            effectRadius = radius;
            effectPad = pad;
            // CLAMP: the edges of the recorded area repeat instead of fading to black.
            RenderEffect blur = blurPx > 0f
                    ? RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                    : null;
            RenderEffect effect = blur;
            if (glassy) {
                try {
                    effect = LiquidGlassEffect.create(blur, pill.width(), pill.height(), pad, radius, density, look);
                } catch (Throwable t) {
                    // The shader was refused: the plain blur is used from now on.
                    liquidFailed = true;
                    effectGlassy = false;
                    effect = blur;
                    Logger.printException(() -> "LiquidGlassPreview: the liquid glass shader could not be built", t);
                }
            }
            node.setRenderEffect(effect);
        }

        Canvas recording = node.beginRecording(width, height);
        try {
            recording.translate(-(pill.left - pad), -(pill.top - pad));
            page.draw(recording);
        } finally {
            node.endRecording();
        }

        canvas.clipPath(clip);
        canvas.translate(pill.left - pad, pill.top - pad);
        canvas.drawRenderNode(node);
    }
}
