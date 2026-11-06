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
| Exported surface | Only `MainActivity` plus the three SMS-role components, each guarded by a system-only permission. Everything else, including AndroidX diagnostics receivers, is `exported=false`. No http/custom-scheme deep links; in-app routes validated against an allow-list with numeric ids | `AndroidManifest.xml`, `navigation/DeepLinks.kt`, `ManifestPolicyTest` |
| PendingIntents | Always `FLAG_IMMUTABLE`, always explicit and same-package (enforced at runtime) | `security/PendingIntents.kt` |
| Data at rest | Room encrypted with SQLCipher (AES-256); random 256-bit passphrase wrapped by an AndroidKeyStore AES-GCM key, stored in `noBackupFilesDir`; `allowBackup=false` and all backup / transfer domains excluded | `data/db/PrivateInboxDatabase.kt`, `security/DatabaseKeyManager.kt` |
| Notifications | OTP digits never on the lock screen (public version without code; `VISIBILITY_SECRET` when "Hide OTPs" is on); OTP notifications time out after 10 min; copied codes flagged sensitive (API 33+ `EXTRA_IS_SENSITIVE`) | `notifications/`, `security/SensitiveClipboard.kt` |
| Screens | `FLAG_SECURE` on OTP / finance / conversation screens when app lock or "Hide OTPs" is on | `security/SecureScreen.kt` |
| App lock | BiometricPrompt (biometric or device credential), relocks after a configurable timeout; content is not composed until the lock state is known | `ui/lock/`, `MainActivity.kt` |
| Send confirmation | Default-on dialog shows recipient, SIM, and text before the single-use send token is minted | `ui/compose/MessageComposer.kt` |
| Backups | User-initiated only; PBKDF2-HMAC-SHA256 (310k) → AES-256-GCM with authenticated header; zip-bomb and size guards on restore; restored rules re-validated; no upload path exists | `backup/` |
| Background work | WorkManager jobs are local only (provider import, recycle-bin purge, bill reminders); receivers use `goAsync` and never log content | `work/`, `sms/` |
| Logging | `SafeLog` is the only logger; redacts digits, sender headers, emails; debug output off unless enabled per tag; tests fail on raw `Log.*`/`println` or on passing body/sender/code to SafeLog | `security/SafeLog.kt`, `LogHygieneTest` |
