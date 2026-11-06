package app.privateinbox.data.scam

import app.privateinbox.data.scam.ScamDetector.Signal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScamDetectorTest {
    private fun a(body: String, phone: Boolean = false) = ScamDetector.analyze(body, phone)

    @Test fun kycBlockWithShortLinkIsLikelyScam() {
        val r = a("Dear customer your SBI account will be blocked today. Update KYC now: bit.ly/x9k2", phone = true)
        assertTrue(r.score.toString(), r.isLikelyScam)
        assertTrue(Signal.SHORT_LINK in r.signals && Signal.URGENT_THREAT in r.signals && Signal.KYC_UPDATE in r.signals)
    }

    @Test fun apkAndIpLinks() {
        val r = a("Install the new bank app http://103.21.4.9/secure/app.apk to keep your account active")
        assertTrue(Signal.APK_LINK in r.signals && Signal.IP_LINK in r.signals)
        assertTrue(r.isLikelyScam)
    }

    @Test fun askingToShareOtpIsFlagged() {
        val r = a("Our executive will call you, please share the OTP to complete your refund")
        assertTrue(Signal.SHARE_OTP in r.signals)
        assertTrue(r.isSuspicious)
    }

    @Test fun genuineOtpWithDoNotShareIsClean() {
        val r = a("482913 is your OTP for login. Do not share it with anyone. -HDFC Bank")
        assertFalse(r.isSuspicious)
        assertFalse(Signal.SHARE_OTP in r.signals)
    }

    @Test fun normalMessagesAreClean() {
        assertFalse(a("Reached home, call you later", phone = true).isSuspicious)
        assertFalse(a("Rs.1,250.00 debited from A/c XX1234 on 05-11-26 to UPI/merchant").isSuspicious)
        assertFalse(a("Your electricity bill of Rs 1240 is due on 12 Nov").isSuspicious)
    }

    @Test fun prizeAndPayToReceive() {
        val r = a("Congratulations! You have won Rs 25 lakh. Scan the QR and pay Rs 10 to receive your prize www.claim-now.xyz")
        assertTrue(r.isLikelyScam)
    }

    @Test fun reasonsRoundTrip() {
        val s = listOf(Signal.PRIZE, Signal.APK_LINK)
        assertTrue(ScamDetector.reasonsFromString(ScamDetector.reasonsToString(s)) == s)
        assertTrue(ScamDetector.reasonsFromString("bogus,,PRIZE") == listOf(Signal.PRIZE))
    }
}
