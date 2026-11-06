package app.privateinbox.data.otp

import app.privateinbox.domain.OtpMatch

/**
 * Finds one-time passwords in SMS text, fully offline.
 *
 * Strategy:
 *  1. Find every standalone 4–8 digit token (also "123-456" / "123 456" split codes).
 *  2. Keep a token only if an OTP keyword sits close before it ("OTP is 123456")
 *     or right after it ("123456 is your OTP").
 *  3. Drop tokens that look like money ("Rs. 5000"), masked accounts ("XX1234"),
 *     or parts of longer numbers / dates / decimals.
 *  The earliest surviving token wins.
 */
object OtpExtractor {

    private const val KEYWORDS =
        "otp|one[\\s-]?time[\\s-]?pass(?:word|code)?|verification\\s+code|verify\\s+code|" +
            "security\\s+code|login\\s+code|auth(?:entication)?\\s+code|passcode|pin|code"

    /** Standalone code token, not glued to other digits, letters, or decimal points. */
    private val candidate = Regex(
        "(?<![\\w.,/:-])(\\d{3,4}[\\s-]\\d{3,4}|\\d{4,8})(?![\\w/:]|[.,]\\d|-\\d)",
    )

    private val keywordBefore = Regex("\\b(?:$KEYWORDS)\\b", RegexOption.IGNORE_CASE)
    private val keywordAfter = Regex(
        "^\\s*(?:is|as)\\s+(?:your|the)?\\s*(?:$KEYWORDS)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val amountPrefix = Regex("(?:rs\\.?|inr|usd|\\$|₹|€|£)\\s*$", RegexOption.IGNORE_CASE)

    private const val LOOKBEHIND_CHARS = 40

    fun extract(body: String): OtpMatch? =
        candidate.findAll(body)
            .mapNotNull { m ->
                val group = m.groups[1] ?: return@mapNotNull null
                val code = group.value.filter(Char::isDigit)
                if (code.length !in 4..8) return@mapNotNull null

                val before = body.substring(0, group.range.first)
                if (amountPrefix.containsMatchIn(before)) return@mapNotNull null

                val window = before.takeLast(LOOKBEHIND_CHARS)
                val after = body.substring(group.range.last + 1)
                val keywordNear = keywordBefore.containsMatchIn(window) || keywordAfter.containsMatchIn(after)
                if (keywordNear) OtpMatch(code, group.range) else null
            }
            .firstOrNull()

    fun containsOtp(body: String): Boolean = extract(body) != null
}
