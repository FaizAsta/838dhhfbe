/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.settings.preference.widgets;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.preference.Preference;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.patches.navigation.FloatingNavBar;
import app.morphe.extension.instagram.patches.navigation.FloatingNavSize;
import app.morphe.extension.instagram.patches.navigation.SelectedTabPill;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.utils.Pref;

/**
 * The size of the floating navigation bar: a live preview of the pill and three sliders, a width, a height and a
 * bottom gap (how far the bar floats above the bottom of the screen), all in one row of the settings screen.
 * <p>
 * The thumbs glide under the finger (see {@link SmoothSlider}); the sizes are still whole dp, so the preview and the
 * numbers change each time the thumb passes the middle between two values.
 * <p>
 * The preview is redrawn on every step of a slider, so the pill grows and shrinks under the finger. It is drawn from
 * the same numbers and with the same shade as the real bar ({@link FloatingNavSize}, {@link FloatingNavBar}), at the
 * proportions of the real screen, so what it shows is what the bar will look like. The values are written when a drag
 * settles (and at once for a keyboard or accessibility step); the bar itself picks them up when the app is shown again.
 * <p>
 * While "Custom selected tab pill" is on, the preview also shows the highlight behind the selected tab (Home), drawn
 * with the user's picture or the default capsule, and resized by the same two sliders as the real bar's highlight.
 * <p>
 * The sliders are shown the way a person thinks of them: the right end is the widest and the tallest pill. What is
 * stored is the distance from the sides and the air above and below the pill, which run the other way round.
 */
public class FloatingNavSizePreference extends Preference {
    private static final int TAB_COUNT = 5;
    private static final int RESET_TAP_PADDING_DP = 8;

    /** Blur radius (dp) at 100 % on the blur slider: the same scale the real bar uses (see NativeBarBlur). */
    private static final float MAX_BLUR_RADIUS_DP = 44f;

    // What the blur and transparency sliders show while they are being dragged (-1: not dragging, read the saved
    // value). Written and read on the main thread.
    private static int liveBlur = -1;
    private static int liveTransparency = -1;
    private static WeakReference<PreviewView> activePreview = new WeakReference<>(null);

    /**
     * Called by the blur and transparency sliders on every step, so the preview shows the glass of Instagram's own
     * floating bar as it will look. A negative number leaves that value as it is.
     */
    public static void previewGlass(int blurPercent, int transparencyPercent) {
        if (blurPercent >= 0) liveBlur = Math.min(100, blurPercent);
        if (transparencyPercent >= 0) liveTransparency = Math.min(100, transparencyPercent);
        PreviewView view = activePreview.get();
        if (view != null) view.invalidate();
    }

    /** Redraws the preview (the highlight's picture was replaced or deleted). Safe from any thread. */
    public static void refreshPreview() {
        PreviewView view = activePreview.get();
        if (view != null) view.postInvalidate();
    }

    // The "Custom selected tab pill" switch redraws the preview when it is turned on or off. The listener is kept in a
    // static field because the preferences hold their listeners weakly.
    private static final SharedPreferences.OnSharedPreferenceChangeListener TAB_PILL_LISTENER =
            (preferences, key) -> {
                if (!Settings.CUSTOM_TAB_PILL.key.equals(key)) return;
                PreviewView view = activePreview.get();
                if (view != null) view.postInvalidate();
            };
    private static boolean tabPillListenerRegistered;

    private TextView titleView;
    private TextView summaryView;
    private TextView resetView;
    private TextView widthLabel;
    private TextView widthValue;
    private TextView heightLabel;
    private TextView heightValue;
    private TextView bottomLabel;
    private TextView bottomValue;
    private SeekBar widthBar;
    private SeekBar heightBar;
    private SeekBar bottomBar;
    /** What makes each thumb glide, and tells this row the value it is on. */
    private SmoothSlider widthSlider;
    private SmoothSlider heightSlider;
    private SmoothSlider bottomSlider;
    private PreviewView preview;

