/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.StringSetting;
import app.morphe.extension.instagram.utils.Pref;

/**
 * Every number of the liquid glass look of Instagram's own floating bar (see {@link LiquidGlassEffect} and
 * {@link NativeBarBlur}), read from the settings, and the table of sliders the settings screen is built from.
 * <p>
 * The options are the ones of the liquid-glass.ybouane.com playground: blur amount, refraction, chromatic
 * aberration, edge highlight, specular, fresnel, distortion, corner radius, z-radius, opacity, saturation,
 * brightness, shadow opacity, shadow spread and bevel mode. Each slider is stored as a whole percent (0 to 100) and
 * turned into the real number here, so the settings only ever hold small integers. An instance is immutable and has
 * {@link #equals}, so the bar can tell cheaply whether anything changed.
 */
public final class LiquidGlassLook {
    /** How a slider shows its value (the percent it is on). */
    public interface Format {
        String show(int percent);
    }

    /** One slider of the settings screen. */
    public static final class Option {
        public final StringSetting setting;
        public final int def;
        public final Format format;
        /** The title of the row and the key of its string ({@code piko_} + the setting's key). */
        public final String titleKey;

        Option(StringSetting setting, Format format) {
            this.setting = setting;
            this.def = Integer.parseInt(setting.defaultValue);
            this.format = format;
            this.titleKey = "piko_" + setting.key;
        }

        public int get() {
            return Pref.liquidPercent(setting, def);
        }

        public void set(int percent) {
            Pref.setLiquidPercent(setting, percent);
        }
    }

    // Declared before the formats below: a lambda may not use a static field that is declared after it.
    static final float SPREAD_MAX_DP = 40f;
    /** The ripple of the distortion, at 100 %, in dp. */
    static final float DISTORTION_MAX_DP = 6f;

    private static final Format PERCENT = p -> p + "%";
    /** 0 to 100 % shown as -1.00 to 1.00, with 50 % as 0 (saturation). */
    private static final Format PLUS_MINUS_ONE = p -> signed((p - 50) / 50f);
    /** 0 to 100 % shown as -0.50 to 0.50, with 50 % as 0 (brightness). */
    private static final Format PLUS_MINUS_HALF = p -> signed((p - 50) / 100f);
    /** 0 to 100 % shown as 0.00 to 2.00 (fresnel). */
    private static final Format ZERO_TO_TWO = p -> String.format(java.util.Locale.US, "%.2f", p / 50f);
    private static final Format SPREAD_DP = p -> Math.round(SPREAD_MAX_DP * p / 100f) + " dp";

    /** The sliders under the liquid glass switch, in the order of the settings screen. Blur and transparency are the existing ones above. */
    public static final Option[] OPTIONS = {
            new Option(Settings.NATIVE_FLOATING_NAV_REFRACTION, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_CHROMA, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_EDGE_HIGHLIGHT, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_SPECULAR, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_FRESNEL, ZERO_TO_TWO),
            new Option(Settings.NATIVE_FLOATING_NAV_DISTORTION, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_CORNER_RADIUS, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_Z_RADIUS, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_OPACITY, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_SATURATION, PLUS_MINUS_ONE),
            new Option(Settings.NATIVE_FLOATING_NAV_BRIGHTNESS, PLUS_MINUS_HALF),
            new Option(Settings.NATIVE_FLOATING_NAV_SHADOW_OPACITY, PERCENT),
            new Option(Settings.NATIVE_FLOATING_NAV_SHADOW_SPREAD, SPREAD_DP),
    };

    /** The look with every slider on its default. */
    static final LiquidGlassLook DEFAULT = new LiquidGlassLook(60, 20, 20, 30, 50, 0, 100, 80, 100, 50, 50, 0, 35,
            false);

    /** Every value is a whole percent, 0 to 100. */
    final int refraction;
    final int chroma;
    final int edgeHighlight;
    final int specular;
    final int fresnel;
    final int distortion;
    final int corner;
    final int zRadius;
    final int opacity;
    final int saturation;
    final int brightness;
    final int shadowOpacity;
    final int shadowSpread;
    /** Bevel mode 1: a dome (a magnifier with a flat back) instead of the biconvex pill. */
    final boolean dome;

