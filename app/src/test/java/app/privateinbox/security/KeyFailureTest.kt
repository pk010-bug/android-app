package app.privateinbox.security

import app.privateinbox.data.db.PrivateInboxDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeyFailureTest {
    @Test fun permanentVersusTransient() {
        assertTrue(KeyFailure.isPermanent(javax.crypto.AEADBadTagException("tag")))
        assertTrue(KeyFailure.isPermanent(RuntimeException(java.security.UnrecoverableKeyException("x"))))
        assertFalse(KeyFailure.isPermanent(java.security.KeyStoreException("Keystore not initialized")))
        assertFalse(KeyFailure.isPermanent(java.security.ProviderException("binder died")))
        assertFalse(KeyFailure.isPermanent(java.io.IOException("io")))
        assertFalse(KeyFailure.isPermanent(java.security.InvalidKeyException("busy")))
    }

    private val retry = PrivateInboxDatabase.Companion

    @Test fun transientFailuresRetryAndNeverWipe() {
        var calls = 0
        var wiped = false
        try {
            retry.passphraseWithRetry(
                load = { calls++; throw DatabaseKeyManager.KeyTemporarilyUnavailableException("busy") },
                onPermanentLoss = { wiped = true; ByteArray(32) },
                sleep = {}, log = {},
            )
            fail("expected DatabaseTemporarilyUnavailableException")
        } catch (e: PrivateInboxDatabase.Companion.DatabaseTemporarilyUnavailableException) {
            // expected
        }
        assertEquals(3, calls)
        assertFalse("F-13: a transient Keystore error must never wipe the database", wiped)
    }

    @Test fun transientThenSuccess() {
        var calls = 0
        val key = retry.passphraseWithRetry(
            load = { if (calls++ == 0) throw DatabaseKeyManager.KeyTemporarilyUnavailableException("busy") else byteArrayOf(1, 2) },
            onPermanentLoss = { fail("no wipe"); ByteArray(0) },
            sleep = {}, log = {},
        )
        assertArrayEquals(byteArrayOf(1, 2), key)
    }

    @Test fun onlyPermanentLossWipes() {
        var wiped = false
        retry.passphraseWithRetry(
            load = { throw DatabaseKeyManager.KeyUnavailableException("gone") },
            onPermanentLoss = { wiped = true; ByteArray(32) },
            sleep = {}, log = {},
        )
        assertTrue(wiped)
    }

    @Test fun keyManagerNeverCreatesAKeyWhileUnwrapping() {
        val src = app.privateinbox.TestFiles.mainSources().single { it.name == "DatabaseKeyManager.kt" }.readText()
        assertTrue(src.contains("existingWrappingKey()"))
        assertTrue(src.contains("KeyTemporarilyUnavailableException"))
        val db = app.privateinbox.TestFiles.mainSources().single { it.name == "PrivateInboxDatabase.kt" }.readText()
        assertEquals("deleteDatabase only on permanent loss", 1, Regex("""deleteDatabase\(""").findAll(db).count())
        assertTrue(db.substringBefore("deleteDatabase(").contains("onPermanentLoss"))
    }
}
