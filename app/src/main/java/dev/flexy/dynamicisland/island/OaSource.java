package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import dev.flexy.dynamicisland.xposed.Refl;
import dev.flexy.dynamicisland.xposed.XLog;

final class OaSource {
    static final String HELPER = "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper";
    private static final int CALL_GREEN = 0xFF34C759;

    private final Context ctx;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Class<?> helper;
    private final Map<Object, IslandItem> cache = new WeakHashMap<>();
    Object callController;

    /**
     * AOSP SystemUI's NotifCollection (LineageOS and other non-Samsung ROMs). Used instead of the
     * One UI Now Bar data helper when {@link #HELPER} doesn't exist.
     */
    volatile Object notifCollection;
    private volatile String aospForeground;
    private final Map<Object, IslandItem> aospCache = new WeakHashMap<>();

    OaSource(Context ctx, ClassLoader cl) {
        this.ctx = ctx;
        this.helper = Refl.findClass(HELPER, cl);
        if (helper == null) watchForegroundAosp(cl);
    }

    String foregroundPackage() {
        if (helper == null) return aospForeground;
        Object cn = Refl.getStatic(helper, "baseActivityComponentName");
        return cn instanceof ComponentName ? ((ComponentName) cn).getPackageName() : null;
    }

    /** Tracks the top task via SystemUI's TaskStackChangeListeners (AOSP). */
    private void watchForegroundAosp(ClassLoader cl) {
        try {
            Class<?> listeners = Refl.findClass("com.android.systemui.shared.system.TaskStackChangeListeners", cl);
            Class<?> listener = Refl.findClass("com.android.systemui.shared.system.TaskStackChangeListener", cl);
            Object instance = Refl.getStatic(listeners, "INSTANCE");
            if (instance == null || listener == null) return;
            Object proxy = Refl.proxy(listener, (pr, m, a) -> {
                String name = m.getName();
                if ("onTaskStackChanged".equals(name) || "onTaskRemoved".equals(name)
                        || "onTaskMovedToFront".equals(name)) {
                    main.post(this::updateForegroundAosp);
                }
                return null;
            });
            Refl.call(instance, "registerTaskStackListener", proxy);
            main.post(this::updateForegroundAosp);
        } catch (Throwable t) {
            XLog.e("task stack listener", t);
        }
    }

