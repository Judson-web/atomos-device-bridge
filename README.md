# Atomos Device Bridge

Android companion app for explicitly authorized device automation. Current source version: **0.2.2**.

## Implemented

- Kotlin + Jetpack Compose AMOLED dashboard.
- **GitHub-only sign-in** through Supabase Auth OAuth using the system browser and PKCE.
- App deep-link callback at `dev.atomos.bridge://auth-callback`; the PKCE verifier is stored encrypted with Android Keystore.
- Owner-authenticated device registration and pairing.
- 12-hex-character one-time pairing code, three-minute expiry, single-use atomic claim, and a per-account issuance limit of five challenges per 15 minutes.
- Random 256-bit device session token; only its SHA-256 hash is stored server-side.
- Device credential encrypted at rest with AES-256-GCM and a non-exportable Android Keystore key.
- Migration of credentials from the previous encrypted-preferences format.
- One-time automatic Supabase session refresh on authenticated requests.
- Bounded network timeouts and response sizes, input validation, safe error handling, and no HTTP cleartext.
- Device status verification and owner-triggered revocation.
- Accessibility service with visible UI-tree inspection, tap/swipe, Back/Home/notifications and focused-field text entry.
- Optional notification listener. Notification content is not persisted.

## Not implemented yet

This is a hardened identity and pairing milestone, **not yet a complete remote-control product**. There is no command queue, background command receiver, MCP server transport, live screen streaming, or MediaProjection flow. Pairing authenticates a phone but does not enable remote commands.

## GitHub OAuth setup

1. In GitHub Developer Settings, create an OAuth App.
2. Set its **Authorization callback URL** to `https://swvlujplpqfppnhdhxyf.supabase.co/auth/v1/callback`.
3. In Supabase Dashboard → Authentication → Providers → GitHub, enable GitHub and enter the OAuth App client ID and client secret. Keep the secret in Supabase, never in the Android app or repository.
4. In Supabase Dashboard → Authentication → URL Configuration, add `dev.atomos.bridge://auth-callback` to the allowed redirect URLs.
5. Confirm GitHub is the only enabled provider if you want to disable other sign-in methods at the project level.

The Android app uses the system browser, an S256 PKCE challenge, a one-time callback code exchange, and encrypted local storage for the verifier and resulting session tokens.

## Build and release

Open in Android Studio with JDK 17 and Android SDK 35. Package: `dev.atomos.bridge`. GitHub Actions builds debug APKs on pushes and signed release APKs on `v*` tags. Release signing secrets remain in GitHub Actions and are never stored in the repository.

## Setup

1. Install the APK.
2. Enable Atomos Device Bridge under Android Settings → Accessibility.
3. Tap **Continue with GitHub** and authorize in the browser.
4. Tap **Register this phone**, then **Test secure connection**.
5. Credentials are stored encrypted with Android Keystore. You can revoke this device from the app.

Notification access is optional. Screen capture will use Android MediaProjection and require a fresh system consent flow. No root or network-facing ADB shell is used.

## Security boundary

- The app contains only the public Supabase publishable key. Service-role credentials remain server-side.
- Owner operations require a verified Supabase user session.
- Pairing claims require the same authenticated account that created the challenge.
- Pairing codes are hashed at rest, expire after three minutes, and can be claimed once.
- Device sessions expire after 30 days and can be revoked.
- No notification content or screen content is retained by this app.

## Compatibility

Minimum Android 8.0 (API 26), target/compile SDK 35.