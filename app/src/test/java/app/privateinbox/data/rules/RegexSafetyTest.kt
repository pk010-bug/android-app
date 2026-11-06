package app.privateinbox.data.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexSafetyTest {
    @Test fun classicRedosShapesAreRejected() {
        listOf(
            "(a+)+", "(a*)*b", "(a|aa)+", "(\\w+\\s?)*$", "(x+x+)+y", "((ab)*)+", "(?:a+)+", "(?<n>a+)+",
            "(.*a){5}", "^(\\d+)*$", "(a|b)*c",
        ).forEach { assertNotNull("should reject $it", RegexSafety.check(it)) }
    }

    @Test fun backReferencesHugeRepeatsAndWildcardSoupAreRejected() {
        assertNotNull(RegexSafety.check("(a)\\1"))
        assertNotNull(RegexSafety.check("(?<x>a)\\k<x>"))
        assertNotNull(RegexSafety.check("a{1000}"))
        assertNotNull(RegexSafety.check("a{2,5000}"))
        assertNotNull(RegexSafety.check(".*a.*b.*c.*d"))
        assertNotNull(RegexSafety.check("a".repeat(301)))
        assertNotNull(RegexSafety.check("("))
    }

    @Test fun ordinaryPatternsAreAccepted() {
        listOf(
            "salary\\s+credited", "^AX-", "(?i)otp", "(bank|card)?\\s*alert", "\\d{4,8}", "(\\d{1,3},)*\\d+",
            "rs\\.?\\s*\\d+", "^\\QHDFCBK\\E$", "[(a+)+]", "\\(a+\\)+", "due (on|by) \\d{1,2}", ".*sale.*",
        ).forEach { assertNull("should accept $it: ${RegexSafety.check(it)}", RegexSafety.check(it)) }
    }

    @Test fun bundledDefaultRulesStillPassTheChecker() {
        val text = listOf(java.io.File("src/main/assets/default_rules.json"), java.io.File("app/src/main/assets/default_rules.json")).first { it.exists() }.readText()
        val bad = RuleSetParser.parse(text).rules.flatMap { r ->
            (r.senderPatterns + r.bodyPatterns + r.excludeBodyPatterns).mapNotNull { p -> RegexSafety.check(p)?.let { "${r.id}: $p -> $it" } }
        }
        // Defaults are trusted (not filtered), but keeping them clean shows the checker isn't overly strict.
        assertEquals(emptyList<String>(), bad)
    }

    @Test fun lookAroundsAreRejectedForCustomRules() {
        assertNotNull(RegexSafety.check("(?=x)a"))
        assertNotNull(RegexSafety.check("(?<!rs)\\d+"))
    }

    @Test fun catastrophicMatchIsCutOffByTheTimeBudget() {
        val evil = Regex("(.*a){12}x") // polynomial blow-up: runs for minutes on JDK 17 without the budget
        val input = "a".repeat(40)
        val t0 = System.nanoTime()
        assertFalse(RegexSafety.containsMatch(evil, input, budgetMs = 50))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took ${ms}ms", ms < 2_000)
    }

    @Test fun normalMatchesStillWork() {
        assertTrue(RegexSafety.containsMatch(Regex("credited", RegexOption.IGNORE_CASE), "Salary CREDITED to a/c"))
        assertFalse(RegexSafety.containsMatch(Regex("debited"), "Salary credited"))
    }

    @Test fun engineNeverHangsOnACatastrophicRule() {
        // Bundled-style rule (java.util.regex + deadline) and a user rule that bypassed validation
        // (RE2/J, linear time): neither may hang classification.
        listOf("evil", "user-evil").forEach { id ->
            val engine = RegexRuleEngine(RuleSet(rules = listOf(RuleSpec(id, "Spam", priority = 1, bodyPatterns = listOf("(.*a){12}x")))))
            val t0 = System.nanoTime()
            val c = engine.classify(app.privateinbox.domain.SmsMessage(address = "x", body = "a".repeat(60), timestampMillis = 0))
            assertEquals(app.privateinbox.domain.Category.UNKNOWN, c.category)
            assertTrue(id, (System.nanoTime() - t0) / 1_000_000 < 2_000)
        }
    }

    @Test fun userRulesRunOnRe2AndStillMatch() {
        val engine = RegexRuleEngine(RuleSet(rules = listOf(RuleSpec("user-x", "Important", priority = 1, bodyPatterns = listOf("salary\\s+credited")))))
        assertEquals(app.privateinbox.domain.Category.IMPORTANT, engine.classify(app.privateinbox.domain.SmsMessage(address = "x", body = "SALARY  Credited", timestampMillis = 0)).category)
        val t0 = System.nanoTime()
        assertFalse(RegexSafety.containsMatch(RegexSafety.compileUntrusted("(a+)+b"), "a".repeat(5_000)))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }
}
