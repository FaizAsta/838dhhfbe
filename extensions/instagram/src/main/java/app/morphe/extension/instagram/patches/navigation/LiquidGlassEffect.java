/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.navigation;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;

/**
 * The "liquid glass" look for the pill of Instagram's own floating bar (see {@link NativeBarBlur}): what is behind
 * the pill is bent at its rim like the edge of a thick piece of glass (or magnified, in dome mode), with a faint
 * colour fringe, a light rim from the upper left and a milky reflection at the very edge, the way iOS and the web
 * "Liquid Glass" demos draw it. Every number comes from {@link LiquidGlassLook}.
 * <p>
 * It is one AGSL shader that is put on the {@code RenderNode} that records the page behind the bar, after the blur
 * (so the blur amount is the frosting and the refraction is the bend). The shader needs {@code RuntimeShader}, which
 * is Android 13 (API 33). Callers check {@link #supported()} first and fall back to the plain blur below that; this
 * class is never touched on an older Android.
 * <p>
 * The shader works in the coordinates of the recorded node: the pill sits at {@code pillOrigin} with the size
 * {@code pillSize}, and the node has {@link #reachPx} of extra room around it, so the bent rim can look at the page
 * outside the pill without running out of picture.
 */
final class LiquidGlassEffect {
    /** {@code RuntimeShader} needs Android 13. */
    static final int MIN_SDK = Build.VERSION_CODES.TIRAMISU;
    /** The widest bent rim, in dp (a thin pill uses half its height). */
    private static final float BEVEL_MAX_DP = 22f;
    /** How far the rim looks outwards, as a part of the bevel, at 100 % refraction. */
    private static final float BEND_OF_BEVEL = 0.8f;
    /** How far the dome pulls from inside, as a part of half the pill's height, at 100 % refraction. */
    private static final float DOME_OF_HALF = 0.5f;
    /** How fast the ripple of the distortion waves, in waves per dp. */
    private static final float WARP_PER_DP = 1f / 28f;

    private static final String AGSL = ""
            + "uniform shader content;\n"
            + "uniform float2 pillSize;\n"
            + "uniform float2 pillOrigin;\n"
            + "uniform float cornerRadius;\n"
            + "uniform float bevel;\n"
            + "uniform float bend;\n"
            + "uniform float chroma;\n"
            + "uniform float shine;\n"
            + "uniform float edgeLight;\n"
            + "uniform float fresnel;\n"
            + "uniform float warp;\n"
            + "uniform float warpFreq;\n"
            + "uniform float satur;\n"
            + "uniform float bright;\n"
            + "uniform float dome;\n"
            + "uniform float domeBend;\n"
            + "\n"
            + "float roundBox(float2 p, float2 hs, float r) {\n"
            + "    float2 q = abs(p) - (hs - float2(r));\n"
            + "    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 hs = pillSize * 0.5;\n"
            + "    float2 p = coord - (pillOrigin + hs);\n"
            + "    float d = roundBox(p, hs, cornerRadius);\n"
            + "\n"
            + "    // Outward normal from the slope of the distance field.\n"
            + "    float2 n = float2(\n"
            + "        roundBox(p + float2(1.0, 0.0), hs, cornerRadius) - roundBox(p - float2(1.0, 0.0), hs, cornerRadius),\n"
            + "        roundBox(p + float2(0.0, 1.0), hs, cornerRadius) - roundBox(p - float2(0.0, 1.0), hs, cornerRadius));\n"
            + "    float len = length(n);\n"
            + "    n = len > 0.0001 ? n / len : float2(0.0, -1.0);\n"
            + "\n"
            + "    // 1 on the rim, 0 once we are one bevel width inside.\n"
            + "    float t = clamp(1.0 - (-d) / max(bevel, 1.0), 0.0, 1.0);\n"
            + "    // Biconvex pill: the rim looks at the page outside it. Dome: everything looks inwards, most at the rim.\n"
            + "    float2 shift = n * (bend * t * t);\n"
            + "    if (dome > 0.5) {\n"
            + "        float u = clamp((-d) / max(min(hs.x, hs.y), 1.0), 0.0, 1.0);\n"
            + "        shift = -n * (domeBend * (1.0 - u));\n"
            + "    }\n"
            + "\n"
            + "    // A slow ripple over the whole glass.\n"
            + "    float2 base = coord + float2(\n"
            + "        sin(coord.y * warpFreq + coord.x * warpFreq * 0.5),\n"
            + "        cos(coord.x * warpFreq - coord.y * warpFreq * 0.5)) * warp;\n"
            + "\n"
            + "    float4 sr = float4(content.eval(base + shift * (1.0 + chroma)));\n"
            + "    float4 sg = float4(content.eval(base + shift));\n"
            + "    float4 sb = float4(content.eval(base + shift * (1.0 - chroma)));\n"
            + "    float3 col = float3(sr.r, sg.g, sb.b);\n"
            + "\n"
            + "    float luma = dot(col, float3(0.2126, 0.7152, 0.0722));\n"
            + "    col = mix(float3(luma), col, satur) + float3(bright);\n"
            + "\n"
            + "    // Rim light from the upper left, a faint shade on the far side, a milky reflection at the edge.\n"
            + "    float rim = t * t * t;\n"
            + "    float facing = dot(n, normalize(float2(-0.55, -0.83)));\n"
            + "    col += float3(rim * (edgeLight + shine * max(facing, 0.0)));\n"
            + "    col -= col * (rim * 0.12 * max(-facing, 0.0));\n"
            + "    col = mix(col, float3(1.0), clamp(fresnel * 0.12 * rim * t, 0.0, 1.0));\n"
            + "\n"
            + "    return half4(half3(clamp(col, float3(0.0), float3(1.0))), half(sg.a));\n"
            + "}\n";

