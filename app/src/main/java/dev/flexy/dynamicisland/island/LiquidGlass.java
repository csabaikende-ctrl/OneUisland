package dev.flexy.dynamicisland.island;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;

import dev.flexy.dynamicisland.xposed.XLog;

/**
 * Liquid glass surface for a rounded rectangle (cards, the blurred pill and the second capsule).
 *
 * <p>The backdrop is blurred by SurfaceFlinger (SemBlurInfo, see {@link BlurLayer}), so System UI
 * never sees the pixels behind the island and can't displace them the way Kyant0's backdrop lens
 * does. This shader instead renders the optical response of a glass slab on top of that live blur,
 * using the same model as Kyant0/AndroidLiquidGlass and huozhi/vaso:
 * <ul>
 *   <li>a rounded-rect signed distance field gives the distance to the edge and the outward
 *       surface normal;</li>
 *   <li>vaso's convex squircle bezel, h(t) = (1 - (1 - t)^4)^(1/4), with Snell refraction at
 *       IOR 1.5 gives the per-pixel lens strength across the bezel (1 at the rim, 0 at the flat
 *       interior);</li>
 *   <li>a directional light from the top produces a bright specular rim on the facing edge and a
 *       weaker one on the far edge, the lens band darkens slightly where light bends away, and a
 *       red/blue fringe on the rim approximates dispersion.</li>
 * </ul>
 * Output is premultiplied, drawn src-over the card fill.
 */
final class LiquidGlass {
    private static final String AGSL =
            "uniform float2 uOrigin;\n"
            + "uniform float2 uSize;\n"
            + "uniform float uRadius;\n"
            + "uniform float uBezel;\n"
            + "uniform float uAlpha;\n"
            + "uniform float uChroma;\n"
            + "uniform float uDp;\n"
            + "uniform float2 uLight;\n"
            // vaso bezel: lateral offset of a ray refracted through the squircle surface, normalized.
            // t = 0 at the rim, 1 where the glass turns flat.
            + "float lens(float t) {\n"
            + "  float u = 1.0 - t;\n"
            + "  float u4 = u * u * u * u;\n"
            // pow(0, y) is undefined in AGSL (NaN on Mali), so keep every pow base positive.
            + "  float slope = u * u * u / pow(max(1.0 - u4, 1e-6), 0.75);\n"
            + "  float inc = atan(slope);\n"
            + "  float refr = asin(sin(inc) / 1.5);\n"
            + "  return clamp(tan(inc - refr) / 1.1170, 0.0, 1.0);\n"
            + "}\n"
            + "half4 main(float2 fc) {\n"
            + "  float2 hs = uSize * 0.5;\n"
            + "  float2 p = fc - uOrigin - hs;\n"
            + "  float r = min(uRadius, min(hs.x, hs.y));\n"
            + "  float2 q = abs(p) - (hs - r);\n"
            + "  float dist = r - length(max(q, 0.0)) - min(max(q.x, q.y), 0.0);\n"
            + "  if (dist <= 0.0) return half4(0.0);\n"
            + "  float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);\n"
            + "  float2 n;\n"
            + "  if (q.x > 0.0 && q.y > 0.0) n = normalize(q) * s;\n"
            + "  else if (q.x > q.y) n = float2(s.x, 0.0);\n"
            + "  else n = float2(0.0, s.y);\n"
            + "  float m = lens(clamp(dist / uBezel, 0.0, 1.0));\n"
            // The squircle concentrates refraction in the outermost few percent of the bezel; its
            // square root keeps that profile but spreads the visible band across the whole bezel.
            + "  float e = sqrt(m);\n"
            + "  float facing = dot(n, uLight);\n"
            + "  float lit = max(facing, 0.0);\n"
            + "  float opp = max(-facing, 0.0);\n"
            // specular rim: a thin ~1.5dp line kept clear of the anti-aliased outermost pixel
            + "  float rim = smoothstep(0.0, 0.6 * uDp, dist) * (1.0 - smoothstep(1.0 * uDp, 2.2 * uDp, dist));\n"
            + "  float spec = e * e * (0.14 * lit + 0.04 * opp)\n"
            + "             + rim * (0.05 + 0.21 * lit * sqrt(lit) + 0.09 * opp * sqrt(opp));\n"
            // faint sheen on the upper part of the slab
            + "  float sheen = 0.05 * (1.0 - smoothstep(0.0, 0.6, (p.y + hs.y) / uSize.y));\n"
            // refraction band: light bends away mid-bezel, the glass looks thicker there
            + "  float band = 4.0 * e * (1.0 - e);\n"
            + "  float dark = 0.20 * band * (1.0 - 0.5 * lit);\n"
            // dispersion lives in the refraction band, not on the specular line (which stays white)
            + "  float f = uChroma * e * e * (1.0 - rim) * 0.30;\n"
            + "  float3 light = float3(spec + sheen)\n"
            + "                + f * float3(lit - 0.25 * opp, 0.10 * (lit + opp), opp - 0.25 * lit);\n"
            + "  light = clamp(light, 0.0, 1.0);\n"
            + "  float la = max(light.r, max(light.g, light.b));\n"
            + "  float a = 1.0 - (1.0 - dark) * (1.0 - la);\n"
            + "  return half4(half3(light * uAlpha), half(a * uAlpha));\n"
            + "}\n";

