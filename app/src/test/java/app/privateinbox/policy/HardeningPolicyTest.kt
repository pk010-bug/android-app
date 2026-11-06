package app.privateinbox.policy

import app.privateinbox.TestFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guards for the v1.0.1 hardening (things that need a device to observe). */
class HardeningPolicyTest {
    private fun src(path: String) = TestFiles.module("src/main/java/app/privateinbox/$path").readText()

    @Test fun sharedAttachmentsAreNotCopiedOnIntentArrival() {
        val main = src("MainActivity.kt")
        assertFalse("MainActivity must not import shared streams itself", Regex("""\.import\(|::import""").containsMatchIn(main))
        assertTrue("grant-aware source check expected", main.contains("FLAG_GRANT_READ_URI_PERMISSION"))
    }

    @Test fun appLockSecuresWholeWindow() {
        val main = src("MainActivity.kt")
        assertTrue(main.contains("SecureWindow.setAppWide(this, true)"))
        assertTrue(main.contains("appLockEnabled.collect { SecureWindow.setAppWide"))
        assertTrue(src("security/SecureScreen.kt").contains("setRecentsScreenshotEnabled"))
    }

    @Test fun sendAndConfirmUiIsTapjackGuarded() {
        val composer = src("ui/compose/MessageComposer.kt")
        assertTrue("composer + both confirm dialogs", Regex("TapjackGuard\\(\\)").findAll(composer).count() >= 3)
        assertTrue(src("security/TapjackGuard.kt").contains("filterTouchesWhenObscured = true"))
    }

    @Test fun lockScreenHidingIsDefaultOn() {
        val settings = src("data/prefs/SettingsStore.kt")
        assertTrue(settings.contains("it[Keys.hideOnLockScreen] ?: true"))
        assertTrue(src("notifications/MessageNotifier.kt").contains("VISIBILITY_SECRET"))
    }

    @Test fun mmsReceiversCatchThrowable() {
        assertTrue(src("sms/MmsReceiver.kt").contains("catch (t: Throwable)"))
        assertTrue(src("sms/MmsDownloadedReceiver.kt").contains("catch (t: Throwable)"))
    }
}
