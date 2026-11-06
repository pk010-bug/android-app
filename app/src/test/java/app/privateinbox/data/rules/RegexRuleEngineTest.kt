package app.privateinbox.data.rules

import app.privateinbox.domain.Category
import app.privateinbox.domain.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegexRuleEngineTest {

    /** Unit tests read the real bundled rules so the shipped JSON is always validated. */
    private val engine: RegexRuleEngine by lazy {
        val candidates = listOf(
            File("src/main/assets/default_rules.json"),
            File("app/src/main/assets/default_rules.json"),
        )
        val file = candidates.first { it.exists() }
        RegexRuleEngine.fromJson(file.readText())
    }

    private fun sms(address: String, body: String) =
        SmsMessage(address = address, body = body, timestampMillis = 0L)

    private fun categoryOf(address: String, body: String) = engine.classify(sms(address, body)).category

    @Test fun bundledRulesAllCompile() {
        assertTrue("skipped: ${engine.skippedRuleIds}", engine.skippedRuleIds.isEmpty())
    }

    @Test fun otpBeatsEverythingElse() {
        assertEquals(Category.OTP, categoryOf("AX-HDFCBK", "Your OTP for login is 482913. Do not share it."))
        assertEquals(Category.OTP, categoryOf("VM-AMAZON", "739201 is your verification code"))
    }

    @Test fun bankDebitIsImportant() {
        assertEquals(
            Category.IMPORTANT,
            categoryOf("AD-SBIINB", "Rs.1,250.00 debited from A/c XX1234 on 05-11-26 to UPI/merchant."),
        )
    }

    @Test fun billDueIsImportant() {
        assertEquals(Category.IMPORTANT, categoryOf("JD-BESCOM", "Your electricity bill of Rs 1240 is due on 12 Nov"))
    }

    @Test fun promoIsPromotional() {
        assertEquals(Category.PROMOTIONAL, categoryOf("VM-SHOPCO", "Mega SALE! Flat 50% off. Shop now"))
    }

    @Test fun lotteryIsSpam() {
        assertEquals(Category.SPAM, categoryOf("+919800000000", "Congratulations! You have won Rs 25 lakh in lucky draw"))
    }

    @Test fun phoneNumberSenderIsPersonal() {
        assertEquals(Category.PERSONAL, categoryOf("+91 98765 43210", "Reached home, call you later"))
    }

    @Test fun unmatchedIsUnknown() {
        val result = engine.classify(sms("TM-RANDOM", "Hello there"))
        assertEquals(Category.UNKNOWN, result.category)
        assertNull(result.ruleId)
    }

    @Test fun invalidRulesAreSkippedNotFatal() {
        val json = """
            {"rules":[
              {"id":"bad-regex","category":"Spam","bodyPatterns":["(unclosed"]},
              {"id":"bad-category","category":"Nope","bodyPatterns":["x"]},
              {"id":"good","category":"Personal","bodyPatterns":["hi"]}
            ]}
        """.trimIndent()
        val e = RegexRuleEngine.fromJson(json)
        assertEquals(listOf("bad-regex", "bad-category"), e.skippedRuleIds)
        assertEquals(Category.PERSONAL, e.classify(sms("x", "hi there")).category)
    }

    @Test fun higherPriorityWins() {
        val json = """
            {"rules":[
              {"id":"low","category":"Promotional","priority":1,"bodyPatterns":["deal"]},
              {"id":"high","category":"Important","priority":10,"bodyPatterns":["deal"]}
            ]}
        """.trimIndent()
        assertEquals("high", RegexRuleEngine.fromJson(json).classify(sms("x", "big deal")).ruleId)
    }

    @Test fun disabledRuleIsIgnored() {
        val json = """{"rules":[{"id":"off","category":"Spam","enabled":false,"bodyPatterns":["."]}]}"""
        assertEquals(Category.UNKNOWN, RegexRuleEngine.fromJson(json).classify(sms("x", "anything")).category)
    }
}
