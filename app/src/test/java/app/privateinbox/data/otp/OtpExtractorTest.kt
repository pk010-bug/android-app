package app.privateinbox.data.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {

    private fun code(body: String) = OtpExtractor.extract(body)?.code

    @Test fun keywordBeforeCode() {
        assertEquals("482913", code("Your OTP is 482913. Valid for 10 minutes."))
        assertEquals("1234", code("OTP: 1234"))
        assertEquals("987654", code("Use verification code 987654 to sign in"))
    }

    @Test fun codeBeforeKeyword() {
        assertEquals("739201", code("739201 is your verification code for Example"))
        assertEquals("5521", code("5521 is your OTP. Do not share."))
    }

    @Test fun splitCodeIsJoined() {
        assertEquals("123456", code("Your login code is 123-456"))
        assertEquals("123456", code("Security code: 123 456"))
    }

    @Test fun skipsAmountAndFindsRealCode() {
        assertEquals("846201", code("OTP for txn of Rs. 5000 at STORE is 846201. Never share."))
    }

    @Test fun ignoresMaskedAccountsAndAmounts() {
        assertNull(code("Rs. 1250 debited from A/c XX1234 on 05-11-2026"))
        assertNull(code("Payment of INR 4999 received"))
    }

    @Test fun ignoresMessagesWithoutKeyword() {
        assertNull(code("Meet me at gate 4 at 1830"))
        assertFalse(OtpExtractor.containsOtp("Call me on 9876543210"))
    }

    @Test fun ignoresDecimalsAndDates() {
        assertNull(code("Your code balance is 1234.56"))
        assertNull(code("Code valid till 2026/11/06"))
    }

    @Test fun rangePointsAtCode() {
        val body = "Your OTP is 482913."
        val match = OtpExtractor.extract(body)!!
        assertEquals("482913", body.substring(match.range))
    }
}
