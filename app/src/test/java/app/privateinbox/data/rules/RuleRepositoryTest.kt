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

    @Test fun senderRuleMatchesOnlyThatWholeSender() {
        // F-12: anchored to the whole normalised sender, so every route prefix of that header matches
        // but a longer header that merely contains it does not.
        val key = app.privateinbox.domain.AddressNormalizer.key("AX-HDFCBK")
        val r = RuleRepository.toSpec(RuleRepository.senderRule(key, Category.IMPORTANT))
        assertEquals(listOf("^\\QHDFCBK\\E$"), r.senderPatterns)
        val engine = RegexRuleEngine(RuleSet(rules = listOf(r)))
        fun cat(a: String) = engine.classify(SmsMessage(address = a, body = "x", timestampMillis = 0)).category
        assertEquals(Category.IMPORTANT, cat("AX-HDFCBK"))
        assertEquals(Category.IMPORTANT, cat("VM-HDFCBK-S"))
        assertEquals(Category.IMPORTANT, cat("HDFCBK"))
        assertEquals(Category.UNKNOWN, cat("XHDFCBKX"))
        assertEquals(Category.UNKNOWN, cat("AX-HDFCBK2"))
    }

    @Test fun phoneSenderRuleMatchesEverySpellingOfThatNumberOnly() {
        val key = app.privateinbox.domain.AddressNormalizer.key("+91 98765 43210", home = "91")
        val engine = RegexRuleEngine(RuleSet(rules = listOf(RuleRepository.toSpec(RuleRepository.senderRule(key, Category.IMPORTANT)))))
        fun cat(a: String) = engine.classify(SmsMessage(address = a, body = "x", timestampMillis = 0)).category
        val saved = app.privateinbox.domain.AddressNormalizer.homeCallingCode
        try {
            app.privateinbox.domain.AddressNormalizer.homeCallingCode = "91"
            assertEquals(Category.IMPORTANT, cat("09876543210"))
            assertEquals(Category.IMPORTANT, cat("+919876543210"))
            assertEquals(Category.UNKNOWN, cat("+4498765432101"))
            assertEquals(Category.UNKNOWN, cat("+449876543210")) // same last 10 digits, other country
        } finally {
            app.privateinbox.domain.AddressNormalizer.homeCallingCode = saved
        }
    }

    @Test fun legacyUnanchoredSenderRulesAreAnchoredOnLoad() {
        val legacy = RuleEntity("user-sender-hdfcbk", Category.IMPORTANT, priority = 500, senderPatterns = Regex.escape("HDFCBK"))
        val spec = RuleRepository.toSpec(legacy)
        assertEquals(listOf("^" + Regex.escape("HDFCBK") + "$"), spec.senderPatterns)
        // user-written sender patterns (not "Move to…" rules) are left alone
        assertEquals(listOf("HDFC"), RuleRepository.toSpec(RuleEntity("user-abc", Category.IMPORTANT, senderPatterns = "HDFC")).senderPatterns)
    }

    @Test fun unsafeUserRulesAreRejectedAndSkipped() {
        assertNotNull(RuleRepository.validate("", "(a+)+$", ""))
        assertNotNull(RuleRepository.validate("", "ok", "(x|xx)*y"))
        val bad = RuleRepository.toSpec(RuleEntity("user-bad", Category.IMPORTANT, bodyPatterns = "(a+)+b"))
        assertEquals(false, RuleRepository.isSafe(bad))
        assertEquals(true, RuleRepository.isSafe(RuleRepository.toSpec(RuleEntity("user-ok", Category.IMPORTANT, bodyPatterns = "salary\\s+credited"))))
    }
}
