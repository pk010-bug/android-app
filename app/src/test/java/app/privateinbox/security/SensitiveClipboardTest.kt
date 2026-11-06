package app.privateinbox.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveClipboardTest {
    @Test fun everythingWeCopyIsCleared() {
        assertEquals(SensitiveClipboard.OTP_CLEAR_MS, SensitiveClipboard.clearDelayFor("OTP"))
        assertEquals(SensitiveClipboard.MESSAGE_CLEAR_MS, SensitiveClipboard.clearDelayFor("Message"))
        assertTrue(SensitiveClipboard.MESSAGE_CLEAR_MS in 30_000L..300_000L)
    }

    @Test fun conversationSelectionGoesThroughSensitiveClipboard() {
        // F-07
        val files = app.privateinbox.TestFiles.mainSources()
        val conv = files.single { it.name == "ConversationScreen.kt" }.readText()
        assertTrue(conv.contains("SensitiveSelectionContainer"))
        assertTrue(!Regex("""(?<!Sensitive)SelectionContainer\s*\{""").containsMatchIn(conv))
        val sel = files.single { it.name == "SensitiveSelection.kt" }.readText()
        assertTrue(sel.contains("LocalClipboardManager provides") && sel.contains("SensitiveClipboard.copy("))
    }
}
