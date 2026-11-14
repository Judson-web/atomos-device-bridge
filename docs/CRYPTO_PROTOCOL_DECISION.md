# Atomos E2EE Protocol Decision

Status: implementation candidate selected; production use remains gated.

## Decision

Use Messaging Layer Security (MLS, RFC 9420) as the primary protocol candidate for both one-to-one and group conversations, with OpenMLS as the cryptographic core candidate. Do not use an unofficial Kotlin wrapper as the production cryptographic boundary. Build a deliberately small Rust-to-Kotlin bridge around a pinned OpenMLS release, then verify every Android ABI that Atomos ships.

This is a technical direction, not a claim that Atomos currently provides end-to-end encryption.

## Why MLS / OpenMLS

- MLS is standardized in IETF RFC 9420 and designed for secure groups from two participants to many.
- OpenMLS is maintained by Phoenix R&D and Cryspen, implements RFC 9420, and supports multiple standard ciphersuites.
- OpenMLS publishes Android target build configurations, including arm64-v8a, armeabi-v7a, x86 and x86_64 Rust targets, but explicitly classifies Android targets as unsupported / built in CI rather than supported / tested. Atomos must independently build and run protocol interoperability tests on each ABI it ships.
- OpenMLS underwent an independent SRLabs security audit in 2026. The published audit summary reports eight findings, including one high severity; seven fixes were merged and published in 0.8.1 and 0.7.3, while the remaining low-severity issue was described as being addressed. OpenMLS 0.9.2 was released on 22 October 2026 and includes later fixes, but we must read the full audit report and map each finding to 0.9.2 before relying on it. The audit is evidence, not a certification of Atomos.
- Signal libsignal is not selected: upstream explicitly says use outside Signal is unsupported and APIs can change without notice. Its official Android build toolchain uses JDK 21, while Atomos currently uses JDK 17. A direct dependency would create a maintenance and compatibility burden without upstream support.

## Integration strategy

1. Pin OpenMLS 0.9.2 and its complete Cargo dependency graph. This release requires Rust 1.91; the dependency lockfile must be committed before production builds. Never use a floating version.
2. Use a small Rust crate as the only owner of MLS state and cryptographic operations. Expose a narrow UniFFI or JNI API to Kotlin. Kotlin must not parse, mutate or serialize private MLS state itself.
3. Keep group state and private key material in a dedicated encrypted local store. Protect the wrapping key with Android Keystore. Never upload serialized private group state or identity secrets.
4. Treat the server as an untrusted delivery service. It stores opaque routing identifiers, protocol messages, public key packages and delivery metadata only.
5. Implement key-package publication, authenticated credential binding, group creation/join, add/remove/update commits, message protect/unprotect, replay/order handling, epoch persistence and recovery using OpenMLS APIs.
6. Use one MLS group per direct conversation as well as group conversations. Device enrollment must define whether each user device is a separate MLS leaf and how device add/remove and key-change UX work.
7. Keep application identity separate from MLS credentials. Discord OAuth PKCE establishes the account session; an authenticated device enrollment binds a device key to that account. Never derive MLS private keys from the Discord token or password.
8. Encrypt attachments client-side with a fresh content key. Put the content key, filename, MIME type, caption, thumbnail metadata and chunk manifest inside the protected MLS application message. Upload ciphertext chunks only.
9. Use opaque push payloads. Push must not include sender names, message text, attachment names or media previews.
10. Add protocol test vectors, cross-client interoperability tests, replay/tamper tests, group membership transition tests, crash/restart persistence tests, and multi-device lifecycle tests before enabling the feature.

## Android build gates

- Atomos currently targets Android API 29+ and packages arm64-v8a, armeabi-v7a and x86_64 in its release configuration. The native bridge must build and test each supported ABI; add x86 only if the product intentionally supports x86 emulator builds.
- Build with the NDK version required by the pinned Rust and crypto-provider dependencies. Do not assume the repository's existing JDK 17 / SDK 37 workflow is sufficient.
- Disable all OpenMLS debug-only features that can expose content or key material, including content-debug and crypto-debug.
- Generate an SBOM and run cargo audit / cargo deny against the locked dependency graph.
- Verify license obligations for the complete Rust dependency tree and include required notices in the APK.
- Pin native toolchains and dependencies, record reproducible build inputs, and sign with the existing Atomos release identity.

## Security gates before production

- Read the full SRLabs audit report and map each finding to the exact pinned version and configuration.
- Review the latest OpenMLS advisories and dependency advisories before every release.
- Obtain an independent review of the Rust/Kotlin bridge, credential binding, local persistence, server authorization and attachment encryption.
- Demonstrate that the delivery service cannot decrypt message bodies, filenames, captions, thumbnails or attachment bytes.
- Document MLS metadata exposure, identity lookup behavior, key-change handling, backups, device loss and account recovery.
- Keep messaging disabled if any required cryptographic/native component fails to initialize. Never silently downgrade to plaintext.

## Current implementation status

- Kotlin ciphertext envelope and repository boundaries exist.
- A first-run inbox screen exists and explicitly says secure messaging is not yet enabled.
- OpenMLS 0.9.2 and its RustCrypto provider are now pinned in a native Rust bridge crate. A JNI runtime-version handshake and Android ABI build workflow have been added. This is dependency/build integration only: no MLS identity, key package, group, message protect/unprotect operation, device enrollment, live backend transport or encrypted attachment pipeline has been enabled.
