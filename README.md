# Atomos

Android cloud-control companion. Current source: **0.3.0**.

## Included in this milestone
- Custom Atomos adaptive launcher mark and Android 12+ splash branding.
- GitHub-only Supabase OAuth PKCE and encrypted Android Keystore credentials.
- Devices registered to the same GitHub-linked Supabase user are owned by the same account.
- Secure pairing, device verification and owner-only device revocation.
- Cloud command queue with short expiry, owner validation, device-token polling, acknowledgements and command history.
- Android foreground cloud listener, explicitly started by the user and represented by a persistent notification.
- Compose interface with drawer and Home, Control, Devices, Settings and About destinations.
- Accessibility execution for inspect, tap, swipe, back, home, notifications and focused-field text entry.

## Cloud command API
All requests are POST requests to `/functions/v1/atomos-device-bridge` with the publishable `apikey` and a user Bearer token for owner actions. The Android listener uses `x-device-token` for device actions.

Owner actions:
- `create_command`: `device_id`, `command_type`, `payload`, optional `ttl_seconds`.
- `list_commands`: `device_id`.
- `cancel_command`: `command_id`.
- `list_devices`, `revoke_device`.

Device actions:
- `poll_command` claims one queued command.
- `complete_command` acknowledges a claimed command with `command_id`, `success`, `result`, and optional `error`.

Supported command types: `inspect`, `tap`, `swipe`, `back`, `home`, `notifications`, `type`. Commands expire after 10-120 seconds. The device only polls while the user has started the foreground connection. Android AccessibilityService must also be enabled.

## Important boundaries
This milestone provides the Android cloud command relay and an authenticated REST control surface. A hosted browser dashboard and a standards-compliant MCP server transport are **not included yet**. Live screen streaming and MediaProjection are also not included; screen capture must use Android's explicit consent flow. Notification content is not collected. Remote commands cannot bypass Android permission prompts or OS restrictions.

## Security
- GitHub OAuth is the only sign-in provider in the app.
- User actions verify the Supabase session with `auth.getUser()`.
- A user can issue commands only to active devices owned by that same Supabase user.
- Device sessions are random 256-bit tokens; only SHA-256 hashes are stored server-side.
- Command payloads are bounded, commands are short-lived, and device tokens are revocable.
- Service-role credentials remain exclusively server-side; no ADB shell or cleartext HTTP is used.

## Build
JDK 17, Android SDK 35, Gradle 8.9. Package: `dev.atomos.bridge`. GitHub Actions builds debug APKs on main pushes and signed APKs on version tags.
