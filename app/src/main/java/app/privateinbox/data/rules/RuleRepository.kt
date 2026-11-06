package app.privateinbox.data.rules

import app.privateinbox.data.db.RuleDao
import app.privateinbox.data.db.RuleEntity
import app.privateinbox.domain.Category
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Combines the bundled default rules with the user's own rules (stored encrypted in Room).
 * User rules get [USER_PRIORITY_BOOST] so they always win over defaults.
 */
class RuleRepository(
    private val defaults: RuleSet,
    private val dao: RuleDao,
) {
    private val mutex = Mutex()
    @Volatile private var cached: RegexRuleEngine? = null

    val defaultRules: List<RuleSpec> get() = defaults.rules

    suspend fun engine(): RegexRuleEngine = cached ?: mutex.withLock {
        cached ?: RegexRuleEngine(RuleSet(rules = defaults.rules + dao.getAll().map(::toSpec))).also { cached = it }
    }

    fun invalidate() {
        cached = null
    }

    suspend fun save(rule: RuleEntity) {
        dao.upsert(rule)
        invalidate()
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        invalidate()
    }

    companion object {
        const val USER_PRIORITY_BOOST = 1_000
        const val USER_PREFIX = "user-"

        fun toSpec(e: RuleEntity) = RuleSpec(
            id = e.id,
            category = e.category.name,
            description = e.description,
            priority = USER_PRIORITY_BOOST + e.priority,
            enabled = e.enabled,
            senderPatterns = lines(e.senderPatterns),
            bodyPatterns = lines(e.bodyPatterns),
            excludeBodyPatterns = lines(e.excludeBodyPatterns),
        )

        private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

        /** Validates user input; returns an error message or null. */
        fun validate(senderPatterns: String, bodyPatterns: String, excludePatterns: String): String? {
            val all = lines(senderPatterns) + lines(bodyPatterns) + lines(excludePatterns)
            if (lines(senderPatterns).isEmpty() && lines(bodyPatterns).isEmpty()) {
                return "Add at least one sender or message pattern"
            }
            all.forEach { p ->
                if (p.length > 300) return "Pattern too long (max 300 characters)"
                try {
                    Regex(p)
                } catch (e: IllegalArgumentException) {
                    return "Invalid pattern: $p"
                }
            }
            return null
        }

        /** Rule that pins every message from a sender to a category ("Move to…"). */
        fun senderRule(addressKey: String, category: Category): RuleEntity = RuleEntity(
            id = "${USER_PREFIX}sender-${addressKey.lowercase().filter { it.isLetterOrDigit() }}",
            category = category,
            description = "Messages from $addressKey",
            priority = 500,
            senderPatterns = Regex.escape(addressKey),
        )
    }
}