    // Light comes from above, slightly from the left (normalized).
    private static final float LIGHT_X = -0.4472f;
    private static final float LIGHT_Y = -0.8944f;

    private static Boolean supported;

    private final RuntimeShader shader;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean broken;

    private LiquidGlass(RuntimeShader shader, float density) {
        this.shader = shader;
        this.density = density;
        paint.setShader(shader);
    }

    /** Returns null if AGSL is unavailable or the shader fails to compile. */
    static LiquidGlass create(float density) {
        if (Boolean.FALSE.equals(supported)) return null;
        try {
            LiquidGlass g = new LiquidGlass(new RuntimeShader(AGSL), density);
            supported = true;
            return g;
        } catch (Throwable t) {
            supported = false;
            XLog.e("liquid glass shader unavailable", t);
            return null;
        }
    }

    /**
     * Draws the glass surface for {@code rect} with corner radius {@code radius}.
     * RuntimeShader only works on a hardware canvas; software canvases are skipped.
     */
    void draw(Canvas canvas, RectF rect, float radius, float alpha) {
        if (alpha <= 0.003f || rect.width() < 2f || rect.height() < 2f) return;
        if (!canvas.isHardwareAccelerated()) return;
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        // Never wider than the corner radius, otherwise the straight edges and the corner arcs
        // would get different normals inside the bezel and show a diagonal seam.
        float bezel = Math.max(1f, Math.min(IslandSettings.liquidEdgeDp() * density, r));
        try {
            shader.setFloatUniform("uOrigin", rect.left, rect.top);
            shader.setFloatUniform("uSize", rect.width(), rect.height());
            shader.setFloatUniform("uRadius", r);
            shader.setFloatUniform("uBezel", bezel);
            shader.setFloatUniform("uAlpha", Math.min(1f, alpha));
            shader.setFloatUniform("uChroma", IslandSettings.liquidChroma() ? 1f : 0f);
            shader.setFloatUniform("uDp", density);
            shader.setFloatUniform("uLight", LIGHT_X, LIGHT_Y);
        } catch (Throwable t) {
            // Never take System UI down over a visual effect: fall back to the regular glass.
            broken = true;
            XLog.e("liquid glass disabled", t);
            return;
        }
        canvas.drawRoundRect(rect, r, r, paint);
    }

    /** False once the shader has failed at runtime; callers then use the regular glass style. */
    boolean usable() {
        return !broken;
    }
}
