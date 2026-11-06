package app.privateinbox.data.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BillParserTest {
    private val ref = LocalDate.of(2026, 11, 6)

    @Test fun electricityWithDayMonth() {
        val b = BillParser.parse("Your electricity bill of Rs 1,240 is due on 12 Nov. Pay now to avoid late fee.", ref)!!
        assertEquals(124_000L, b.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 12), b.dueDate)
        assertEquals("electricity", b.kind)
    }

    @Test fun numericDayFirst() {
        val b = BillParser.parse("Credit card statement: Total due Rs.15,320.00, minimum due Rs 800. Pay by 05/12/2026", ref)!!
        assertEquals(LocalDate.of(2026, 12, 5), b.dueDate)
        assertEquals("credit card", b.kind)
    }

    @Test fun yearRollover() {
        val b = BillParser.parse("Broadband bill due on 3 Jan, amount Rs 799", LocalDate.of(2026, 12, 28))!!
        assertEquals(LocalDate.of(2027, 1, 3), b.dueDate)
    }

    @Test fun monthFirstAndIso() {
        assertEquals(LocalDate.of(2026, 11, 20), BillParser.parse("Insurance premium due Nov 20, 2026", ref)!!.dueDate)
        assertEquals(LocalDate.of(2026, 11, 25), BillParser.parse("Loan EMI due date 2026-11-25", ref)!!.dueDate)
    }

    @Test fun billWithoutDate() {
        val b = BillParser.parse("Your mobile bill of Rs 399 is due. Pay via app.", ref)!!
        assertNull(b.dueDate)
        assertEquals(39_900L, b.amountMinor)
    }

    @Test fun notBills() {
        assertNull(BillParser.parse("Thank you for your payment of Rs 1240 towards your bill", ref))
        assertNull(BillParser.parse("Your OTP is 123456", ref))
        assertNull(BillParser.parse("See you on 12 Nov!", ref))
    }
}
