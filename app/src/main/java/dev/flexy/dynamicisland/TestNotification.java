package dev.flexy.dynamicisland;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;

/**
 * A heads-up message with a Reply action, for trying notifications in the island. Replies are
 * added to the conversation, like a messaging app would.
 */
public final class TestNotification {
    private static final String CHANNEL = "island_test";
    private static final int ID = 7101;
    private static final String KEY_REPLY = "reply";
    private static final String ACTION_REPLY = "dev.flexy.dynamicisland.TEST_REPLY";
    private static final String ACTION_READ = "dev.flexy.dynamicisland.TEST_READ";

    private TestNotification() {
    }

    public static void post(Context ctx, CharSequence reply) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Island test", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);

        Person me = new Person.Builder().setName("You").build();
        Person anna = new Person.Builder().setName("Anna").setKey("anna").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me);
        long now = System.currentTimeMillis();
        style.addMessage("Hey! This is a test message from the island 👋", now - 1000, anna);
        if (reply != null) {
            style.addMessage(reply, now, me);
            style.addMessage("Got it: “" + reply + "”", now + 1, anna);
        }

        RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel("Reply to Anna").build();
        Intent replyIntent = new Intent(ACTION_REPLY).setClass(ctx, Receiver.class);
        PendingIntent replyPi = PendingIntent.getBroadcast(ctx, 1, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action replyAction = new Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_menu_send), "Reply", replyPi)
                .addRemoteInput(input)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .build();
        PendingIntent readPi = PendingIntent.getBroadcast(ctx, 2, new Intent(ACTION_READ).setClass(ctx, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action readAction = new Notification.Action.Builder(
                Icon.createWithResource(ctx, android.R.drawable.ic_menu_view), "Mark as read", readPi).build();
        PendingIntent open = PendingIntent.getActivity(ctx, 3, new Intent(ctx, SettingsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setStyle(style)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .addAction(replyAction)
                .addAction(readAction)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(reply != null)
                .build();
        nm.notify(ID, n);
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (ACTION_READ.equals(intent.getAction())) {
                ctx.getSystemService(NotificationManager.class).cancel(ID);
                return;
            }
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = results == null ? null : results.getCharSequence(KEY_REPLY);
            if (text != null) post(ctx, text);
        }
    }
}
