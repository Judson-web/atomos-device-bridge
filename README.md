# Atomos Device Bridge

Android companion app for user-authorized device automation. Version 0.2.0 adds email OTP sign-in, secure device registration, encrypted credential storage, cloud status checks and device revocation.

## Implemented
- Kotlin + Jetpack Compose AMOLED dashboard
- Supabase email OTP authentication
- One-time pairing challenge creation and claim through the Atomos Edge Function
- 30-day per-device credential stored encrypted with an AES-256 key held by Android Keystore
- Device status verification and owner-triggered revocation
- Accessibility service with visible UI-tree inspection, tap/swipe, Back/Home/notifications and focused-field text entry
- Optional notification-listener declaration; notification content is not persisted

## Not implemented yet
This is a secure pairing milestone, **not yet a complete remote-control product**. There is no command queue, background command receiver, MCP server transport, live screen streaming, or MediaProjection flow in this version. Pairing authenticates a phone but does not enable remote commands. Those capabilities must be added and tested before describing the phone as remotely controllable.

## Build
Open in Android Studio with JDK 17 and Android SDK 35. Package: `dev.atomos.bridge`. GitHub Actions builds debug APKs on pushes and signed release APKs on `v*` tags. Release signing secrets remain in GitHub Actions and are never stored in the repository.

## Setup
1. Install the signed APK.
2. Enable Atomos Device Bridge under Android Settings → Accessibility.
3. Open the app, enter your account email, and verify the one-time code.
4. Tap **Register this phone**, then **Test secure connection**.
5. Device credentials are encrypted at rest using Android Keystore. You can revoke the registered device from the app.

Email OTP delivery depends on the Supabase project's Auth email provider and email template. Configure the Magic Link template to include `{{ .Token }}` if you want a numeric OTP instead of a magic link.

Notification access is optional. Screen capture will use Android MediaProjection and require a fresh system consent flow. No root or network-facing ADB shell is used.

## Security boundary
- Public Supabase publishable key only in the app; service-role credentials stay server-side.
- Owner operations require a verified Supabase user session.
- Device operations require a random 256-bit per-device token; only its hash is stored server-side.
- Device tokens are encrypted at rest with an Android Keystore AES-GCM key.
- Pairing codes expire after three minutes and are single-use.
- Device sessions expire after 30 days and can be revoked.
- No notification content or screen content is retained by this app.

## Compatibility
Minimum Android 8.0 (API 26), target/compile SDK 35. Validate against the actual Android 17 / One UI build and update SDK/toolchain versions once confirmed in Android Studio.
