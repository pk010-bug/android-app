package app.privateinbox.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendConfirmPolicyTest {
    @Test fun externallyPrefilledDraftsAreAlwaysConfirmed() {
        assertTrue(SendConfirmPolicy.mustConfirm(confirmSetting = false, externallyPrefilled = true))
        assertTrue(SendConfirmPolicy.mustConfirm(confirmSetting = true, externallyPrefilled = false))
        assertFalse(SendConfirmPolicy.mustConfirm(confirmSetting = false, externallyPrefilled = false))
    }

    @Test fun summaryShowsTheWholeText() {
        // F-14: text hidden past the old 300-character preview must be visible in the dialog.
        val hidden = "a".repeat(400) + " SECRET-TAIL " + "b".repeat(4_500)
        val s = SendConfirmPolicy.summary(listOf("+15551234567"), "SIM 1", hasPhoto = false, text = hidden, externallyPrefilled = true)
        assertTrue(s.contains(hidden))
        assertTrue(s.contains("SECRET-TAIL"))
        assertTrue(s.contains("filled in by another app"))
        assertTrue(s.contains("(${hidden.length} characters)"))
        assertTrue(s.contains("From: SIM 1"))
    }

    @Test fun composerUsesThePolicyAndAScrollableDialog() {
        val src = app.privateinbox.TestFiles.mainSources().single { it.name == "MessageComposer.kt" }.readText()
        assertFalse("no truncated preview", src.contains("text.take(300)"))
        assertTrue(src.contains("SendConfirmPolicy.mustConfirm(confirmSetting, externallyPrefilled)"))
        assertTrue(src.contains(".verticalScroll("))
        val main = app.privateinbox.TestFiles.mainSources().single { it.name == "MainActivity.kt" }.readText()
        assertTrue("external drafts are marked", main.contains("external = true"))
        val compose = app.privateinbox.TestFiles.mainSources().single { it.name == "ComposeScreen.kt" }.readText()
        assertTrue(compose.contains("externallyPrefilled = draft.external"))
    }
}
