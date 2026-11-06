# Security policy

PrivateInbox handles personal messages, bank alerts and login codes, so we treat security
reports as a priority.

## Reporting a vulnerability (privately)

**Please do not open a public issue, discussion, or pull request for security problems.**

1. Use GitHub's **private vulnerability reporting**: on the repository page go to
   *Security → Report a vulnerability*. This opens a private advisory visible only to the
   maintainers.
2. If that is not available, email the maintainers at the security contact listed in the
   repository profile (`TODO: add security contact address before first public release`),
   with the subject `[PrivateInbox security]`.

Please include:
- affected version / commit, device and Android version
- steps to reproduce or a proof of concept
- impact (what data or capability an attacker gains)

Never include real SMS content, phone numbers, or OTPs from your own device. Use made-up data.

**What to expect:** acknowledgement within 7 days, a fix or mitigation plan within 30 days for
confirmed issues, and credit in the release notes if you want it. Please give us a reasonable
window to ship a fix before public disclosure.

## Scope

In scope: anything that lets data leave the device, lets another app read our data, send SMS,
place calls, or trigger actions without the user's tap, bypasses app lock / FLAG_SECURE, or
leaks message content, senders, or codes into logs, notifications, or the clipboard.

Out of scope: attacks needing root, an unlocked bootloader, or physical access to an unlocked
device; issues in Android itself or the carrier network.

## Security design summary

| Area | Control | Where |
|---|---|---|
| Network | No `INTERNET` permission; manifest `tools:node="remove"`; Gradle task fails the build if the merged manifest requests INTERNET, CALL_PHONE, location or AD_ID; banned-dependency guard (Firebase, Play Services, OkHttp, Retrofit, Ktor, Volley, Sentry, Crashlytics) | `AndroidManifest.xml`, `app/build.gradle.kts` |
| Never send without a tap | External intents (`sms:`/`smsto:`/`mms:`/`mmsto:`, SEND/SENDTO, share) are triaged into a *draft* only; auto-send extras are dropped. `SmsSender` needs a single-use, 5-second `UserSendConfirmation` minted by the composer's Send button. Source-scan tests enforce that only `ui/compose` can send. | `compose/IntentTriage.kt`, `sms/SmsSender.kt`, `SendPathTest` |
| Attachments | Only `content://` URIs from other apps; `file://` and our own authorities rejected; magic-byte sniffing (JPEG/PNG/GIF/WebP/HEIC); 5 MiB cap enforced while streaming; copied into private no-backup storage immediately | `attachments/` |
| Calls | No `CALL_PHONE`. Call button uses `ACTION_DIAL` (system dialer, user presses call) | `calls/Dialer.kt` |
| Exported surface | Only `MainActivity` plus the three SMS-role components (MMS download/sent callbacks are non-exported) and the home-screen widget provider (guarded by `BIND_APPWIDGET`), each guarded by a system-only permission. Everything else, including AndroidX diagnostics receivers, is `exported=false`. No http/custom-scheme deep links; in-app routes validated against an allow-list with numeric ids | `AndroidManifest.xml`, `navigation/DeepLinks.kt`, `ManifestPolicyTest` |
| PendingIntents | Always `FLAG_IMMUTABLE`, always explicit and same-package (enforced at runtime) | `security/PendingIntents.kt` |
| Data at rest | Room encrypted with SQLCipher (AES-256); random 256-bit passphrase wrapped by an AndroidKeyStore AES-GCM key, stored in `noBackupFilesDir`; `allowBackup=false` and all backup / transfer domains excluded | `data/db/PrivateInboxDatabase.kt`, `security/DatabaseKeyManager.kt` |
| Notifications | OTP digits never on the lock screen (public version without code; `VISIBILITY_SECRET` when "Hide OTPs" is on); OTP notifications time out after 10 min; copied codes flagged sensitive (API 33+ `EXTRA_IS_SENSITIVE`) | `notifications/`, `security/SensitiveClipboard.kt` |
| Screens | Since v1.0.1: whole-window `FLAG_SECURE` on every screen (and no Recents snapshot on Android 13+) whenever app lock is on; the window starts secure until settings load. With "Hide OTPs" on, OTP / finance / conversation screens are also `FLAG_SECURE` | `security/SecureScreen.kt`, `MainActivity.kt` |
| App lock | BiometricPrompt with Class 3 (strong) biometrics or device credential (Android 11+; Android 8-10 fall back to the only supported combination, weak biometric or credential); on Android 11+ unlock also requires an auth-bound Keystore key via CryptoObject (`security/AppLockKey.kt`); fresh authentication before turning the lock on/off, changing its timeout, showing OTPs/lock-screen notifications, and backup export/restore; the database key is intentionally not bound to user auth; relocks after a configurable timeout; content is not composed until the lock state is known | `ui/lock/`, `MainActivity.kt` |
| Send confirmation | Default-on dialog shows recipient, SIM, and text before the single-use send token is minted | `ui/compose/MessageComposer.kt` |
| Backups | User-initiated only; PBKDF2-HMAC-SHA256 (600k; KDF id + iterations in the authenticated v2 header, v1/310k files still restore) → AES-256-GCM; new backups need a 12+ character password that passes a strength check (`BackupPasswordPolicy`); export/restore need a fresh authentication; zip-bomb and size guards on restore; restored rules re-validated; no upload path exists | `backup/` |
| Background work | WorkManager jobs are local only (provider import, recycle-bin purge, bill reminders); receivers use `goAsync` and never log content | `work/`, `sms/` |
| Scheduled send | Scheduling consumes the same tap token; the row (id, recipients, body hash, time, SIM) is sealed with a non-exportable AndroidKeyStore HMAC-SHA256 key. The worker re-verifies, claims the row atomically (no double send), and drops messages >24 h late. Only `ScheduledSendWorker` may call `sendScheduled` (source-scan test) | `sms/ScheduledSendAuthorizer.kt`, `sms/SmsSender.kt`, `SendPathTest` |
| MMS | Push payloads and downloaded PDUs parsed by a bounds-checked, fuzz-tested codec with part/size caps; transport by the system MMS service only (no INTERNET). Incoming images re-checked by magic bytes + size, stored under `noBackupFilesDir/mms` with validated bare file names; scratch PDUs in a narrow FileProvider path and deleted after each callback. Outgoing photos re-encoded (EXIF stripped). `MmsTransport` may only send its own NotifyResp (test-enforced); user MMS goes through `SmsSender` with a tap token. Image viewer dialog is `FLAG_SECURE` | `mms/`, `sms/Mms*.kt`, `SendPathTest` |
| Received media | Video/audio/vCard accepted only if bytes match an allow-listed signature (MediaPolicy; declared type ignored), 5 MiB cap; never rendered or auto-opened. "Open with…" requires tap + confirm and hands a one-off READ grant on a copy in `cache/open` (swept after 1 h) | `attachments/MediaPolicy.kt`, `mms/MediaOpener.kt`, `ManifestPolicyTest` |
| Widget | Home-screen only (no keyguard category); receiver guarded by `BIND_APPWIDGET`; renders counts and optional contact names only (off by default, hidden when app lock is on); source-scan test forbids widget code touching bodies/previews/OTP; tap opens MainActivity so app lock applies | `widget/`, `WidgetContentTest` |
| Backup media | Format 2 adds MMS parts; each restored file must re-pass MediaPolicy; written only for messages created by that restore; files removed if the transaction rolls back | `backup/BackupManager.kt`, `BackupMediaTest` |
| Logging | `SafeLog` is the only logger; redacts digits, sender headers, emails; debug output off unless enabled per tag; tests fail on raw `Log.*`/`println` or on passing body/sender/code to SafeLog | `security/SafeLog.kt`, `LogHygieneTest` |

