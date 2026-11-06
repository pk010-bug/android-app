# Security policy

PrivateInbox handles personal messages, bank alerts and login codes, so we treat security
reports as a priority.

## Reporting a vulnerability (privately)

**Please do not open a public issue, discussion, or pull request for security problems.**

1. Use GitHub's **private vulnerability reporting**: on the repository page go to
   *Security → Report a vulnerability*. This opens a private advisory visible only to the
   maintainers.
   Direct link: <https://github.com/pk010-bug/android-app/security/advisories/new>
   (GitHub Security Advisories for `pk010-bug/android-app`). This is the preferred channel.
2. If that is not available, email the maintainers at the security contact listed in the
   repository profile (`TODO: add security contact address before first public release`;
   until then, use the GitHub advisory link above), with the subject `[PrivateInbox security]`.

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
| MMS download (F-04) | A WAP push creates a placeholder + `pending_mms` row in one transaction, de-duplicated by an `mms_receipts` primary key (subscription, transaction id, content location). Nothing is downloaded automatically from anyone; a download starts only from the user's tap, unknown senders after a warning; blocked senders never (no pending row at receipt; re-checked on every tap). 5 MB cap. Undownloaded MMS get an M-NotifyResp "deferred" (sent by the system MMS service); a download with no callback for 10 min becomes retryable (startup + periodic reset, per-attempt tag ignores late callbacks); exactly one callback can complete a placeholder (`COMPLETING` claim + transaction) (`MmsDownloadPolicy`, `NoNetworkPolicyTest`) | `mms/MmsDownloadPolicy.kt`, `AppContainer.kt`, `sms/MmsReceiver.kt` |
| Network | No `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` or Wi-Fi state permissions (stripped with `tools:node="remove"`, checked on the merged manifest at build time and by `ManifestPolicyTest`); no app-side networking APIs (`NoNetworkPolicyTest`); MMS only via the system MMS service | `AndroidManifest.xml`, `app/build.gradle.kts` |
| Runtime integrity (I-01) | Local-only checks, no SDK, no network: signing-cert SHA-256 pin (release, `privateinbox.releaseCertSha256`), debuggable flag, Frida (maps, thread names, port 27042 via `/proc/net/tcp`), Xposed/LSPosed (classes, maps, stack), debugger (`isDebuggerConnected`, TracerPid), root (su, Magisk/KernelSU files and mounts, test-keys, rw system, Riru/Zygisk loaders). A confirmed cert mismatch, debuggable release, debugger, or Frida/Xposed-specific signal in release re-locks the app once per new signal set (process-wide) and shows a one-time warning; root, zygote-module loaders and weak hints (GLib threads, port 27042) are an informational, dismissible notice unless corroborated by a strong hooking signal. Never blocks; heuristics only | `security/integrity/` |
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

## v1.0.3 hardening

Owner's F-04 / I-01 / no-network work (from the "hardened" zip), rebased onto v1.0.2, plus fixes
for the remaining Low findings of the external review:

- **MMS (F-04):** "Auto-download MMS from contacts" (default on; unknown senders always need a
  tap with a warning, blocked senders never download). Carrier gets M-NotifyResp "deferred" for
  anything left undownloaded and M-Acknowledge.ind after a deferred retrieval. Stuck downloads
  time out after 10 min (reset on app start and by the periodic job) and can be retried; each
  attempt is tagged so a late callback can't clobber a newer attempt. DB v5 (`pending_mms`
  `updatedAt` / `attempt` / `deferredSent`).
- **Runtime integrity (I-01):** release builds pin the signing certificate
  (`db63bd02…bee9`, in `gradle.properties`). High-risk signals only re-lock and warn once; root is
  an informational notice. Nothing is ever blocked.
- **No network-state permissions:** `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` and Wi-Fi state
  are stripped. Verified safe: no WorkManager job sets a network constraint (source test), so
  WorkManager 2.10 schedules them with `NETWORK_TYPE_NONE` and never needs the permission.
- **F-14 send confirmation:** the dialog shows the whole message (scrollable, with a character
  count) instead of the first 300 characters, and drafts prefilled by another app are always
  confirmed, even with "Confirm before sending" off.
- **F-13 database key:** only a permanent key loss (invalidated/missing key, tag mismatch, corrupt
  wrapped file) wipes the encrypted cache. Transient Keystore errors are retried and then show a
  "Try again" screen; the unwrap path never creates a new key.
- **F-05 rule ReDoS:** custom (and restored) rule patterns are rejected if they have nested
  repetition/alternation, back-references, look-arounds, huge repeat counts or many `.*`. All rules
  run on RE2/J (linear time) with a 50 ms per-match budget; unsafe stored rules are skipped.
- **F-15 PendingIntents:** every PendingIntent has its own request-code range per purpose plus an
  Intent identifier (kind + full id) on Android 10+, so notifications can't overwrite each other's
  tap targets.
