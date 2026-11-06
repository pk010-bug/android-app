# PrivateInbox

A privacy-first smart SMS app for Android. PrivateInbox sorts your messages into clear
categories, surfaces one-time passwords, and (soon) turns bank SMS into spending insights —
**without ever connecting to the internet.**

## Privacy principles

1. **No network, enforced by the OS.** The app does not request `android.permission.INTERNET`
   (and the manifest actively strips it if a library ever tries to add it). Android will refuse
   any socket this app tries to open.
2. **Your data stays in the app sandbox.** Messages, categories, OTPs, transactions and rules are
   stored locally with Room and DataStore. Nothing is uploaded, synced, or mirrored.
3. **No trackers of any kind.** No analytics, crash reporting, ads, Firebase, or Google Play
   Services. The build fails if such a dependency appears (see the privacy guard in
   `app/build.gradle.kts`).
4. **No silent backups.** App data is excluded from Android cloud backup and device-to-device
   transfer (`allowBackup=false` + `data_extraction_rules.xml`).
5. **Transparent sorting.** Categories come from plain, human-readable JSON regex rules
   (`app/src/main/assets/default_rules.json`). Every message records which rule placed it, so
   you can always see *why* it landed where it did.
6. **Minimum permissions.** Only what the default-SMS-app role needs, plus read-only contacts
   for sender names, local notifications, and (only when you open the SIM picker) phone state
   to list your SIMs.
7. **Logs never contain content.** All logging goes through `SafeLog`, which redacts numbers,
   sender IDs and emails; unit tests fail if code logs any other way.
8. **Nothing sends by itself.** Links and shares from other apps (`sms:`, `smsto:`, share sheet…)
   only fill in a draft. A message goes out only when you tap **Send** in PrivateInbox.
9. **No direct calls.** The app has no `CALL_PHONE` permission. The call button opens your phone's
   dialer with the number filled in, and you press call there.
10. **Encrypted at rest.** The local database is encrypted with SQLCipher (AES-256). Its key is
    random and protected by the Android Keystore.
11. **Careful with attachments.** Only `content://` images from other apps are accepted; they are
    checked by their actual bytes, size-limited (5 MiB), and copied into private storage right away.
12. **OTPs stay off the lock screen.** Codes are never shown on a locked screen, copied codes are
    marked sensitive, and OTP/finance/chat screens can block screenshots.

Security details and how to report a vulnerability privately: [SECURITY.md](SECURITY.md).

The longer version lives in [docs/privacy.md](docs/privacy.md).

## Tech stack

- Kotlin 2.0, Jetpack Compose, Material 3 (teal/indigo privacy palette, light + dark)
- Navigation Compose, Room (KSP), DataStore, WorkManager, AndroidX Biometric
- kotlinx.serialization (rule JSON parsing — local only)
- SQLCipher for Android (`net.zetetic:sqlcipher-android`) for encrypted Room storage
- Min SDK 26 · target/compile SDK 35 · package `app.privateinbox`
- Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`)

## Project layout

```
app/src/main/java/app/privateinbox/
├── PrivateInboxApp.kt          # Application + tiny manual DI
├── MainActivity.kt             # Compose entry point; triages every incoming Intent
├── AppContainer.kt             # Manual DI + receive pipeline
├── backup/                     # Encrypted backup format + export/restore
├── compose/                    # IntentTriage: external intents → drafts only
├── attachments/                # AttachmentPolicy (URI + magic bytes + size) and importer
├── calls/                      # Dialer (ACTION_DIAL only)
├── navigation/                 # DeepLinks allow-list
├── notifications/              # OTP notifications (lock-screen safe) + copy receiver
├── security/                   # SafeLog, Redactor, PendingIntents, Keystore DB key, FLAG_SECURE
├── domain/                     # Category enum, plain models
├── data/
│   ├── db/                     # Room entities, DAOs, database
│   ├── repo/                   # MessageRepository: single write path
│   ├── telephony/              # System SMS provider + contacts bridge
│   ├── scam/                   # ScamDetector heuristics
│   ├── finance/                # Transaction + bill parsers, monthly summary
│   ├── rules/                  # RuleEngine, JSON defaults + user rules
│   ├── otp/                    # OtpExtractor
│   └── prefs/                  # DataStore settings
├── sms/                        # SmsSender (tap-only), receivers, SIM manager, role helper
├── work/                       # Import, recycle-bin purge, bill reminder workers
└── ui/
    ├── navigation/             # NavHost + bottom bar (Inbox / Finance / Alerts / Settings)
    ├── theme/                  # Material 3 colours & type
    ├── inbox/ conversation/ compose/ finance/ alerts/ settings/
    ├── search/ rules/ blocked/ recycle/ backup/ onboarding/ lock/ common/
