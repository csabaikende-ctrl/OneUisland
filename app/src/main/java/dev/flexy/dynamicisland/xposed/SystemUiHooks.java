package dev.flexy.dynamicisland.xposed;

import android.app.Application;
import android.app.Instrumentation;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;

import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import dev.flexy.dynamicisland.island.IslandController;

final class SystemUiHooks {
    private static final String SUI = "com.android.systemui";
    private static final String OA = "com.android.systemui.statusbar.phone.ongoingactivity.";

    private static volatile Object oaController;
    private static volatile Object callController;
    private static volatile Object castController;
    private static volatile Object castBundle;
    private static volatile Object notifCollection;
    private static volatile int barState;
    private static volatile boolean dozing;
    private static volatile int windowState;

    private static int idCapsule;
    private static int idCapsuleContainer;
    private static int idChipsContainer;
    private static int idCastChip;
    private static int idCallChip;
    private static int idAospChipPrimary;
    private static int idAospChipSecondary;

    private SystemUiHooks() {
    }

    static void install(ClassLoader cl) {
        hookInit(cl);
        hookStatusBarView(cl);
        hookCapsuleHiding(cl);
        hookDataChanges(cl);
        hookCalls(cl);
        hookCast(cl);
        hookSignals(cl);
        if (Build.VERSION.SDK_INT == 36) hookAndroid16Chips(cl);
        hookAospNotifications(cl);
        hookHeadsUpDecision(cl);
        ShadePerf.install(cl);
        ShadeLayoutCallbacks.install();
        ShadeLiveCards.install(cl);
        HideDuplicated.install(cl);
        TorchNotificationDelay.install(cl);
        ShadeCardTheme.install(cl);
        ShadeMediaPlayer.install(cl);
        XLog.i("SystemUI hooks installed");
    }

    private static IslandController c() {
        return IslandController.get();
    }

    private static void refresh() {
        IslandController c = c();
        if (c != null) c.requestRefresh();
    }


    private static void hookInit(ClassLoader cl) {
        XposedBridge.hookAllMethods(Instrumentation.class, "callApplicationOnCreate",
                SafeHook.before("app onCreate", p -> {
                    if (!(p.args[0] instanceof Application)) return;
                    Application app = (Application) p.args[0];
                    if (!SUI.equals(app.getPackageName())) return;
                    resolveIds(app.getResources());
                    IslandController c = IslandController.init(app, cl);
                    if (oaController != null) c.setOngoingActivityController(oaController);
                    if (callController != null) c.setCallController(callController);
                    if (castController != null) c.setCastController(castController);
                    if (castBundle != null) c.setCastBundle(castBundle);
                    if (notifCollection != null) c.setNotifCollection(notifCollection);
                    c.setBarState(barState);
                    c.setDozing(dozing);
                    c.setStatusBarWindowState(windowState);
                }));
    }

    private static void resolveIds(Resources res) {
        idCapsule = res.getIdentifier("ongoing_activity_capsule", "id", SUI);
        idCapsuleContainer = res.getIdentifier("samsung_ongoing_activity_chip_container", "id", SUI);
        idChipsContainer = res.getIdentifier("samsung_status_bar_chips_container", "id", SUI);
        idCastChip = res.getIdentifier("samsung_ongoing_cast_chip", "id", SUI);
        idCallChip = res.getIdentifier("ongoing_call_chip", "id", SUI);
        idAospChipPrimary = res.getIdentifier("ongoing_activity_chip_primary", "id", SUI);
        idAospChipSecondary = res.getIdentifier("ongoing_activity_chip_secondary", "id", SUI);
    }

    private static void hookStatusBarView(ClassLoader cl) {
        String ctrl = "com.android.systemui.statusbar.phone.PhoneStatusBarViewController";
        Refl.hookAll(cl, ctrl, "onViewAttached", SafeHook.after("onViewAttached", p -> {
            Object v = Refl.get(p.thisObject, "mView");
            IslandController c = c();
            if (v instanceof View && c != null) c.onStatusBarViewAttached((View) v);
            if (v instanceof View) hideAospChips((View) v);
        }));
        Refl.hookAll(cl, ctrl, "onViewDetached", SafeHook.after("onViewDetached", p -> {
            Object v = Refl.get(p.thisObject, "mView");
            IslandController c = c();
            if (v instanceof View && c != null) c.onStatusBarViewDetached((View) v);
        }));
    }