- **F-12 sender grouping:** last-10-digit merging only when the country prefix is compatible with
  the phone's home country (SIM/network country); other countries' numbers get their own thread
  key. "Move to…" sender rules are anchored to the whole sender (old rules are anchored on load).
  The block list also checks the pre-1.0.3 key, so nobody blocked earlier becomes unblocked.
- **F-06 / F-08:** OTPs are masked in list previews when "Hide OTPs" is on; new "Content-free
  notifications while locked" (default on; turning it off needs fresh authentication).
- **F-07:** copied message text (menu or text selection) is flagged sensitive and cleared after 2 min.
- **F-03:** re-lock on return is decided synchronously in `onStart` from in-memory settings.
- **Docs (I-06):** privacy notes updated; private reporting via GitHub Security Advisories.

Known limits: foreign-number threads created before v1.0.3 start a new thread on the next message;
release builds keep `Log.i/w/e` (redacted); media files rely on Android file-based encryption.

## Post-v1.0.3 fixes (database v6)

Fixes from the review of the rebased v1.0.3 tree. App version is unchanged (1.0.3 / 6); the
database moves to v6.

- **RB-1 / F-04, manual-only MMS:** automatic MMS download is removed completely, including for
  contacts; the "Auto-download MMS from contacts" setting is gone and its stored value is deleted
  on upgrade (nothing reads it, so upgrades are manual-only either way). Downloads start only from
  a tap on the placeholder. Blocked senders are refused when the notification arrives and again at
  every tap; unknown senders are warned first.
- **RB-3, protocol traffic (informative, unchanged):** the system MMS service still sends
  M-NotifyResp "deferred" when a notification arrives and "retrieved" / M-Acknowledge after a
  user-started download, with read/delivery reports refused. This is carrier-network traffic by
  the platform's MMS service; PrivateInbox has no `INTERNET` permission and no networking code,
  but "no INTERNET permission" is not the same as "no MMS-related traffic".
- **RB-2 / F-13, no automatic wipe:** `IllegalBlockSizeException` (which AndroidKeyStore uses to
  wrap generic Keystore errors), `BadPaddingException`, `InvalidAlgorithmParameterException` and
  anything with a `KeyStoreException` / `ProviderException` / `RemoteException` / `IOException`
  cause are treated as possibly transient. Even a definite loss (GCM tag mismatch, invalidated key,
  missing wrapping key, corrupt wrapped file, or a database without its wrapped key) deletes
  nothing: the app shows "Try again", plus "Reset local data…" behind an explicit confirmation
  and a fresh authentication (if a screen lock exists). `PrivateInboxDatabase.resetAfterUserConfirmation`
  is the only code that deletes the database (source test). After a reset, SMS are re-imported from
  the system store; rules, block list, recycle bin, scheduled messages, finance data and MMS
  placeholders are lost.
- **HB-1, integrity lock ordering:** the integrity re-lock is held by a process-wide gate
  (`IntegrityLockGate`) separate from the app-lock state, so loading the app-lock preference can't
  clear it in either order, rotation doesn't drop or repeat it, and each new signal set asks once.
- **HB-2, fewer false positives:** generic Riru/Zygisk loaders are root information, not hooking;
  `gmain`/`gdbus` threads and a listener on port 27042 are weak hints that only count when a
  strong hooking signal corroborates them. Root alone stays informational.
- **HB-3, MMS de-duplication:** new `mms_receipts` table (primary key = subscription | transaction
  id | content location, kept 30 days, back-filled from existing `pending_mms` rows by migration
  5→6). The receipt claim, placeholder and pending row are written in one transaction, so a
  duplicate WAP push creates nothing. Completion is claimed (`COMPLETING`) by one callback only and
  committed with the placeholder removal in one transaction; a failure or process death leaves it
  retryable.

Known limits / residual risk (HB-4, not resolved, by design for availability):
- **Integrity checks fail open:** a check that errors counts as "nothing found"; without a usable
  screen lock a high-risk result only warns (locking would make the app unusable); `/proc/net/tcp`
  is unreadable to apps on Android 10+; root with hiding tools (Shamiko, DenyList, KernelSU
  umount) evades every check. None of this protects data cryptographically.
- **Keystore classification is heuristic:** a real key loss reported with a transient-looking
  cause shows "Try again" repeatedly until the user chooses the reset; nothing is lost meanwhile.
- **MMS:** a downloaded message is mirrored to the system MMS store inside the completion
  transaction; if that transaction rolls back, a provider copy may remain while the placeholder
  stays retryable (a retry can then mirror a second copy). Two simultaneous taps: one wins, the
  other shows "Couldn't start". Receipts older than 30 days are pruned, so a carrier re-sending
  the same notification after that would create a new placeholder.
- Not verified on devices or real carriers yet (see the review's device checklist).
