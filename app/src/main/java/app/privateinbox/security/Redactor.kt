package app.privateinbox.security

/**
 * Last line of defence for log output. Callers must still never pass message bodies, senders,
 * or codes to [SafeLog]; this scrubs anything that slips through.
 */
object Redactor {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    /** Any run of 3+ digits, optionally with separators (OTPs, phone numbers, amounts, accounts). */
    private val DIGITS = Regex("\\+?\\d[\\d\\s\\-().]{1,}\\d")
    /** Sender headers like "AX-HDFCBK" / "VM-AMAZON". */
    private val SENDER_HEADER = Regex("\\b[A-Z]{2}-[A-Z0-9]{3,9}\\b")

    const val MAX_LEN = 200

    fun redact(message: String): String =
        message
            .replace(EMAIL, "[email]")
            .replace(SENDER_HEADER, "[sender]")
            .replace(DIGITS) { if (it.value.count(Char::isDigit) >= 3) "[#]" else it.value }
            .let { if (it.length > MAX_LEN) it.take(MAX_LEN) + "…" else it }
}
