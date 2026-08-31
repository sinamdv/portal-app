import UIKit
import WebKit
import Network
import LocalAuthentication
import Capacitor

/**
 Ustyle portal shell — iOS (US-1612).

 The counterpart to Android's `MainActivity` + `PortalLock`. The webview loads
 the LIVE portal (`server.url`), which is the unmodified web app and carries no
 Capacitor JS, so every native feature is driven from here rather than called by
 the page. That is also why the portal needs no changes to gain any of it.

 Guideline 4.2 features: offline state, biometric lock, pull-to-refresh, safe
 areas.

 ⚠️ WRITTEN ON WINDOWS, NEVER COMPILED. Everything here is untested against a
 real SDK. Expect small fixes on the first Xcode build — see the README.
 */
class PortalViewController: CAPBridgeViewController {

    // MARK: - State

    private var offlineView: UIView?
    private var lockView: UIView?
    private var refreshControl: UIRefreshControl?

    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "it.ustyle.portal.network")
    private var isOnline = true

    private var unlocked = false
    private var prompting = false
    private var backgroundedAt: Date?

    /// Re-lock only after a real absence — locking on a 2-second app switch is hostile.
    private let graceInterval: TimeInterval = 15

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()

        applySafeAreaBackground()
        addPullToRefresh()
        offlineView = addOverlay(
            title: "You're offline",
            body: "Ustyle needs a connection to show your latest numbers. "
                + "Your data is safe — reconnect and pick up where you left off.",
            buttonTitle: "Try again",
            action: #selector(retryTapped))
        lockView = addOverlay(
            title: "Locked",
            body: "Unlock to see your store's numbers.",
            buttonTitle: "Unlock",
            action: #selector(unlockTapped))

        startNetworkMonitor()
        observeAppState()
    }

    /**
     Keep the webview OUT of the notch and home-indicator bands.

     The portal draws its overlays with `position: fixed; inset: 0`, which spans
     the WEBVIEW — not the safe area. So if the webview covers the full screen,
     the gallery lightbox's close button renders underneath the status bar and
     Dynamic Island, where the system swallows the tap and it simply does not
     work. Insetting the webview itself fixes every such overlay at once and
     needs no change to the portal.

     Android does not need this: its status bar already sits clear of content.
     */
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let webView = webView else { return }
        let inset = view.bounds.inset(by: view.safeAreaInsets)
        if webView.frame != inset { webView.frame = inset }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        evaluateLock()
    }

    // MARK: - Safe area

    /**
     The portal commits to a light UI. WKWebView does not paint behind the notch
     or the home indicator, so without a matching background the device's own
     (possibly black) window shows through those bands.
     */
    private func applySafeAreaBackground() {
        view.backgroundColor = UIColor(red: 0.957, green: 0.961, blue: 0.973, alpha: 1) // #f4f5f8
        webView?.isOpaque = false
        webView?.backgroundColor = .clear
        webView?.scrollView.backgroundColor = .clear
        // .never, not .always: the frame is already inset to the safe area in
        // viewDidLayoutSubviews, so letting the scroll view add its own inset on
        // top would push the content down twice.
        webView?.scrollView.contentInsetAdjustmentBehavior = .never

        // An APP should not pinch-zoom like a web page. The portal's viewport
        // meta sets width=device-width with no maximum-scale, so WKWebView
        // allows zooming - and once zoomed the page pans sideways, which reads
        // as "the layout is broken and scrolls horizontally". Locking the zoom
        // scale is the app's business, not the portal's, so this stays here.
        webView?.scrollView.pinchGestureRecognizer?.isEnabled = false
        webView?.scrollView.minimumZoomScale = 1
        webView?.scrollView.maximumZoomScale = 1
        webView?.scrollView.bouncesZoom = false
        // Stop sideways rubber-banding on a page that fits exactly.
        webView?.scrollView.alwaysBounceHorizontal = false
    }

    // MARK: - Pull to refresh

    /**
     The only reload control the client has — an app has no browser chrome.
     Attaches to the webview's own scroll view so it behaves like any native list.
     */
    private func addPullToRefresh() {
        guard let webView = webView else { return }
        let control = UIRefreshControl()
        control.tintColor = UIColor(white: 0.09, alpha: 1)
        control.addTarget(self, action: #selector(pullToRefresh(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = control
        refreshControl = control
    }

    @objc private func pullToRefresh(_ sender: UIRefreshControl) {
        // Pulling while offline must not spin forever against a dead network.
        guard isOnline else {
            sender.endRefreshing()
            setOverlay(offlineView, visible: true)
            return
        }
        webView?.reload()
        // WKWebView gives no completion here without owning the navigation
        // delegate (which Capacitor holds), so the spinner is ended on a timer.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { sender.endRefreshing() }
    }

    // MARK: - Offline state

    /**
     Connectivity drives the overlay on iOS, rather than webview load errors as
     on Android: Capacitor owns the navigation delegate here, so there is no
     equivalent of `WebViewListener.onReceivedError` to hook without taking it
     over and breaking the bridge.
     */
    private func startNetworkMonitor() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let nowOnline = path.status == .satisfied
            let wasOnline = self.isOnline
            self.isOnline = nowOnline
            DispatchQueue.main.async {
                if !nowOnline {
                    self.setOverlay(self.offlineView, visible: true)
                } else if !wasOnline {
                    // Recovery is automatic — no tap required.
                    self.setOverlay(self.offlineView, visible: false)
                    self.webView?.reload()
                }
            }
        }
        monitor.start(queue: monitorQueue)
    }

    @objc private func retryTapped() {
        guard isOnline else { return } // leave it up rather than flashing it away
        setOverlay(offlineView, visible: false)
        webView?.reload()
    }

    // MARK: - Biometric lock

    private func observeAppState() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(willResignActive),
            name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(didBecomeActive),
            name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    /// Cover the content BEFORE the app leaves the foreground, so the lock —
    /// not the client's revenue — is what the app switcher snapshot shows.
    @objc private func willResignActive() {
        backgroundedAt = Date()
        hasSession { [weak self] signedIn in
            guard let self = self, signedIn, self.canAuthenticate() else { return }
            self.setOverlay(self.lockView, visible: true)
        }
    }

    @objc private func didBecomeActive() {
        evaluateLock()
    }

    private func evaluateLock() {
        hasSession { [weak self] signedIn in
            guard let self = self else { return }
            // No session means the portal's own login screen is already the
            // gate; asking for Face ID to reach a login form is theatre.
            guard signedIn, self.canAuthenticate() else {
                self.setOverlay(self.lockView, visible: false)
                return
            }
            let brief = self.backgroundedAt.map { Date().timeIntervalSince($0) < self.graceInterval } ?? false
            if self.unlocked && brief {
                self.setOverlay(self.lockView, visible: false)
                return
            }
            self.unlocked = false
            self.promptForUnlock()
        }
    }

    /// A device with neither biometrics nor a passcode must never be locked out.
    private func canAuthenticate() -> Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    }

    private func promptForUnlock() {
        guard !prompting else { return } // didBecomeActive can fire twice
        prompting = true
        setOverlay(lockView, visible: true)

        let context = LAContext()
        // .deviceOwnerAuthentication, not ...WithBiometrics: it falls back to
        // the passcode, so a phone with no enrolled Face ID still opens.
        context.evaluatePolicy(.deviceOwnerAuthentication,
                               localizedReason: "Your store data is private to you.") { [weak self] success, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.prompting = false
                if success {
                    self.unlocked = true
                    self.setOverlay(self.lockView, visible: false)
                }
                // On cancel the overlay stays up with its own Unlock button
                // rather than closing the app — the client may be handing the
                // phone over, and a hard exit loses whatever they were reading.
            }
        }
    }

    @objc private func unlockTapped() {
        promptForUnlock()
    }

    /**
     Signed in? Read from the WEBVIEW's cookie store, not the page: the portal
     session cookie is httpOnly and invisible to JavaScript, but WKHTTPCookieStore
     is the native jar the webview itself writes to. (Same reasoning as Android's
     CookieManager, which is proven to work.)
     */
    private func hasSession(_ completion: @escaping (Bool) -> Void) {
        WKWebsiteDataStore.default().httpCookieStore.getAllCookies { cookies in
            let found = cookies.contains {
                $0.name == "portal_session" && $0.domain.contains("ustyle.it")
            }
            DispatchQueue.main.async { completion(found) }
        }
    }

    // MARK: - Overlays

    /// Opaque and above the webview, so figures are not readable behind it.
    private func addOverlay(title: String, body: String,
                            buttonTitle: String, action: Selector) -> UIView {
        let overlay = UIView()
        overlay.translatesAutoresizingMaskIntoConstraints = false
        overlay.backgroundColor = UIColor(red: 0.957, green: 0.961, blue: 0.973, alpha: 1)
        overlay.isHidden = true
        view.addSubview(overlay)
        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: view.topAnchor),
            overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])

        let icon = UIImageView(image: UIImage(named: "AppIcon"))
        icon.contentMode = .scaleAspectFit
        icon.layer.cornerRadius = 16
        icon.clipsToBounds = true

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 20, weight: .semibold)
        titleLabel.textColor = UIColor(white: 0.09, alpha: 1)
        titleLabel.textAlignment = .center

        let bodyLabel = UILabel()
        bodyLabel.text = body
        bodyLabel.font = .systemFont(ofSize: 15)
        bodyLabel.textColor = UIColor(white: 0.32, alpha: 1)
        bodyLabel.numberOfLines = 0
        bodyLabel.textAlignment = .center

        let button = UIButton(type: .system)
        button.setTitle(buttonTitle, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
        button.backgroundColor = UIColor(white: 0.09, alpha: 1)
        button.layer.cornerRadius = 10
        button.contentEdgeInsets = UIEdgeInsets(top: 14, left: 32, bottom: 14, right: 32)
        button.addTarget(self, action: action, for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [icon, titleLabel, bodyLabel, button])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 12
        stack.setCustomSpacing(28, after: icon)
        stack.setCustomSpacing(28, after: bodyLabel)
        stack.translatesAutoresizingMaskIntoConstraints = false
        overlay.addSubview(stack)

        NSLayoutConstraint.activate([
            icon.widthAnchor.constraint(equalToConstant: 72),
            icon.heightAnchor.constraint(equalToConstant: 72),
            stack.centerYAnchor.constraint(equalTo: overlay.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: overlay.leadingAnchor, constant: 40),
            stack.trailingAnchor.constraint(equalTo: overlay.trailingAnchor, constant: -40),
        ])
        return overlay
    }

    private func setOverlay(_ overlay: UIView?, visible: Bool) {
        guard let overlay = overlay else { return }
        DispatchQueue.main.async {
            if visible { overlay.superview?.bringSubviewToFront(overlay) }
            overlay.isHidden = !visible
        }
    }

    deinit {
        // DO NOT call monitor.cancel() here. Doing so segfaults:
        //   EXC_BAD_ACCESS / KERN_INVALID_ADDRESS at 0x18, with
        //   NWPathMonitor.cancel() on top of __deallocating_deinit.
        // By the time deinit runs the monitor's internals are already being
        // torn down, so cancel() dereferences freed memory. It is also
        // unnecessary: the monitor is owned by this controller and stops when
        // it deallocates, and its handler captures self weakly, so nothing
        // keeps either alive.
        NotificationCenter.default.removeObserver(self)
    }

    /// Cancel while the object is still fully alive — the safe moment deinit is not.
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || isMovingFromParent {
            monitor.cancel()
        }
    }
}
