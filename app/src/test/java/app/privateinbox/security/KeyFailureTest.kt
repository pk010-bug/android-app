package app.privateinbox.security

import app.privateinbox.data.db.PrivateInboxDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyStoreException
import javax.crypto.IllegalBlockSizeException

class KeyFailureTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun definiteVersusPossiblyTransient() {
        assertTrue(KeyFailure.isPermanent(javax.crypto.AEADBadTagException("tag")))
        assertTrue(KeyFailure.isPermanent(RuntimeException(java.security.UnrecoverableKeyException("x"))))
        assertFalse(KeyFailure.isPermanent(KeyStoreException("Keystore not initialized")))
        assertFalse(KeyFailure.isPermanent(java.security.ProviderException("binder died")))
        assertFalse(KeyFailure.isPermanent(java.io.IOException("io")))
        assertFalse(KeyFailure.isPermanent(java.security.InvalidKeyException("busy")))
        assertFalse(KeyFailure.isPermanent(RuntimeException("unknown")))
    }

    @Test fun illegalBlockSizeAloneIsNeverProofOfKeyLoss() {
        // RB-2: AndroidKeyStore maps any generic Keystore error in doFinal to IllegalBlockSizeException.
        assertFalse(KeyFailure.isPermanent(IllegalBlockSizeException("bare")))
        assertFalse(KeyFailure.isPermanent(IllegalBlockSizeException().apply { initCause(KeyStoreException("system busy")) }))
        assertFalse(KeyFailure.isPermanent(javax.crypto.BadPaddingException("invalid argument")))
        assertFalse(KeyFailure.isPermanent(java.security.InvalidAlgorithmParameterException("x")))
    }

    @Test fun transientNestedCausesStayTransient() {
        val nested = RuntimeException(
            "wrapped",
            IllegalBlockSizeException().apply { initCause(java.security.ProviderException("keystore2", KeyStoreException("-28"))) },
        )
        assertFalse(KeyFailure.isPermanent(nested))
        // AndroidKeyStore's UnrecoverableKeyException("Failed to obtain information about key") wraps a KeyStoreException.
        assertFalse(KeyFailure.isPermanent(java.security.UnrecoverableKeyException("info").apply { initCause(KeyStoreException("busy")) }))
        // Subclass of a transient marker is recognised too.
        class DeadBinder : java.io.IOException("dead")
        assertFalse(KeyFailure.isPermanent(IllegalStateException(DeadBinder())))
    }

    @Test fun permanentInvalidationIsRecognisedEvenWhenWrapped() {
        // KM_ERROR_VERIFICATION_FAILED: AEADBadTagException with a KeyStoreException cause stays definite.
        assertTrue(KeyFailure.isPermanent(javax.crypto.AEADBadTagException("tag").apply { initCause(KeyStoreException("verify")) }))
        assertTrue(KeyFailure.isPermanent(RuntimeException(IllegalStateException(javax.crypto.AEADBadTagException("x")))))
        // (KeyPermanentlyInvalidatedException is matched by class name; Android stubs can't be constructed on the JVM.)
    }

    private val retry = PrivateInboxDatabase.Companion

    @Test fun transientFailuresRetryAndNeverWipe() {
        var calls = 0
        try {
            retry.passphraseWithRetry(
                load = { calls++; throw DatabaseKeyManager.KeyTemporarilyUnavailableException("busy") },
                sleep = {}, log = {},
            )
            fail("expected DatabaseTemporarilyUnavailableException")
        } catch (e: PrivateInboxDatabase.Companion.DatabaseTemporarilyUnavailableException) {
            // expected
        }
        assertEquals(3, calls)
    }

    @Test fun transientThenSuccess() {
        var calls = 0
        val key = retry.passphraseWithRetry(
            load = { if (calls++ == 0) throw DatabaseKeyManager.KeyTemporarilyUnavailableException("busy") else byteArrayOf(1, 2) },
            sleep = {}, log = {},
        )
        assertArrayEquals(byteArrayOf(1, 2), key)
    }

    @Test fun permanentLossIsReportedAndFilesArePreserved() {
        val db = tmp.newFile("privateinbox.db").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
        val wrapped = tmp.newFile("db_key.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            retry.obtainPassphrase(
                load = { throw DatabaseKeyManager.KeyUnavailableException("gone") },
                wrappedKeyExists = wrapped.exists(), databaseExists = db.exists(), sleep = {}, log = {},
            )
            fail("expected DatabaseKeyLostException")
        } catch (e: PrivateInboxDatabase.Companion.DatabaseKeyLostException) {
            // expected: the UI shows the recovery screen
        }
        assertArrayEquals("RB-2: no automatic wipe", byteArrayOf(9, 8, 7, 6), db.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), wrapped.readBytes())
    }

    @Test fun databaseWithoutItsWrappedKeyIsNotGivenANewKey() {
        var loads = 0
        try {
            retry.obtainPassphrase(load = { loads++; ByteArray(32) }, wrappedKeyExists = false, databaseExists = true, sleep = {}, log = {})
            fail("expected DatabaseKeyLostException")
        } catch (e: PrivateInboxDatabase.Companion.DatabaseKeyLostException) {
            // expected
        }
        assertEquals("must not create a fresh key over an existing database", 0, loads)
        // Fresh install: no database yet, a key may be created.
        assertArrayEquals(ByteArray(32), retry.obtainPassphrase(load = { ByteArray(32) }, wrappedKeyExists = false, databaseExists = false, sleep = {}, log = {}))
    }

    @Test fun deletionOnlyBehindUserConfirmation() {
        val src = app.privateinbox.TestFiles.mainSources().single { it.name == "DatabaseKeyManager.kt" }.readText()
        assertTrue(src.contains("existingWrappingKey()"))
        assertTrue(src.contains("KeyTemporarilyUnavailableException"))
        val db = app.privateinbox.TestFiles.mainSources().single { it.name == "PrivateInboxDatabase.kt" }.readText()
        assertEquals("deleteDatabase in exactly one place", 1, Regex("""deleteDatabase\(""").findAll(db).count())
        assertEquals(1, Regex("""\.destroy\(\)""").findAll(db).count())
        val reset = db.substringAfter("fun resetAfterUserConfirmation(").substringBefore("internal fun obtainPassphrase")
        assertTrue(reset.contains("deleteDatabase(") && reset.contains(".destroy()"))
        val callers = app.privateinbox.TestFiles.mainSources().filter { "resetAfterUserConfirmation(" in it.readText() }.map { it.name }.toSet()
        assertEquals(setOf("PrivateInboxDatabase.kt", "MainActivity.kt"), callers)
        val main = app.privateinbox.TestFiles.mainSources().single { it.name == "MainActivity.kt" }.readText()
        val resetFn = main.substringAfter("private fun resetLocalData()")
        assertTrue("fresh auth before reset", Regex("""requireFreshAuth\([\s\S]{0,400}resetAfterUserConfirmation""").containsMatchIn(resetFn))
        val screen = app.privateinbox.TestFiles.mainSources().single { it.name == "DatabaseStatusScreen.kt" }.readText()
        assertTrue("explicit confirmation dialog", screen.contains("AlertDialog") && screen.contains("onReset()"))
        assertFalse("no other file deletes the database", app.privateinbox.TestFiles.mainSources().any { it.name != "PrivateInboxDatabase.kt" && "deleteDatabase(" in it.readText() })
    }
}
