package app.privateinbox.security

import app.privateinbox.data.otp.OtpExtractor

/**
 * F-06: list previews (Inbox, Archived, Search, Recycle bin) are not screenshot-protected when app
 * lock is off, so with "Hide OTPs" on the code itself is masked there. Pure; unit-tested.
 */
object OtpMask {
    private const val MAX_CODES = 4

    fun mask(text: String): String {
        var out = text
        repeat(MAX_CODES) {
            val m = OtpExtractor.extract(out) ?: return out
            out = out.replaceRange(m.range, "•".repeat(m.range.last - m.range.first + 1))
        }
        return out
    }

    fun maybeMask(text: String, hideOtp: Boolean): String = if (hideOtp) mask(text) else text
}
