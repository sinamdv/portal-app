package it.ustyle.portal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

/**
 * Ustyle portal shell (US-1612).
 *
 * The webview loads the LIVE portal (server.url), which is the unmodified web
 * app and carries no Capacitor JS. Everything native is therefore driven from
 * here rather than called by the page — which is also why the portal needs no
 * changes to gain any of it.
 *
 * Guideline 4.2 features owned by this class: the offline state, pull-to-refresh
 * and status-bar handling. Biometric lock lives in {@link PortalLock}.
 *
 * OFFLINE STATE — Apple rejects a wrapper that shows a blank or error webview
 * with no network; without this the client sees Chromium's
 * "net::ERR_INTERNET_DISCONNECTED". The overlay is a NATIVE view for a reason
 * that is not stylistic: when the web is what failed to load, a web-rendered
 * offline page cannot be shown. It sits ON TOP of the webview rather than
 * replacing it, so an already-loaded page survives underneath and is still there
 * when the connection returns.
 */
public class MainActivity extends BridgeActivity {

    private View offlineOverlay;
    private PortalLock lock;
    private SwipeRefreshLayout refreshLayout;
    private ConnectivityManager connectivity;
    private ActivityResultLauncher<String> notificationPermission;
    private ConnectivityManager.NetworkCallback networkCallback;

    /** Written from the ConnectivityManager thread, read from the UI thread. */
    private volatile boolean online = true;
    /** True once a page has actually painted, so recovery can reload rather than re-navigate. */
    private volatile boolean hasLoadedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        connectivity = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        applyStatusBarAppearance();
        addPullToRefresh();
        addOfflineOverlay();
        lock = new PortalLock(this, addLockOverlay());

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView webView) {
                stopRefreshSpinner();
                // Chromium's OWN error page is a fully loaded page as far as the
                // webview is concerned, so this fires while offline too. Without
                // the guard the overlay is shown and then instantly hidden,
                // leaving the client on net::ERR_INTERNET_DISCONNECTED.
                if (!online) return;
                hasLoadedOnce = true;
                setOverlayVisible(false);
            }

            @Override
            public void onReceivedError(WebView webView) {
                stopRefreshSpinner();
                // Capacitor fires this for EVERY failed resource, not just the
                // main frame, and the listener is not handed the request — so it
                // cannot tell a dead page from one missing image. Connectivity
                // is the guard: without it a single failed thumbnail would
                // cover a working portal with a full-screen "you are offline".
                if (!online) setOverlayVisible(true);
            }
        });

        online = hasValidatedInternet();
        if (!online) setOverlayVisible(true);
        watchConnectivity();

        setUpPush();
        lock.onCreate();
    }

    @Override
    public void onPause() {
        // Cover the content BEFORE the app leaves the foreground, so the lock —
        // not the client's revenue — is what the recents preview shows.
        if (lock != null) lock.onPause();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (lock != null) lock.onResume();
        // Cheap and idempotent: no-ops without a session, and skips the network
        // when this token was already registered.
        PushRegistrar.registerIfPossible();
    }

    /**
     * Push setup (US-1612). The permission prompt is asked for ONCE, on first
     * launch — Android 13+ drops notifications silently without it, and there is
     * no second chance from code if the client declines.
     */
    private void setUpPush() {
        PushService.createChannel(this);
        notificationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { /* declined is a valid answer; the app works without it */ });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // ── Native chrome ────────────────────────────────────────────────────────

    /**
     * The portal commits to a light UI, so the status-bar icons must be dark or
     * they disappear against it on a device running in dark mode.
     */
    private void applyStatusBarAppearance() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
    }

    /**
     * Pull-to-refresh — a native gesture Apple looks for, and the only reload
     * control the client has: an app has no browser chrome to reload from.
     * The webview is re-parented into a SwipeRefreshLayout in place, which
     * leaves Capacitor's own layout otherwise untouched.
     */
    private void addPullToRefresh() {
        WebView webView = getBridge().getWebView();
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent == null) return;

        int index = parent.indexOfChild(webView);
        ViewGroup.LayoutParams params = webView.getLayoutParams();
        parent.removeView(webView);

        refreshLayout = new SwipeRefreshLayout(this);
        refreshLayout.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshLayout.setColorSchemeColors(0xFF171717);
        refreshLayout.setOnRefreshListener(() -> {
            // Pulling while offline must not spin forever against a dead network.
            if (!hasValidatedInternet()) {
                stopRefreshSpinner();
                setOverlayVisible(true);
                return;
            }
            webView.reload();
        });
        parent.addView(refreshLayout, index, params);
    }

    /** The spinner is ours to stop — the webview does not know it started. */
    private void stopRefreshSpinner() {
        if (refreshLayout != null) refreshLayout.setRefreshing(false);
    }

    // ── Offline state ────────────────────────────────────────────────────────

    /**
     * NET_CAPABILITY_VALIDATED, not NET_CAPABILITY_INTERNET. The latter only
     * means the network claims to offer internet — it stays true on a router
     * with a dead uplink or a captive portal, which is exactly when the client
     * is staring at an error page.
     */
    private boolean hasValidatedInternet() {
        if (connectivity == null) return true; // cannot tell: assume online rather than block the app
        Network network = connectivity.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void watchConnectivity() {
        if (connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                setOnline(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }

            @Override
            public void onLost(Network network) {
                setOnline(false);
            }

            @Override
            public void onUnavailable() {
                setOnline(false);
            }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
    }

    /** Recovery is automatic: coming back online reloads and drops the overlay. */
    private void setOnline(boolean nowOnline) {
        boolean was = online;
        online = nowOnline;
        if (!nowOnline) {
            setOverlayVisible(true);
        } else if (!was) {
            runOnUiThread(this::reloadPortal);
        }
    }

    private void addOfflineOverlay() {
        ViewGroup root = findViewById(android.R.id.content);
        offlineOverlay = getLayoutInflater().inflate(R.layout.offline_overlay, root, false);
        offlineOverlay.findViewById(R.id.offline_retry).setOnClickListener(v -> {
            // Leave the overlay up if it is still down — flashing it away and
            // straight back reads as a broken button.
            if (hasValidatedInternet()) reloadPortal();
        });
        root.addView(offlineOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Added AFTER the offline overlay, so the lock covers that too. */
    private View addLockOverlay() {
        ViewGroup root = findViewById(android.R.id.content);
        View view = getLayoutInflater().inflate(R.layout.lock_overlay, root, false);
        root.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return view;
    }

    private void reloadPortal() {
        setOverlayVisible(false);
        WebView webView = getBridge().getWebView();
        // A failed load leaves the webview sitting on Chromium's error page, so
        // reload() would just re-show it. Navigate to the start URL instead.
        if (hasLoadedOnce) webView.reload();
        else webView.loadUrl(getBridge().getServerUrl());
    }

    private void setOverlayVisible(boolean visible) {
        runOnUiThread(() -> offlineOverlay.setVisibility(visible ? View.VISIBLE : View.GONE));
    }

    @Override
    public void onDestroy() {
        if (connectivity != null && networkCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // already unregistered — nothing to undo
            }
        }
        super.onDestroy();
    }
}
