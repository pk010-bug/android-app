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

    private class CompiledRule(
        val id: String,
        val category: Category,
        val priority: Int,
        val sender: List<Regex>,
        val body: List<Regex>,
        val excludeBody: List<Regex>,
    ) {
        fun matches(message: SmsMessage): Boolean {
            if (sender.isNotEmpty() && sender.none { it.containsMatchIn(message.address) }) return false
            if (body.isNotEmpty() && body.none { it.containsMatchIn(message.body) }) return false
            if (excludeBody.any { it.containsMatchIn(message.body) }) return false
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
            val rule = try {
                category?.let {
                    CompiledRule(
                        id = spec.id,
                        category = it,
                        priority = spec.priority,
                        sender = spec.senderPatterns.map(::toRegex),
                        body = spec.bodyPatterns.map(::toRegex),
                        excludeBody = spec.excludeBodyPatterns.map(::toRegex),
                    )
                }
            } catch (e: IllegalArgumentException) {
                null // PatternSyntaxException is an IllegalArgumentException
            }
            if (rule == null) skipped += spec.id else ok += rule
        }
        // Stable sort: equal priority keeps file order.
        compiled = ok.sortedByDescending { it.priority }
        skippedRuleIds = skipped
    }

    override fun classify(message: SmsMessage): Classification {
        val hit = compiled.firstOrNull { it.matches(message) }
        return Classification(hit?.category ?: Category.UNKNOWN, hit?.id)
    }

    companion object {
        fun fromJson(text: String): RegexRuleEngine = RegexRuleEngine(RuleSetParser.parse(text))

        private fun toRegex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)
    }
}
