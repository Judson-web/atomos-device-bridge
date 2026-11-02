# Atomos Device Bridge

Android companion app for user-authorized device automation. This is the Android-side foundation, not yet a remotely controllable release.

## Current starter scope
- Kotlin + Jetpack Compose dashboard
- Accessibility service declaration and service implementation
- Visible UI-tree snapshotting
- Tap, swipe, Back, Home, notification shade and focused-field text entry primitives
- Optional notification-listener declaration (no notification content is retained)
- Permission/status onboarding UI

## Build
Open this directory in Android Studio (JDK 17; Android SDK 35 installed), allow Gradle sync, then run the `app` configuration. Package: `dev.atomos.bridge`.

## Enable the accessibility engine
On the phone: Settings → Accessibility → Installed apps / Installed services → Atomos Device Bridge → On. Return to the app and tap Refresh status.

Notification access is separate and optional. Screen capture will use Android MediaProjection and requires a fresh system consent flow. Shizuku integration is a later optional module; no root is required for the base app.

## Security boundary
There is deliberately no network listener in this starter. Before remote commands are added, implement QR-based pairing, a high-entropy per-device secret, TLS/WSS, replay protection, command allowlists, rate limits, session expiry, audit events without screen-content retention, and a visible kill switch. Destructive or sensitive actions must require confirmation. Never expose an unauthenticated ADB shell to the network.

## Compatibility
Minimum Android 8.0 (API 26), target/compile SDK 35 in this starter. Validate against the actual Android 17 / One UI build and update SDK/toolchain versions once confirmed in Android Studio.