```

## Open in Android Studio

1. Install Android Studio (Ladybug 2024.2 or newer) with SDK Platform 35.
2. *File → Open…* and select the `PrivateInbox` folder (the one containing `settings.gradle.kts`).
3. Let Gradle sync, pick a device/emulator (API 26+), and press **Run**.
4. To receive real SMS, set PrivateInbox as the default SMS app (the role request helper lives in
   `sms/DefaultSmsApp.kt`; the onboarding UI for it is on the roadmap).

From the command line: `./gradlew testDebugUnitTest` runs the unit tests and
`./gradlew assembleDebug` builds the APK (requires `ANDROID_HOME` or a `local.properties`
pointing at your SDK). Both run `verifyDebugManifestPermissions`, which fails the build if the
merged manifest ever requests `INTERNET`, `CALL_PHONE`, location, or the advertising ID.

## Features

**Shipped in v0.2 (builds; 102 JVM unit tests pass; not yet verified on a physical device):**
- Default SMS app: onboarding + role request, receive (`SMS_DELIVER`), write to the system SMS
  store, incremental import of existing messages, read-only mode when not default
- Smart inbox: Important / Personal / OTP / Promotional / Spam / Unknown, with unread counts per
  category, pin, mute, archive, mark read, and "Move to category" (learns a sender rule)
- Conversation view: chat bubbles, OTP "copy code" chip (sensitive clipboard), "Why is this
  here?" explanations, scam warning banner with reasons, dial-only call button
- Sending: tap-only, with a confirmation dialog (recipient, SIM, text) on by default; dual-SIM
  picker; delivery status (Sending / Sent / Failed)
- Smart inbox rules manager: your own regex rules (always win), built-in rules shown read-only,
  rule tester, re-apply to all messages
- Scam heuristics: short/IP/APK links, scam-prone domains, KYC/blocking threats, prizes,
  "share your OTP", pay-to-receive, brand impersonation from personal numbers, job scams
- Finance dashboard from bank/card/UPI SMS: monthly spent/received, top merchants, transactions
- Bills & alerts: due-date + amount detection, reminders 3 days before, mark paid; recent OTPs;
  suspicious messages
- Search, block/unblock, recycle bin (restore / delete forever / auto-purge after 7-90 days)
- Encrypted local backup & restore (password, AES-256-GCM + PBKDF2, file you choose)
- App lock (biometric / device credential) with timeout; theme (system / light / dark)
- Composer: recipient chips, system contact picker (needs no permission), optional on-device
  contact suggestions (READ_CONTACTS)
- Scheduled send (SMS): pick date/time, confirm once; survives reboot (WorkManager); pending list
  per conversation and in Settings → Scheduled messages, with cancel
- Groups: send one text to up to 20 people (each gets an individual SMS); group threads
- MMS receive: photos and text through the system MMS service, shown as image bubbles with a
  full-screen viewer; other media types appear as a placeholder
- MMS send: one photo (+ text) per message, re-encoded as metadata-free JPEG sized for the carrier;
  multiple recipients become a group MMS

**Still missing / known gaps:**
- MMS is not written to the system MMS store (other SMS apps won't see it); backups don't include
  MMS images; video/audio/vCard MMS aren't displayed; incoming group MMS appear in the sender's
  1:1 thread (tagged "Group MMS") because we deliberately don't read your own phone number
- Scheduled send is SMS-only and may fire a few minutes late in Doze; >24h late = not sent
- Per-conversation notification sounds, home-screen widget, instrumented UI tests
- Signed reproducible release build (planned as a separate step)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
