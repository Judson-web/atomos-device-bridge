# Atomos

Atomos is a modular Android utility suite focused on network visibility, local file management and package installation. The previous cloud-device-control prototype has been retired; its remote accessibility, SMS and device-command code is not part of this application.

## Current foundation

- Native Kotlin + Jetpack Compose application.
- Android 10 (API 29) and newer.
- Discord OAuth through Supabase Auth using PKCE.
- Encrypted local session storage backed by Android Keystore.
- Live network connectivity, transport, validated-internet state, local IP and device-wide RX/TX throughput.
- Internet speed test with download, upload, latency and jitter measurements.
- Storage Access Framework file browser. Atomos only sees folders explicitly granted by the user.
- APK, APKM, APKS, APKX and XAPK archive inspection and local installation through Android PackageInstaller, including multi-APK split sessions.
- ABI-specific APKs plus a universal APK.
- Utility tiles are independent UI modules, ready for future additions.

## Authentication

Configure the Discord provider in the Supabase project under Authentication > Sign In / Providers > Discord. Set the Discord OAuth client credentials and add the Supabase callback URL to the Discord developer application. The app uses the dev.atomos.bridge://auth-callback redirect URI. Configure this URI in the Supabase redirect allow-list.

There is no GitHub-user allow-list or single-device restriction. Each Discord-authenticated Supabase user can use the app on their own devices. Access tokens are stored encrypted using Android Keystore.

## APK signing

Release builds retain the existing Atomos APK signing identity. GitHub Actions expects these repository secrets:

- ANDROID_SIGNING_KEY_BASE64
- ANDROID_SIGNING_STORE_PASSWORD
- ANDROID_SIGNING_KEY_ALIAS
- ANDROID_SIGNING_KEY_PASSWORD

Do not generate a replacement keystore for routine releases. Losing the original signing key would prevent seamless updates over already installed signed builds.

## Build

- JDK 17
- Android SDK 35
- Gradle 8.9
- gradle :app:assembleDebug
- gradle :app:assembleRelease

GitHub Actions builds debug and signed release APKs, publishes signed APKs to GitHub Releases, and includes ABI variants and a universal APK.

## Privacy and platform behavior

- File access is granted through Android's Storage Access Framework; there is no broad storage permission.
- Package installation always passes through Android's system installer and user confirmation.
- Network throughput uses Android traffic counters and represents total device traffic, not only Atomos traffic.
- Speed tests use Cloudflare's public speed measurement endpoints. Network results depend on routing, server distance and carrier policies.
- No SMS, accessibility, notification-listener, remote-control, or always-running background service permissions are requested.
