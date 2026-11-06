package app.privateinbox.data.rules

import app.privateinbox.data.db.RuleEntity
import app.privateinbox.domain.Category
import app.privateinbox.domain.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleRepositoryTest {
    @Test fun validation() {
        assertNotNull(RuleRepository.validate("", "", ""))
        assertNotNull(RuleRepository.validate("(", "", ""))
        assertNotNull(RuleRepository.validate("", "a".repeat(301), ""))
        assertNull(RuleRepository.validate("^AX-", "salary\ncredited", ""))
    }

    @Test fun userRulesOutrankDefaults() {
        val defaults = RuleSpec("d", "Promotional", priority = 70, bodyPatterns = listOf("sale"))
        val user = RuleRepository.toSpec(RuleEntity("user-x", Category.IMPORTANT, priority = 0, bodyPatterns = "sale"))
        val engine = RegexRuleEngine(RuleSet(rules = listOf(defaults, user)))
        assertEquals("user-x", engine.classify(SmsMessage(address = "x", body = "big sale", timestampMillis = 0)).ruleId)
    }

    @Test fun senderRuleMatchesOnlyThatSenderLiterally() {
        val r = RuleRepository.toSpec(RuleRepository.senderRule("HDFC.BK", Category.IMPORTANT))
        val engine = RegexRuleEngine(RuleSet(rules = listOf(r)))
        assertEquals(Category.IMPORTANT, engine.classify(SmsMessage(address = "AX-HDFC.BK", body = "x", timestampMillis = 0)).category)
        assertEquals(Category.UNKNOWN, engine.classify(SmsMessage(address = "HDFCXBK", body = "x", timestampMillis = 0)).category)
    }
}