    private static void hookCapsuleHiding(ClassLoader cl) {
        Refl.hookAll(cl, "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl",
                "adjustVisibility", SafeHook.before("adjustVisibility", p -> {
                    if (p.args.length < 1 || !(p.args[0] instanceof View)) return;
                    View v = (View) p.args[0];
                    int id = v.getId();
                    if (id != View.NO_ID && (id == idCapsuleContainer || id == idChipsContainer
                            || id == idCastChip || id == idCallChip)) {
                        v.animate().cancel();
                        v.setVisibility(View.GONE);
                        p.setResult(null);
                    }
                }));

        Refl.hookAll(cl, OA + "OngoingActivityController", "updateParentViewVisibility",
                SafeHook.before("updateParentViewVisibility", p -> {
                    Object ctrl = p.thisObject;
                    Object parent = Refl.get(ctrl, "mParentView");
                    if (parent instanceof View) {
                        ((View) parent).animate().cancel();
                        ((View) parent).setVisibility(View.GONE);
                    }
                    Object flow = Refl.get(ctrl, "showingOAChip");
                    if (flow != null) Refl.call(flow, "updateState", null, Boolean.FALSE);
                    p.setResult(null);
                    refresh();
                }));

        Refl.hookAll(cl, OA + "OngoingActivityController", "isChipExpandCondition",
                SafeHook.before("isChipExpandCondition", p -> p.setResult(Boolean.FALSE)));

        String anim = OA + "ChipAnimationController";
        Refl.hookAll(cl, anim, "handleOnGoingActivityChipAnimation",
                SafeHook.before("handleOnGoingActivityChipAnimation", p -> p.setResult(null)));
        Refl.hookAll(cl, anim, "clockViewNudgeAnimation",
                SafeHook.before("clockViewNudgeAnimation", p -> p.setResult(null)));
        Refl.hookAll(cl, anim, "animateChipShow", SafeHook.before("animateChipShow", p -> {
            if (p.args.length > 0 && p.args[0] instanceof View && ((View) p.args[0]).getId() == idCapsule) {
                ((View) p.args[0]).setVisibility(View.GONE);
                p.setResult(null);
            }
        }));
        Refl.hookAll(cl, anim, "handleOnGoingCallChipAnimation",
                SafeHook.before("handleOnGoingCallChipAnimation", p -> p.setResult(null)));

        Refl.hookAll(cl, OA + "OngoingActivityController", "sendChipInfoForAppExitAnimation",
                SafeHook.before("sendChipInfoForAppExitAnimation", p -> {
                    oaController = p.thisObject;
                    IslandController c = c();
                    if (c == null) return;
                    c.onExitTargetsRequested();
                    p.setResult(null);
                }));
    }

    private static void hookAndroid16Chips(ClassLoader cl) {
        Refl.hookAll(cl, "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment", "animateShow",
                SafeHook.before("animateShow (A16)", p -> {
                    if (p.args.length < 1 || !(p.args[0] instanceof View)) return;
                    View v = (View) p.args[0];
                    int id = v.getId();
                    if (id != View.NO_ID && (id == idCastChip || id == idCallChip)) {
                        v.animate().cancel();
                        v.setVisibility(View.GONE);
                        p.setResult(null);
                    }
                }));

        String call = "com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController";
        Refl.hookConstructors(cl, call, SafeHook.after("call ctor (A16)", p -> {
            callController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setCallController(p.thisObject);
        }));
        SafeHook refreshHook = SafeHook.after("call change (A16)", p -> refresh());
        for (String m : new String[]{"sendStateChangeEvent", "updateChip", "removeChipInfo"}) {
            Refl.hookAll(cl, call, m, refreshHook);
        }
    }