    private int sideDp = FloatingNavSize.SIDE_DEFAULT_DP;
    private int gapDp = FloatingNavSize.GAP_DEFAULT_DP;
    private int bottomDp = FloatingNavSize.BOTTOM_DEFAULT_DP;
    /** True between a touch going down and coming up on a slider, so a drag writes once at the end. */
    private boolean dragging;

    public FloatingNavSizePreference(Context context) {
        super(context);
        // The values live in their own settings, written by this row when a slider settles.
        setPersistent(false);
        setSelectable(false);
        if (!tabPillListenerRegistered) {
            tabPillListenerRegistered = SharedPref.registerOnSharedPreferenceChangeListener(TAB_PILL_LISTENER);
        }
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(
                InstagramPreferenceStyle.dp(context, 15),
                InstagramPreferenceStyle.dp(context, 14),
                InstagramPreferenceStyle.dp(context, 15),
                InstagramPreferenceStyle.dp(context, 14)
        );
        row.setBackgroundColor(InstagramPreferenceStyle.backgroundColor());
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        // Title and the reset button.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        resetView = new TextView(context);
        resetView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        resetView.setText(str("piko_floating_nav_size_reset"));
        resetView.setClickable(true);
        resetView.setPadding(
                InstagramPreferenceStyle.dp(context, RESET_TAP_PADDING_DP),
                InstagramPreferenceStyle.dp(context, 4),
                InstagramPreferenceStyle.dp(context, RESET_TAP_PADDING_DP),
                InstagramPreferenceStyle.dp(context, 4)
        );
        resetView.setOnClickListener(v -> reset());
        header.addView(resetView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        summaryView = new TextView(context);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.topMargin = InstagramPreferenceStyle.dp(context, 2);
        row.addView(summaryView, summaryParams);

        // The live preview.
        preview = new PreviewView(context);
        preview.setContentDescription(str("piko_floating_nav_size_preview"));
        activePreview = new WeakReference<>(preview);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        previewParams.topMargin = InstagramPreferenceStyle.dp(context, 12);
        previewParams.bottomMargin = InstagramPreferenceStyle.dp(context, 4);
        row.addView(preview, previewParams);

        // Width.
        widthLabel = new TextView(context);
        widthValue = new TextView(context);
        addSliderHeader(context, row, widthLabel, widthValue, str("piko_floating_nav_width"));
        widthSlider = addSlider(context, row,
                FloatingNavSize.SIDE_MAX_DP - FloatingNavSize.SIDE_MIN_DP, new SizeListener(KIND_WIDTH));
        widthBar = widthSlider.bar();

        // Height.
        heightLabel = new TextView(context);
        heightValue = new TextView(context);
        addSliderHeader(context, row, heightLabel, heightValue, str("piko_floating_nav_height"));
        heightSlider = addSlider(context, row,
                FloatingNavSize.GAP_MAX_DP - FloatingNavSize.GAP_MIN_DP, new SizeListener(KIND_HEIGHT));
        heightBar = heightSlider.bar();

        // Bottom gap: how far the whole bar floats above the bottom of the screen.
        bottomLabel = new TextView(context);
        bottomValue = new TextView(context);
        addSliderHeader(context, row, bottomLabel, bottomValue, str("piko_floating_nav_bottom"));
        bottomSlider = addSlider(context, row,
                FloatingNavSize.BOTTOM_MAX_DP - FloatingNavSize.BOTTOM_MIN_DP, new SizeListener(KIND_BOTTOM));
        bottomBar = bottomSlider.bar();

        return row;
    }

    private static void addSliderHeader(Context context, LinearLayout row, TextView label, TextView value,
                                        String name) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lineParams.topMargin = InstagramPreferenceStyle.dp(context, 10);
        row.addView(line, lineParams);

        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setText(name);
        line.addView(label, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        line.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    /** A slider with the values 0 to {@code steps}, whose thumb glides (see {@link SmoothSlider}). */
    private static SmoothSlider addSlider(Context context, LinearLayout row, int steps,
                                          SmoothSlider.Listener listener) {
        SeekBar bar = new SeekBar(context);
        bar.setPadding(
                bar.getPaddingLeft(),
                InstagramPreferenceStyle.dp(context, 8),
                bar.getPaddingRight(),
                0
        );
        SmoothSlider slider = new SmoothSlider(bar, steps, listener);
        row.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        return slider;
    }

    private static final int KIND_WIDTH = 0;
    private static final int KIND_HEIGHT = 1;
    private static final int KIND_BOTTOM = 2;

    /** One slider's listener. {@code kind} tells which of the three it belongs to. */
    private final class SizeListener extends SmoothSlider.Listener {
        private final int kind;

        SizeListener(int kind) {
            this.kind = kind;
        }

        @Override
        void onStart() {
            dragging = true;
        }

        @Override
        void onStep(int step, boolean touching) {
            // The preview and the numbers follow the finger on every step.
            takeProgress(kind, step);
            // A keyboard or accessibility step moves the slider without a touch, so it never reaches
            // onSettle and has to be written here.
            if (!touching) save();
        }

        @Override
        void onSettle(int step) {
            // A drag writes once it settles, rather than on every notch it passes.
            dragging = false;
            takeProgress(kind, step);
            save();
        }
    }

    /**
     * A slider moved: the width slider's right end is the widest pill, the height slider's the tallest, and the
     * bottom gap slider's the highest above the bottom of the screen.
     */
    private void takeProgress(int kind, int progress) {
        if (kind == KIND_WIDTH) {
            sideDp = FloatingNavSize.clampSide(FloatingNavSize.SIDE_MAX_DP - progress);
        } else if (kind == KIND_HEIGHT) {
            gapDp = FloatingNavSize.clampGap(FloatingNavSize.GAP_MAX_DP - progress);
        } else {
            bottomDp = FloatingNavSize.clampBottom(FloatingNavSize.BOTTOM_MIN_DP + progress);
        }
        showValues();
    }

    private void save() {
        Pref.setFloatingNavSideDp(sideDp);
        Pref.setFloatingNavGapDp(gapDp);
        Pref.setFloatingNavBottomDp(bottomDp);
    }

    private void reset() {
        if (!isEnabled()) return;
        sideDp = FloatingNavSize.SIDE_DEFAULT_DP;
        gapDp = FloatingNavSize.GAP_DEFAULT_DP;
        bottomDp = FloatingNavSize.BOTTOM_DEFAULT_DP;
        syncSliders(true);
        showValues();
        save();
    }

    /**
     * Puts the sliders where the current values are (this does not count as the user moving them). {@code animate}
     * lets the thumbs glide there, as after "Reset".
     */
    private void syncSliders(boolean animate) {
        if (widthSlider != null) widthSlider.set(FloatingNavSize.SIDE_MAX_DP - sideDp, animate);
        if (heightSlider != null) heightSlider.set(FloatingNavSize.GAP_MAX_DP - gapDp, animate);
        if (bottomSlider != null) bottomSlider.set(bottomDp - FloatingNavSize.BOTTOM_MIN_DP, animate);
    }

    /** Shows the numbers next to the sliders and redraws the preview. */
    private void showValues() {
        if (widthValue != null) {
            widthValue.setText(FloatingNavSize.widthPercent(sideDp, PreviewView.screenWidthPx(getContext())
                    / getContext().getResources().getDisplayMetrics().density) + "%");
        }
        if (heightValue != null) {
            heightValue.setText(Math.round(FloatingNavSize.pillHeightDp(gapDp)) + " dp");
        }
        if (bottomValue != null) bottomValue.setText(bottomDp + " dp");
        if (preview != null) preview.setSize(sideDp, gapDp, bottomDp);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        boolean enabled = isEnabled();
        int titleColor = enabled
                ? InstagramPreferenceStyle.primaryTextColor()
                : InstagramPreferenceStyle.disabledTextColor();
        int valueColor = enabled
                ? InstagramPreferenceStyle.selectionColor()
                : InstagramPreferenceStyle.disabledTextColor();
        int summaryColor = enabled
                ? InstagramPreferenceStyle.secondaryTextColor()
                : InstagramPreferenceStyle.disabledTextColor();

        // The saved blur and transparency are current again (a slider that was dragged has saved them).
        liveBlur = -1;
        liveTransparency = -1;
        // Never move a slider under a finger that is dragging it.
        if (!dragging) {
            sideDp = Pref.floatingNavSideDp();
            gapDp = Pref.floatingNavGapDp();
            bottomDp = Pref.floatingNavBottomDp();
            syncSliders(false);
        }

        if (titleView != null) {
            titleView.setText(getTitle());
            titleView.setTextColor(titleColor);
        }
        if (summaryView != null) {
            CharSequence summary = getSummary();
            summaryView.setText(summary);
            summaryView.setVisibility(summary == null || summary.length() == 0 ? View.GONE : View.VISIBLE);
            summaryView.setTextColor(summaryColor);
        }
        if (resetView != null) {
            resetView.setEnabled(enabled);
            resetView.setTextColor(valueColor);
        }
        if (widthLabel != null) widthLabel.setTextColor(titleColor);
        if (heightLabel != null) heightLabel.setTextColor(titleColor);
        if (bottomLabel != null) bottomLabel.setTextColor(titleColor);
        if (widthValue != null) widthValue.setTextColor(valueColor);
        if (heightValue != null) heightValue.setTextColor(valueColor);
        if (bottomValue != null) bottomValue.setTextColor(valueColor);
        tint(widthBar, enabled, valueColor);
        tint(heightBar, enabled, valueColor);
        tint(bottomBar, enabled, valueColor);
        if (preview != null) {
            preview.refreshTheme();
            preview.setAlpha(enabled ? 1f : 0.45f);
        }
        showValues();
        view.setEnabled(enabled);
    }

    private static void tint(SeekBar bar, boolean enabled, int color) {
        if (bar == null) return;
        bar.setEnabled(enabled);
        SliderStyle.apply(bar, color, enabled);
    }

    /**
     * The bottom of a phone screen, drawn at the real screen's proportions: some page, and on top of it the pill with
     * the five tabs. The geometry is the real bar's (a pill that keeps {@code side} from each edge and {@code gap}
     * from the top and bottom of Instagram's bar, above the system inset), only scaled to the width of the row.
     */
    private static final class PreviewView extends View {
        /** How much of the page above the bar is shown, in dp. */
        private static final float PAGE_ABOVE_DP = 56f;
        private static final float ICON_DP = 24f;
        private static final float EDGE_DP = 1f;

        private final float density;
        private final float screenWidthPx;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lift = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF ring = new RectF();
        private final RectF iconRect = new RectF();
        private final RectF tabRect = new RectF();
        private final SelectedTabPill.Renderer tabRenderer;
        private final Path clip = new Path();
        private final Path shape = new Path();
        private final Path pillClip = new Path();
        /** The page behind the pill, blurred (Android 12+); null until it is first needed. */
        private Glass31 glass;

        /** The unscaled height the preview was last measured for, to tell when it must be laid out again. */
        private int measuredBase = -1;
        private int sideDp = FloatingNavSize.SIDE_DEFAULT_DP;
        private int gapDp = FloatingNavSize.GAP_DEFAULT_DP;
        /** How far the whole bar floats above the bottom of the screen (above the system inset), in dp. */
        private int bottomDp = FloatingNavSize.BOTTOM_DEFAULT_DP;
        private int page = Color.BLACK;
        private int text = Color.WHITE;
        private boolean dark = true;

        PreviewView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            screenWidthPx = screenWidthPx(context);
            tabRenderer = new SelectedTabPill.Renderer(density);

            fill.setStyle(Paint.Style.FILL);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, EDGE_DP * density));
            lift.setStyle(Paint.Style.FILL);
            lift.setColor(Color.BLACK);
            iconStroke.setStyle(Paint.Style.STROKE);
            iconStroke.setStrokeCap(Paint.Cap.ROUND);
            iconStroke.setStrokeJoin(Paint.Join.ROUND);
            // The icons are drawn on a 24 by 24 grid, scaled to ICON_DP.
            iconStroke.setStrokeWidth(2f);
            iconFill.setStyle(Paint.Style.FILL);
            refreshTheme();
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            // Back from the picture picker: the picture may be a new one.
            if (hasWindowFocus) invalidate();
        }