    private LiquidGlassLook(int refraction, int chroma, int edgeHighlight, int specular, int fresnel, int distortion,
                            int corner, int zRadius, int opacity, int saturation, int brightness, int shadowOpacity,
                            int shadowSpread, boolean dome) {
        this.refraction = clamp(refraction);
        this.chroma = clamp(chroma);
        this.edgeHighlight = clamp(edgeHighlight);
        this.specular = clamp(specular);
        this.fresnel = clamp(fresnel);
        this.distortion = clamp(distortion);
        this.corner = clamp(corner);
        this.zRadius = clamp(zRadius);
        this.opacity = clamp(opacity);
        this.saturation = clamp(saturation);
        this.brightness = clamp(brightness);
        this.shadowOpacity = clamp(shadowOpacity);
        this.shadowSpread = clamp(shadowSpread);
        this.dome = dome;
    }

    /** Reads every slider from the settings. */
    static LiquidGlassLook read() {
        Option[] o = OPTIONS;
        return new LiquidGlassLook(o[0].get(), o[1].get(), o[2].get(), o[3].get(), o[4].get(), o[5].get(),
                o[6].get(), o[7].get(), o[8].get(), o[9].get(), o[10].get(), o[11].get(), o[12].get(),
                Pref.nativeFloatingNavDome());
    }

    /**
     * Like {@link #read()}, but a slider that is being dragged shows the value it is on right now: {@code live[i]}
     * (0 to 100) stands in for the saved value of {@code OPTIONS[i]}, and a negative number leaves the saved one. Used
     * by the preview on the settings screen, which shows the glass before a drag has saved anything.
     */
    public static LiquidGlassLook read(int[] live) {
        Option[] o = OPTIONS;
        int[] v = new int[o.length];
        for (int i = 0; i < v.length; i++) {
            v[i] = live != null && i < live.length && live[i] >= 0 ? live[i] : o[i].get();
        }
        return new LiquidGlassLook(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12],
                Pref.nativeFloatingNavDome());
    }

    private static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static String signed(float value) {
        return String.format(java.util.Locale.US, "%+.2f", value).replace("+0.00", "0").replace("-0.00", "0");
    }

    // ---- the real numbers ----

    float refractionStrength() { return refraction / 100f; }
    /** Colour fringe, as a part of the bend (0 to 0.25, the playground's range). */
    float chromaAmount() { return 0.25f * chroma / 100f; }
    float edgeLight() { return 0.4f * edgeHighlight / 100f; }
    float specularLight() { return 0.8f * specular / 100f; }
    /** 0 to 2, as in the playground. */
    float fresnelAmount() { return 2f * fresnel / 100f; }
    float distortionPx(float density) { return DISTORTION_MAX_DP * density * distortion / 100f; }
    /** The corner radius as a part of a full pill (1 = the ends are half circles). */
    float cornerPart() { return corner / 100f; }
    /** The width of the bent rim as a part of half the pill's height. */
    float zRadiusPart() { return zRadius / 100f; }
    float opacityPart() { return opacity / 100f; }
    /** 0 = grey, 1 = unchanged, 2 = twice the colour. */
    float saturationFactor() { return 1f + (saturation - 50) / 50f; }
    float brightnessAdd() { return (brightness - 50) / 100f; }
    float shadowAlpha() { return shadowOpacity / 100f; }
    float shadowSpreadPx(float density) { return SPREAD_MAX_DP * density * shadowSpread / 100f; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LiquidGlassLook)) return false;
        LiquidGlassLook o = (LiquidGlassLook) other;
        return refraction == o.refraction && chroma == o.chroma && edgeHighlight == o.edgeHighlight
                && specular == o.specular && fresnel == o.fresnel && distortion == o.distortion
                && corner == o.corner && zRadius == o.zRadius && opacity == o.opacity
                && saturation == o.saturation && brightness == o.brightness && shadowOpacity == o.shadowOpacity
                && shadowSpread == o.shadowSpread && dome == o.dome;
    }

    @Override
    public int hashCode() {
        int h = refraction;
        h = h * 31 + chroma;
        h = h * 31 + edgeHighlight;
        h = h * 31 + specular;
        h = h * 31 + fresnel;
        h = h * 31 + distortion;
        h = h * 31 + corner;
        h = h * 31 + zRadius;
        h = h * 31 + opacity;
        h = h * 31 + saturation;
        h = h * 31 + brightness;
        h = h * 31 + shadowOpacity;
        h = h * 31 + shadowSpread;
        return h * 31 + (dome ? 1 : 0);
    }
}
