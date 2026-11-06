package app.privateinbox.data.scam

/**
 * Offline scam heuristics. Produces a 0-100 score plus human-readable reasons so the UI can
 * explain every warning. No reputation lookups, no network: just text signals.
 */
object ScamDetector {

    enum class Signal(val weight: Int, val explanation: String) {
        SHORT_LINK(25, "Uses a link shortener that hides the real website"),
        IP_LINK(35, "Link points to a raw IP address"),
        APK_LINK(45, "Asks you to download an app file (.apk)"),
        SUSPICIOUS_TLD(15, "Link uses a domain ending often used in scams"),
        URGENT_THREAT(25, "Threatens to block or suspend your account"),
        KYC_UPDATE(25, "Asks you to update KYC / PAN / Aadhaar via a link or call"),
        PRIZE(30, "Claims you won a prize, lottery or reward"),
        SHARE_OTP(40, "Asks you to share or forward a code"),
        PAY_TO_RECEIVE(30, "Asks you to pay or scan a QR code to receive money"),
        PHONE_SENDER_BRAND(20, "A personal number claims to be a bank or company"),
        CALL_THIS_NUMBER(10, "Pushes you to call an unknown number urgently"),
        JOB_EASY_MONEY(25, "Promises easy money / part-time job income"),
    }

    data class Result(val score: Int, val signals: List<Signal>) {
        val isSuspicious: Boolean get() = score >= SUSPICIOUS
        val isLikelyScam: Boolean get() = score >= LIKELY_SCAM
    }

    const val SUSPICIOUS = 40
    const val LIKELY_SCAM = 70

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val URL = rx("""(?:https?://|www\.)\S+|\b[a-z0-9-]+\.(?:[a-z]{2,10})(?:/\S*)?""")
    private val SHORTENERS = rx("""\b(bit\.ly|tinyurl\.com|t\.co|goo\.gl|is\.gd|cutt\.ly|rb\.gy|shorturl\.at|tiny\.cc|ow\.ly|rebrand\.ly)\b""")
    private val IP_URL = rx("""\b(?:https?://)?\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?/""")
    private val APK = rx("""\.apk\b""")
    private val BAD_TLD = rx("""\b[a-z0-9-]+\.(xyz|top|click|link|info|buzz|online|site|live|icu|rest|cfd|sbs)\b""")
    private val THREAT = rx("""\b(will be|has been|is)\s+(blocked|suspended|deactivated|closed|frozen)\b|\b(blocked|suspended)\s+today\b""")
    private val KYC = rx("""\b(kyc|pan\s*card|aadhaar|aadhar)\b[^\n]{0,60}\b(update|expire|expired|verify|link|pending)\b|\b(update|verify|complete|link)\b[^\n]{0,20}\b(kyc|pan\s*card|aadhaar|aadhar)\b""")
    private val PRIZE = rx("""\b(you have won|you've won|congratulations[^\n]{0,40}won|lucky draw|lottery|claim your (prize|reward|gift)|cash prize)\b""")
    private val SHARE_OTP = rx("""\b(share|send|tell|forward)\b[^\n]{0,30}\b(otp|code|pin)\b|\b(otp|code)\b[^\n]{0,20}\bto (our|the) (agent|executive|officer)\b""")
    private val DONT_SHARE = rx("""\b(do not|don't|never)\s+share\b""")
    private val PAY_TO_RECEIVE = rx("""\b(scan|pay)\b[^\n]{0,40}\b(to receive|to get|to claim)\b|\benter (your )?upi pin to receive\b""")
    private val BRAND = rx("""\b(bank|sbi|hdfc|icici|axis|kotak|paytm|phonepe|gpay|amazon|flipkart|electricity|income tax|customs|courier|fedex|dhl)\b""")
    private val CALL_NOW = rx("""\b(call|contact)\b[^\n]{0,20}\b(immediately|urgently|now)\b""")
    private val JOB = rx("""\b(part[ -]?time|work from home|daily income|earn)\b[^\n]{0,40}\b(rs\.?|inr|₹|\$)\s?\d""")

    /**
     * @param senderIsPhoneNumber true when the sender is a regular number, not a business header.
     */
    fun analyze(body: String, senderIsPhoneNumber: Boolean): Result {
        val s = mutableListOf<Signal>()
        val hasLink = URL.containsMatchIn(body)
        if (SHORTENERS.containsMatchIn(body)) s += Signal.SHORT_LINK
        if (IP_URL.containsMatchIn(body)) s += Signal.IP_LINK
        if (APK.containsMatchIn(body)) s += Signal.APK_LINK
        if (BAD_TLD.containsMatchIn(body)) s += Signal.SUSPICIOUS_TLD
        if (THREAT.containsMatchIn(body)) s += Signal.URGENT_THREAT
        if (KYC.containsMatchIn(body)) s += Signal.KYC_UPDATE
        if (PRIZE.containsMatchIn(body)) s += Signal.PRIZE
        if (SHARE_OTP.containsMatchIn(body) && !DONT_SHARE.containsMatchIn(body)) s += Signal.SHARE_OTP
        if (PAY_TO_RECEIVE.containsMatchIn(body)) s += Signal.PAY_TO_RECEIVE
        if (senderIsPhoneNumber && BRAND.containsMatchIn(body) && (hasLink || THREAT.containsMatchIn(body))) {
            s += Signal.PHONE_SENDER_BRAND
        }
        if (CALL_NOW.containsMatchIn(body)) s += Signal.CALL_THIS_NUMBER
        if (JOB.containsMatchIn(body)) s += Signal.JOB_EASY_MONEY

        var score = s.sumOf { it.weight }
        // Threat/KYC/prize without any link or call-to-action is much less dangerous.
        if (!hasLink && Signal.SHARE_OTP !in s && Signal.CALL_THIS_NUMBER !in s && Signal.PAY_TO_RECEIVE !in s) {
            score /= 2
        }
        return Result(score.coerceIn(0, 100), s)
    }

    fun reasonsToString(signals: List<Signal>): String = signals.joinToString(",") { it.name }

    fun reasonsFromString(s: String): List<Signal> =
        s.split(',').mapNotNull { n -> Signal.entries.firstOrNull { it.name == n.trim() } }
}