## v1.0.1 hardening (internal audit fixes)

- **MMS parser (High):** all PDU length checks are overflow-safe (`n > end - pos`); any unexpected
  error or allocation failure is reported as a malformed PDU, and both MMS receivers catch
  `Throwable`, so a crafted WAP push or downloaded MMS can no longer crash the app. Regression
  tests cover each overflow; the fuzz tests now fail on any exception other than `MalformedPdu`.
- **Shared attachments (Medium):** SMS/MMS, contacts, call-log and other personal-data
  authorities are blocked. A URI on a protected provider is accepted only if the sending app
  granted it (ClipData + `FLAG_GRANT_READ_URI_PERMISSION`) and our own permissions would not
  let us read it anyway. Nothing is copied when the intent arrives: the user taps
  "Attach shared photo". Draft copies are swept after 12 h and the folder is capped at 20 MB.
  Authorities are checked in canonical form: percent-escapes in the authority are refused
  (`content://0%40mms/...`), a `N@` user-ID prefix is allowed only for our own user and stripped
  before the block lists, encoded `..` path segments are refused, and a provider PackageManager
  cannot resolve is rejected even when the sender claims a grant. The "Attach" tap re-checks with
  the sender's real grant instead of assuming one.
- **Screen privacy (Medium):** with app lock on, the whole window is `FLAG_SECURE` (every screen,
  plus Recents; `setRecentsScreenshotEnabled(false)` on Android 13+). The app starts secure and
  relaxes only after settings load.
- **Lock screen (Medium):** new "Hide notifications on lock screen" setting, on by default:
  message, OTP and bill notifications use `VISIBILITY_SECRET`.
- **Tap-jacking (Medium):** the composer and the send/schedule confirmation dialogs drop
  touches that arrive through another app's overlay and hide other apps' overlays on
  Android 12+ (`HIDE_OVERLAY_WINDOWS`, a normal permission).
- OTPs copied to the clipboard are cleared after 60 s (best-effort).

## v1.0.2 hardening

Contributed by the project owner (medium-severity fixes, reviewed before merge):

- **Shared-attachment authority bypass:** authorities are validated in canonical form.
  Percent-escapes are refused (`content://0%40mms/...` previously reached the MMS provider), a
  `N@` user-ID prefix is allowed only for our own user, encoded `..` segments are refused, and
  unresolvable providers are rejected. The "Attach" tap now uses the sender's real grant.
- **App lock:** Class 3 biometrics or device credential on Android 11+ (Android 8-10 keep the
  only supported weak+credential combination), an auth-bound Keystore key checked via
  CryptoObject, and fresh authentication for lock on/off, timeout, showing OTPs or lock-screen
  content, and backup export/restore.
- **Backups:** PBKDF2-HMAC-SHA256 at 600k iterations with the KDF id in the authenticated v2
  header (v1/310k files still restore), and a strength policy for new backup passwords.

Also in v1.0.2: turning off "Confirm before sending" and showing contact names on the widget
need a fresh authentication.
