package app.privateinbox.data.finance

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Detects bill / EMI / premium due reminders and extracts amount + due date, offline. */
object BillParser {

    data class Parsed(val amountMinor: Long?, val dueDate: LocalDate?, val kind: String)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val IS_BILL = rx("""\b(bill|emi|premium|dues?|payment|recharge|statement)\b[^\n]{0,80}\b(due|overdue|expires?|expiring|last date|pay by|before)\b|\b(due date|pay by|last date)\b""")
    private val NOT_BILL = rx("""\b(otp|verification code|has been paid|payment received|thank you for (your )?payment|successfully paid|debited)\b""")
    private val AMOUNT = rx("""(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val KIND = rx("""\b(electricity|water|gas|broadband|internet|mobile|postpaid|dth|credit card|card|loan|emi|insurance|premium|rent|recharge)\b""")

    private val MONTHS = java.time.Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() }

    // 12 Nov / 12-Nov-26 / 12 November 2026 / Nov 12
    private val D_MON = rx("""\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/]*([A-Za-z]{3})[A-Za-z]*[\s\-/,]*(\d{2,4})?\b""")
    private val MON_D = rx("""\b([A-Za-z]{3})[A-Za-z]*\s+(\d{1,2})(?:st|nd|rd|th)?,?\s*(\d{4})?\b""")
    // 12/11/2026, 12-11-26, 12.11.2026 (day-first, as used by Indian billers)
    private val NUMERIC = rx("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})\b""")
    // 2026-11-12
    private val ISO = rx("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    fun parse(body: String, receivedAt: LocalDate): Parsed? {
        if (NOT_BILL.containsMatchIn(body) || !IS_BILL.containsMatchIn(body)) return null
        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.let(TransactionParser::toMinor)
        val kind = KIND.find(body)?.value?.lowercase() ?: "bill"
        return Parsed(amount, findDate(body, receivedAt), kind)
    }

    fun findDate(body: String, ref: LocalDate): LocalDate? {
        // Prefer a date right after "due"/"by"/"before".
        val anchor = rx("""\b(due|by|before|on or before|last date)\b""").find(body)?.range?.last ?: -1
        val tail = if (anchor >= 0) body.substring(anchor + 1) else body
        return parseFirstDate(tail, ref) ?: parseFirstDate(body, ref)
    }

    private fun parseFirstDate(s: String, ref: LocalDate): LocalDate? {
        val candidates = buildList {
            ISO.find(s)?.let { m -> add(m.range.first to safe(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())) }
            NUMERIC.find(s)?.let { m ->
                add(m.range.first to safe(year(m.groupValues[3], ref), m.groupValues[2].toInt(), m.groupValues[1].toInt()))
            }
            D_MON.findAll(s).firstOrNull { MONTHS.containsKey(it.groupValues[2].lowercase()) }?.let { m ->
                val month = MONTHS.getValue(m.groupValues[2].lowercase()).value
                add(m.range.first to withYear(m.groupValues[3], month, m.groupValues[1].toInt(), ref))
            }
            MON_D.findAll(s).firstOrNull { MONTHS.containsKey(it.groupValues[1].lowercase()) }?.let { m ->
                val month = MONTHS.getValue(m.groupValues[1].lowercase()).value
                add(m.range.first to withYear(m.groupValues[3], month, m.groupValues[2].toInt(), ref))
            }
        }
        return candidates.filter { it.second != null }.minByOrNull { it.first }?.second
    }

    private fun year(raw: String, ref: LocalDate): Int =
        raw.toInt().let { if (raw.length == 2) 2000 + it else it }.takeIf { it in ref.year - 1..ref.year + 5 } ?: ref.year

    /** No year in the text: pick the next occurrence on/after (received - 7 days). */
    private fun withYear(rawYear: String, month: Int, day: Int, ref: LocalDate): LocalDate? {
        if (rawYear.isNotEmpty()) return safe(year(rawYear, ref), month, day)
        val sameYear = safe(ref.year, month, day) ?: return null
        return if (sameYear.isBefore(ref.minusDays(7))) safe(ref.year + 1, month, day) else sameYear
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    fun toEpochMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()
}
