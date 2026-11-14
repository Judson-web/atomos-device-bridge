# Atomos Messenger Architecture

Status: architecture baseline, not a claim that end-to-end encryption is implemented.

## Product direction

Atomos is evolving from a local-first Android utility suite into a messaging app. Existing on-device capabilities remain and become first-class messaging tools:

- File manager: attachment picker, downloads, shared-file browser and storage manager.
- Image/video/audio viewers: in-conversation media presentation and a unified media gallery.
- Text editor: compose, inspect and edit local text attachments before sharing.
- ZIP tools: archive creation, archive preview and extraction for received bundles.
- APK installer: package attachment inspection and explicit user-confirmed installation. Never auto-install.
- Offline HTML viewer: safe local preview of HTML attachments, with scripts and remote network loading disabled by default.
- Network monitor and speed test: connection diagnostics, upload progress and retry guidance. Speed tests remain explicitly user initiated.

The app must retain Android 10+ support, the existing dev.atomos.bridge application ID, the established release signing identity, and the current local file-management permission model unless a separately reviewed change is necessary.

## Core product areas

1. Chats: direct conversations, group conversations, message history, replies, reactions, edits, deletion, read state and search.
2. People: public profile, unique handle, discovery controls, contact requests, block and report.
3. Media: attachment composer, upload/download queue, per-conversation media and documents.
4. Profile and settings: identity, privacy, notification preferences, linked devices, storage and diagnostics.

## Security invariants

- Do not invent or implement cryptographic primitives.
- Do not describe the app as end-to-end encrypted until a reviewed protocol implementation, key lifecycle, encrypted attachment pipeline and independent security review are complete.
- Use an established, maintained protocol implementation. Evaluate Signal's libsignal (X3DH/PQXDH session establishment and Double Ratchet) for one-to-one messaging, and a maintained group protocol implementation such as MLS (RFC 9420) or the protocol's supported group mechanism. Confirm Android compatibility, API stability, license, distribution and maintenance before choosing a dependency.
- Private keys are generated on-device and protected by Android Keystore-backed key encryption. Private identity/session state must never be uploaded in plaintext.
- Encrypt message bodies and attachment bytes on the sender's device before network transmission. Encrypt thumbnails, previews, filenames and captions too. Never upload plaintext previews or generate server-side plaintext thumbnails.
- Use authenticated encryption with unique nonces and protocol-defined associated data. Reject invalid tags, malformed envelopes, replayed or out-of-order protocol messages according to the selected protocol.
- The server stores ciphertext, opaque routing identifiers, delivery state and the minimum operational metadata required to deliver messages. Explain unavoidable metadata to users.
- Never log message plaintext, attachment keys, access tokens, recovery secrets or private key material.
- Do not add contact, SMS, call-log, accessibility, notification-listener or broad background-control permissions for convenience.
- Blocked users cannot initiate new conversations or deliver new messages. Enforce membership and authorization on the server, not only in the UI.
- Key changes, new devices and safety-number/fingerprint changes must be visible to users. Do not silently trust a newly added device.
- Backup and account recovery must not silently weaken E2EE. Recovery design must be explicit, user-controlled and documented.

## Message and attachment envelope

The server-facing message record should contain only fields needed for routing and synchronization, such as opaque message ID and conversation ID, sender device ID and protocol header, ciphertext payload and protocol version, server receive timestamp and delivery state, and optional expiry timestamp.

The exact envelope must follow the selected protocol library. Do not invent a parallel home-grown ratchet or key exchange.

Attachments are encrypted locally with a fresh random content key. The content key is included only inside the encrypted message payload. The object store receives ciphertext only. Use authenticated chunked encryption for large media, with integrity verification before a download is exposed to viewers. Use short-lived, authenticated object access and enforce per-conversation authorization.

## Backend boundaries

The current app README describes a local-only utility app and the legacy Supabase migrations/functions as historical. Treat that as the current baseline, not as a production messaging backend. Do not deploy, revive or apply historical migrations blindly.

Before enabling cloud messaging, create a separately reviewed backend migration set for profiles and public handles; devices and public key bundles; conversations and conversation membership; ciphertext messages and delivery cursors; encrypted attachment object metadata; contact requests, blocks and reports; and push notification routing using opaque identifiers only.

Use strict row-level security and server-side authorization. Realtime is a delivery transport, not an authorization boundary. Push notifications must never include message plaintext.

Authentication is a product decision that must be resolved before backend implementation. A messaging account needs a stable identity and authenticated device enrollment; do not assume the retired device-bridge OAuth flow is suitable or reintroduce it without explicit product approval.

## Reuse and migration plan

Keep existing utility modules isolated from the messaging domain. Expose safe, narrow interfaces for AttachmentSource from the existing Storage Access Framework file manager; MediaPreview backed by existing image/audio/video viewers; ArchivePreview and ArchiveExtraction backed by existing bounded ZIP tools; PackageInspection and explicit installation handoff; and NetworkDiagnostics backed by the existing network monitor.

The chat UI must not gain direct unrestricted filesystem access. File selection remains user initiated through Android's document picker or the existing granted-folder model. Preserve bounded parsing, ZIP traversal protections, file-size limits and installer confirmation.

## Delivery phases

1. Product and security baseline: confirm identity/authentication and recovery policy; select protocol libraries; document metadata and threat model.
2. Domain foundation: typed Kotlin models, repository interfaces, encrypted local persistence and migration strategy.
3. Protocol integration: device identity, pre-key/device bundles, session establishment, ratcheting, key-change UX and protocol test vectors.
4. Messaging backend: new isolated migrations, RLS policies, ciphertext transport, delivery cursors and opaque push.
5. Attachments: client-side encryption, chunked transfer, integrity checks and integration with existing viewers.
6. Product features: direct/group chats, reactions, receipts, search, notifications and people discovery.
7. Verification: protocol test vectors, multi-device tests, tamper/replay tests, RLS tests, offline/retry tests, Android 10+ device tests, accessibility and release signing.

## Release gate

No release may claim E2EE until the protocol implementation and versions are pinned and reviewed; key generation, storage, rotation, device addition/removal and recovery are tested; message and attachment content is ciphertext at rest and in transit on the server; notification payloads and logs contain no plaintext; server authorization and RLS tests pass; protocol interoperability/test vectors and adversarial tests pass; and security limitations and metadata exposure are documented.
