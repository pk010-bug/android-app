# Release signing (F-Droid-style)

PrivateInbox release APKs are signed with a dedicated RSA-4096 keystore.

- Keystore path (local, **do not commit**): `release/privateinbox-release.jks`
- Alias: `privateinbox`
- Password file (local, **do not commit**): `release/.keystore-pass`
- Both paths are gitignored.

F-Droid rebuilds from source with its own reproducible pipeline; for self-hosted
Obtainium/GitHub Releases, ship the signed APK from this key and keep the
keystore backed up offline. Losing the keystore means users cannot update over
the same signing identity.
