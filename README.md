# Atomos

Atomos is a **local-first Android utility app for a personally managed device**. It provides a file manager, built-in file viewers, ZIP tools, network diagnostics and a user-confirmed APK installer.

## Local-only design

- No account, OAuth flow, sign-in gate, device pairing or cloud command channel is used by the Android app.
- The app does not request SMS, contacts, call-log, accessibility, notification-listener or background remote-control permissions.
- On Android 11 and newer, users can optionally grant Android's special **All files access** permission to browse and manage shared internal storage. This requires explicit approval in system Settings and is never granted silently.
- Without that permission, Atomos uses the Storage Access Framework and only accesses folders the user selects. Removable SD cards and USB storage can be opened through the folder picker when exposed by the device. Android continues to restrict protected app-private and system locations, including parts of `Android/data` and `Android/obb`.
- Selected Storage Access Framework locations are saved in encrypted app preferences backed by the Android Keystore. Android's persisted URI permission remains the authority for those locations. All-files access is checked against Android system settings whenever the file manager is opened.
- File contents and archive contents are processed on-device. The only built-in network transfer is the optional speed test described below.

## Features

### File manager
- Read-only storage insights dashboard with free/used capacity, category totals, and largest discovered files (bounded scan; never deletes automatically).
- Browse folders granted by the user, with breadcrumbs and back navigation.
- Search the current folder, filter by file category, show or hide dotfiles, and sort by name, size or modification time.
- Switch between list and grid layouts.
- Create folders, rename, inspect details, copy and move files, and delete with a confirmation prompt.
- Compress files or folders into ZIP archives and extract ZIP archives into the current folder.
- ZIP operations reject unsafe paths and enforce limits for entry count, folder depth and expanded data. Compression excludes its own output file when the destination is inside the source folder.

### Built-in viewers
- Image preview with down-sampled decoding to keep memory use bounded.
- UTF-8 text editor for common text and source formats.
- Offline HTML viewer with JavaScript and network loading disabled.
- Audio and video playback through AndroidX Media3.
- ZIP content listing with bounded preview work.

Text and ZIP previews are limited to 32 MB of source data. ZIP listing also has a 64 MB expanded-data budget. Large files can be opened with another installed app where supported.

### Network
- Current connection type, validated internet state, IP address and device-wide RX/TX throughput.
- Optional speed test using Cloudflare's public speed.cloudflare.com measurement endpoint. A test transfers approximately 25 MB for download and 5 MB for upload, so it uses mobile data when run over a cellular connection.

### Package installer
- Supports APK, APKM, APKS, APKX and XAPK archives.
- Split APKs are staged into a single Android PackageInstaller session.
- Archive inspection is bounded to 20,000 entries, 32 APK splits and 1 GB of expanded APK data.
- Android owns the final install confirmation. Atomos never silently bypasses the system installer.

## Platform and build

- Android 10 (API 29) and newer.
- Kotlin, Jetpack Compose and AndroidX Media3.
- JDK 17, Android SDK 35 and Gradle 8.9.
- Build locally with `gradle :app:assembleDebug` or `gradle :app:assembleRelease`.
- GitHub Actions builds debug APKs on pull requests and signed release APKs on pushes to `main` or version tags. ABI-specific and universal APKs are produced.

## Release signing

Release builds retain the existing Atomos signing identity. GitHub Actions expects these repository secrets:

- `ANDROID_SIGNING_KEY_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

Do not generate a replacement keystore for routine releases. Losing the existing signing key would prevent seamless updates over already installed signed builds.

## Legacy backend files

The `supabase/migrations` directory contains historical migrations from an earlier device-bridge prototype. They are retained for audit/history and are **not required by this local Android app**. Do not apply them to a new project for the local-only app. The retired `atomos-device-bridge` Edge Function is represented by a 410 Gone stub; no Android feature calls it. The separate legacy `atomos-app-api` endpoint is not part of this repository or used by this app.

## Privacy and platform behavior

- The Android manifest requests internet access, network-state access, package-install permission and `MANAGE_EXTERNAL_STORAGE`. The latter is optional and only takes effect after the user explicitly approves it in Android Settings.
- Network activity occurs only for the optional speed test and for opening unsupported file types in an external app chosen by the user.
- Package installation and external file opening remain subject to Android's own user prompts and permission model.
