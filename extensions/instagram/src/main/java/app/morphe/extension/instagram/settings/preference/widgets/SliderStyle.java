/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.SeekBar;

/**
 * The look of every slider in the settings: a thick rounded track (the accent colour up to the thumb, grey after
 * it) and a thumb shaped like a pill, filled with the colour of the page, with a white edge and a soft shadow.
 * <p>
 * Only the drawables of the {@link SeekBar} are replaced, so its behaviour (touch, keyboard, accessibility,
 * listeners) is the platform's own. Call {@link #apply} from the row's bind code; it does nothing when the look
 * is already on the slider with the same colours.
 */
final class SliderStyle {
    /** Thickness of the track. */
    private static final float TRACK_DP = 8f;
    /** Size of the thumb itself, without its shadow. */
    private static final float THUMB_WIDTH_DP = 40f;
    private static final float THUMB_HEIGHT_DP = 27f;
    /** Room around the thumb for its shadow, so the shadow is not cut off by the slider's bounds. */
    private static final float SHADOW_MARGIN_DP = 8f;
    private static final float EDGE_DP = 1.5f;
    private static final int SHADOW_STEPS = 5;

    private SliderStyle() {}

    /**
     * Gives the slider the look. {@code accent} is the colour of the filled part of the track (the grey of a
     * disabled row when the slider is disabled).
     */
    static void apply(SeekBar bar, int accent, boolean enabled) {
        Context context = bar.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int page = InstagramPreferenceStyle.backgroundColor() | 0xFF000000;

        // Already like this: nothing to build again (a slider that is being dragged is not touched).
        String key = accent + ":" + page + ":" + enabled;
        if (key.equals(bar.getTag())) return;
        bar.setTag(key);

        boolean dark = Color.luminance(page) < 0.5f;
        int trackColor = dark ? mix(page, Color.WHITE, 0.20f) : mix(page, Color.BLACK, 0.09f);
        int thumbFill = dark ? mix(page, Color.WHITE, 0.16f) : mix(page, Color.WHITE, 0.10f);
        int thumbEdge = dark ? 0x99FFFFFF : 0xFFFFFFFF;

        int trackPx = Math.max(2, Math.round(TRACK_DP * density));

        GradientDrawable back = new GradientDrawable();
        back.setColor(trackColor);
        back.setCornerRadius(trackPx / 2f);
        GradientDrawable front = new GradientDrawable();
        front.setColor(accent);
        front.setCornerRadius(trackPx / 2f);
        ClipDrawable filled = new ClipDrawable(front, Gravity.START, ClipDrawable.HORIZONTAL);

        LayerDrawable track = new LayerDrawable(new Drawable[] {back, filled});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            track.setLayerHeight(i, trackPx);
            track.setLayerGravity(i, Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
        }

        Thumb thumb = new Thumb(density, thumbFill, thumbEdge, dark, enabled ? 255 : 150);

        bar.setBackground(null);
        bar.setSplitTrack(false);
        bar.setProgressTintList(null);
        bar.setProgressBackgroundTintList(null);
        bar.setThumbTintList(null);
        bar.setProgressDrawable(track);
        bar.setThumb(thumb);
        // The thumb hangs half off each end of the track: room for its visible half, so it stays inside the slider.
        int side = Math.round(THUMB_WIDTH_DP * density / 2f);
        bar.setPadding(side, bar.getPaddingTop(), side, bar.getPaddingBottom());
    }

    private static int mix(int from, int to, float amount) {
        float keep = 1f - amount;
        return Color.argb(255,
                Math.round(Color.red(from) * keep + Color.red(to) * amount),
                Math.round(Color.green(from) * keep + Color.green(to) * amount),
                Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
    }

    /** The pill-shaped thumb: a soft shadow, the fill, and a white edge. */
    private static final class Thumb extends Drawable {
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF ring = new RectF();
        private final int fillColor;
        private final int edgeColor;
        private final int shadowAlpha;
        private final int alpha;

        Thumb(float density, int fillColor, int edgeColor, boolean dark, int alpha) {
            this.density = density;
            this.fillColor = fillColor;
            this.edgeColor = edgeColor;
            this.alpha = alpha;
            // A dark page hides a dark shadow: it is only a hint there.
            this.shadowAlpha = dark ? 10 : 8;
            fill.setStyle(Paint.Style.FILL);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, EDGE_DP * density));
            shadow.setStyle(Paint.Style.FILL);
            shadow.setColor(Color.BLACK);
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round((THUMB_WIDTH_DP + 2f * SHADOW_MARGIN_DP) * density);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round((THUMB_HEIGHT_DP + 2f * SHADOW_MARGIN_DP) * density);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float halfWidth = THUMB_WIDTH_DP * density / 2f;
            float halfHeight = THUMB_HEIGHT_DP * density / 2f;
            float left = bounds.exactCenterX() - halfWidth;
            float right = bounds.exactCenterX() + halfWidth;
            float top = bounds.exactCenterY() - halfHeight;
            float bottom = bounds.exactCenterY() + halfHeight;
            float radius = halfHeight;

            // The shadow: a few faint rings, the widest the faintest, a little lower than the thumb.
            float drop = 2.5f * density;
            for (int i = SHADOW_STEPS; i >= 1; i--) {
                float grow = i * 1.1f * density;
                ring.set(left - grow, top - grow + drop, right + grow, bottom + grow + drop);
                shadow.setAlpha(scale(shadowAlpha));
                canvas.drawRoundRect(ring, radius + grow, radius + grow, shadow);
            }

            rect.set(left, top, right, bottom);
            fill.setColor(fillColor);
            fill.setAlpha(scale(Color.alpha(fillColor)));
            canvas.drawRoundRect(rect, radius, radius, fill);

            float half = edge.getStrokeWidth() / 2f;
            ring.set(left + half, top + half, right - half, bottom - half);
            edge.setColor(edgeColor);
            edge.setAlpha(scale(Color.alpha(edgeColor)));
            canvas.drawRoundRect(ring, radius - half, radius - half, edge);
        }

        private int scale(int value) {
            return Math.round(value * alpha / 255f);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