    /**
     * AOSP (Android 16) status bar chips for calls, promoted notifications, screen recording and
     * casting. The island shows these itself, so the chips are kept hidden: whatever binder shows
     * them, they are put back to GONE before they draw.
     */
    private static void hideAospChips(View statusBar) {
        for (int id : new int[]{idAospChipPrimary, idAospChipSecondary}) {
            if (id == 0) continue;
            View chip = statusBar.findViewById(id);
            if (chip == null || chip.getTag(TAG_CHIP_HIDER) != null) continue;
            chip.setTag(TAG_CHIP_HIDER, Boolean.TRUE);
            chip.setVisibility(View.GONE);
            chip.getViewTreeObserver().addOnPreDrawListener(() -> {
                if (chip.getVisibility() != View.GONE) chip.setVisibility(View.GONE);
                return true;
            });
        }
    }

    private static final int TAG_CHIP_HIDER = 0x7e0c4155;

    private static final String VIDP = "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl";

    /**
     * Notifications go to the island instead of a heads-up: when SystemUI decides a notification
     * should pop up, the island is offered it first and, if it takes it, the decision becomes
     * "don't interrupt". Both the logged (new posts) and unlogged (updates) decisions are covered.
     */
    private static void hookHeadsUpDecision(ClassLoader cl) {
        Class<?> impl = Refl.findClass(VIDP + "$DecisionImpl", cl);
        java.lang.reflect.Constructor<?> ctor;
        try {
            ctor = impl == null ? null : impl.getDeclaredConstructor(String.class, boolean.class);
            if (ctor != null) ctor.setAccessible(true);
        } catch (Throwable t) {
            XLog.e("heads-up DecisionImpl", t);
            ctor = null;
        }
        if (ctor == null) return;
        final java.lang.reflect.Constructor<?> decision = ctor;
        SafeHook hook = SafeHook.after("heads-up decision", p -> {
            Object r = p.getResult();
            if (r == null || !Boolean.TRUE.equals(Refl.call(r, "getShouldInterrupt"))) return;
            Object sbn = p.args.length > 0 ? Refl.get(p.args[0], "mSbn") : null;
            IslandController c = c();
            if (!(sbn instanceof android.service.notification.StatusBarNotification) || c == null) return;
            if (c.offerHeadsUp((android.service.notification.StatusBarNotification) sbn)) {
                p.setResult(decision.newInstance("shown in dynamic island", false));
            }
        });
        Refl.hookAll(cl, VIDP, "makeAndLogHeadsUpDecision", hook);
        Refl.hookAll(cl, VIDP, "makeUnloggedHeadsUpDecision", hook);
    }

    private static void hookAospNotifications(ClassLoader cl) {
        String coll = "com.android.systemui.statusbar.notification.collection.NotifCollection";
        Refl.hookConstructors(cl, coll, SafeHook.after("notif collection ctor", p -> {
            notifCollection = p.thisObject;
            IslandController c = c();
            if (c != null) c.setNotifCollection(p.thisObject);
        }));
        Refl.hookAll(cl, coll, "dispatchEventsAndRebuildList", SafeHook.after("notif collection change", p -> refresh()));
    }

    private static void hookDataChanges(ClassLoader cl) {
        Refl.hookConstructors(cl, OA + "OngoingActivityController", SafeHook.after("OA ctor", p -> {
            oaController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setOngoingActivityController(p.thisObject);
        }));
        SafeHook refreshHook = SafeHook.after("OA change", p -> refresh());
        String[] ctrlMethods = {"onAsyncInflationFinished", "OaDataListChanged", "add$1", "update", "update$1$1",
                "remove", "updateMediaChipData", "removeOngoingActivityData", "killGhost"};
        for (String m : ctrlMethods) Refl.hookAll(cl, OA + "OngoingActivityController", m, refreshHook);
        String[] helperMethods = {"removeOngoingActivityByKey", "updateOngoingList", "notifyUpdateItemNowbarObservers",
                "notifyRemoveItemNowbarObservers", "setBaseActivityComponentName", "notifyRankingStateChanged"};
        for (String m : helperMethods) Refl.hookAll(cl, OA + "OngoingActivityDataHelper", m, refreshHook);
    }

