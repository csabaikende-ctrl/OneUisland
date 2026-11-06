package dev.flexy.dynamicisland.island;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import dev.flexy.dynamicisland.xposed.Refl;
import dev.flexy.dynamicisland.xposed.XLog;

@SuppressLint("ViewConstructor")
final class BlurLayer extends FrameLayout implements IslandView.BlurSink {
    private static final int RADIUS = 120;

    private static Constructor<?> builderCtor;
    private static Method setRadius;
    private static Method setCorner;
    private static Method setColor;
    private static Method build;
    private static Method semSetBlurInfo;
    private static Method semSetCorner;
    private static Boolean supported;
    /** True when the blur comes from AOSP's BackgroundBlurDrawable instead of Samsung's SemBlurInfo. */
    private static boolean aosp;
    /** AOSP: mirrors WindowManager#isCrossWindowBlurEnabled ("Allow window-level blurs", battery saver). */
    private static volatile boolean crossWindowBlur = true;

    private final Map<Object, Slot> slots = new IdentityHashMap<>();
    private final Set<Object> placed = new HashSet<>();
    private final java.util.ArrayDeque<Slot> free = new java.util.ArrayDeque<>();
    private static final int POOL = 6;
    private static final float PARKED_ALPHA = 0.004f;
    private IslandView.CardPainter painter;

