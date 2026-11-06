package app.privateinbox.data.finance

import app.privateinbox.data.db.TransactionEntity
import app.privateinbox.domain.TransactionType
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Pure aggregation for the finance dashboard (unit-tested). */
data class FinanceSummary(
    val spentMinor: Long,
    val receivedMinor: Long,
    val topMerchants: List<Pair<String, Long>>,
    val transactions: List<TransactionEntity>,
) {
    companion object {
        fun forMonth(all: List<TransactionEntity>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): FinanceSummary {
            val inMonth = all.filter { YearMonth.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == month }
            val debits = inMonth.filter { it.type == TransactionType.DEBIT }
            return FinanceSummary(
                spentMinor = debits.sumOf { it.amountMinor },
                receivedMinor = inMonth.filter { it.type == TransactionType.CREDIT }.sumOf { it.amountMinor },
                topMerchants = debits.groupBy { (it.merchant ?: "Other").trim().lowercase().replaceFirstChar(Char::uppercase) }
                    .mapValues { (_, v) -> v.sumOf { it.amountMinor } }
                    .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
                transactions = inMonth.sortedByDescending { it.timestamp },
            )
        }
    }
}
