package app.privateinbox.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val pw = "correct horse".toCharArray()
    private val fast = 10_000

    @Test fun roundTrip() {
        val plain = "hello backup".repeat(100).toByteArray()
        val blob = BackupCrypto.encrypt(plain, pw, fast)
        assertArrayEquals(plain, BackupCrypto.decrypt(blob, "correct horse".toCharArray()))
        assertFalse("plaintext must not appear", String(blob, Charsets.ISO_8859_1).contains("hello backup"))
    }

    @Test(expected = BackupCrypto.BadPasswordOrCorrupt::class)
    fun wrongPassword() {
        BackupCrypto.decrypt(BackupCrypto.encrypt("x".toByteArray(), pw, fast), "wrong password".toCharArray())
    }

    @Test fun tamperingAnyByteFails() {
        val blob = BackupCrypto.encrypt("secret data".toByteArray(), pw, fast)
        listOf(6, 12, 30, blob.size - 1).forEach { i ->
            val t = blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            var failed = false
            try {
                BackupCrypto.decrypt(t, "correct horse".toCharArray())
            } catch (e: BackupCrypto.BadPasswordOrCorrupt) {
                failed = true
            }
            assertEquals("byte $i", true, failed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPasswordRejected() {
        BackupCrypto.encrypt("x".toByteArray(), "short".toCharArray(), fast)
    }

    // F-11: the old 8-character minimum is no longer enough for NEW backups.
    @Test fun weakPasswordsRejectedForNewBackups() {
        listOf("password", "password1234", "Password123!", "aaaaaaaaaaaa", "123456789012", "qwertyuiop12", "elevenchars").forEach { weak ->
            var rejected = false
            try {
                BackupCrypto.encrypt("x".toByteArray(), weak.toCharArray(), fast)
            } catch (e: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(weak, rejected)
        }
    }

    @Test fun newBackupsUseVersion2HeaderWithStrongKdf() {
        assertTrue(BackupCrypto.DEFAULT_ITERATIONS >= 600_000)
        val blob = BackupCrypto.encrypt("x".toByteArray(), pw, fast)
        assertEquals(2, blob[4].toInt())        // format version
        assertEquals(1, blob[5].toInt())        // KDF id: PBKDF2-HMAC-SHA256
        assertEquals(fast, BackupCrypto.kdfIterations(blob))
    }

    @Test fun productionDefaultIs600kAndRoundTrips() {
        val blob = BackupCrypto.encrypt("prod".toByteArray(), pw)
        assertEquals(BackupCrypto.DEFAULT_ITERATIONS, BackupCrypto.kdfIterations(blob))
        assertArrayEquals("prod".toByteArray(), BackupCrypto.decrypt(blob, "correct horse".toCharArray()))
    }

    @Test fun tamperingKdfParamsFails() {
        val blob = BackupCrypto.encrypt("secret".toByteArray(), pw, fast)
        // KDF id (byte 5) and iteration count (bytes 6..9) are authenticated header fields.
        listOf(5, 7, 9).forEach { i ->
            val t = blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            var failed = false
            try {
                BackupCrypto.decrypt(t, "correct horse".toCharArray())
            } catch (e: BackupCrypto.BadPasswordOrCorrupt) {
                failed = true
            }
            assertTrue("byte $i", failed)
        }
    }

    /**
     * Backward compatibility: a real version-1 file written by PrivateInbox 1.0.1's BackupCrypto
     * (310,000 iterations, password "password", which met the old 8-character rule) must still restore.
     */
    @Test fun legacyVersion1BackupStillRestores() {
        val legacy = java.util.Base64.getDecoder().decode(LEGACY_V1)
        assertEquals(1, legacy[4].toInt())
        assertEquals(BackupCrypto.LEGACY_ITERATIONS, BackupCrypto.kdfIterations(legacy))
        assertEquals(
            "{\"legacy\":\"v1 backup made by PrivateInbox 1.0.1\"}",
            String(BackupCrypto.decrypt(legacy, "password".toCharArray())),
        )
    }

    @Test(expected = BackupCrypto.BadPasswordOrCorrupt::class)
    fun legacyVersion1WrongPasswordRejected() {
        BackupCrypto.decrypt(java.util.Base64.getDecoder().decode(LEGACY_V1), "password1".toCharArray())
    }

    @Test(expected = BackupCrypto.BadPasswordOrCorrupt::class)
    fun unknownVersionRejected() {
        val blob = BackupCrypto.encrypt("x".toByteArray(), pw, fast).also { it[4] = 3 }
        BackupCrypto.decrypt(blob, pw)
    }

    @Test(expected = BackupCrypto.BadPasswordOrCorrupt::class)
    fun garbageRejected() {
        BackupCrypto.decrypt("not a backup file at all, definitely not".toByteArray(), pw)
    }

    @Test fun jsonRoundTrip() {
        val p = BackupPayload(
            createdAt = 1L,
            conversations = listOf(BConversation(1, "HDFCBK", "AX-HDFCBK", muted = true)),
            messages = listOf(BMessage(1, 1, "AX-HDFCBK", "hi ₹", 5, true, false, "RECEIVED")),
            rules = listOf(BRule("user-1", "IMPORTANT", bodyPatterns = "salary")),
            blocked = listOf(BBlocked("SPAMMR", "VM-SPAMMR", 3)),
        )
        assertEquals(p, BackupJson.decode(BackupJson.encode(p)))
    }

    private companion object {
        /** Generated with the unmodified 1.0.1 BackupCrypto.encrypt(plain, "password") (v1, 310k). */
        const val LEGACY_V1 =
            "UElCSwEABLrwOVvA8uPmL/sJIkzCmY1BhxdN9m1PHKhl73avd9Wc6rU8GglTM6t/FoWGI20noezSBSGHbE9JhRtrvud57Lxh+rViRwgt9aS+RLM0gHZqRFR17CZtIvGvAPKvL6ChG3HcGdQzyNBNQuuzWZH452TRXbY="
    }
}
