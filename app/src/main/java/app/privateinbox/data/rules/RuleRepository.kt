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
        // F-05: user rules saved before v1.0.3 (or slipped in some other way) are re-checked here;
        // unsafe ones are skipped rather than run on every incoming message.
        cached ?: RegexRuleEngine(RuleSet(rules = defaults.rules + dao.getAll().map(::toSpec).filter(::isSafe))).also { cached = it }
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
            senderPatterns = lines(e.senderPatterns).map { if (e.id.startsWith(SENDER_RULE_PREFIX)) anchorSenderPattern(it) else it },
            bodyPatterns = lines(e.bodyPatterns),
            excludeBodyPatterns = lines(e.excludeBodyPatterns),
        )

        private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

        const val SENDER_RULE_PREFIX = "${USER_PREFIX}sender-"

        fun isSafe(spec: RuleSpec): Boolean =
            (spec.senderPatterns + spec.bodyPatterns + spec.excludeBodyPatterns).all { RegexSafety.check(it) == null }

        /**
         * F-12: "Move to…" rules made before v1.0.3 stored an unanchored `\Qkey\E`, which also matched
         * any longer sender containing that key ("HDFCBK" in "XHDFCBKX"). Anchor them to the whole
         * sender (raw address or normalised key; see RegexRuleEngine).
         */
        fun anchorSenderPattern(p: String): String =
            if (p.startsWith("\\Q") && p.endsWith("\\E")) "^$p$" else p

        /** Validates user input; returns an error message or null. */
        fun validate(senderPatterns: String, bodyPatterns: String, excludePatterns: String): String? {
            val all = lines(senderPatterns) + lines(bodyPatterns) + lines(excludePatterns)
            if (lines(senderPatterns).isEmpty() && lines(bodyPatterns).isEmpty()) {
                return "Add at least one sender or message pattern"
            }
            // F-05: syntax, length and ReDoS-shape checks. Also used for rules restored from a backup.
            all.forEach { p -> RegexSafety.check(p)?.let { return it } }
            return null
        }

        /** Rule that pins every message from a sender to a category ("Move to…"). */
        fun senderRule(addressKey: String, category: Category): RuleEntity = RuleEntity(
            id = "$SENDER_RULE_PREFIX${addressKey.lowercase().filter { it.isLetterOrDigit() }}",
            category = category,
            description = "Messages from $addressKey",
            priority = 500,
            senderPatterns = "^" + Regex.escape(addressKey) + "$", // F-12: whole sender only
        )
    }
}
