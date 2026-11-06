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

    @Test fun autoDownloadOnlyForKnownSendersWithTheSettingOn() {
        assertTrue(MmsDownloadPolicy.autoDownload(settingOn = true, decision = Decision.ALLOW))
        assertFalse(MmsDownloadPolicy.autoDownload(settingOn = false, decision = Decision.ALLOW))
        listOf(Decision.WARN_UNKNOWN_SENDER, Decision.REFUSE_BLOCKED, Decision.REFUSE_TOO_LARGE, Decision.REFUSE_BAD_LOCATION)
            .forEach { assertFalse(it.name, MmsDownloadPolicy.autoDownload(settingOn = true, decision = it)) }
    }

    @Test fun stuckDownloadsBecomeRetryable() {
        val s = app.privateinbox.data.db.PendingMmsState.DOWNLOADING
        val t = 1_000_000L
        assertEquals(MmsDownloadPolicy.UiState.DOWNLOADING, MmsDownloadPolicy.uiState(s, t, t + MmsDownloadPolicy.STUCK_AFTER_MS - 1))
        assertEquals(MmsDownloadPolicy.UiState.RETRY, MmsDownloadPolicy.uiState(s, t, t + MmsDownloadPolicy.STUCK_AFTER_MS))
        assertEquals(MmsDownloadPolicy.UiState.RETRY, MmsDownloadPolicy.uiState(app.privateinbox.data.db.PendingMmsState.FAILED, t, t))
        assertEquals(MmsDownloadPolicy.UiState.DOWNLOAD, MmsDownloadPolicy.uiState(app.privateinbox.data.db.PendingMmsState.WAITING, t, t))
    }

    @Test fun pipelineDefersResetsAndTagsAttempts() {
        val files = app.privateinbox.TestFiles.mainSources()
        val c = files.single { it.name == "AppContainer.kt" }.readText()
        assertTrue("undownloaded MMS are reported as deferred", c.contains("notifyDeferred(tid, subscriptionId)"))
        assertTrue("auto-download needs the setting + ALLOW", c.contains("MmsPolicy.autoDownload(settings.autoDownloadMmsFromContacts.first(), decision)"))
        assertTrue("stuck rows can be re-claimed", c.contains("staleBefore = now - MmsPolicy.STUCK_AFTER_MS"))
        assertTrue("late callbacks only fail their own attempt", c.contains("failAttempt(placeholderId, attempt"))
        val daos = files.single { it.name == "Daos.kt" }.readText()
        assertTrue(daos.contains("state != 'DOWNLOADING' OR updatedAt < :staleBefore"))
        assertTrue(files.single { it.name == "MainActivity.kt" }.readText().contains("resetStuckMmsDownloads()"))
        assertTrue(files.single { it.name == "Workers.kt" }.readText().contains("resetStuckMmsDownloads()"))
        assertTrue(files.single { it.name == "MmsDownloadedReceiver.kt" }.readText().contains("EXTRA_ATTEMPT"))
    }
}
