package app.privateinbox.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialerTest {
    @Test fun normalizesRealNumbers() {
        assertEquals("+919876543210", Dialer.normalize("+91 98765-43210"))
        assertEquals("02212345678", Dialer.normalize("(022) 1234 5678"))
    }

    @Test fun refusesAlphanumericSendersAndJunk() {
        assertNull(Dialer.normalize("AX-HDFCBK"))
        assertNull(Dialer.normalize("*#06#"))
        assertNull(Dialer.normalize("12"))
        assertNull(Dialer.normalize("tel:123456"))
    }
}
