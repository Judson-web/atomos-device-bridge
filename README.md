# Atomos Messenger

Atomos is evolving into a privacy-first Android messaging app while retaining and repurposing its existing on-device utility capabilities.

## Product direction

Atomos will provide private and group conversations, media and document sharing, profiles, notifications and a unified attachment library. Its existing file manager, image/video/audio viewers, text editor, ZIP tools, HTML viewer, package inspection/installer and network diagnostics remain part of the product as messaging capabilities.

The current application is still the local-first utility baseline. Cloud messaging, authentication and end-to-end encryption are not yet implemented by this direction document. Do not treat the app as an E2EE messenger until the protocol, key lifecycle, encrypted attachment path, backend authorization and security release gates are complete.

## Existing Android capabilities to preserve

- File manager with user-granted Storage Access Framework locations, bounded scans and explicit file operations.
- Image preview, UTF-8 text editor and offline HTML viewer.
- Audio/video playback using AndroidX Media3.
- ZIP listing, compression and extraction with bounded resource limits and path traversal protections.
- APK/APKM/APKS/APKX/XAPK inspection and user-confirmed Android PackageInstaller handoff.
- Network state, throughput diagnostics and explicitly user-initiated speed test.

These capabilities will be integrated through narrow attachment and preview interfaces. Chat screens must not gain unrestricted filesystem access.

## Security direction

- Select and integrate a maintained, established end-to-end encryption protocol implementation. Do not write custom cryptography.
- Generate and protect private keys on device; encrypt message bodies and attachment contents before upload.
- Store ciphertext, not plaintext conversations or media, on messaging infrastructure.
- Keep push notifications free of message content.
- Apply strict server-side authorization and row-level security.
- Make device enrollment, key changes, backup and recovery explicit and visible.
- Do not claim E2EE until the documented security release gate has passed.

See docs/MESSENGER_ARCHITECTURE.md for the architecture, security invariants, migration plan and release gate.

## Platform and build

- Android 10 (API 29) and newer.
- Kotlin, Jetpack Compose and AndroidX Media3.
- JDK 17, Android SDK 35+ and Gradle wrapper.
- Existing application ID: dev.atomos.bridge.
- Preserve the established Atomos release signing identity. Do not generate a replacement keystore for routine releases.

## Legacy backend files

The existing Supabase migrations and retired Edge Function are historical artifacts from an earlier device-bridge prototype. They are not a production messaging backend and must not be applied or revived blindly. Messaging requires a separately reviewed schema, authorization model and deployment plan.
