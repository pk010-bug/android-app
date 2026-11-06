# PrivateInbox privacy principles

PrivateInbox reads some of the most sensitive data on a phone: personal chats, bank alerts,
and login codes. These rules exist so that data can never become someone else's.

## What we promise

| Promise | How it is enforced |
|---|---|
| No data leaves the device | No `INTERNET` permission; manifest uses `tools:node="remove"` to strip it from merged libraries. |
| No third-party tracking | No analytics, crash reporting, ads, Firebase, or Play Services. A Gradle resolution guard fails the build if one appears. |
| No cloud backup | `allowBackup="false"` and `data_extraction_rules.xml` exclude all domains from cloud backup and device transfer. |
| Local storage only | Room database + DataStore in the app's private directory. |
| Explainable categories | Plain JSON regex rules; each message stores the id of the rule that classified it. |
| Quiet logs | Only `SafeLog` may log; it redacts numbers, sender IDs and emails. Unit tests fail on raw `Log`/`println` or on passing body/sender/code to the logger. |
| Encrypted at rest | Room database encrypted with SQLCipher (AES-256). Random 256-bit passphrase, wrapped by an AndroidKeyStore AES-GCM key, kept in `noBackupFilesDir`. If the Keystore key is ever lost, the local cache is wiped and rebuilt rather than left unprotected. |
| Nothing sends by itself | Other apps can only prefill a draft (`sms:`, `smsto:`, `mms:`, `mmsto:`, SEND/SENDTO, share). Auto-send extras are ignored and the user is told. Sending requires a single-use confirmation created by the Send button. |
| No direct calls | `CALL_PHONE` is not requested (and is stripped/blocked at build time). Calling opens the system dialer with `ACTION_DIAL`; you press call there. |
| Attachments | Only `content://` from other apps. `file://` and URIs from our own FileProvider/package are refused. Images are identified by their bytes, capped at 5 MiB, and copied into private storage immediately. |
| Lock-screen-safe OTPs | Codes never appear on the lock screen. With "Hide OTPs" on (default) notifications are secret on the lock screen and omit the digits entirely. Copied codes are flagged sensitive (Android 13+). |
| Screenshot blocking | `FLAG_SECURE` on OTP, finance and conversation screens when app lock or "Hide OTPs" is on. |
| Minimal exported surface | Only the main activity and the three components the SMS role needs are reachable by other apps, each guarded by a system-only permission. |

## Permissions and why

- `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` — required to act as
  the default SMS app.
- `READ_CONTACTS` — show names instead of numbers; contacts are only read, never stored elsewhere.
- `POST_NOTIFICATIONS` — local new-message / OTP notifications.

Merged in by AndroidX libraries (harmless without network access):

- `USE_BIOMETRIC` / `USE_FINGERPRINT` (Biometric) — for the planned app lock.
- `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `ACCESS_NETWORK_STATE` (WorkManager)
  — local background jobs. `ACCESS_NETWORK_STATE` only lets WorkManager *read* connectivity state;
  it cannot send or receive anything.

Nothing else. In particular: no `INTERNET`, no `CALL_PHONE`, no location, no account access.

Verify on any build:

```
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

## Rules for contributors

1. Never add a dependency that performs network I/O, telemetry, or ads.
2. Never add `INTERNET` or similar permissions, even "temporarily".
3. Never log message content, phone numbers, or codes.
4. Any new persistent data must live in app-private storage and stay excluded from backups.
5. Any classification logic must be explainable to the user.

## Planned hardening

- Biometric / device-credential app lock (setting exists; the lock screen itself is not built yet).
- Persisting received SMS (receiver is still a stub), then MMS support under the same attachment rules.
- A one-tap "wipe local data" action.

See [SECURITY.md](../SECURITY.md) for the full control list and private vulnerability reporting.
