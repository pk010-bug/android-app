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
| Encrypted at rest | Room database encrypted with SQLCipher (AES-256). Random 256-bit passphrase, wrapped by an AndroidKeyStore AES-GCM key, kept in `noBackupFilesDir`. Only if the Keystore key is *permanently* lost (invalidated, deleted, or the wrapped key no longer verifies) is the local cache wiped and rebuilt from the system SMS store; a temporary Keystore error shows a "Try again" screen and deletes nothing. |
| Nothing sends by itself | Other apps can only prefill a draft (`sms:`, `smsto:`, `mms:`, `mmsto:`, SEND/SENDTO, share). Auto-send extras are ignored and the user is told. Sending requires a single-use confirmation created by the Send button. |
| No direct calls | `CALL_PHONE` is not requested (and is stripped/blocked at build time). Calling opens the system dialer with `ACTION_DIAL`; you press call there. |
| Attachments | Only `content://` from other apps. `file://` and URIs from our own FileProvider/package are refused. Images are identified by their bytes and capped at 5 MiB. A shared file is only checked when it arrives; it is copied into private storage when you tap "Attach" in the draft, and scratch copies are swept. |
| Lock-screen-safe OTPs | Codes never appear on the lock screen. With "Hide OTPs" on (default) notifications are secret on the lock screen and omit the digits entirely, and codes are masked in Inbox / Search / Recycle-bin previews. While app lock is on, notifications are content-free by default ("New message" only: no sender, text or code). Copied codes and message text are flagged sensitive (Android 13+) and cleared from the clipboard after 60 s / 2 min. |
| Screenshot blocking | With app lock on, every screen is `FLAG_SECURE` (and hidden in Recents). With "Hide OTPs" on, OTP, finance and conversation screens are `FLAG_SECURE`. |
| Backups you control | Backups are created only when you ask, encrypted with your password (PBKDF2-HMAC-SHA256, 600k iterations → AES-256-GCM, header with KDF parameters authenticated; new backups need a strong 12+ character password, older 310k backups still restore), and saved only where you choose with the system file picker. The app never uploads them. |
| Blocking & scam checks are local | Block lists, rules, and scam heuristics run on-device; there are no reputation lookups. |
| Minimal exported surface | Only the main activity and the three components the SMS role needs are reachable by other apps, each guarded by a system-only permission. |

## Permissions and why

- `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` — required to act as
  the default SMS app.
- `READ_CONTACTS` — show names instead of numbers and suggest recipients while typing; contacts
  are only read, never stored elsewhere. The contact picker button works without it.
- `POST_NOTIFICATIONS` — local new-message / OTP / bill notifications.
- `READ_PHONE_STATE` — only to list active SIMs (slot + carrier label) for the dual-SIM picker.
  Requested the first time you open the picker; phone numbers and device IDs are never read.

Merged in by AndroidX libraries (harmless without network access):

- `USE_BIOMETRIC` / `USE_FINGERPRINT` (Biometric) — for app lock.
- `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` (WorkManager) — local background jobs.

Nothing else. In particular: no `INTERNET`, no `ACCESS_NETWORK_STATE` / `CHANGE_NETWORK_STATE` /
Wi-Fi state (WorkManager's `ACCESS_NETWORK_STATE` is stripped with `tools:node="remove"`; no job
uses a network constraint), no `CALL_PHONE`, no location, no account access. The Gradle
`verify<Variant>ManifestPermissions` task fails the build if any of these reappear in the merged manifest.

### MMS and the network

MMS travels over mobile data, but PrivateInbox never opens a connection. It hands a request to
Android's built-in MMS service (`SmsManager.downloadMultimediaMessage` / `sendMultimediaMessage`),
which talks to your carrier. Picture messages from **contacts or people you've texted** download
when they arrive ("Auto-download MMS from contacts", on by default; turn it off to tap for every
MMS). From anyone else you see a "Picture message, not downloaded" placeholder and tap Download,
after a warning for unknown senders. Blocked senders are never downloaded. The only things sent
without a tap are protocol acknowledgements the carrier requires: "deferred" for a message left on
the carrier's server (so it stops re-sending the notification), and "retrieved" / acknowledge after
a download, always with read/delivery reports explicitly *refused*.

Runtime integrity checks (root, hooking, debugger, signing certificate) run entirely on the phone
by reading local files and the app's own package info; they make no network request and report nothing.
Photos you send are re-encoded, which strips EXIF metadata such as GPS location. Received images
are stored in app-private, no-backup storage and deleted when the message is purged.

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

- A one-tap "wipe local data" action.
- Optional backup-on-schedule to a local folder.

See [SECURITY.md](../SECURITY.md) for the full control list and private vulnerability reporting.