    private static void hookCalls(ClassLoader cl) {
        String call = "com.android.systemui.statusbar.phone.ongoingcall.SamsungOngoingCallController";
        Refl.hookConstructors(cl, call, SafeHook.after("call ctor", p -> {
            callController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setCallController(p.thisObject);
        }));
        SafeHook refreshHook = SafeHook.after("call change", p -> refresh());
        for (String m : new String[]{"sendStateChangeEvent", "updateChip", "removeChipInfo"}) {
            Refl.hookAll(cl, call, m, refreshHook);
        }
    }

    private static void hookCast(ClassLoader cl) {
        String cast = "com.android.systemui.statusbar.phone.ongoingcast.";
        Refl.hookConstructors(cl, cast + "SamsungOngoingCastViewModel$SamsungCastChipBundle",
                SafeHook.after("cast bundle", p -> {
                    castBundle = p.thisObject;
                    IslandController c = c();
                    if (c != null) c.setCastBundle(p.thisObject);
                }));
        Refl.hookConstructors(cl, cast + "SamsungOngoingCastController", SafeHook.after("cast ctor", p -> {
            castController = p.thisObject;
            IslandController c = c();
            if (c != null) c.setCastController(p.thisObject);
        }));
    }

    private static volatile boolean headsUpTracking;

