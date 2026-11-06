package app.privateinbox.data.finance

import app.privateinbox.domain.TransactionType

/**
 * Parses bank / card / UPI transaction alerts on-device.
 * Returns null for promotions, OTPs, and anything without a clear debit/credit + amount.
 */
object TransactionParser {

    data class Parsed(
        val type: TransactionType,
        val amountMinor: Long,
        val currency: String,
        val accountHint: String?,
        val merchant: String?,
        val balanceMinor: Long?,
    )

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private const val AMT = """(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private val AMOUNT = rx(AMT)
    private val DEBIT = rx("""\b(debited|debit|spent|withdrawn|paid|sent|purchase|dr\b)""")
    private val CREDIT = rx("""\b(credited|credit|received|deposited|refund(?:ed)?|cr\b)""")
    private val ACCOUNT = rx("""\b(?:a/c|acct|account|card)\b[^0-9\n]{0,15}?(?:no\.?\s*)?(?:x+|\*+|ending\s*(?:with\s*)?)?\s*([0-9]{3,6})\b""")
    private val BALANCE = rx("""\b(?:avl\.?\s*bal(?:ance)?|available\s+bal(?:ance)?|bal(?:ance)?)\b[^0-9\n]{0,12}?$AMT""")
    private val MERCHANT_PATTERNS = listOf(
        rx("""\bat\s+([A-Za-z0-9&'.\- ]{2,40}?)(?=\s+(?:on|via|ref|using|txn|avl|\.|,)|[.,]|$)"""),
        rx("""\bto\s+(?:vpa\s+)?([A-Za-z0-9@&'.\- ]{2,40}?)(?=\s+(?:on|via|ref|upi|txn|avl|from)|[.,]|$)"""),
        rx("""\binfo[:\s]+([A-Za-z0-9@&'.\-/ ]{2,40}?)(?=[.,]|\s+avl|$)"""),
        rx("""\bfrom\s+(?:vpa\s+)?([A-Za-z0-9@&'.\- ]{2,40}?)(?=\s+(?:on|via|ref|upi|txn|avl)|[.,]|$)"""),
    )
    private val NOT_A_TXN = rx("""\b(otp|one time password|verification code|offer|cashback up ?to|pre-approved|eligible for|apply now|win|emi starting|loan of up ?to|will be debited|due on|is due|bill)\b""")

    fun parse(body: String): Parsed? {
        if (NOT_A_TXN.containsMatchIn(body)) return null
        val debitAt = DEBIT.find(body)?.range?.first
        val creditAt = CREDIT.find(body)?.range?.first
        val type = when {
            debitAt != null && creditAt != null -> if (debitAt < creditAt) TransactionType.DEBIT else TransactionType.CREDIT
            debitAt != null -> TransactionType.DEBIT
            creditAt != null -> TransactionType.CREDIT
            else -> return null
        }
        val balanceMatch = BALANCE.find(body)
        val amountMatch = AMOUNT.findAll(body).firstOrNull { m ->
            balanceMatch == null || m.range.first !in balanceMatch.range
        } ?: return null
        val amount = toMinor(amountMatch.groupValues[1]) ?: return null
        if (amount <= 0) return null

        val merchant = MERCHANT_PATTERNS.asSequence()
            .mapNotNull { it.find(body)?.groupValues?.get(1)?.trim() }
            .map { it.trimEnd('.', '-', ' ') }
            .firstOrNull { it.length >= 2 && !it.matches(Regex("(?i)(a/c|acct|account|card|your|you).*")) }
        return Parsed(
            type = type,
            amountMinor = amount,
            currency = "INR",
            accountHint = ACCOUNT.find(body)?.groupValues?.get(1),
            merchant = merchant,
            balanceMinor = balanceMatch?.groupValues?.get(1)?.let(::toMinor),
        )
    }

    fun toMinor(raw: String): Long? {
        val clean = raw.replace(",", "")
        val parts = clean.split('.')
        val whole = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0
        return whole * 100 + frac
    }
}
