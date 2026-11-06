package app.privateinbox.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    @Test fun acceptsKnownRoutesAndNumericIds() {
        assertEquals("inbox", DeepLinks.validate("inbox"))
        assertEquals("compose", DeepLinks.validate("compose"))
        assertEquals("conversation/42", DeepLinks.validate("conversation/42"))
        assertEquals(42L, DeepLinks.conversationId("conversation/42"))
    }

    @Test fun rejectsEverythingElse() {
        listOf(
            null, "", "conversation/", "conversation/0", "conversation/-1", "conversation/1a",
            "conversation/1/../settings", "conversation/{conversationId}", "settings?x=1", "http://evil",
            "conversation/99999999999999999999", "unknown",
        ).forEach { assertNull("should reject $it", DeepLinks.validate(it)) }
    }
}
