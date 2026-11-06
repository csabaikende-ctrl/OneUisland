package dev.flexy.dynamicisland.island;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * A horizontal battery drawn by the island itself (One UI's battery drawable doesn't exist on
 * AOSP). With {@code animate}, the charging-started animation plays once from {@code start}:
 * the fill sweeps up to the level with a light shimmer running through it, then the bolt pops in.
 */
final class BatteryIcon extends Drawable {
    static final long FILL_MS = 950;
    static final long BOLT_MS = 380;
    static final long SHIMMER_MS = 1300;
    /** How long the island has to keep drawing frames for the animation. */
    static final long ANIM_MS = Math.max(FILL_MS + BOLT_MS, SHIMMER_MS) + 50;

    private final float level;
    private final int color;
    private final boolean bolt;
    private final boolean animate;
    private final long start;
    private int alpha = 255;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF r = new RectF();
    private final Path boltPath = new Path();

    BatteryIcon(int levelPercent, int color, boolean bolt, boolean animate, long startUptime) {
        this.level = Math.max(0f, Math.min(1f, levelPercent / 100f));
        this.color = color;
        this.bolt = bolt;
        this.animate = animate;
        this.start = startUptime;
        outline.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        boltPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width();
        float h = b.height();
        if (w <= 0 || h <= 0) return;
        long t = animate ? SystemClock.uptimeMillis() - start : Long.MAX_VALUE / 4;

        // body ~2.1:1 with a cap on the right, centred in the bounds
        float capW = w * 0.07f;
        float bodyW = w * 0.86f - capW;
        float bodyH = Math.min(h * 0.56f, bodyW / 2.1f);
        float left = b.left + (w - bodyW - capW) / 2f;
        float top = b.top + (h - bodyH) / 2f;
        body.set(left, top, left + bodyW, top + bodyH);
        float stroke = Math.max(1.5f, bodyH * 0.1f);
        float corner = bodyH * 0.3f;

        outline.setStrokeWidth(stroke);
        outline.setColor(withAlpha(0x8CFFFFFF));
        r.set(body);
        r.inset(stroke / 2f, stroke / 2f);
        c.drawRoundRect(r, corner, corner, outline);
        fill.setShader(null);
        fill.setColor(withAlpha(0x8CFFFFFF));
        r.set(body.right + stroke * 0.4f, top + bodyH * 0.32f, body.right + stroke * 0.4f + capW, top + bodyH * 0.68f);
        c.drawRoundRect(r, capW, capW, fill);

        // fill sweeping up to the level
        float gap = stroke * 1.55f;
        float innerL = body.left + gap;
        float innerR = body.right - gap;
        float innerT = body.top + gap;
        float innerB = body.bottom - gap;
        float p = easeOutCubic(clamp01(t / (float) FILL_MS));
        float minW = (innerB - innerT) * 0.55f;
        float fillW = Math.max(level > 0 ? minW : 0f, (innerR - innerL) * level * p);
        if (fillW > 0f) {
            float fc = Math.max(1f, corner - gap);
            r.set(innerL, innerT, innerL + fillW, innerB);
            fill.setColor(withAlpha(color));
            c.drawRoundRect(r, fc, fc, fill);
            if (animate && t < SHIMMER_MS) {
                // a light band running left to right through the fill
                float s = clamp01(t / (float) SHIMMER_MS);
                float band = (innerR - innerL) * 0.45f;
                float x = innerL - band + (innerR - innerL + band * 2f) * s;
                int a = Math.round(alpha * 0.55f * (1f - s * s));
                shine.setShader(new LinearGradient(x - band, 0, x + band, 0,
                        new int[]{0x00FFFFFF, (a << 24) | 0xFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, fc, fc, shine);
            }
        }

        // bolt pops in once the fill has arrived
        if (bolt) {
            float bp = animate ? clamp01((t - FILL_MS * 0.7f) / (float) BOLT_MS) : 1f;
            if (bp > 0f) {
                float scale = backOut(bp);
                float cx = body.centerX();
                float cy = body.centerY();
                float bh = bodyH * 0.78f * scale;
                float bw = bh * 0.62f;
                boltPath.rewind();
                boltPath.moveTo(cx + bw * 0.12f, cy - bh / 2f);
                boltPath.lineTo(cx - bw / 2f, cy + bh * 0.08f);
                boltPath.lineTo(cx - bw * 0.02f, cy + bh * 0.08f);
                boltPath.lineTo(cx - bw * 0.12f, cy + bh / 2f);
                boltPath.lineTo(cx + bw / 2f, cy - bh * 0.08f);
                boltPath.lineTo(cx + bw * 0.02f, cy - bh * 0.08f);
                boltPath.close();
                // dark edge keeps the bolt readable over the fill and over the empty part alike
                boltPaint.setStyle(Paint.Style.STROKE);
                boltPaint.setStrokeJoin(Paint.Join.ROUND);
                boltPaint.setStrokeWidth(stroke * 0.9f);
                boltPaint.setColor(withAlpha(0xCC000000));
                c.drawPath(boltPath, boltPaint);
                boltPaint.setStyle(Paint.Style.FILL);
                boltPaint.setColor(withAlpha(0xFFFFFFFF));
                c.drawPath(boltPath, boltPaint);
            }
        }
    }

    private int withAlpha(int argb) {
        int a = Math.round(((argb >>> 24) & 0xFF) * (alpha / 255f));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float easeOutCubic(float x) {
        float u = 1f - x;
        return 1f - u * u * u;
    }

    private static float backOut(float x) {
        float c1 = 1.9f;
        float c3 = c1 + 1f;
        float u = x - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    @Override
    public void setAlpha(int a) {
        alpha = a;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
