package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

/**
 * Regular notifications presented by the island instead of a heads-up: sender, message and the
 * app's actions, with Reply for actions that take free-form text (RemoteInput).
 */
final class NotifSource {
    interface ReplyStarter {
        void startReply(IslandItem item, Notification.Action action);
    }

    private final Context ctx;
    private final ReplyStarter replies;

    NotifSource(Context ctx, ReplyStarter replies) {
        this.ctx = ctx;
        this.replies = replies;
    }

    /** Whether the island should take this heads-up. Calls, alarms, media and ongoing ones stay with the system. */
    static boolean candidate(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_GROUP_SUMMARY)) != 0) return false;
        if (n.fullScreenIntent != null) return false;
        if (Notification.CATEGORY_CALL.equals(n.category) || Notification.CATEGORY_ALARM.equals(n.category)) return false;
        Bundle x = n.extras;
        if (x.containsKey(Notification.EXTRA_MEDIA_SESSION) || OaSource.isCallStyle(n)) return false;
        return x.getCharSequence(Notification.EXTRA_TITLE) != null || x.getCharSequence(Notification.EXTRA_TEXT) != null
                || x.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class) != null;
    }

    IslandItem build(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        String pkg = sbn.getPackageName();
        IslandItem it = new IslandItem("notif:" + sbn.getKey());
        it.notification = true;
        it.notificationKey = sbn.getKey();
        it.packageName = pkg;
        it.userId = sbn.getUid() / 100_000; // UserHandle.PER_USER_RANGE
        it.postTime = sbn.getPostTime();
        it.appName = label(pkg);
        it.accent = n.color == 0 ? 0xFFFFFFFF : Colors.readableOnBlack(n.color);
        it.contentIntent = n.contentIntent;

        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = x.getCharSequence(Notification.EXTRA_TEXT);
        Drawable image = null;

        // MessagingStyle: show the latest message and its sender
        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            Bundle last = (Bundle) messages[messages.length - 1];
            CharSequence body = last.getCharSequence("text");
            Person sender = last.getParcelable("sender_person", Person.class);
            CharSequence senderName = sender != null ? sender.getName() : last.getCharSequence("sender");
            CharSequence convo = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            boolean group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
            if (body != null) {
                if (group && senderName != null) {
                    title = convo != null ? convo : title;
                    text = senderName + ": " + body;
                } else {
                    if (senderName != null) title = senderName;
                    text = body;
                }
            }
            if (sender != null && sender.getIcon() != null) image = load(sender.getIcon());
        }
        it.title = title;
        it.subtitle = text;

        if (image == null && n.getLargeIcon() != null) image = load(n.getLargeIcon());
        it.cardImageRound = image != null;
        if (image == null) image = appIcon(pkg);
        it.cardImage = image;
        it.cardImageIsImage = true;
        // kept on the island after the card folds back: the app's (colour) icon and who it's from
        it.kind = IslandItem.Kind.NOTIFICATION;
        it.icon = appIcon(pkg);
        it.iconIsImage = true;
        it.iconColor = true;
        if (it.icon == null && n.getSmallIcon() != null) {
            it.icon = load(n.getSmallIcon());
            it.iconIsImage = false;
            it.iconColor = false;
        }
        if (title != null) {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = title;
        }

        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a == null || a.title == null || a.isContextual()) continue;
                if (isFreeFormReply(a)) {
                    IslandItem.Action reply = new IslandItem.Action(a.title, null, null,
                            () -> replies.startReply(it, a), false);
                    reply.emphasized = true;
                    it.actions.add(reply);
                } else if (a.actionIntent != null) {
                    it.actions.add(new IslandItem.Action(a.title, null, a.actionIntent, null, OaSource.isDestructive(a.title)));
                }
                if (it.actions.size() == 3) break;
            }
        }
        return it;
    }

    static boolean isFreeFormReply(Notification.Action a) {
        RemoteInput[] inputs = a.getRemoteInputs();
        if (inputs == null || a.actionIntent == null) return false;
        for (RemoteInput r : inputs) if (r.getAllowFreeFormInput()) return true;
        return false;
    }

    private Drawable load(Icon icon) {
        try {
            Drawable d = icon.loadDrawable(ctx);
            return d == null ? null : d.mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    private Drawable appIcon(String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }
}
