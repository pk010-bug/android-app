package app.privateinbox.data.finance

import app.privateinbox.data.db.TransactionEntity
import app.privateinbox.domain.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class FinanceSummaryTest {
    private fun t(id: Long, day: LocalDate, amt: Long, type: TransactionType, merchant: String?) = TransactionEntity(
        id = id, messageId = id, amountMinor = amt, type = type, merchant = merchant,
        timestamp = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )

    @Test fun aggregatesOneMonth() {
        val nov = LocalDate.of(2026, 11, 3)
        val all = listOf(
            t(1, nov, 10_000, TransactionType.DEBIT, "Swiggy"),
            t(2, nov, 5_000, TransactionType.DEBIT, "swiggy"),
            t(3, nov, 20_000, TransactionType.DEBIT, "Amazon"),
            t(4, nov, 100_000, TransactionType.CREDIT, null),
            t(5, LocalDate.of(2026, 10, 30), 99_999, TransactionType.DEBIT, "Old"),
        )
        val s = FinanceSummary.forMonth(all, YearMonth.of(2026, 11), ZoneOffset.UTC)
        assertEquals(35_000L, s.spentMinor)
        assertEquals(100_000L, s.receivedMinor)
        assertEquals(listOf("Amazon" to 20_000L, "Swiggy" to 15_000L), s.topMerchants)
        assertEquals(4, s.transactions.size)
    }
}
