package it.ustyle.portal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Receives pushes from FCM (US-1612).
 *
 * The portal's news engine already ranks what is worth telling a client about
 * (lib/portal/news.ts) — attribution wins, then the weekly digest, then
 * generation-finished. The SEND side lives on the server; this class only
 * decides how a delivered message looks and where tapping it goes.
 */
public class PushService extends FirebaseMessagingService {

    /** One channel, so a client can silence Ustyle without silencing everything. */
    static final String CHANNEL_ID = "ustyle_portal";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        // Fires on install, restore and whenever FCM rotates the token. Without
        // this the server keeps pushing to a token that is already dead.
        PushRegistrar.onTokenRefreshed(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);

        Map<String, String> data = message.getData();
        String title = firstNonEmpty(
                message.getNotification() != null ? message.getNotification().getTitle() : null,
                data.get("title"), getString(R.string.app_name));
        String body = firstNonEmpty(
                message.getNotification() != null ? message.getNotification().getBody() : null,
                data.get("body"), "");
        // A push is only useful if it lands the client somewhere specific, so
        // the payload may name a portal path (e.g. /portal/gallery?status=pending).
        String path = data.get("path");

        show(title, body, path);
    }

    private void show(String title, String body, String path) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        createChannel(manager);

        // Route through MainActivity rather than a browser: the app IS the
        // portal, and opening Chrome would drop the session and the shell.
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (path != null && !path.isEmpty()) {
            intent.setData(Uri.parse("https://ustyle.it" + path));
        }

        PendingIntent pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        manager.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), notification);
    }

    static void createChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager == null) return;
        // Creating an existing channel is a no-op, so this is safe to repeat.
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Ustyle", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Orders your looks influenced, and your weekly summary.");
        manager.createNotificationChannel(channel);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    static void createChannel(Context context) {
        createChannel(context.getSystemService(NotificationManager.class));
    }
}