    private LiquidGlassEffect() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT >= MIN_SDK;
    }

    /** The width of the bent rim in px for a pill of this height. */
    static float bevelPx(float pillHeightPx, float density, LiquidGlassLook look) {
        return Math.min(pillHeightPx * 0.5f * look.zRadiusPart(), BEVEL_MAX_DP * density * 2f);
    }

    /** How far the rim looks outwards, in px (the dome only looks inwards). */
    private static float bendPx(float pillHeightPx, float density, LiquidGlassLook look) {
        if (look.dome) return 0f;
        return bevelPx(pillHeightPx, density, look) * BEND_OF_BEVEL * look.refractionStrength();
    }

    /** How far the dome pulls from inside, in px. */
    private static float domePx(float pillWidthPx, float pillHeightPx, LiquidGlassLook look) {
        return Math.min(pillWidthPx, pillHeightPx) * 0.5f * DOME_OF_HALF * look.refractionStrength();
    }

    /** How far the recorded area has to reach outside the pill for the shader, in px. */
    static float reachPx(float pillHeightPx, float density, LiquidGlassLook look) {
        float shift = bendPx(pillHeightPx, density, look) * (1f + look.chromaAmount());
        return shift + look.distortionPx(density) + 2f * density;
    }

    /**
     * The effect for a node whose pill starts at ({@code originPx}, {@code originPx}) and is {@code width} by
     * {@code height} px with corners of {@code radius} px. {@code blur} (may be null) is applied first, so the
     * refraction bends the already frosted page. Throws if the shader cannot be built; the caller falls back to the
     * blur alone.
     */
    static RenderEffect create(RenderEffect blur, float width, float height, float originPx, float radius,
                               float density, LiquidGlassLook look) {
        RuntimeShader shader = new RuntimeShader(AGSL);
        shader.setFloatUniform("pillSize", width, height);
        shader.setFloatUniform("pillOrigin", originPx, originPx);
        shader.setFloatUniform("cornerRadius", radius);
        shader.setFloatUniform("bevel", bevelPx(height, density, look));
        shader.setFloatUniform("bend", bendPx(height, density, look));
        shader.setFloatUniform("chroma", look.chromaAmount());
        shader.setFloatUniform("shine", look.specularLight());
        shader.setFloatUniform("edgeLight", look.edgeLight());
        shader.setFloatUniform("fresnel", look.fresnelAmount());
        shader.setFloatUniform("warp", look.distortionPx(density));
        shader.setFloatUniform("warpFreq", WARP_PER_DP / density * (float) (2.0 * Math.PI));
        shader.setFloatUniform("satur", look.saturationFactor());
        shader.setFloatUniform("bright", look.brightnessAdd());
        shader.setFloatUniform("dome", look.dome ? 1f : 0f);
        shader.setFloatUniform("domeBend", domePx(width, height, look));
        RenderEffect glass = RenderEffect.createRuntimeShaderEffect(shader, "content");
        // createChainEffect(outer, inner): the inner effect (the blur) runs first.
        return blur == null ? glass : RenderEffect.createChainEffect(glass, blur);
    }
}
