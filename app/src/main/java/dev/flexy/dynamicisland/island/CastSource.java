package dev.flexy.dynamicisland.island;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import dev.flexy.dynamicisland.xposed.Refl;

final class CastSource {
    static final int RED = 0xFFFF3B30;
    private static final int BLUE = 0xFF0A84FF;

    private final Context ctx;
    private Object bundle;
    Object castController;

    CastSource(Context ctx) {
        this.ctx = ctx;
    }

    void onBundle(Object b) {
        bundle = b;
    }

    void collect(List<IslandItem> out) {
        if (bundle == null) return;
        boolean ru = "ru".equals(Locale.getDefault().getLanguage());
        Object record = Refl.get(bundle, "screenRecord");
        Object share = Refl.get(bundle, "shareToApp");
        Object cast = Refl.get(bundle, "castToOtherDevice");
        if (isActive(record)) {
            out.add(item("cast:record", record, IslandItem.Kind.RECORDING, RED, ru ? "Запись экрана" : "Screen recording"));
        } else if (isActive(share)) {
            String mgr = (String) Refl.get(share, "managingPackageName");
            boolean smartCapture = mgr != null && mgr.contains("com.samsung.android.app.smartcapture");
            out.add(item("cast:share", share, IslandItem.Kind.RECORDING, RED,
                    smartCapture ? (ru ? "Запись экрана" : "Screen recording") : (ru ? "Демонстрация экрана" : "Sharing screen")));
        }
        if (isActive(cast)) {
            out.add(item("cast:device", cast, IslandItem.Kind.LIVE, BLUE, ru ? "Трансляция экрана" : "Casting screen"));
        }
    }

    private IslandItem item(String key, Object model, IslandItem.Kind kind, int accent, String title) {
        IslandItem it = new IslandItem(key);
        it.kind = kind;
        it.accent = accent;
        it.pulsingDot = kind == IslandItem.Kind.RECORDING;
        it.title = title;
        it.appName = title;
        it.packageName = (String) Refl.get(model, "managingPackageName");
        long[] chrono = timer(Refl.get(model, "content"));
        if (chrono != null) {
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.chronoBase = chrono[0];
            it.chronoCountDown = chrono[1] != 0;
        }
        Object chipObj = castController == null ? null : Refl.get(castController, "chipView");
        if (chipObj instanceof View) {
            View chip = (View) chipObj;
            boolean ru = "ru".equals(Locale.getDefault().getLanguage());
            it.actions.add(new IslandItem.Action(ru ? "Остановить" : "Stop", null, null, () -> {
                Object l = Refl.get(chip, "customClickListener");
                if (l instanceof View.OnClickListener) ((View.OnClickListener) l).onClick(chip);
                else chip.performClick();
            }, true));
        }
        it.postTime = Long.MAX_VALUE - 1;
        return it;
    }

    private static boolean isActive(Object model) {
        return model != null && model.getClass().getName().endsWith("$Active");
    }

    private static long[] timer(Object content) {
        if (content == null || !content.getClass().getName().endsWith("$Timer")) return null;
        Object value = Refl.get(content, "value");
        if (value == null) return null;
        String cls = value.getClass().getName();
        if (cls.endsWith("$Running")) {
            Object event = Refl.get(value, "eventTime");
            boolean countdown = Refl.getBool(value, "isCountdown", false);
            if (event == null) return null;
            Object elapsed = Refl.get(event, "elapsedRealtime");
            if (elapsed instanceof Long) {
                long wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - (Long) elapsed);
                return new long[]{wall, countdown ? 1 : 0};
            }
            Object instant = Refl.get(event, "instant");
            if (instant instanceof Instant) return new long[]{((Instant) instant).toEpochMilli(), countdown ? 1 : 0};
        } else if (cls.endsWith("$Paused")) {
            Object d = Refl.get(value, "atDuration");
            if (d instanceof Duration) {
                return new long[]{System.currentTimeMillis() - ((Duration) d).toMillis(), 0};
            }
        }
        return null;
    }
}
