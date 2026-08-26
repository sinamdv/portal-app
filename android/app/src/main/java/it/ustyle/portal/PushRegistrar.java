package it.ustyle.portal;

import android.util.Log;
import android.webkit.CookieManager;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Registers this device's FCM token against the signed-in client (US-1612).
 *
 * The portal page is remote and unmodified, so it cannot hand us a client id.
 * Instead the SESSION COOKIE is the identity: it is read from the WebView's
 * native cookie jar and replayed on a plain POST to /api/portal/devices, which
 * authenticates it exactly like any other portal request. Nothing about the
 * portal changes to support this.
 *
 * `portal_session` is httpOnly, which hides it from document.cookie but NOT
 * from CookieManager — that is the native store the WebView itself writes to.
 * (Proven in practice: PortalLock only prompts when it finds this cookie, and
 * it prompts.)
 */
final class PushRegistrar {

    private static final String TAG = "UstylePush";
    private static final String ENDPOINT = "https://ustyle.it/api/portal/devices";
    private static final String COOKIE_HOST = "https://ustyle.it";

    /** Cheap guard so a resume storm does not fire the same POST repeatedly. */
    private static String lastRegisteredToken = null;

    private PushRegistrar() {}

    /**
     * Safe to call on every resume: it no-ops without a session, and skips the
     * network when the same token was already registered this process.
     */
    static void registerIfPossible() {
        if (!PortalLock.hasSession()) return; // signed out — nothing to attach a token to

        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Log.w(TAG, "no FCM token yet", task.getException());
                return;
            }
            String token = task.getResult();
            if (token.equals(lastRegisteredToken)) return;
            post(token);
        });
    }

    /** Called by {@link PushService} when FCM rotates the token. */
    static void onTokenRefreshed(String token) {
        lastRegisteredToken = null; // a rotated token must always be re-sent
        if (PortalLock.hasSession()) post(token);
    }

    private static void post(String token) {
        Executors.newSingleThreadExecutor().execute(() -> {
            HttpURLConnection conn = null;
            try {
                String cookies = CookieManager.getInstance().getCookie(COOKIE_HOST);
                if (cookies == null || !cookies.contains("portal_session=")) return;

                JSONObject payload = new JSONObject()
                        .put("platform", "android")
                        .put("token", token);

                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                // HttpURLConnection does NOT share the WebView's cookie jar, so
                // the session has to be attached by hand.
                conn.setRequestProperty("Cookie", cookies);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload.toString().getBytes("UTF-8"));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    lastRegisteredToken = token;
                    Log.i(TAG, "device registered");
                } else {
                    // 401 simply means the session expired between the check and
                    // the call; the next resume retries. Anything else is worth
                    // seeing, because silence here means the client never
                    // receives a notification again.
                    Log.w(TAG, "registration failed: HTTP " + code);
                }
            } catch (Exception e) {
                Log.w(TAG, "registration error", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
