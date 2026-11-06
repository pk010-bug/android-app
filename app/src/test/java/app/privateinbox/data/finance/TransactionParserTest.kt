package app.privateinbox.data.finance

import app.privateinbox.domain.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionParserTest {
    @Test fun upiDebitWithBalance() {
        val p = TransactionParser.parse("Rs.1,250.50 debited from A/c XX1234 on 05-11-26 to VPA swiggy@icici. Avl Bal Rs 10,000.00")!!
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(125_050L, p.amountMinor)
        assertEquals("1234", p.accountHint)
        assertEquals(1_000_000L, p.balanceMinor)
        assertEquals("swiggy@icici", p.merchant)
    }

    @Test fun cardSpendAtMerchant() {
        val p = TransactionParser.parse("INR 499.00 spent on HDFC Bank Card x9876 at AMAZON on 2026-11-05.")!!
        assertEquals(TransactionType.DEBIT, p.type)
        assertEquals(49_900L, p.amountMinor)
        assertEquals("9876", p.accountHint)
        assertEquals("AMAZON", p.merchant)
    }

    @Test fun credit() {
        val p = TransactionParser.parse("Your a/c no. XXXXXX5678 is credited with Rs 25,000 on 01-Nov by NEFT from ACME PVT LTD")!!
        assertEquals(TransactionType.CREDIT, p.type)
        assertEquals(2_500_000L, p.amountMinor)
        assertEquals("5678", p.accountHint)
    }

    @Test fun rupeeSymbol() {
        assertEquals(1_00_000L, TransactionParser.parse("₹1000 sent to Ravi via UPI")!!.amountMinor)
    }

    @Test fun ignoresOtpPromoAndBills() {
        assertNull(TransactionParser.parse("OTP for txn of Rs 5000 is 846201"))
        assertNull(TransactionParser.parse("Get cashback up to Rs 500 on your next purchase! Offer valid today"))
        assertNull(TransactionParser.parse("Your bill of Rs 1240 is due on 12 Nov"))
        assertNull(TransactionParser.parse("Your EMI of Rs 5,000 will be debited on 10th"))
        assertNull(TransactionParser.parse("Hello, how are you?"))
    }

    @Test fun toMinor() {
        assertEquals(123_405L, TransactionParser.toMinor("1,234.05"))
        assertEquals(123_450L, TransactionParser.toMinor("1234.5"))
        assertEquals(100L, TransactionParser.toMinor("1"))
    }
}
