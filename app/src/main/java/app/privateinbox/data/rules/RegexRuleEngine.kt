package app.privateinbox.data.rules

import app.privateinbox.domain.Category
import app.privateinbox.domain.Classification
import app.privateinbox.domain.SmsMessage

/**
 * Transparent, JSON-backed regex categorizer.
 *
 * Invalid regexes or unknown categories in a rule are skipped (and reported via
 * [skippedRuleIds]) instead of crashing, so a bad user-edited rule never breaks the inbox.
 */
class RegexRuleEngine(ruleSet: RuleSet) : RuleEngine {

    /**
     * One compiled pattern. Everything runs on RE2/J (linear time, and its deadline works on
     * Android); only a bundled rule that RE2 can't express falls back to java.util.regex. User rules
     * must compile on RE2/J or they are skipped.
     */
    private fun interface Finder {
        fun find(input: String): Boolean
    }

    private class CompiledRule(
        val id: String,
        val category: Category,
        val priority: Int,
        val sender: List<Finder>,
        val body: List<Finder>,
        val excludeBody: List<Finder>,
    ) {
        fun matches(message: SmsMessage, senderKey: String): Boolean {
            // Sender patterns see the raw address and its normalised key, so anchored "Move to…"
            // rules (^key$, F-12) match every spelling of that one sender and nothing else.
            if (sender.isNotEmpty() && sender.none { it.find(message.address) || it.find(senderKey) }) return false
            if (body.isNotEmpty() && body.none { it.find(message.body) }) return false
            if (excludeBody.any { it.find(message.body) }) return false
            // A rule with no positive conditions would match everything; ignore it.
            return sender.isNotEmpty() || body.isNotEmpty()
        }
    }

    private val compiled: List<CompiledRule>
    val skippedRuleIds: List<String>

    init {
        val ok = mutableListOf<CompiledRule>()
        val skipped = mutableListOf<String>()
        for (spec in ruleSet.rules) {
            if (!spec.enabled) continue
            val category = Category.fromName(spec.category)
            val compile: (String) -> Finder = if (spec.id.startsWith(USER_RULE_PREFIX)) ::untrusted else ::trusted
            val rule = try {
                category?.let {
                    CompiledRule(
                        id = spec.id,
                        category = it,
                        priority = spec.priority,
                        sender = spec.senderPatterns.map(compile),
                        body = spec.bodyPatterns.map(compile),
                        excludeBody = spec.excludeBodyPatterns.map(compile),
                    )
                }
            } catch (e: IllegalArgumentException) {
                null // PatternSyntaxException (java and RE2/J) is an IllegalArgumentException
            }
            if (rule == null) skipped += spec.id else ok += rule
        }
        // Stable sort: equal priority keeps file order.
        compiled = ok.sortedByDescending { it.priority }
        skippedRuleIds = skipped
    }

    override fun classify(message: SmsMessage): Classification {
        val key = app.privateinbox.domain.AddressNormalizer.key(message.address)
        val hit = compiled.firstOrNull { it.matches(message, key) }
        return Classification(hit?.category ?: Category.UNKNOWN, hit?.id)
    }

    companion object {
        fun fromJson(text: String): RegexRuleEngine = RegexRuleEngine(RuleSetParser.parse(text))

        /** Same prefix as RuleRepository.USER_PREFIX: rules the user wrote, restored or created via "Move to…". */
        private const val USER_RULE_PREFIX = "user-"

        /** F-05: every match runs with a time budget; a timed-out match counts as no match. */
        private fun trusted(pattern: String): Finder {
            runCatching { RegexSafety.compileUntrusted(pattern) }.getOrNull()?.let { p ->
                return Finder { RegexSafety.containsMatch(p, it) }
            }
            val r = Regex(pattern, RegexOption.IGNORE_CASE)
            return Finder { RegexSafety.containsMatch(r, it) }
        }

        private fun untrusted(pattern: String): Finder {
            val p = RegexSafety.compileUntrusted(pattern)
            return Finder { RegexSafety.containsMatch(p, it) }
        }
    }
}