    private static void hookSignals(ClassLoader cl) {
        Refl.hookConstructors(cl, "com.android.systemui.shade.ShadeExpansionStateManager",
                SafeHook.after("shade ctor", p -> {
                    Class<?> listener = Refl.findClass("com.android.systemui.shade.ShadeExpansionListener", cl);
                    if (listener == null) return;
                    Object proxy = Refl.proxy(listener, (pr, m, a) -> {
                        if ("onPanelExpansionChanged".equals(m.getName()) && a != null && a.length == 1) {
                            Object f = Refl.get(a[0], "fraction");
                            IslandController c = c();
                            if (!(f instanceof Float) || c == null) return null;
                            float fraction = (Float) f;
                            if (!Refl.getBool(a[0], "expanded", true) && !Refl.getBool(a[0], "tracking", false)) fraction = 0f;
                            c.setShadeExpansion(fraction);
                        }
                        return null;
                    });
                    Refl.call(p.thisObject, "addExpansionListener", proxy);
                }));
        SafeHook sleepHook = SafeHook.after("started going to sleep", p -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            IslandController c = c();
            if (c != null) c.onStartedGoingToSleep();
        }));
        SafeHook wakeHook = SafeHook.after("started waking up", p -> new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            IslandController c = c();
            if (c != null) c.onStartedWakingUp();
        }));
        int sleepHooks = Refl.hookAll(cl, "com.android.systemui.keyguard.KeyguardViewMediator", "onStartedGoingToSleep", sleepHook);
        int wakeHooks = Refl.hookAll(cl, "com.android.systemui.keyguard.KeyguardViewMediator", "onStartedWakingUp", wakeHook);
        // AOSP 16 (LineageOS): the mediator methods are gone; the window manager calls the keyguard
        // service binder directly.
        if (sleepHooks == 0) Refl.hookAll(cl, "com.android.systemui.keyguard.KeyguardService$2", "onStartedGoingToSleep", sleepHook);
        if (wakeHooks == 0) Refl.hookAll(cl, "com.android.systemui.keyguard.KeyguardService$2", "onStartedWakingUp", wakeHook);
        Refl.hookConstructors(cl, "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl",
                SafeHook.after("heads-up manager", p -> {
                    java.lang.ref.WeakReference<Object> hum = new java.lang.ref.WeakReference<>(p.thisObject);
                    dev.flexy.dynamicisland.island.IslandView.headsUpProbe = () -> {
                        Object h = hum.get();
                        return headsUpTracking || (h != null && Boolean.TRUE.equals(Refl.call(h, "hasNotifications")));
                    };
                }));
        Refl.hookAll(cl, "com.android.systemui.statusbar.notification.headsup.HeadsUpTouchHelper", "setTrackingHeadsUp",
                SafeHook.after("heads-up tracking", p -> {
                    if (p.args.length == 2 && p.args[1] instanceof Boolean) headsUpTracking = (Boolean) p.args[1];
                }));
        Refl.hookAll(cl, "com.android.systemui.shade.NotificationPanelViewController$ShadeHeadsUpChangedListener",
                "onHeadsUpPinnedModeChanged", SafeHook.after("heads-up pinned", p -> {
                    IslandController c = c();
                    if (c != null && p.args.length == 1 && p.args[0] instanceof Boolean) c.setHeadsUpPinned((Boolean) p.args[0]);
                }));
        Refl.hookAll(cl, "com.android.systemui.shade.ShadeExpansionStateManager", "updateStateInternal",
                SafeHook.after("shade state", p -> {
                    if (p.args.length != 1 || !(p.args[0] instanceof Integer)) return;
                    IslandController c = c();
                    if (c == null) return;
                    int state = (Integer) p.args[0];
                    c.setPanelState(state);
                    if (state == 0) c.setShadeExpansion(0f);
                }));

        Refl.hookConstructors(cl, "com.android.systemui.statusbar.StatusBarStateControllerImpl",
                SafeHook.after("state ctor", p -> {
                    Class<?> listener = Refl.findClass(
                            "com.android.systemui.plugins.statusbar.StatusBarStateController$StateListener", cl);
                    if (listener == null) return;
                    Object proxy = Refl.proxy(listener, (pr, m, a) -> {
                        IslandController c = c();
                        if ("onStateChanged".equals(m.getName()) && a != null && a.length == 1 && a[0] instanceof Integer) {
                            barState = (Integer) a[0];
                            if (c != null) c.setBarState(barState);
                        } else if ("onDozingChanged".equals(m.getName()) && a != null && a.length == 1) {
                            dozing = Boolean.TRUE.equals(a[0]);
                            if (c != null) c.setDozing(dozing);
                        }
                        return null;
                    });
                    Refl.call(p.thisObject, "addCallback", proxy);
                    Object state = Refl.get(p.thisObject, "mState");
                    if (state instanceof Integer) barState = (Integer) state;
                }));

        // AOSP 16 builds may have the controller's constructor optimized away; its CommandQueue
        // callback still receives setWindowState(displayId, window, state).
        Refl.hookAll(cl, "com.android.systemui.statusbar.window.StatusBarWindowStateController$commandQueueCallback$1",
                "setWindowState", SafeHook.after("window state (A16)", p -> {
                    if (p.args.length != 3 || !Integer.valueOf(0).equals(p.args[0])
                            || !Integer.valueOf(1).equals(p.args[1]) || !(p.args[2] instanceof Integer)) return;
                    windowState = (Integer) p.args[2];
                    IslandController c = c();
                    if (c != null) c.setStatusBarWindowState(windowState);
                }));
        Refl.hookConstructors(cl, "com.android.systemui.statusbar.window.StatusBarWindowStateController",
                SafeHook.after("window state ctor", p -> {
                    if (!(p.args.length > 0 && Integer.valueOf(0).equals(p.args[0]))) return;
                    Class<?> listener = Refl.findClass("com.android.systemui.statusbar.window.StatusBarWindowStateListener", cl);
                    Object set = Refl.get(p.thisObject, "listeners");
                    if (listener == null || !(set instanceof Set)) return;
                    Object proxy = Refl.proxy(listener, (pr, m, a) -> {
                        if ("onStatusBarWindowStateChanged".equals(m.getName()) && a != null && a.length == 1
                                && a[0] instanceof Integer) {
                            windowState = (Integer) a[0];
                            IslandController c = c();
                            if (c != null) c.setStatusBarWindowState(windowState);
                        }
                        return null;
                    });
                    @SuppressWarnings("unchecked")
                    Set<Object> s = (Set<Object>) set;
                    s.add(proxy);
                }));
    }
}
