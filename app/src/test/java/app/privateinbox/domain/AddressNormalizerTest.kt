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

    @Test fun lastTenDigitMergeOnlyWithinTheHomeCountry() {
        // F-12: an Indian phone merges +91 / 0 / bare spellings, but not other countries' numbers.
        val home = "91"
        assertEquals("9876543210", AddressNormalizer.key("+91 98765 43210", home))
        assertEquals("9876543210", AddressNormalizer.key("09876543210", home))
        assertEquals("9876543210", AddressNormalizer.key("919876543210", home))
        assertEquals("9876543210", AddressNormalizer.key("0091 98765 43210", home))
        assertEquals("+44-9876543210", AddressNormalizer.key("+44 98765 43210", home))
        assertEquals("+44-9876543210", AddressNormalizer.key("0044 98765 43210", home))
        assertEquals("+1-5551234567", AddressNormalizer.key("+1 555 123 4567", home))
        // A US phone: +1 and 1-prefixed spellings merge; +91 does not.
        assertEquals("5551234567", AddressNormalizer.key("+1 (555) 123-4567", "1"))
        assertEquals("5551234567", AddressNormalizer.key("1-555-123-4567", "1"))
        assertEquals("+91-9876543210", AddressNormalizer.key("+91 98765 43210", "1"))
    }

    @Test fun unknownHomeCountryKeepsLegacyGrouping() {
        assertEquals("9876543210", AddressNormalizer.key("+44 98765 43210", null))
        assertEquals("9876543210", AddressNormalizer.key("+91 98765 43210", null))
    }

    @Test fun blockListLookupAlsoUsesTheLegacyKey() {
        // A number blocked before v1.0.3 was stored under its last 10 digits and must stay blocked.
        assertEquals(listOf("+44-9876543210", "9876543210"), AddressNormalizer.lookupKeys("+44 98765 43210", "91"))
        assertEquals(listOf("9876543210"), AddressNormalizer.lookupKeys("+91 98765 43210", "91"))
    }

    @Test fun callingCodes() {
        assertEquals("91", AddressNormalizer.callingCodeFor("in"))
        assertEquals("1", AddressNormalizer.callingCodeFor("US"))
        assertEquals("44", AddressNormalizer.callingCodeFor("gb"))
        assertEquals(null, AddressNormalizer.callingCodeFor("zz"))
        assertEquals(null, AddressNormalizer.callingCodeFor(""))
    }
}
