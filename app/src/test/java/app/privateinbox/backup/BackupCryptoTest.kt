package app.privateinbox.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
