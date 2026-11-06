package app.privateinbox.widget

import app.privateinbox.TestFiles
import app.privateinbox.domain.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContentTest {
    private val counts = mapOf(Category.PERSONAL to 2, Category.OTP to 1, Category.PROMOTIONAL to 0)

    @Test fun lockedShowsTotalOnly() {
        val m = WidgetContent.build(appLocked = true, showNames = true, unreadByCategory = counts, unreadNames = listOf("Asha"))
        assertEquals("3 unread", m.total)
        assertNull(m.recent)
        assertFalse("Personal" in m.categories)
    }

    @Test fun namesOnlyWhenOptedIn() {
        assertNull(WidgetContent.build(false, false, counts, listOf("Asha")).recent)
        assertEquals("Asha", WidgetContent.build(false, true, counts, listOf("Asha")).recent)
        assertEquals("Personal 2 · OTP 1", WidgetContent.build(false, false, counts, emptyList()).categories)
    }

    /** Source scan: widget code must never touch message text, previews, subjects or OTP codes. */
    @Test fun widgetNeverReadsContent() {
        val banned = Regex("""\.body\b|otpCode|lastMessagePreview|mmsSubject|messageDao\(""")
        TestFiles.mainSources().filter { "/widget/" in it.invariantSeparatorsPath }.forEach { f ->
            f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.forEach { l ->
                assertFalse("${f.name}: $l", banned.containsMatchIn(l))
            }
        }
        assertTrue(TestFiles.mainSources().any { it.name == "InboxWidget.kt" })
    }
}