        /** The width of the screen the preview stands for: a phone held upright. */
        static float screenWidthPx(Context context) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            return Math.max(1f, Math.min(metrics.widthPixels, metrics.heightPixels));
        }

        void setSize(int side, int gap, int bottom) {
            if (sideDp == side && gapDp == gap && bottomDp == bottom) return;
            sideDp = side;
            gapDp = gap;
            bottomDp = bottom;
            invalidate();
        }

        /** Takes the colours from Instagram's own theme, as the settings screen does. */
        void refreshTheme() {
            page = InstagramPreferenceStyle.backgroundColor() | 0xFF000000;
            text = InstagramPreferenceStyle.primaryTextColor() | 0xFF000000;
            dark = Color.luminance(page) < 0.5f;
            iconStroke.setColor(text);
            iconFill.setColor(text);
            // The real bar's height may have been measured since the preview was last measured.
            if (Math.round(totalHeightPx()) != measuredBase) requestLayout();
            invalidate();
        }

        private float totalHeightPx() {
            return (PAGE_ABOVE_DP + FloatingNavSize.barDp() + FloatingNavSize.insetDp()) * density;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width <= 0) width = Math.round(screenWidthPx);
            float scale = width / screenWidthPx;
            measuredBase = Math.round(totalHeightPx());
            setMeasuredDimension(width, Math.round(totalHeightPx() * scale));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            // Everything below is in the real screen's pixels; the canvas scales it to the row.
            float scale = getWidth() / screenWidthPx;
            float w = screenWidthPx;
            float h = getHeight() / scale;
            canvas.save();
            canvas.scale(scale, scale);

            float corner = 14f * density;
            rect.set(0f, 0f, w, h);
            clip.reset();
            clip.addRoundRect(rect, corner, corner, Path.Direction.CW);

            canvas.save();
            canvas.clipPath(clip);
            drawBackdrop(canvas, w, h);
            drawPage(canvas, w);
            drawPill(canvas, w, h);
            drawHomeIndicator(canvas, w, h);
            canvas.restore();

            // The outline of the screen.
            edge.setColor(withAlpha(text, 0x22));
            float half = edge.getStrokeWidth() / 2f;
            ring.set(half, half, w - half, h - half);
            canvas.drawRoundRect(ring, corner - half, corner - half, edge);
            canvas.restore();
        }

        /** The plain colour of the screen under the page. */
        private void drawBackdrop(Canvas canvas, float w, float h) {
            rect.set(0f, 0f, w, h);
            fill.setColor(mix(page, dark ? Color.WHITE : Color.BLACK, 0.04f));
            canvas.drawRect(rect, fill);
        }

        /**
         * Whether the preview shows the glass of Instagram's own floating bar: only while that bar and its blur are
         * both on, on a phone that can blur (Android 12+). The pill of the floating navigation bar is not glass.
         */
        private boolean glassOn() {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && Pref.nativeFloatingNav() && Pref.nativeFloatingNavBlur();
        }

        /** A post whose picture runs all the way down behind the bar, as the page does with the floating bar. */
        private void drawPage(Canvas canvas, float w) {
            float margin = 10f * density;
            fill.setColor(mix(page, text, 0.18f));
            canvas.drawCircle(margin + 8f * density + 4f * density, 22f * density, 8f * density, fill);
            rect.set(margin + 30f * density, 19f * density, margin + 110f * density, 25f * density);
            canvas.drawRoundRect(rect, 3f * density, 3f * density, fill);

            fill.setColor(mix(page, text, 0.09f));
            rect.set(margin, 38f * density, w - margin, (PAGE_ABOVE_DP + FloatingNavSize.barDp()
                    + FloatingNavSize.insetDp() + 40f) * density);
            canvas.drawRoundRect(rect, 10f * density, 10f * density, fill);

            // Something in the picture, so it is clear the pill is on top of the page and not beside it.
            fill.setColor(mix(page, text, 0.15f));
            canvas.drawCircle(w * 0.78f, 74f * density, 30f * density, fill);
            canvas.drawCircle(w * 0.22f, 96f * density, 22f * density, fill);
        }

        /** The pill, with the same geometry and shade as the real bar, and the five tabs on it. */
        private void drawPill(Canvas canvas, float w, float h) {
            float inset = FloatingNavSize.insetDp() * density;
            float bar = FloatingNavSize.barDp() * density;
            float side = sideDp * density;
            float gap = gapDp * density;
            // The bar floats this far above the bottom of the screen, on top of the system inset. The preview keeps
            // its height (so the sliders below do not move under the finger): the pill rises over the page instead.
            float raise = bottomDp * density;

            float left = side;
            float right = w - side;
            float top = h - inset - raise - bar + gap;
            float bottom = h - inset - raise - gap;
            if (right - left < 8f || bottom - top < 8f) return;
            float radius = (bottom - top) / 2f;

            if (!dark) {
                // The soft lift under the pill on a light bar.
                for (int i = 3; i >= 1; i--) {
                    float grow = i * 1.5f * density;
                    ring.set(left - grow, top, right + grow, bottom + grow + density);
                    lift.setAlpha(8 + (4 - i) * 6);
                    canvas.drawRoundRect(ring, radius + grow, radius + grow, lift);
                }
            }
            rect.set(left, top, right, bottom);
            int shade = FloatingNavBar.pillFill(page, dark);
            if (glassOn()) {
                int blur = liveBlur >= 0 ? liveBlur : Pref.nativeFloatingNavBlurLevel();
                int transparency = liveTransparency >= 0 ? liveTransparency : Pref.nativeFloatingNavTransparency();
                pillClip.rewind();
                pillClip.addRoundRect(rect, radius, radius, Path.Direction.CW);
                if (glass == null) glass = new Glass31();
                float blurPx = MAX_BLUR_RADIUS_DP * density * blur / 100f;
                glass.draw(this, canvas, pillClip, w, h, blurPx < 0.5f ? 0f : blurPx);
                // The tint over the blur: 0 % transparency is solid, 100 % is none (as on the real bar).
                int alpha = Math.round(255f * (100 - transparency) / 100f);
                fill.setColor((shade & 0x00FFFFFF) | (alpha << 24));
            } else {
                fill.setColor(shade);
            }
            canvas.drawRoundRect(rect, radius, radius, fill);
            edge.setColor(FloatingNavBar.pillEdge(dark));
            float half = edge.getStrokeWidth() / 2f;
            ring.set(left + half, top + half, right - half, bottom - half);
            canvas.drawRoundRect(ring, radius - half, radius - half, edge);

            // The tabs share the space inside the pill equally, as they do in the bar.
            float tabWidth = (right - left) / TAB_COUNT;

            // The highlight behind the selected tab (Home): the same rule as the real bar's, so it follows the sliders
            // the same way. Only while the setting is on. On Instagram's own floating bar the real highlight is drawn
            // by the same background as its pill, which needs Android 12 (as the glass does), so the preview shows it
            // only there.
            boolean tabPillShown = Pref.customTabPill()
                    && (!Pref.nativeFloatingNav() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
            if (tabPillShown
                    && SelectedTabPill.bounds(tabRect, left, left + tabWidth, left, right, top, bottom, density)) {
                tabRenderer.draw(canvas, tabRect, SelectedTabPill.image(getContext()), dark);
            }
            float centerY = (top + bottom) / 2f;
            float icon = ICON_DP * density;
            for (int i = 0; i < TAB_COUNT; i++) {
                float centerX = left + tabWidth * (i + 0.5f);
                canvas.save();
                canvas.translate(centerX - icon / 2f, centerY - icon / 2f);
                canvas.scale(icon / 24f, icon / 24f);
                drawIcon(canvas, i);
                canvas.restore();
            }
        }

        /** The page behind the pill, recorded and blurred. Only made on Android 12 or newer. */
        private static final class Glass31 {
            private final RenderNode node = new RenderNode("PikoPreviewGlass");
            private float lastBlurPx = -1f;

            void draw(PreviewView view, Canvas canvas, Path clip, float w, float h, float blurPx) {
                if (!canvas.isHardwareAccelerated()) return;
                node.setPosition(0, 0, Math.round(w), Math.round(h));
                if (blurPx != lastBlurPx) {
                    lastBlurPx = blurPx;
                    // CLAMP: the edges repeat instead of fading to black.
                    node.setRenderEffect(blurPx > 0f
                            ? RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                            : null);
                }
                Canvas recording = node.beginRecording(Math.round(w), Math.round(h));
                try {
                    view.drawBackdrop(recording, w, h);
                    view.drawPage(recording, w);
                } finally {
                    node.endRecording();
                }
                canvas.save();
                canvas.clipPath(clip);
                canvas.drawRenderNode(node);
                canvas.restore();
            }
        }

        /** One tab's icon on a 24 by 24 grid: Home (selected), Reels, Messages, Search, Profile. */
        private void drawIcon(Canvas canvas, int index) {
            switch (index) {
                case 0:
                    shape.reset();
                    shape.moveTo(3f, 10.5f);
                    shape.lineTo(12f, 3f);
                    shape.lineTo(21f, 10.5f);
                    shape.lineTo(21f, 20.5f);
                    shape.lineTo(14.5f, 20.5f);
                    shape.lineTo(14.5f, 14f);
                    shape.lineTo(9.5f, 14f);
                    shape.lineTo(9.5f, 20.5f);
                    shape.lineTo(3f, 20.5f);
                    shape.close();
                    canvas.drawPath(shape, iconFill);
                    canvas.drawPath(shape, iconStroke);
                    break;
                case 1:
                    iconRect.set(3.5f, 3.5f, 20.5f, 20.5f);
                    canvas.drawRoundRect(iconRect, 5f, 5f, iconStroke);
                    canvas.drawLine(3.5f, 9f, 20.5f, 9f, iconStroke);
                    shape.reset();
                    shape.moveTo(10f, 12.2f);
                    shape.lineTo(15f, 15f);
                    shape.lineTo(10f, 17.8f);
                    shape.close();
                    canvas.drawPath(shape, iconFill);
                    break;
                case 2:
                    shape.reset();
                    shape.moveTo(21f, 3f);
                    shape.lineTo(14f, 21f);
                    shape.lineTo(10.5f, 13.5f);
                    shape.lineTo(3f, 10f);
                    shape.close();
                    canvas.drawPath(shape, iconStroke);
                    canvas.drawLine(21f, 3f, 10.5f, 13.5f, iconStroke);
                    break;
                case 3:
                    canvas.drawCircle(10.5f, 10.5f, 7f, iconStroke);
                    canvas.drawLine(15.7f, 15.7f, 21f, 21f, iconStroke);
                    break;
                default:
                    canvas.drawCircle(12f, 12f, 9f, iconStroke);
                    canvas.drawCircle(12f, 9.8f, 3f, iconStroke);
                    shape.reset();
                    shape.moveTo(6.2f, 18.2f);
                    shape.quadTo(12f, 13f, 17.8f, 18.2f);
                    canvas.drawPath(shape, iconStroke);
                    break;
            }
        }

        /** The gesture bar under the pill, so the space below it reads as the system's. */
        private void drawHomeIndicator(Canvas canvas, float w, float h) {
            float inset = FloatingNavSize.insetDp() * density;
            if (inset < 10f * density) return;
            float width = 108f * density;
            float thickness = 4f * density;
            float centerY = h - inset / 2f;
            fill.setColor(withAlpha(text, 0x59));
            rect.set((w - width) / 2f, centerY - thickness / 2f, (w + width) / 2f, centerY + thickness / 2f);
            canvas.drawRoundRect(rect, thickness / 2f, thickness / 2f, fill);
        }

        private static int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | (alpha << 24);
        }

        /** {@code from} moved {@code amount} (0 to 1) of the way towards {@code to}, fully opaque. */
        private static int mix(int from, int to, float amount) {
            float keep = 1f - amount;
            return Color.argb(255,
                    Math.round(Color.red(from) * keep + Color.red(to) * amount),
                    Math.round(Color.green(from) * keep + Color.green(to) * amount),
                    Math.round(Color.blue(from) * keep + Color.blue(to) * amount));
        }
    }
}
