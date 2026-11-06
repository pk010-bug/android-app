package app.privateinbox.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactorTest {
    @Test fun scrubsCodesNumbersEmailsAndSenders() {
        val out = Redactor.redact("OTP 482913 from AX-HDFCBK to +91 98765 43210, mail a.b@example.com")
        assertFalse(out.contains("482913"))
        assertFalse(out.contains("98765"))
        assertFalse(out.contains("HDFCBK"))
        assertFalse(out.contains("example.com"))
    }

    @Test fun keepsHarmlessText() {
        assertEquals("parts=2 status ok", Redactor.redact("parts=2 status ok"))
    }

    @Test fun truncatesLongMessages() {
        assertEquals(Redactor.MAX_LEN + 1, Redactor.redact("a".repeat(1000)).length)
    }
}
