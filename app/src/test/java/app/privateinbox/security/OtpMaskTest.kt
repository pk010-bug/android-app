package app.privateinbox.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpMaskTest {
    @Test fun codesAreMaskedInPreviews() {
        val m = OtpMask.mask("Your OTP is 482913. Do not share it.")
        assertFalse(m.contains("482913"))
        assertTrue(m.contains("••••••"))
        assertTrue(m.startsWith("Your OTP is "))
    }

    @Test fun amountsAndOrdinaryNumbersStay() {
        val s = "Rs. 5000 debited from a/c XX1234 on 12/05"
        assertEquals(s, OtpMask.mask(s))
    }

    @Test fun onlyWhenHideOtpIsOn() {
        val s = "Use code 1234 to log in"
        assertEquals(s, OtpMask.maybeMask(s, hideOtp = false))
        assertFalse(OtpMask.maybeMask(s, hideOtp = true).contains("1234"))
    }

    @Test fun listScreensUseTheMask() {
        val names = setOf("InboxScreen.kt", "SearchScreen.kt", "RecycleBinScreen.kt")
        app.privateinbox.TestFiles.mainSources().filter { it.name in names }.forEach {
            assertTrue(it.name, it.readText().contains("OtpMask.maybeMask("))
        }
    }
}
