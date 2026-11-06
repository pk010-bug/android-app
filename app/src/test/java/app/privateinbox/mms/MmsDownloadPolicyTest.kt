package app.privateinbox.mms

import app.privateinbox.mms.MmsDownloadPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsDownloadPolicyTest {
    private val url = "http://mmsc.carrier.example/mms/abc123"

    @Test fun blockedSendersAreNeverDownloaded() {
        assertEquals(Decision.REFUSE_BLOCKED, MmsDownloadPolicy.decide(blocked = true, knownSender = true, sizeBytes = 10, contentLocation = url))
        assertEquals(Decision.REFUSE_BLOCKED, MmsDownloadPolicy.decide(blocked = true, knownSender = false, sizeBytes = null, contentLocation = url))
    }

    @Test fun unknownSendersNeedConfirmation() {
        assertEquals(Decision.WARN_UNKNOWN_SENDER, MmsDownloadPolicy.decide(false, knownSender = false, sizeBytes = 1000, contentLocation = url))
        assertEquals(Decision.ALLOW, MmsDownloadPolicy.decide(false, knownSender = true, sizeBytes = 1000, contentLocation = url))
        assertEquals(Decision.ALLOW, MmsDownloadPolicy.decide(false, knownSender = true, sizeBytes = null, contentLocation = "https://mmsc.example/x"))
    }

    @Test fun sizeAndLocationLimits() {
        assertEquals(Decision.REFUSE_TOO_LARGE, MmsDownloadPolicy.decide(false, true, MmsDownloadPolicy.MAX_BYTES + 1, url))
        assertEquals(Decision.ALLOW, MmsDownloadPolicy.decide(false, true, MmsDownloadPolicy.MAX_BYTES, url))
        listOf("file:///sdcard/x", "content://mms/1", "ftp://mmsc/x", "http://", "", "http://a b/c", "javascript:alert(1)", "http://x/" + "a".repeat(3000))
            .forEach { assertEquals(it.take(40), Decision.REFUSE_BAD_LOCATION, MmsDownloadPolicy.decide(false, true, 10, it)) }
        assertTrue(MmsDownloadPolicy.isFetchableLocation("HTTP://MMSC.EXAMPLE/abc"))
        assertFalse(MmsDownloadPolicy.isFetchableLocation("http://mmsc/x\r\nHost: evil"))
    }

    @Test fun placeholderText() {
        assertEquals("Picture message (2 KB). Not downloaded yet.", MmsDownloadPolicy.placeholderBody(1025, blocked = false))
        assertEquals("Picture message. Not downloaded yet.", MmsDownloadPolicy.placeholderBody(null, blocked = false))
        assertTrue(MmsDownloadPolicy.placeholderBody(1000, blocked = true).contains("blocked"))
    }

    @Test fun receiptKeyIsScopedToSubscriptionTransactionAndLocation() {
        assertEquals("-1|T1|http://m/x", MmsDownloadPolicy.receiptKey(null, "T1", " http://m/x "))
        assertEquals("2|T1|http://m/x", MmsDownloadPolicy.receiptKey(2, "T1", "http://m/x"))
        assertFalse(MmsDownloadPolicy.receiptKey(1, "T1", "http://m/x") == MmsDownloadPolicy.receiptKey(2, "T1", "http://m/x"))
        assertFalse(MmsDownloadPolicy.receiptKey(1, "T1", "http://m/x") == MmsDownloadPolicy.receiptKey(1, "T1", "http://m/y"))
        assertEquals(MmsDownloadPolicy.receiptKey(1, "T".repeat(300), "u"), MmsDownloadPolicy.receiptKey(1, "T".repeat(100), "u"))
    }

    @Test fun stuckDownloadsBecomeRetryable() {
        val s = app.privateinbox.data.db.PendingMmsState.DOWNLOADING
        val t = 1_000_000L
        assertEquals(MmsDownloadPolicy.UiState.DOWNLOADING, MmsDownloadPolicy.uiState(s, t, t + MmsDownloadPolicy.STUCK_AFTER_MS - 1))
        assertEquals(MmsDownloadPolicy.UiState.RETRY, MmsDownloadPolicy.uiState(s, t, t + MmsDownloadPolicy.STUCK_AFTER_MS))
        assertEquals(MmsDownloadPolicy.UiState.RETRY, MmsDownloadPolicy.uiState(app.privateinbox.data.db.PendingMmsState.FAILED, t, t))
        assertEquals(MmsDownloadPolicy.UiState.DOWNLOAD, MmsDownloadPolicy.uiState(app.privateinbox.data.db.PendingMmsState.WAITING, t, t))
        val c = app.privateinbox.data.db.PendingMmsState.COMPLETING
        assertEquals(MmsDownloadPolicy.UiState.DOWNLOADING, MmsDownloadPolicy.uiState(c, t, t))
        assertEquals(MmsDownloadPolicy.UiState.RETRY, MmsDownloadPolicy.uiState(c, t, t + MmsDownloadPolicy.STUCK_AFTER_MS))
    }

    @Test fun pipelineDefersResetsAndTagsAttempts() {
        val files = app.privateinbox.TestFiles.mainSources()
        val c = files.single { it.name == "AppContainer.kt" }.readText()
        assertTrue("undownloaded MMS are reported as deferred", c.contains("notifyDeferred(tid, subscriptionId)"))
        assertTrue("stuck rows can be re-claimed", c.contains("staleBefore = now - MmsPolicy.STUCK_AFTER_MS"))
        assertTrue("late callbacks only fail their own attempt", c.contains("failAttempt(placeholderId, attempt"))
        val daos = files.single { it.name == "Daos.kt" }.readText()
        assertTrue(daos.contains("state IN ('WAITING', 'FAILED') OR (state IN ('DOWNLOADING', 'COMPLETING') AND updatedAt < :staleBefore)"))
        assertTrue(files.single { it.name == "MainActivity.kt" }.readText().contains("resetStuckMmsDownloads()"))
        assertTrue(files.single { it.name == "Workers.kt" }.readText().contains("resetStuckMmsDownloads()"))
        assertTrue(files.single { it.name == "MmsDownloadedReceiver.kt" }.readText().contains("EXTRA_ATTEMPT"))
    }

    @Test fun nothingIsEverDownloadedAutomatically() {
        // RB-1: no auto-download setting, no auto-download policy, and the receipt path never starts one.
        val files = app.privateinbox.TestFiles.mainSources()
        files.forEach { f ->
            val t = f.readText()
            assertFalse(f.name, Regex("""autoDownload|AutoDownloadMms""").containsMatchIn(t))
        }
        val c = files.single { it.name == "AppContainer.kt" }.readText()
        val receive = c.substringAfter("suspend fun receiveMmsNotification").substringBefore("/** What tapping Download")
        assertFalse(receive.contains("startMmsDownload"))
        assertFalse(receive.contains(".download("))
        assertTrue("blocked senders get no pending row", receive.contains("!blocked && MmsPolicy.isFetchableLocation(location)"))
        val callers = files.filter { "startMmsDownload(" in it.readText() }.map { it.name }.toSet()
        assertEquals("only the tap in the conversation starts a download", setOf("AppContainer.kt", "ConversationScreen.kt"), callers)
        val settings = files.single { it.name == "SettingsStore.kt" }.readText()
        assertTrue("legacy preference is removed on upgrade", settings.contains("it.remove(Keys.obsoleteMmsAutoKey)"))
        assertTrue(files.single { it.name == "MainActivity.kt" }.readText().contains("dropLegacyMmsAutoDownload()"))
    }

    @Test fun receiptAndCompletionAreAtomicAndSingleWinner() {
        val files = app.privateinbox.TestFiles.mainSources()
        val c = files.single { it.name == "AppContainer.kt" }.readText()
        val receive = c.substringAfter("suspend fun receiveMmsNotification").substringBefore("/** What tapping Download")
        // HB-3: the receipt claim (PK insert) and the placeholder + pending row are one transaction.
        assertTrue(Regex("""withTransaction \{[\s\S]*insertReceipt\(receipt\) == -1L[\s\S]*ingestIncoming[\s\S]*dao\.insert\(""").containsMatchIn(receive))
        assertFalse("no race-prone exists check", c.contains("existsTransaction"))
        val finish = c.substringAfter("suspend fun finishMmsDownload")
        assertTrue(finish.contains("beginCompletion(placeholderId"))
        assertTrue(Regex("""withTransaction \{[\s\S]*receiveMms\(pdu, subscriptionId, notify = false\)[\s\S]*purge\(listOf\(placeholderId\)\)""").containsMatchIn(finish))
        assertTrue(finish.contains("failCompletion(placeholderId"))
        val daos = files.single { it.name == "Daos.kt" }.readText()
        assertTrue(daos.contains("state != 'COMPLETING'"))
        assertTrue(daos.contains("OnConflictStrategy.IGNORE"))
        // Never log sender or location on the MMS paths.
        val mmsFiles = setOf("AppContainer.kt", "MmsReceiver.kt", "MmsDownloadedReceiver.kt", "MmsTransport.kt")
        files.filter { it.name in mmsFiles }.forEach { f ->
            Regex("""SafeLog\.[a-z]\([^\n]*""").findAll(f.readText()).forEach { m ->
                assertFalse("${f.name}: ${m.value}", Regex("""\$\{?(from|pdu|contentLocation|location|address|tid|transactionId)""").containsMatchIn(m.value))
            }
        }
    }
}
