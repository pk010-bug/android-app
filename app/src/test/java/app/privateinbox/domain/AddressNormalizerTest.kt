package app.privateinbox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressNormalizerTest {
    @Test fun phoneNumbersGroupByLastTenDigits() {
        assertEquals("9876543210", AddressNormalizer.key("+91 98765 43210"))
        assertEquals("9876543210", AddressNormalizer.key("09876543210"))
        assertEquals("9876543210", AddressNormalizer.key("98765-43210"))
        assertEquals("56161", AddressNormalizer.key("56161"))
    }

    @Test fun senderHeadersDropRoutePrefix() {
        assertEquals("HDFCBK", AddressNormalizer.key("AX-HDFCBK"))
        assertEquals("HDFCBK", AddressNormalizer.key("vm-hdfcbk"))
        assertEquals("HDFCBK", AddressNormalizer.key("JD-HDFCBK-S"))
        assertEquals("AMAZON", AddressNormalizer.key("Amazon"))
    }

    @Test fun isPhoneNumber() {
        assertTrue(AddressNormalizer.isPhoneNumber("+91 98765 43210"))
        assertFalse(AddressNormalizer.isPhoneNumber("AX-HDFCBK"))
        assertFalse(AddressNormalizer.isPhoneNumber("56161"))
    }
}
