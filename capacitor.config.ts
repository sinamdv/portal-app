import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Ustyle client portal — native shell (US-1612).
 *
 * The app is a Capacitor wrapper around the LIVE portal, not a rebuild. Every
 * portal feature therefore ships to the app on the normal web deploy, with no
 * App Store release needed for content changes.
 */
const config: CapacitorConfig = {
  // NOTE: confirm with the CTO before US-1613 (store submission) — a bundle id
  // is permanent once registered with Apple/Google and cannot be changed after.
  appId: 'it.ustyle.portal',
  appName: 'Ustyle',
  webDir: 'www',

  server: {
    // REMOTE MODE. The webview's origin stays ustyle.it, which is load-bearing
    // for three separate things, each of which breaks a locally-served bundle:
    //   1. /api/portal/image refuses anything that is not sec-fetch-site:
    //      same-origin, so every gallery photo would 403;
    //   2. the portal_session cookie is httpOnly + sameSite:lax, so it would
    //      not be sent cross-origin and the client could never stay signed in;
    //   3. the portal is server-rendered (SSR + Cosmos + PostHog) — a static
    //      export is not possible at all.
    // Proven end to end in the Phase 0 spike on both platforms.
    // TEMPORARY - throwaway branch. Points at a cloudflared quick tunnel to the
    // local dev portal so the safe-area CSS can be tested before deploying.
    // NEVER merge this branch.
    url: 'https://leasing-food-legislation-string.trycloudflare.com/portal',
    cleartext: false,
  },

  android: {
    // Lets Chrome DevTools attach over chrome://inspect. Keep this ON until the
    // app is submitted — debugging a webview blind is not worth the saving.
    webContentsDebuggingEnabled: true,
  },

  ios: {
    // The portal commits to a light UI (colorScheme: light on the shell), so a
    // dark-mode device must not tint the scroll bounce area behind it.
    backgroundColor: '#f4f5f8',
  },

  // Tags every request from the app. /api/portal/track reads this to record
  // viewsApp alongside views — it is the ONLY thing separating an app session
  // from a browser one. Changing this string silently stops that tracking.
  appendUserAgent: 'UstyleApp',
};

export default config;
