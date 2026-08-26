package it.ustyle.portal;

import android.os.SystemClock;
import android.view.View;
import android.webkit.CookieManager;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

/**
 * Biometric lock for the portal shell (US-1612, Guideline 4.2).
 *
 * WHAT IT PROTECTS, and why that is not what the ticket literally described:
 *
 * The ticket says to store the portal CREDENTIALS in the Keystore after first
 * login. Doing that would mean injecting JavaScript into the live login form to
 * scrape a password — fragile (it breaks the day the form changes) and a real
 * security decision that nobody has signed off. What it buys is silent re-login
 * after the 7-day session expiry.
 *
 * This instead gates the SESSION that is already there. The session cookie
 * survives process death (proven in the Phase 0 spike), so in practice the app
 * is signed in and the lock is what stands between someone holding the phone and
 * the client's revenue figures. Storing credentials remains an open decision —
 * see the README.
 *
 * The lock is NOT shown when there is no session: the portal's own login screen
 * is already the gate, and prompting for a fingerprint to reach a login form is
 * theatre.
 */
class PortalLock {

    /** Re-lock only after a real absence — locking on a 2-second app switch is hostile. */
    private static final long GRACE_MS = 15_000L;

    private final FragmentActivity activity;
    private final View overlay;

    private boolean unlocked = false;
    private boolean prompting = false;
    private long backgroundedAt = 0L;

    PortalLock(FragmentActivity activity, View overlay) {
        this.activity = activity;
        this.overlay = overlay;
        overlay.findViewById(R.id.lock_unlock).setOnClickListener(v -> prompt());
    }

    /** True when the device can actually satisfy a prompt — biometric OR a PIN. */
    private boolean canAuthenticate() {
        int allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        return BiometricManager.from(activity).canAuthenticate(allowed)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Signed in? Read from the native cookie store rather than the page.
     * httpOnly hides a cookie from document.cookie, NOT from CookieManager,
     * which is the native jar the WebView itself writes to.
     */
    static boolean hasSession() {
        String cookies = CookieManager.getInstance().getCookie("https://ustyle.it");
        return cookies != null && cookies.contains("portal_session=");
    }

    /** A device with no biometric and no PIN must never be locked out of the app. */
    private boolean shouldLock() {
        return hasSession() && canAuthenticate();
    }

    void onCreate() {
        if (shouldLock()) lockNow();
    }

    /**
     * Cover the content BEFORE the app leaves the foreground, so the lock — not
     * the dashboard — is what appears in the recents/task-switcher preview.
     */
    void onPause() {
        backgroundedAt = SystemClock.elapsedRealtime();
        if (shouldLock()) lockNow();
    }

    void onResume() {
        if (!shouldLock()) {          // signed out, or the device lost its enrolment
            hide();
            return;
        }
        boolean returningFromBackground =
                backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt < GRACE_MS;
        if (unlocked && returningFromBackground) {
            hide();                    // brief app switch — do not re-prompt
            return;
        }
        unlocked = false;
        prompt();
    }

    private void lockNow() {
        unlocked = false;
        overlay.setVisibility(View.VISIBLE);
    }

    private void hide() {
        unlocked = true;
        overlay.setVisibility(View.GONE);
    }

    private void prompt() {
        if (prompting) return;         // onResume can fire twice; two prompts stack badly
        prompting = true;
        overlay.setVisibility(View.VISIBLE);

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_prompt_title))
                .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
                // DEVICE_CREDENTIAL is the fallback, so a phone with no enrolled
                // fingerprint still opens with its PIN. Note that allowing it
                // forbids a negative button — setting both throws.
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        BiometricPrompt biometricPrompt = new BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        prompting = false;
                        hide();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        prompting = false;
                        // Cancelling leaves the overlay up with its own Unlock
                        // button rather than closing the app: the client may be
                        // handing the phone to someone, and a hard exit loses
                        // whatever they were reading.
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // A single unrecognised finger — the system prompt stays
                        // open and handles its own retry. Nothing to do here.
                    }
                });

        biometricPrompt.authenticate(info);
    }
}
