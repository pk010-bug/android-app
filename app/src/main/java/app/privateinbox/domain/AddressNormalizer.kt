package app.privateinbox.domain

/**
 * Groups messages from the same sender into one conversation.
 *  - Phone numbers: keep digits; numbers with 10+ digits are keyed by their last 10 digits so
 *    "+91 98765 43210" and "09876543210" match.
 *  - Indian-style sender headers ("AX-HDFCBK", "VM-HDFCBK-S"): keyed by the brand part
 *    ("HDFCBK") because the 2-letter route prefix changes between messages.
 *  - Anything else: trimmed, upper-cased.
 */
object AddressNormalizer {
    private val HEADER = Regex("^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[A-Z])?$")
    private val PHONEISH = Regex("^\\+?[0-9 ()\\-.]{3,20}$")

    fun key(address: String): String {
        val a = address.trim()
        if (PHONEISH.matches(a)) {
            val digits = a.filter(Char::isDigit)
            return if (digits.length >= 10) digits.takeLast(10) else digits
        }
        val upper = a.uppercase()
        HEADER.matchEntire(upper)?.let { return it.groupValues[1] }
        return upper
    }

    fun isPhoneNumber(address: String): Boolean =
        PHONEISH.matches(address.trim()) && address.count(Char::isDigit) >= 7

    /** Pretty name for a header sender when no contact exists. */
    fun display(address: String): String = address.trim()
}
