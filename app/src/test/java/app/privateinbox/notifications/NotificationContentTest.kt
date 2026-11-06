package app.privateinbox.notifications

import app.privateinbox.notifications.NotificationContent.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationContentTest {
    private fun plan(lock: Boolean, contentFree: Boolean = true, hideOtp: Boolean = true, otp: Boolean = false, scam: Boolean = false) =
        NotificationContent.plan(lock, contentFree, hideOtp, otp, scam, scamWarnings = true, displayName = "Alice", body = "secret text 123456")

    @Test fun contentFreeWhileAppLockIsOn() {
        // F-08: nothing readable, not even the sender or the code.
        listOf(false, true).forEach { otp ->
            val p = plan(lock = true, otp = otp) as Plan.Message
            assertEquals(NotificationContent.CONTENT_FREE_TITLE, p.title)
            assertEquals(NotificationContent.CONTENT_FREE_TEXT, p.text)
        }
    }

    @Test fun optionOffOrLockOffKeepsTheUsualBehaviour() {
        assertEquals(Plan.OtpWithCode, plan(lock = true, contentFree = false, otp = true))
        assertEquals(Plan.Message("Alice", "secret text 123456", false), plan(lock = false))
        assertEquals(Plan.Message("Alice", NotificationContent.HIDDEN_CODE_TEXT, true), plan(lock = false, otp = true, scam = true))
    }

    @Test fun containerAndDefaultsAreWired() {
        val files = app.privateinbox.TestFiles.mainSources()
        assertTrue(files.single { it.name == "AppContainer.kt" }.readText().contains("NotificationContent.plan("))
        assertTrue(files.single { it.name == "SettingsStore.kt" }.readText().contains("it[Keys.contentFreeWhenLocked] ?: true"))
    }
}
