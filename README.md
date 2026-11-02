# Atomos Device Bridge

Android companion app for explicitly authorized device automation. Current source version: **0.2.1**.

## Implemented

- Kotlin + Jetpack Compose AMOLED dashboard.
- Supabase email OTP sign-in.
- Owner-authenticated device registration and pairing.
- 12-hex-character one-time pairing code, three-minute expiry, single-use atomic claim, and a per-account issuance limit of five challenges per 15 minutes.
- Random 256-bit device session token; only its SHA-256 hash is stored server-side.
- Device credential encrypted at rest with AES-256-GCM and a non-exportable Android Keystore key. The encrypted value is authenticated against its storage key name.
- Migration of credentials from the previous encrypted-preferences format.
- One-time automatic Supabase session refresh on authenticated requests.
- Bounded network timeouts and response sizes, input validation, safe error handling, and no HTTP cleartext.
- Device status verification and owner-triggered revocation.
- Accessibility service with visible UI-tree inspection, tap/swipe, Back/Home/notifications and focused-field text entry.
- Optional notification listener. Notification content is not persisted.

## Not implemented yet

This is a hardened identity and pairing milestone, **not yet a complete remote-control product**. There is no command queue, background command receiver, MCP server transport, live screen streaming, or MediaProjection flow. Pairing authenticates a phone but does not enable remote commands. These capabilities must be implemented and tested before the phone can be described as remotely controllable.

## Build and release

Open in Android Studio with JDK 17 and Android SDK 35. Package: `dev.atomos.bridge`. GitHub Actions builds debug APKs on pushes and signed release APKs on `v*` tags. Release signing secrets remain in GitHub Actions and are never stored in the repository.

## Setup

1. Install the signed APK.
2. Enable Atomos Device Bridge under Android Settings → Accessibility.
3. Open the app, enter your account email, and verify the one-time code.
4. Tap **Register this phone**, then **Test secure connection**.
5. Credentials are stored encrypted with Android Keystore. You can revoke this device from the app.

Email OTP delivery depends on the Supabase project's Auth email provider and template. Configure the email template to include `{{ .Token }}` if numeric OTP delivery is desired.

Notification access is optional. Screen capture will use Android MediaProjection and require a fresh system consent flow. No root or network-facing ADB shell is used.

## Security boundary

- The app contains only the public Supabase publishable key. Service-role credentials remain server-side.
- Owner operations require a verified Supabase user session.
- Pairing claims require the same authenticated account that created the challenge.
- Pairing codes are hashed at rest, expire after three minutes, and can be claimed once.
- Device sessions expire after 30 days and can be revoked. A revoked device is rejected even if its old token is presented.
- No notification content or screen content is retained by this app.

## Compatibility

Minimum Android 8.0 (API 26), target/compile SDK 35. Validate on the target Android 17 / One UI build and keep the Android Gradle Plugin, Kotlin and SDK toolchain aligned with the CI workflow.
