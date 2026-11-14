# Atomos Messenger

Atomos is evolving into a privacy-first Android messaging app while retaining and repurposing its existing on-device utility capabilities.

## Product direction

Atomos will provide private and group conversations, media and document sharing, profiles, notifications and a unified attachment library. Its existing file manager, image/video/audio viewers, text editor, ZIP tools, HTML viewer, package inspection/installer and network diagnostics remain part of the product as messaging capabilities.

An experimental messaging foundation is now present on this branch: Discord PKCE sign-in, profile discovery and contact requests, direct-conversation bootstrap, ciphertext transport, a native OpenMLS bridge, and encrypted local MLS state. The Rust protocol test exercises a real two-client Welcome and application-message round trip. This is an integration milestone, not a production-secure messenger. Group membership changes, encrypted attachments, device lifecycle and recovery, durable push delivery, Android device interoperability, and independent security review remain incomplete. Do not treat the app as production-verified E2EE until every release gate in the architecture documents passes.

## Existing Android capabilities to preserve

- File manager with user-granted Storage Access Framework locations, bounded scans and explicit file operations.
- Image preview, UTF-8 text editor and offline HTML viewer.
- Audio/video playback using AndroidX Media3.
- ZIP listing, compression and extraction with bounded resource limits and path traversal protections.
- APK/APKM/APKS/APKX/XAPK inspection and user-confirmed Android PackageInstaller handoff.
- Network state, throughput diagnostics and explicitly user-initiated speed test.

These capabilities will be integrated through narrow attachment and preview interfaces. Chat screens must not gain unrestricted filesystem access.

## Security direction

- Use MLS (RFC 9420) through the pinned OpenMLS Rust implementation. Do not write custom cryptographic primitives or protocol logic. Kotlin and Rust glue is application integration, not a replacement cipher or ratchet.
- Generate and protect private keys on device; encrypt message bodies and attachment contents before upload.
- Store ciphertext, not plaintext conversations or media, on messaging infrastructure.
- Keep push notifications free of message content.
- Apply strict server-side authorization and row-level security.
- Make device enrollment, key changes, backup and recovery explicit and visible.
- Do not claim E2EE until the documented security release gate has passed.

See docs/MESSENGER_ARCHITECTURE.md for the architecture and docs/CRYPTO_PROTOCOL_DECISION.md for the MLS/OpenMLS decision, Android native integration requirements and security gates. The native crate currently provides a runtime handshake and an OpenMLS two-client round-trip test; production key lifecycle, group persistence and message transport are still under implementation.

## Platform and build

- Android 10 (API 29) and newer.
- Kotlin, Jetpack Compose and AndroidX Media3.
- JDK 17 for Android/Gradle, Android SDK 37 and NDK 27.2.12479018 for the native bridge, and Rust 1.91+ for OpenMLS 0.9.2.
- Existing application ID: dev.atomos.bridge.
- Preserve the established Atomos release signing identity. Do not generate a replacement keystore for routine releases.

## Legacy backend files

The old device-bridge migrations and retired Edge Function remain historical and must not be revived. The new isolated `messenger` schema migration is a draft for a separately approved Supabase project. It has not been deployed. Before applying it, review the target project's auth provider, add `messenger` to the PostgREST exposed schemas, verify storage and Realtime settings, run RLS tests, and confirm the project contains no unrelated production data that could be affected.
