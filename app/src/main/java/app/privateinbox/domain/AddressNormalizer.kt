package app.privateinbox.domain

/**
 * Groups messages from the same sender into one conversation.
 *  - Phone numbers: keep digits; numbers with 10+ digits are keyed by their last 10 digits so
 *    "+91 98765 43210" and "09876543210" match, but (F-12) only when the part in front of those 10
 *    digits is compatible with the phone's home country: nothing, a trunk "0", or the home calling
 *    code. A number from another country ("+44 7911 123456" on an Indian phone) gets its own key
 *    ("+44-7911123456"), so two different people who share the last 10 digits are never merged.
 *    If the home country is unknown, the old last-10-digit rule applies.
 *  - Indian-style sender headers ("AX-HDFCBK", "VM-HDFCBK-S"): keyed by the brand part
 *    ("HDFCBK") because the 2-letter route prefix changes between messages (DLT-registered
 *    headers; the brand part is what identifies the sender).
 *  - Anything else: trimmed, upper-cased.
 */
object AddressNormalizer {
    private val HEADER = Regex("^[A-Z]{2}-([A-Z0-9]{3,9})(?:-[A-Z])?$")
    private val PHONEISH = Regex("^\\+?[0-9 ()\\-.]{3,20}$")

    /** Home country calling code (digits, e.g. "91"), set at app start from the SIM/network country. */
    @Volatile var homeCallingCode: String? = null

    fun key(address: String, home: String? = homeCallingCode): String {
        val a = address.trim()
        if (PHONEISH.matches(a)) {
            var digits = a.filter(Char::isDigit)
            var international = a.startsWith("+")
            if (!international && digits.startsWith("00") && digits.length > 12) {
                digits = digits.drop(2)
                international = true
            }
            if (digits.length < 10) return digits
            val last10 = digits.takeLast(10)
            val prefix = digits.dropLast(10)
            return if (sameCountry(prefix, international, home)) last10 else "+$prefix-$last10"
        }
        val upper = a.uppercase()
        HEADER.matchEntire(upper)?.let { return it.groupValues[1] }
        return upper
    }

    /**
     * Keys to look [address] up under in the block list: the current key plus the legacy
     * last-10-digit key, so a sender blocked before v1.0.3 stays blocked (F-12).
     */
    fun lookupKeys(address: String, home: String? = homeCallingCode): List<String> =
        listOf(key(address, home), key(address, null)).distinct()

    /** Pure: may a number with [prefix] in front of its last 10 digits merge with home numbers? */
    fun sameCountry(prefix: String, international: Boolean, home: String?): Boolean = when {
        prefix.isEmpty() -> true
        home.isNullOrEmpty() -> true // unknown home country: legacy last-10 behaviour
        international -> prefix == home
        else -> prefix == "0" || prefix == home || prefix == "0$home"
    }

    fun isPhoneNumber(address: String): Boolean =
        PHONEISH.matches(address.trim()) && address.count(Char::isDigit) >= 7

    /** Pretty name for a header sender when no contact exists. */
    fun display(address: String): String = address.trim()

    /** ISO 3166 country (e.g. "in") → calling code, or null if not in the table. */
    fun callingCodeFor(iso: String?): String? = iso?.trim()?.uppercase()?.let { CALLING_CODES[it] }

    private val CALLING_CODES: Map<String, String> = buildMap {
        listOf("US", "CA", "PR", "JM", "TT", "BS", "BB", "DO").forEach { put(it, "1") }
        put("RU", "7"); put("KZ", "7")
        mapOf(
            "EG" to "20", "ZA" to "27", "GR" to "30", "NL" to "31", "BE" to "32", "FR" to "33", "ES" to "34",
            "HU" to "36", "IT" to "39", "RO" to "40", "CH" to "41", "AT" to "43", "GB" to "44", "DK" to "45",
            "SE" to "46", "NO" to "47", "PL" to "48", "DE" to "49", "PE" to "51", "MX" to "52", "CU" to "53",
            "AR" to "54", "BR" to "55", "CL" to "56", "CO" to "57", "VE" to "58", "MY" to "60", "AU" to "61",
            "ID" to "62", "PH" to "63", "NZ" to "64", "SG" to "65", "TH" to "66", "JP" to "81", "KR" to "82",
            "VN" to "84", "CN" to "86", "TR" to "90", "IN" to "91", "PK" to "92", "AF" to "93", "LK" to "94",
            "MM" to "95", "IR" to "98", "MA" to "212", "DZ" to "213", "TN" to "216", "NG" to "234", "GH" to "233",
            "KE" to "254", "TZ" to "255", "UG" to "256", "ET" to "251", "PT" to "351", "IE" to "353",
            "FI" to "358", "UA" to "380", "CZ" to "420", "HK" to "852", "BD" to "880", "TW" to "886",
            "MV" to "960", "JO" to "962", "IQ" to "964", "KW" to "965", "SA" to "966", "OM" to "968",
            "AE" to "971", "IL" to "972", "BH" to "973", "QA" to "974", "BT" to "975", "NP" to "977",
        ).forEach { (k, v) -> put(k, v) }
    }
}