    BlurLayer(Context context) {
        super(context);
        setClipChildren(false);
        setWillNotDraw(true);
        if (isSupported() && aosp) {
            try {
                android.view.WindowManager wm = context.getSystemService(android.view.WindowManager.class);
                crossWindowBlur = wm.isCrossWindowBlurEnabled();
                wm.addCrossWindowBlurEnabledListener(context.getMainExecutor(), on -> {
                    crossWindowBlur = on;
                    XLog.i("cross-window blur " + (on ? "enabled" : "disabled"));
                });
            } catch (Throwable t) {
                XLog.e("cross-window blur listener", t);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getChildCount() == 0 && isSupported()) {
            for (int i = 0; i < POOL; i++) {
                Slot s = new Slot(getContext());
                addView(s, new LayoutParams(0, 0));
                s.park();
                free.add(s);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
    }

    static boolean isSupported() {
        if (supported == null) {
            try {
                Class<?> b = Class.forName("android.view.SemBlurInfo$Builder");
                Class<?> info = Class.forName("android.view.SemBlurInfo");
                builderCtor = b.getConstructor(int.class);
                setRadius = b.getMethod("setRadius", int.class);
                setCorner = b.getMethod("setBackgroundCornerRadius", float.class);
                setColor = b.getMethod("setBackgroundColor", int.class);
                build = b.getMethod("build");
                semSetBlurInfo = View.class.getMethod("semSetBlurInfo", info);
                try {
                    semSetCorner = View.class.getMethod("semSetBackgroundBlurCornerRadius", float.class);
                } catch (Throwable ignored) {
                    semSetCorner = null;
                }
                supported = true;
            } catch (Throwable samsung) {
                try {
                    // AOSP: android.view.ViewRootImpl#createBackgroundBlurDrawable (hidden, used by SystemUI)
                    Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable");
                    Class.forName("android.view.ViewRootImpl").getMethod("createBackgroundBlurDrawable");
                    aosp = true;
                    supported = true;
                    XLog.i("using AOSP BackgroundBlurDrawable");
                } catch (Throwable t) {
                    XLog.e("no blur API (SemBlurInfo / BackgroundBlurDrawable)", t);
                    supported = false;
                }
            }
        }
        return supported;
    }

    @Override
    public boolean available() {
        return isSupported() && (!aosp || crossWindowBlur);
    }

    @Override
    public void setPainter(IslandView.CardPainter p) {
        painter = p;
    }

    @Override
    public void beginFrame() {
        placed.clear();
    }

    private static final long SUSPEND_DELAY_MS = 400;
    private final Runnable suspend = () -> {
        if (placed.isEmpty() && getVisibility() == VISIBLE) setVisibility(GONE);
    };

    @Override
    public void place(Object owner, RectF r, float corner, float alpha, int radius) {
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        removeCallbacks(suspend);
        Slot s = slots.get(owner);
        if (s == null) {
            s = free.poll();
            if (s == null) {
                s = new Slot(getContext());
                addView(s, new LayoutParams(0, 0));
            }
            s.owner = owner;
            slots.put(owner, s);
        }
        placed.add(owner);
        s.update(r, corner, alpha, radius > 0 ? radius : RADIUS);
    }

    @Override
    public void endFrame() {
        for (Iterator<Map.Entry<Object, Slot>> it = slots.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Slot> e = it.next();
            if (placed.contains(e.getKey())) continue;
            e.getValue().hide();
        }
        if (placed.isEmpty() && getVisibility() == VISIBLE) {
            removeCallbacks(suspend);
            postDelayed(suspend, SUSPEND_DELAY_MS);
        }
    }

    private static java.lang.reflect.Method drawableCorner;

    @Override
    public void release(Object owner) {
        Slot s = slots.remove(owner);
        if (s != null) {
            s.owner = null;
            s.park();
            free.add(s);
        }
    }

    private final class Slot extends View {
        private Object owner;
        private boolean blurSet;
        private float corner = -1f;
        private int radius = RADIUS;
        private int appliedRadius = -1;

        Slot(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void update(RectF r, float c, float alpha, int blurRadius) {
            radius = blurRadius;
            if (r.width() < 2 || r.height() < 2) {
                hide();
                return;
            }
            setLeftTopRightBottom(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
            setAlpha(Math.max(PARKED_ALPHA, alpha));
            if (isSupported()) applyBlur(c);
            setBlurAlpha(alpha);
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            invalidate();
        }

        void park() {
            setLeftTopRightBottom(0, 0, 2, 2);
            setAlpha(PARKED_ALPHA);
            if (isSupported()) applyBlur(1f);
            setBlurAlpha(0f);
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            invalidate();
        }

        private android.graphics.drawable.Drawable aospBlur;
        private int aospAlpha = -1;

        private void setBlurAlpha(float a) {
            if (aospBlur == null) return;
            int v = Math.round(255 * Math.max(0f, Math.min(1f, a)));
            if (v == aospAlpha) return;
            aospAlpha = v;
            aospBlur.setAlpha(v);
        }

        private void applyAospBlur(float c) {
            if (aospBlur == null) {
                Object vri = Refl.call(this, "getViewRootImpl");
                Object d = vri == null ? null : Refl.call(vri, "createBackgroundBlurDrawable");
                if (!(d instanceof android.graphics.drawable.Drawable)) return;
                aospBlur = (android.graphics.drawable.Drawable) d;
                aospAlpha = -1;
                setBackground(aospBlur);
                appliedRadius = -1;
                corner = -1f;
            }
            if (appliedRadius != radius) {
                Refl.call(aospBlur, "setBlurRadius", radius);
                Refl.call(aospBlur, "setColor", radius == RADIUS ? 0x14000000 : 0);
                appliedRadius = radius;
            }
            if (Math.abs(c - corner) >= 1f) {
                Refl.call(aospBlur, "setCornerRadius", c);
                corner = c;
            }
            blurSet = true;
        }

        private void applyBlur(float c) {
            if (aosp) {
                applyAospBlur(c);
                return;
            }
            boolean same = blurSet && appliedRadius == radius;
            if (Math.abs(c - corner) < 1f && same) return;
            if (same && setDrawableCorner(c)) {
                corner = c;
                return;
            }
            try {
                if (true) {
                    Object b = builderCtor.newInstance(0);
                    setRadius.invoke(b, radius);
                    setCorner.invoke(b, c);
                    setColor.invoke(b, radius == RADIUS ? 0x14000000 : 0);
                    semSetBlurInfo.invoke(this, build.invoke(b));
                    blurSet = true;
                    appliedRadius = radius;
                } else {
                    semSetCorner.invoke(this, c);
                }
                corner = c;
            } catch (Throwable t) {
                XLog.e("blur update failed", t);
                supported = false;
            }
        }

        private boolean setDrawableCorner(float c) {
            android.graphics.drawable.Drawable bg = getBackground();
            if (bg == null || !bg.getClass().getName().endsWith("BackgroundBlurDrawable")) return false;
            try {
                if (drawableCorner == null) drawableCorner = bg.getClass().getMethod("setCornerRadius", float.class);
                drawableCorner.invoke(bg, c);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (painter != null && owner != null) painter.paintCard(owner, canvas, getLeft(), getTop());
        }

        void hide() {
            park();
        }
    }
}
