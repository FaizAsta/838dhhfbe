/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

/**
 * The size of the floating navigation bar's pill: what the user can choose, and the numbers both the real bar
 * ({@link FloatingNavBar}) and the live preview in the settings draw from, so the two always agree. Instagram's own
 * floating bar, when that is switched on instead of the pill, is given the same two numbers (see
 * {@link NativeBarSize}): the width and the height the settings show are the ones it gets.
 * <p>
 * Two values are stored, both in dp:
 * <ul>
 *   <li><b>side</b>: how far the pill keeps from each side of the screen. A larger side is a narrower pill. The
 *       settings show it the other way round, as a width (a percentage of the screen), so the slider's right end is
 *       the widest pill;</li>
 *   <li><b>gap</b>: the air above and below the pill inside Instagram's own bar. A larger gap is a lower pill. The
 *       settings show it as a height, so the slider's right end is the tallest pill. The pill can never be taller
 *       than Instagram's bar itself (gap 0), because it is drawn inside the bar.</li>
 * </ul>
 */
public final class FloatingNavSize {
    public static final int SIDE_MIN_DP = 0;
    public static final int SIDE_MAX_DP = 56;
    /** The same distance as the Reels reply bar keeps, so the two line up. */
    public static final int SIDE_DEFAULT_DP = 24;

    public static final int GAP_MIN_DP = 0;
    /** With Instagram's usual 48 dp bar this still leaves a pill of about 28 dp, enough for the 24 dp icons. */
    public static final int GAP_MAX_DP = 10;
    public static final int GAP_DEFAULT_DP = 4;

    /** The height of Instagram's tab bar above the system inset, used until the real bar has been measured. */
    public static final float FALLBACK_BAR_DP = 48f;
    /** The system inset under the bar (gesture area), used until the real bar has been measured. */
    public static final float FALLBACK_INSET_DP = 16f;

    private static final float MIN_PLAUSIBLE_BAR_DP = 32f;
    private static final float MAX_PLAUSIBLE_BAR_DP = 96f;

    // Written by the bar on the main thread, read by the settings on the main thread: volatile is enough.
    private static volatile float measuredBarDp;
    private static volatile float measuredInsetDp = -1f;

    private FloatingNavSize() {}

    /** Called by the real bar when it has been laid out, so the preview can show the same proportions. */
    static void measured(float barDp, float insetDp) {
        if (barDp < MIN_PLAUSIBLE_BAR_DP || barDp > MAX_PLAUSIBLE_BAR_DP || insetDp < 0f) return;
        measuredBarDp = barDp;
        measuredInsetDp = insetDp;
    }

    /** The height of Instagram's bar above the system inset, in dp (measured when it is known). */
    public static float barDp() {
        float measured = measuredBarDp;
        return measured > 0f ? measured : FALLBACK_BAR_DP;
    }

    /** The system inset under the bar, in dp (measured when it is known). */
    public static float insetDp() {
        float measured = measuredInsetDp;
        return measured >= 0f ? measured : FALLBACK_INSET_DP;
    }

    public static int clampSide(int dp) {
        return Math.max(SIDE_MIN_DP, Math.min(SIDE_MAX_DP, dp));
    }

    public static int clampGap(int dp) {
        return Math.max(GAP_MIN_DP, Math.min(GAP_MAX_DP, dp));
    }

    /** Reads a stored number; anything that is not a whole number gives the fallback. */
    public static int parse(String stored, int fallback) {
        if (stored == null) return fallback;
        try {
            return Integer.parseInt(stored.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** The pill's height in dp for a gap: Instagram's bar minus the air above and below. */
    public static float pillHeightDp(int gapDp) {
        return Math.max(8f, barDp() - 2f * clampGap(gapDp));
    }

    /** The pill's width as a percentage of the screen's width, for a side. */
    public static int widthPercent(int sideDp, float screenWidthDp) {
        if (screenWidthDp <= 0f) return 100;
        float share = 1f - (2f * clampSide(sideDp)) / screenWidthDp;
        return Math.max(0, Math.min(100, Math.round(share * 100f)));
    }
}