    @SuppressWarnings("deprecation")
    private void updateForegroundAosp() {
        String pkg = null;
        try {
            android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
            List<android.app.ActivityManager.RunningTaskInfo> tasks = am == null ? null : am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                android.app.ActivityManager.RunningTaskInfo t = tasks.get(0);
                ComponentName c = t.baseActivity != null ? t.baseActivity : t.topActivity;
                if (c != null) pkg = c.getPackageName();
            }
        } catch (Throwable t) {
            XLog.e("top task", t);
        }
        if (java.util.Objects.equals(pkg, aospForeground)) return;
        aospForeground = pkg;
        Runnable r = onChanged;
        if (r != null) r.run();
    }

    private static final int FLAG_PROMOTED_ONGOING = 0x00040000; // Notification.FLAG_PROMOTED_ONGOING (API 36)

    /**
     * AOSP equivalent of the Now Bar: calls (CallStyle) and live activities, i.e. promoted ongoing
     * notifications plus ongoing ones that carry live state (chronometer, progress, navigation,
     * short critical text).
     */
    private void collectAosp(List<IslandItem> out, Set<String> hiddenByForeground, Collection<String> packages) {
        Object set = Refl.get(notifCollection, "mReadOnlyNotificationSet");
        if (!(set instanceof Collection)) return;
        String fg = aospForeground;
        for (Object entry : new java.util.ArrayList<>((Collection<?>) set)) {
            try {
                Object sbnObj = Refl.get(entry, "mSbn");
                if (!(sbnObj instanceof StatusBarNotification)) continue;
                StatusBarNotification sbn = (StatusBarNotification) sbnObj;
                Notification n = sbn.getNotification();
                String pkg = sbn.getPackageName();
                String key = sbn.getKey();
                if (n == null || pkg == null || key == null) continue;
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) && !VoiceLevels.PACKAGE.equals(pkg)) continue;
                Object ranking = Refl.get(entry, "mRanking");
                if (ranking instanceof android.service.notification.NotificationListenerService.Ranking) {
                    int imp = ((android.service.notification.NotificationListenerService.Ranking) ranking).getImportance();
                    if (imp >= 0 && imp <= android.app.NotificationManager.IMPORTANCE_MIN) continue;
                }
                boolean call = isCallStyle(n);
                if (!call && !isAospLive(entry, pkg, n)) continue;
                String target = targetPackage(n.contentIntent);
                String appPkg = target != null ? target : pkg;
                packages.add(pkg);
                if (target != null) packages.add(target);
                String itemKey = (call ? "call:" : "oa:") + key;
                if (fg != null && (fg.equals(appPkg) || fg.equals(pkg))) {
                    hiddenByForeground.add(itemKey);
                    continue;
                }
                IslandItem item = aospCache.get(sbn);
                if (item == null) {
                    item = call ? buildAospCall(itemKey, pkg, sbn, n) : build(itemKey, null, pkg, sbn, n, false);
                    aospCache.put(sbn, item);
                }
                item.postTime = call ? Long.MAX_VALUE : sbn.getPostTime();
                out.add(item);
            } catch (Throwable t) {
                XLog.e("AOSP item failed", t);
            }
        }
    }

    private static final Set<String> CLOCK_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.deskclock", "com.android.deskclock"));
    private final Map<Notification, Boolean> chronoViews = new WeakHashMap<>();

    /**
     * Clock apps post timers and stopwatches as plain (not ongoing) notifications with a custom
     * layout holding a Chronometer; those are live activities, the "upcoming alarm" ones aren't.
     */
    private boolean hasChronometerView(Notification n) {
        Boolean cached = chronoViews.get(n);
        if (cached != null) return cached;
        boolean found = false;
        if (n.contentView != null) {
            try {
                found = findChronometer(n.contentView.apply(ctx, new FrameLayout(ctx))) != null;
            } catch (Throwable t) {
                XLog.e("clock RemoteViews", t);
            }
        }
        chronoViews.put(n, found);
        return found;
    }

    /** Timer and stopwatch channels of clock apps ("Timers v2", "Stopwatch"); paused ones have no Chronometer. */
    private static boolean isClockTimerChannel(Notification n) {
        String ch = n.getChannelId();
        if (ch == null) return false;
        ch = ch.toLowerCase(java.util.Locale.ROOT);
        return ch.contains("timer") || ch.contains("stopwatch");
    }

    private boolean isAospLive(Object entry, String pkg, Notification n) {
        if ((n.flags & FLAG_PROMOTED_ONGOING) != 0) return true;
        if (Refl.get(entry, "mPromotedNotificationContentModels") != null) return true;
        if (CLOCK_PACKAGES.contains(pkg) && (isClockTimerChannel(n) || hasChronometerView(n))) return true;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        Bundle x = n.extras;
        if (isProgressStyle(n) || x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) return true;
        if (Notification.CATEGORY_NAVIGATION.equals(n.category) || Notification.CATEGORY_STOPWATCH.equals(n.category)) return true;
        if (x.getCharSequence("android.shortCriticalText") != null) return true;
        return x.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 && !x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
    }

    private static final int CALL_TYPE_INCOMING = 1; // Notification.CallStyle.CALL_TYPE_INCOMING
    private static final int CALL_TYPE_ONGOING = 2; // Notification.CallStyle.CALL_TYPE_ONGOING

    private IslandItem buildAospCall(String itemKey, String pkg, StatusBarNotification sbn, Notification n) {
        Bundle x = n.extras;
        IslandItem it = new IslandItem(itemKey);
        it.kind = IslandItem.Kind.CALL;
        it.packageName = pkg;
        it.userId = sbn.getUid() / 100_000 /* UserHandle.PER_USER_RANGE */;
        it.accent = CALL_GREEN;
        it.icon = load(n.getSmallIcon(), pkg);
        it.iconIsImage = false;
        if (it.icon == null) {
            it.icon = appIcon(pkg);
            it.iconIsImage = true;
        }
        it.appName = appLabel(pkg);
        CharSequence caller = x.getCharSequence(Notification.EXTRA_TITLE);
        android.app.Person person = x.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person.class);
        if (!notEmpty(caller) && person != null) caller = person.getName();
        it.title = notEmpty(caller) ? caller : it.appName;
        CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
        it.subtitle = notEmpty(text) ? text : it.appName;
        Drawable image = n.getLargeIcon() != null ? load(n.getLargeIcon(), pkg) : null;
        if (image == null && person != null && person.getIcon() != null) image = load(person.getIcon(), pkg);
        it.cardImage = image != null ? image : appIcon(pkg);
        it.cardImageIsImage = true;
        it.contentIntent = n.contentIntent;
        addCallActions(it, n.actions == null ? null : java.util.Arrays.asList(n.actions), pkg);
        boolean incoming = x.getInt(Notification.EXTRA_CALL_TYPE, 0) == CALL_TYPE_INCOMING;
        boolean ongoingCall = x.getInt(Notification.EXTRA_CALL_TYPE, 0) == CALL_TYPE_ONGOING;
        if (!incoming && n.when > 0 && (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) || ongoingCall)) {
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.chronoBase = n.when;
            it.chronoCountDown = false;
        }
        return it;
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    void collect(List<IslandItem> out, Set<String> hiddenByForeground, Collection<String> packages) {
        if (helper == null) {
            collectAosp(out, hiddenByForeground, packages);
            return;
        }
        String fg = foregroundPackage();
        Set<String> pipPackages = pipPackages();
        int order = 0;
        List<?> showing = (List<?>) Refl.getStatic(helper, "mOngoingActivityLists");
        List<?> hidden = (List<?>) Refl.getStatic(helper, "hiddenOngoingActivityDataList");
        if (showing != null) {
            for (Object d : showing) order = add(out, d, order, fg, pipPackages, hiddenByForeground, packages, false);
        }
        if (hidden != null) {
            for (Object d : hidden) order = add(out, d, order, fg, pipPackages, hiddenByForeground, packages, false);
        }
        addCallFromController(out, packages, hiddenByForeground);
    }

    IslandItem itemForKey(String notificationKey) {
        if (helper == null || notificationKey == null) return null;
        try {
            for (String name : new String[]{"mOngoingActivityLists", "hiddenOngoingActivityDataList"}) {
                Object list = Refl.getStatic(helper, name);
                if (!(list instanceof List)) continue;
                for (Object d : (List<?>) list) {
                    if (d == null || !notificationKey.equals(Refl.get(d, "mNotiID"))) continue;
                    if (Refl.getBool(d, "mIsMediaOngoingData", false) && !VoiceLevels.PACKAGE.equals(Refl.get(d, "mPackageName"))) return null;
                    String pkg = (String) Refl.get(d, "mPackageName");
                    Object entry = Refl.get(d, "mNotificationEntry");
                    StatusBarNotification sbn = (StatusBarNotification) Refl.get(entry, "mSbn");
                    Notification n = sbn == null ? null : sbn.getNotification();
                    if (pkg == null || style(n) >= 2 || Refl.getInt(d, "mStyle", 0) >= 2) return null;
                    if (n != null && ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                            || isCallStyle(n) || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))) return null;
                    if (classify(n, pkg) == IslandItem.Kind.CALL && !"com.locnall.KimGiSa".equals(pkg)) return null;
                    IslandItem item = cache.get(d);
                    if (item == null) {
                        item = build("oa:" + notificationKey, d, pkg, sbn, n, false);
                        cache.put(d, item);
                    }
                    return item;
                }
            }
        } catch (Throwable t) {
            XLog.e("OA shade item failed", t);
        }
        return null;
    }

    static int style(Notification n) {
        if (n == null) return 0;
        Object v = n.extras.get("android.ongoingActivityNoti.style");
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int add(List<IslandItem> out, Object d, int order, String fg, Set<String> pip,
                    Set<String> hiddenByForeground, Collection<String> packages, boolean nowbarOnly) {
        if (d == null) return order;
        try {
            if (Refl.getBool(d, "mIsMediaOngoingData", false) && !VoiceLevels.PACKAGE.equals(Refl.get(d, "mPackageName"))) return order;
            String key = (String) Refl.get(d, "mNotiID");
            String pkg = (String) Refl.get(d, "mPackageName");
            PendingIntent pi = (PendingIntent) Refl.get(d, "mPendingIntent");
            String targetPkg = targetPackage(pi);
            Object entry = Refl.get(d, "mNotificationEntry");
            StatusBarNotification sbn = entry == null ? null : (StatusBarNotification) Refl.get(entry, "mSbn");
            Notification n = sbn == null ? null : sbn.getNotification();
            if (key == null || pkg == null) return order;
            if (n != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return order;
            if (style(n) >= 2 || Refl.getInt(d, "mStyle", 0) >= 2) return order;
            boolean isCall = n != null && isCallStyle(n);
            if (isCall) return order;
            if (entry != null) {
                Object promoted = Refl.call(entry, "isPromotedState");
                if (Boolean.FALSE.equals(promoted)) return order;
                if (Boolean.TRUE.equals(Refl.get(entry, "mIsBindCutOff"))) return order;
            }
            String appPkg = targetPkg != null ? targetPkg : pkg;
            packages.add(pkg);
            if (targetPkg != null) packages.add(targetPkg);
            String itemKey = "oa:" + key;
            if (pip.contains(appPkg) || pip.contains(pkg)) return order;
            if (fg != null && (fg.equals(appPkg) || fg.equals(pkg))) {
                hiddenByForeground.add(itemKey);
                return order;
            }

            IslandItem item = cache.get(d);
            if (item == null) {
                item = build(itemKey, d, pkg, sbn, n, isCall);
                cache.put(d, item);
            }
            item.postTime = Long.MAX_VALUE - order;
            out.add(item);
        } catch (Throwable t) {
            XLog.e("OA item failed", t);
        }
        return order + 1;
    }

    private Object[] teamsFromChip(RemoteViews rv) {
        if (rv == null) return null;
        try {
            View root = rv.apply(ctx, new FrameLayout(ctx));
            List<Drawable> images = new java.util.ArrayList<>();
            List<String> texts = new java.util.ArrayList<>();
            collectChip(root, images, texts);
            if (images.size() < 2 || texts.isEmpty()) return null;
            String score;
            if (texts.size() == 2) {
                score = texts.get(0) + "-" + texts.get(1);
            } else {
                score = String.join(" ", texts);
            }
            return new Object[]{images.get(0), images.get(images.size() - 1), score};
        } catch (Throwable t) {
            XLog.e("score chip failed", t);
            return null;
        }
    }

    private static void collectChip(View v, List<Drawable> images, List<String> texts) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof android.widget.ImageView) {
            Drawable dr = ((android.widget.ImageView) v).getDrawable();
            if (dr != null) images.add(dr);
        } else if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.toString().trim().length() > 0) texts.add(t.toString().trim());
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectChip(g.getChildAt(i), images, texts);
        }
    }

    private IslandItem build(String itemKey, Object d, String pkg, StatusBarNotification sbn, Notification n, boolean isCall) {
        IslandItem it = new IslandItem(itemKey);
        it.packageName = pkg;
        it.userId = d == null && sbn != null ? sbn.getUid() / 100_000 /* UserHandle.PER_USER_RANGE */ : Refl.getInt(d, "mUserId", 0);
        Bundle extras = n != null ? n.extras : new Bundle();
        it.progressStyle = isProgressStyle(n);
        it.kind = isCall ? IslandItem.Kind.CALL : classify(n, pkg);

        Icon chip = (Icon) Refl.get(d, "mChipIcon");
        if (chip == null) chip = (Icon) Refl.get(d, "mCardIcon");
        it.icon = load(chip, pkg);
        if (d == null && n != null) it.icon = load(n.getSmallIcon(), pkg); // AOSP: chip = small icon
        int bg = Refl.getInt(d, "mChipBackground", 0);
        Object cardBg = Refl.get(d, "mCardIconBg");
        int color = bg != 0 ? bg : (cardBg instanceof Integer && (Integer) cardBg != 0 ? (Integer) cardBg : (n != null ? n.color : 0));
        if (isCall) color = CALL_GREEN;
        it.accent = color == 0 ? 0xFFFFFFFF : Colors.readableOnBlack(color);
        it.leftImage = load((Icon) Refl.get(d, "mFirstIcon"), pkg);
        it.rightImage = load((Icon) Refl.get(d, "mSecondIcon"), pkg);
        CharSequence chipScore = null;
        if (!it.hasTeams() && !isCall) {
            Object[] chipTeams = teamsFromChip((RemoteViews) Refl.get(d, "mExpandedChipView"));
            if (chipTeams != null) {
                it.leftImage = (Drawable) chipTeams[0];
                it.rightImage = (Drawable) chipTeams[1];
                chipScore = (CharSequence) chipTeams[2];
            }
        }
        it.pillColor = 0;

        CharSequence chipText = (CharSequence) Refl.get(d, "mExpandedChipText");
        CharSequence critical = extras.getCharSequence("android.shortCriticalText");
        long[] chrono = chronometer(d, n);
        int progress = Refl.getInt(d, "mProgress", -1);
        int progressMax = Refl.getInt(d, "mProgressMax", 0);
        if (it.progressStyle || progressMax <= 0) {
            progress = extras.getInt(Notification.EXTRA_PROGRESS, progress);
            progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, progressMax);
        }
        if (progress >= 0 && progressMax > 0) it.progress = Math.min(1f, progress / (float) progressMax);
        if (notEmpty(chipScore)) {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = chipScore;
        } else if (!isCall && notEmpty(chipText)) {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = chipText;
        } else if (chrono != null && chrono[2] == 0 && notEmpty(pausedChronoText)) {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = pausedChronoText;
            Long base = isStopwatch(n) ? stopwatchBases.get(itemKey) : null;
            if (base != null && sbn != null && sbn.getPostTime() >= base) it.pausedMs = sbn.getPostTime() - base;
        } else if (chrono != null) {
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.chronoBase = chrono[0];
            it.chronoCountDown = chrono[1] != 0;
            it.chronoMillis = !it.chronoCountDown && isStopwatch(n);
            if (it.chronoMillis) stopwatchBases.put(itemKey, it.chronoBase);
        } else if (notEmpty(critical)) {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = critical;
        } else if (it.progress >= 0) {
            it.trailing = IslandItem.Trailing.PROGRESS_RING;
        } else {
            String primary = (String) Refl.get(d, "mPrimaryInfo");
            if (notEmpty(primary)) {
                it.trailing = IslandItem.Trailing.TEXT;
                it.trailingText = primary;
            }
        }

        it.appName = d == null ? appLabel(pkg) : (String) Refl.get(d, "mAppName");
        String title = (String) Refl.get(d, "mNowbarPrimaryInfo");
        if (!notEmpty(title)) title = (String) Refl.get(d, "mPrimaryInfo");
        if (!notEmpty(title)) {
            CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
            title = t == null ? null : t.toString();
        }
        String sub = (String) Refl.get(d, "mNowbarSecondaryInfo");
        if (!notEmpty(sub)) sub = (String) Refl.get(d, "mSecondaryInfo");
        if (!notEmpty(sub)) {
            CharSequence t = extras.getCharSequence(Notification.EXTRA_TEXT);
            sub = t == null ? null : t.toString();
        }
        it.title = title;
        it.subtitle = sub;
        boolean aosp = n != null && !extras.containsKey("android.ongoingActivityNoti.style");
        if (aosp) {
            CharSequence sourceTitle = extras.getCharSequence(Notification.EXTRA_TITLE);
            if (notEmpty(sourceTitle)) it.title = sourceTitle;
            it.subtitle = extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        it.metrics = Metrics.parse(ctx, extras);
        if (it.metrics != null) {
            int criticalIndex = extras.getInt("android.metrics.criticalIndex", -1);
            it.criticalMetric = criticalIndex >= 0 && criticalIndex < it.metrics.size() ? criticalIndex : -1;
            if (notEmpty(critical)) {
                it.trailing = IslandItem.Trailing.TEXT;
                it.trailingText = critical;
            } else if (it.criticalMetric >= 0) {
                IslandItem.Metric m = it.metrics.get(it.criticalMetric);
                if (m.running()) {
                    it.trailing = IslandItem.Trailing.CHRONOMETER;
                    it.chronoBase = m.zeroTime;
                    it.chronoCountDown = m.countDown;
                } else {
                    it.trailing = IslandItem.Trailing.TEXT;
                    it.trailingText = Metrics.text(m, System.currentTimeMillis());
                }
            }
        }
        if (it.progressStyle) {
            CharSequence sourceTitle = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence sourceText = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (notEmpty(sourceTitle)) it.title = sourceTitle;
            if (notEmpty(sourceText)) it.subtitle = sourceText;
            Object segments = extras.get("android.progressSegments");
            if (segments instanceof List && !((List<?>) segments).isEmpty()) {
                Object segment = ((List<?>) segments).get(0);
                if (segment instanceof Bundle) {
                    int segmentColor = ((Bundle) segment).getInt("colorInt", 0);
                    if (segmentColor != 0) it.accent = Colors.readableOnBlack(segmentColor);
                }
            }
        }
        Drawable appIcon = load((Icon) Refl.get(d, "mAppIcon"));
        if (appIcon == null) appIcon = appIcon(pkg);
        it.cardImage = appIcon;
        if (it.progressStyle && n != null && n.getLargeIcon() != null) {
            Drawable large = load(n.getLargeIcon(), pkg);
            if (large != null) it.cardImage = large;
        }
        it.cardImageIsImage = true;
        if (it.icon == null) {
            it.icon = appIcon;
            it.iconIsImage = true;
        }
        it.cardIcon = copiedAppIcon(pkg, n);
        if (it.cardIcon != null) {
            CharSequence detail = dev.flexy.dynamicisland.xposed.HideDuplicated.originalSubText(
                    n.extras.getCharSequence(Notification.EXTRA_TITLE), n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            if (detail != null && !detail.toString().equals(String.valueOf(it.subtitle))) it.detail = detail;
        }
        it.contentIntent = d == null && n != null ? n.contentIntent : (PendingIntent) Refl.get(d, "mPendingIntent");
        RemoteViews rv = (RemoteViews) Refl.get(d, "mCustomExpandedCardView");
        if (rv == null) rv = (RemoteViews) Refl.get(d, "mNowbarExpandRemoteView");
        if (rv == null && n != null) rv = n.bigContentView != null ? n.bigContentView : n.contentView;
        it.remoteView = rv;
        if (d == null && CLOCK_PACKAGES.contains(pkg)) {
            // Clock timers/stopwatches: the island's own timer card (big countdown + the app's
            // Pause / +1 min buttons) instead of the app's bare RemoteViews layout.
            it.remoteView = null;
            if (!notEmpty(it.title)) it.title = clockLabel(n);
            if (!notEmpty(it.title)) it.title = isStopwatch(n) ? "Stopwatch" : it.appName;
            if (it.trailing != IslandItem.Trailing.CHRONOMETER) {
                // paused: the layout shows the frozen time as plain text
                String frozen = clockTime(n);
                if (frozen != null) {
                    it.trailing = IslandItem.Trailing.TEXT;
                    it.trailingText = frozen;
                    if (isStopwatch(n)) it.pausedMs = pausedStopwatchMs(itemKey, sbn, frozen);
                }
            }
            it.subtitle = null;
            Drawable app = appIcon(pkg);
            if (app != null) {
                it.cardImage = app;
                it.cardImageIsImage = true;
            }
        }
        Object actions = d == null && n != null && n.actions != null
                ? java.util.Arrays.asList(n.actions) : Refl.get(d, "mActions");
        if (actions instanceof List) {
            for (Object o : (List<?>) actions) {
                if (!(o instanceof Notification.Action)) continue;
                Notification.Action a = (Notification.Action) o;
                if (a.actionIntent == null || a.title == null) continue;
                boolean destructive = isDestructive(a.title);
                it.actions.add(new IslandItem.Action(a.title, load(a.getIcon(), pkg), a.actionIntent, null, destructive));
                if (it.actions.size() == 3) break;
            }
        }
        if (VoiceLevels.PACKAGE.equals(pkg)) {
            it.recorder = true;
            it.kind = IslandItem.Kind.RECORDING;
            it.accent = RECORDER_RED;
            it.trailingColor = RECORDER_RED;
            it.actions.removeIf(a -> isCancel(a.title));
            while (it.actions.size() > 2) it.actions.remove(0);
        }
        return it;
    }


    private void addCallFromController(List<IslandItem> out, Collection<String> packages, Set<String> hiddenByForeground) {
        Object info = callController == null ? null : Refl.get(callController, "callNotificationInfo");
        if (info == null) return;
        String key = (String) Refl.get(info, "key");
        String pkg = (String) Refl.get(info, "packageName");
        if (key == null) return;
        if (pkg != null) packages.add(pkg);
        int state = callState();
        if (state == CALL_NONE) return;
        if (state == CALL_APP_VISIBLE) {
            hiddenByForeground.add("call:" + key);
            return;
        }
        IslandItem it = new IslandItem("call:" + key);
        it.kind = IslandItem.Kind.CALL;
        it.packageName = pkg;
        it.accent = CALL_GREEN;
        Object iconView = Refl.get(info, "notificationIconView");
        Drawable icon = null;
        if (iconView instanceof android.widget.ImageView) {
            Drawable d = ((android.widget.ImageView) iconView).getDrawable();
            if (d != null && d.getConstantState() != null) icon = d.getConstantState().newDrawable().mutate();
        }
        it.icon = icon != null ? icon : appIcon(pkg);
        it.iconIsImage = icon == null;
        it.appName = (String) Refl.get(info, "appName");
        it.title = it.appName;
        it.cardImage = appIcon(pkg);
        it.cardImageIsImage = true;
        it.contentIntent = (PendingIntent) Refl.get(info, "intent");
        Map<?, ?> nowbarOnly = (Map<?, ?>) Refl.getStatic(helper, "onlyShownNowbarItemMap");
        Object d = nowbarOnly == null ? null : nowbarOnly.get(key);
        if (d != null) {
            String caller = (String) Refl.get(d, "mPrimaryInfo");
            if (notEmpty(caller)) it.title = caller;
            it.subtitle = it.appName;
            Drawable large = load((Icon) Refl.get(d, "mNowbarIcon"));
            if (large != null) it.cardImage = large;
        }
        addCallActions(it, d == null ? null : Refl.get(d, "mActions"), pkg);
        applyCallChronometer(it, key);
        it.postTime = Long.MAX_VALUE;
        out.add(it);
    }

    private static final Set<String> TELECOM_CALL_PACKAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.samsung.android.incallui", "com.samsung.android.dialer", "com.android.server.telecom",
            "com.android.phone", "com.google.android.dialer", "com.android.dialer", "com.android.incallui"));

    private void addCallActions(IslandItem it, Object actions, String pkg) {
        PendingIntent hangIntent = null;
        Drawable hangIcon = null;
        PendingIntent speakerIntent = null;
        Notification.Action extra = null;
        if (actions instanceof List) {
            for (Object o : (List<?>) actions) {
                if (!(o instanceof Notification.Action)) continue;
                Notification.Action a = (Notification.Action) o;
                if (a.actionIntent == null || a.title == null) continue;
                if (hangIntent == null) {
                    hangIntent = a.actionIntent;
                    hangIcon = load(a.getIcon(), pkg);
                } else if (isSpeakerTitle(a.title)) {
                    speakerIntent = a.actionIntent;
                } else if (extra == null && !isMuteTitle(a.title)) {
                    extra = a;
                }
            }
        }
        boolean telecom = pkg == null || TELECOM_CALL_PACKAGES.contains(pkg);
        android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
        if (am != null && (telecom || speakerIntent != null)) {
            final PendingIntent spIntent = speakerIntent;
            boolean on = speakerOn(am);
            IslandItem.Action speaker = new IslandItem.Action("Speaker", null, null, () -> toggleSpeaker(spIntent, telecom), false);
            speaker.glyph = Glyphs.SPEAKER;
            speaker.active = on;
            it.actions.add(speaker);
        }
        if (extra != null) it.actions.add(new IslandItem.Action(extra.title, load(extra.getIcon(), pkg), extra.actionIntent, null, false));
        final PendingIntent fallback = hangIntent;
        IslandItem.Action hang = new IslandItem.Action("End call", hangIcon, null, () -> endCall(telecom, fallback), true);
        if (hangIcon == null) hang.glyph = Glyphs.HANGUP;
        if (telecom || fallback != null) it.actions.add(hang);
    }

    Runnable onChanged;

    @SuppressWarnings("deprecation")
    private void endCall(boolean telecom, PendingIntent fallback) {
        if (telecom) {
            try {
                android.telecom.TelecomManager tm = ctx.getSystemService(android.telecom.TelecomManager.class);
                if (tm != null && tm.endCall()) {
                    XLog.i("call ended via telecom");
                    return;
                }
            } catch (Throwable t) {
                XLog.e("telecom endCall failed", t);
            }
        }
        if (fallback == null) return;
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            fallback.send(ctx, 0, null, null, null, null, o.toBundle());
            XLog.i("call hang-up intent sent");
        } catch (Throwable t) {
            XLog.e("hang-up intent failed", t);
        }
    }

    private static boolean speakerOn(android.media.AudioManager am) {
        try {
            android.media.AudioDeviceInfo dev = am.getCommunicationDevice();
            return dev != null && dev.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String SPEAKER_TOGGLE = "com.samsung.rcs.intent.action.SPEAKER_TOGGLE";
    private static final String INCALLUI = "com.samsung.android.incallui";

    private void toggleSpeaker(PendingIntent intent, boolean telecom) {
        try {
            if (telecom) {
                ctx.sendBroadcast(new android.content.Intent(SPEAKER_TOGGLE).setPackage(INCALLUI));
                XLog.i("speaker toggled via incallui");
            } else if (intent != null) {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                intent.send(ctx, 0, null, null, null, null, o.toBundle());
                XLog.i("speaker toggled via call action");
            }
        } catch (Throwable t) {
            XLog.e("speaker toggle failed", t);
        }
        if (onChanged != null) main.postDelayed(onChanged, 500);
    }

    private static boolean isSpeakerTitle(CharSequence title) {
        String s = title.toString().toLowerCase(java.util.Locale.ROOT);
        return s.contains("speaker") || s.contains("динамик") || s.contains("громк");
    }

    private static boolean isMuteTitle(CharSequence title) {
        String s = title.toString().toLowerCase(java.util.Locale.ROOT);
        return s.contains("mute") || s.contains("микрофон") || s.contains("звук");
    }

    private void applyCallChronometer(IslandItem it, String key) {
        Object info = callController == null ? null : Refl.get(callController, "callNotificationInfo");
        if (info == null) return;
        Object infoKey = Refl.get(info, "key");
        Object start = Refl.get(info, "callStartTime");
        if (start instanceof Long && (Long) start > 0 && (key == null || key.equals(infoKey))) {
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.chronoBase = (Long) start;
            it.chronoCountDown = false;
        }
    }

    private static final int CALL_NONE = 0;
    private static final int CALL_SHOWN = 1;
    private static final int CALL_APP_VISIBLE = 2;

    private int callState() {
        if (callController == null) return CALL_SHOWN;
        Object info = Refl.get(callController, "callNotificationInfo");
        if (info == null || !Refl.getBool(info, "isOngoing", false)) return CALL_NONE;
        Object has = Refl.call(callController, "hasOngoingCall");
        return Boolean.TRUE.equals(has) ? CALL_SHOWN : CALL_APP_VISIBLE;
    }

    private Set<String> pipPackages() {
        Set<String> out = new java.util.HashSet<>();
        Object list = Refl.getStatic(helper, "pipEnabledComponentNameList");
        if (list instanceof Collection) {
            for (Object o : (Collection<?>) list) {
                if (o instanceof ComponentName) out.add(((ComponentName) o).getPackageName());
            }
        }
        return out;
    }

    private final Map<String, Long> stopwatchBases = new java.util.HashMap<>();

    private static boolean isStopwatch(Notification n) {
        if (n == null) return false;
        String channel = n.getChannelId();
        return Notification.CATEGORY_STOPWATCH.equals(n.category)
                || (channel != null && channel.toLowerCase(java.util.Locale.ROOT).contains("stopwatch"));
    }

    private CharSequence pausedChronoText;

    private long[] chronometer(Object d, Notification n) {
        pausedChronoText = null;
        if (n != null && n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) {
            return new long[]{n.when, n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false) ? 1 : 0, 1};
        }
        RemoteViews rv = d == null && n != null && CLOCK_PACKAGES.contains(pkgOf(n))
                ? n.contentView : (RemoteViews) Refl.get(d, "mChronometerView");
        if (rv == null) return null;
        try {
            FrameLayout parent = new FrameLayout(ctx);
            View v = rv.apply(ctx, parent);
            Chronometer c = findChronometer(v);
            if (c == null) return null;
            long wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - c.getBase());
            boolean running = Refl.getBool(c, "mStarted", true);
            if (!running) pausedChronoText = c.getText();
            return new long[]{wall, c.isCountDown() ? 1 : 0, running ? 1 : 0};
        } catch (Throwable t) {
            XLog.e("chronometer RemoteViews", t);
            return null;
        }
    }

    /** First non-chronometer text of a clock app's layout ("Timer", a timer's label, "Stopwatch"). */
    private String clockLabel(Notification n) {
        if (n == null || n.contentView == null) return null;
        try {
            return firstText(n.contentView.apply(ctx, new FrameLayout(ctx)));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A paused stopwatch has to read like the running one (with hundredths), but the app's paused
     * layout only shows whole seconds. The exact value is the pause moment (the update's post time)
     * minus the start remembered while it ran; it's used when it agrees with the app's seconds,
     * otherwise the app's value is used.
     */
    private long pausedStopwatchMs(String itemKey, StatusBarNotification sbn, String frozen) {
        long shown = parseClockMs(frozen);
        if (shown < 0) return -1;
        Long base = stopwatchBases.get(itemKey);
        if (base != null && sbn != null) {
            long exact = sbn.getPostTime() - base;
            if (exact >= shown - 50 && exact < shown + 1050) return Math.max(shown, exact);
        }
        return shown;
    }

    /** "12:34" or "1:02:03" -> milliseconds; -1 if it isn't a plain duration. */
    static long parseClockMs(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.startsWith("-") || !t.matches("[0-9]+(:[0-9]{2}){1,2}")) return -1;
        long total = 0;
        for (String part : t.split(":")) total = total * 60 + Long.parseLong(part);
        return total * 1000;
    }

    private String clockTime(Notification n) {
        if (n == null || n.contentView == null) return null;
        try {
            return firstTime(n.contentView.apply(ctx, new FrameLayout(ctx)));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstTime(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            return t != null && t.toString().trim().matches("-?[0-9]+(:[0-9]{2}){1,2}") ? t.toString().trim() : null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String t = firstTime(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static String firstText(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof android.widget.TextView && !(v instanceof Chronometer)) {
            CharSequence t = ((android.widget.TextView) v).getText();
            return notEmpty(t) && !t.toString().matches("[0-9:.\\s]+") ? t.toString() : null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String t = firstText(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static String pkgOf(Notification n) {
        android.content.pm.ApplicationInfo ai = n.extras.getParcelable("android.appInfo" /* Notification.EXTRA_BUILDER_APPLICATION_INFO */,
                android.content.pm.ApplicationInfo.class);
        return ai == null ? null : ai.packageName;
    }

    private static Chronometer findChronometer(View v) {
        if (v instanceof Chronometer) return (Chronometer) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Chronometer c = findChronometer(g.getChildAt(i));
                if (c != null) return c;
            }
        }
        return null;
    }

    private static IslandItem.Kind classify(Notification n, String pkg) {
        String cat = n == null ? null : n.category;
        if (Notification.CATEGORY_NAVIGATION.equals(cat)) return IslandItem.Kind.NAVIGATION;
        if (Notification.CATEGORY_STOPWATCH.equals(cat) || Notification.CATEGORY_ALARM.equals(cat)
                || "com.sec.android.app.clockpackage".equals(pkg) || "com.android.deskclock".equals(pkg)
                || "com.google.android.deskclock".equals(pkg)) {
            return IslandItem.Kind.TIMER;
        }
        if ("com.sec.android.app.voicenote".equals(pkg)) return IslandItem.Kind.RECORDING;
        if (Notification.CATEGORY_CALL.equals(cat)) return IslandItem.Kind.CALL;
        return IslandItem.Kind.LIVE;
    }

    static boolean isCallStyle(Notification n) {
        String template = n.extras.getString("android.template");
        return template != null && template.endsWith("$CallStyle");
    }

    static boolean isProgressStyle(Notification n) {
        if (n == null) return false;
        String template = n.extras.getString("android.template");
        String compat = n.extras.getString("androidx.core.app.extra.COMPAT_TEMPLATE");
        return (template != null && template.endsWith("$ProgressStyle"))
                || (compat != null && compat.endsWith("$ProgressStyle"));
    }

    static String targetPackage(PendingIntent pi) {
        if (pi == null) return null;
        try {
            Object intent = Refl.call(pi, "getIntent");
            if (intent instanceof Intent) {
                ComponentName c = ((Intent) intent).getComponent();
                if (c != null) return c.getPackageName();
                String p = ((Intent) intent).getPackage();
                if (p != null) return p;
            }
        } catch (Throwable ignored) {
        }
        return pi.getCreatorPackage();
    }

    private final android.util.LruCache<String, Drawable.ConstantState> iconCache = new android.util.LruCache<>(64);
    private final java.util.Set<String> badIcons = new java.util.HashSet<>();

    private Drawable load(Icon icon) {
        return load(icon, null);
    }

    private Drawable load(Icon icon, String pkg) {
        if (icon == null) return null;
        String key = null;
        try {
            if (icon.getType() == Icon.TYPE_RESOURCE) {
                String res = icon.getResPackage();
                if (res == null || res.isEmpty()) {
                    if (pkg == null) return null;
                    icon = Icon.createWithResource(pkg, icon.getResId());
                    res = pkg;
                }
                key = res + "/" + icon.getResId();
                if (badIcons.contains(key)) return null;
                Drawable.ConstantState cs = iconCache.get(key);
                if (cs != null) return cs.newDrawable().mutate();
            }
            Drawable d = icon.loadDrawable(ctx);
            if (d == null) {
                if (key != null) badIcons.add(key);
                return null;
            }
            if (key != null && d.getConstantState() != null) iconCache.put(key, d.getConstantState());
            return d.mutate();
        } catch (Throwable t) {
            if (key != null) badIcons.add(key);
            return null;
        }
    }

    private final java.util.Map<String, String> packageByLabel = new java.util.HashMap<>();
    private long labelsLoadedAt = -1;

    private Drawable copiedAppIcon(String pkg, Notification n) {
        if (n == null || !"com.locnall.KimGiSa".equals(pkg)) return null;
        String channel = n.getChannelId();
        CharSequence source = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (channel == null || !channel.startsWith("livebridge_") || !notEmpty(source)) return null;
        String label = source.toString();
        String target = packageByLabel.get(label);
        long now = android.os.SystemClock.uptimeMillis();
        if (target == null && (labelsLoadedAt < 0 || now - labelsLoadedAt > 60_000)) {
            labelsLoadedAt = now;
            packageByLabel.clear();
            try {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                android.content.Intent launcher = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                    String app = String.valueOf(ri.activityInfo.applicationInfo.loadLabel(pm));
                    packageByLabel.putIfAbsent(app, ri.activityInfo.packageName);
                }
            } catch (Throwable t) {
                XLog.e("app labels failed", t);
            }
            target = packageByLabel.get(label);
        }
        return target == null ? null : appIcon(target);
    }

    Drawable appIcon(String pkg) {
        if (pkg == null) return null;
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    static final int RECORDER_RED = 0xFFFF5A55;

    private static boolean isCancel(CharSequence title) {
        if (title == null) return false;
        String s = title.toString().toLowerCase(java.util.Locale.ROOT);
        return s.contains("cancel") || s.contains("отмен") || s.contains("discard") || s.contains("удал");
    }

    static boolean isDestructive(CharSequence title) {
        if (title == null) return false;
        String[] stems = {"stop", "cancel", "end", "decline", "hang", "delete", "dismiss", "reject",
                "стоп", "останов", "отмен", "заверш", "отклон", "сброс", "удал"};
        for (String word : title.toString().toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}]+")) {
            for (String stem : stems) if (word.startsWith(stem)) return true;
        }
        return false;
    }

    private static boolean notEmpty(CharSequence s) {
        return s != null && s.length() > 0;
    }
}
