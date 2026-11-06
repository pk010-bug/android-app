package app.privateinbox.data.rules

/**
 * F-05: user (and restored) rule patterns run on every incoming SMS, so a catastrophic-backtracking
 * pattern could stall message processing. Three layers:
 *
 *  1. [check] rejects the classic ReDoS shapes up front: a repeated group that itself contains an
 *     unbounded repetition or an alternation ("(a+)+", "(a|aa)*", "(\w+\s?)*"), back-references,
 *     huge counted repeats, many unbounded wildcards in one pattern, and anything RE2 can't run
 *     (look-arounds).
 *  2. Rules run on RE2/J ([compileUntrusted]), a linear-time engine: no backtracking at all (user
 *     rules always; a bundled rule only falls back to java.util.regex if RE2 can't express it).
 *  3. [containsMatch] runs every match against a deadline (per match, [MATCH_BUDGET_MS]); a match
 *     that runs out of time counts as "no match" instead of blocking the receiver. RE2/J reads its
 *     input through CharSequence.charAt, so the deadline works on Android too. (Android's
 *     java.util.regex copies the input into ICU, so for a bundled rule on the java.util.regex
 *     fallback the deadline is only effective on the JVM; today every bundled rule runs on RE2/J.)
 *
 * Unit-tested in RegexSafetyTest.
 */
object RegexSafety {
    const val MAX_PATTERN_LENGTH = 300
    const val MAX_COUNTED_REPEAT = 100
    const val MAX_UNBOUNDED_WILDCARDS = 3
    const val MATCH_BUDGET_MS = 50L

    /** Returns a user-facing reason the pattern is refused, or null if it is acceptable. */
    fun check(pattern: String): String? {
        if (pattern.length > MAX_PATTERN_LENGTH) return "Pattern too long (max $MAX_PATTERN_LENGTH characters)"
        try {
            Regex(pattern)
        } catch (e: IllegalArgumentException) {
            return "Invalid pattern: $pattern"
        }
        if (runCatching { compileUntrusted(pattern) }.isFailure) {
            return "Custom rules can't use look-arounds like (?=…) or (?<!…), or back-references"
        }
        val t = tokenize(pattern)
        if (t.any { it is Tok.BackRef }) return "Back-references (\\1, \\k<name>) aren't allowed"
        t.filterIsInstance<Tok.Quant>().firstOrNull { it.max != null && it.max > MAX_COUNTED_REPEAT || it.min > MAX_COUNTED_REPEAT }
            ?.let { return "Repeat counts over $MAX_COUNTED_REPEAT aren't allowed" }
        val wildcards = t.indices.count { i -> t[i] is Tok.Dot && (t.getOrNull(i + 1) as? Tok.Quant)?.unbounded == true }
        if (wildcards > MAX_UNBOUNDED_WILDCARDS) return "Too many \".*\" / \".+\" in one pattern (max $MAX_UNBOUNDED_WILDCARDS)"
        if (hasNestedRepetition(t)) return "Repeated groups that contain repeats or alternatives (like (a+)+ or (a|b)*) can freeze the app; simplify the pattern"
        return null
    }

    /** Linear-time (RE2/J) compile for user-written patterns; case-insensitive like the engine. */
    fun compileUntrusted(pattern: String): com.google.re2j.Pattern =
        com.google.re2j.Pattern.compile(pattern, com.google.re2j.Pattern.CASE_INSENSITIVE)

    /** RE2/J find with a time budget: returns false if the match takes too long. */
    fun containsMatch(pattern: com.google.re2j.Pattern, input: CharSequence, budgetMs: Long = MATCH_BUDGET_MS): Boolean =
        try {
            pattern.matcher(DeadlineCharSequence(input, System.nanoTime() + budgetMs * 1_000_000)).find()
        } catch (e: MatchTimeout) {
            false
        }

    /** [Regex.containsMatchIn] with a time budget: returns false if the match takes too long. */
    fun containsMatch(regex: Regex, input: CharSequence, budgetMs: Long = MATCH_BUDGET_MS): Boolean =
        try {
            regex.containsMatchIn(DeadlineCharSequence(input, System.nanoTime() + budgetMs * 1_000_000))
        } catch (e: MatchTimeout) {
            false
        }

    class MatchTimeout : RuntimeException("regex match exceeded its time budget") {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Throws [MatchTimeout] from charAt once the deadline passes (checked every 64 reads). */
    private class DeadlineCharSequence(
        private val base: CharSequence,
        private val deadline: Long,
        private val counter: IntArray = IntArray(1),
    ) : CharSequence {
        override val length: Int get() = base.length
        override fun get(index: Int): Char {
            if ((++counter[0] and 63) == 0 && System.nanoTime() > deadline) throw MatchTimeout()
            return base[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(base.subSequence(startIndex, endIndex), deadline, counter)
        override fun toString(): String = base.toString()
    }

    // --- tiny regex tokenizer (enough to find structure; Regex() already validated the syntax) ---

    private sealed interface Tok {
        data object Open : Tok
        data object Close : Tok
        data object Alt : Tok
        data object Dot : Tok
        data object Atom : Tok
        data object BackRef : Tok
        data class Quant(val min: Int, val max: Int?) : Tok {
            val unbounded get() = max == null
            val repeats get() = max == null || max > 1
        }
    }

    private fun tokenize(p: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length) {
                val n = p[i + 1]
                when {
                    n == 'Q' -> { // \Q...\E literal
                        val end = p.indexOf("\\E", i + 2).let { if (it < 0) p.length else it }
                        if (!inClass) out += Tok.Atom
                        i = if (end >= p.length) p.length else end + 2
                        continue
                    }
                    !inClass && (n in '1'..'9' || n == 'k') -> out += Tok.BackRef
                    !inClass -> out += Tok.Atom
                }
                i += 2
                continue
            }
            if (inClass) {
                if (c == ']') { inClass = false; out += Tok.Atom }
                i++
                continue
            }
            when (c) {
                '[' -> {
                    inClass = true
                    // a leading ']' or '^]' is literal inside the class
                    if (i + 1 < p.length && p[i + 1] == '^') i++
                    if (i + 1 < p.length && p[i + 1] == ']') i++
                }
                '(' -> {
                    out += Tok.Open
                    if (i + 1 < p.length && p[i + 1] == '?') { // skip group flags/names: (?:  (?=  (?<name>  (?i)
                        var j = i + 2
                        if (j < p.length && p[j] == '<' && j + 1 < p.length && p[j + 1] != '=' && p[j + 1] != '!') {
                            j = p.indexOf('>', j).let { if (it < 0) p.length - 1 else it }
                        }
                        i = j
                    }
                }
                ')' -> out += Tok.Close
                '|' -> out += Tok.Alt
                '.' -> out += Tok.Dot
                '*' -> out += Tok.Quant(0, null)
                '+' -> out += Tok.Quant(1, null)
                '?' -> if (out.lastOrNull() is Tok.Quant) Unit else out += Tok.Quant(0, 1) // lazy '?' modifier
                '{' -> {
                    val m = Regex("""\{(\d+)(,(\d*))?}""").matchAt(p, i)
                    if (m != null) {
                        val min = m.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
                        val max = when {
                            m.groups[2] == null -> min
                            m.groupValues[3].isEmpty() -> null
                            else -> m.groupValues[3].toIntOrNull() ?: Int.MAX_VALUE
                        }
                        out += Tok.Quant(min, max)
                        i += m.value.length
                        continue
                    }
                    out += Tok.Atom
                }
                else -> if (c != '^' && c != '$') out += Tok.Atom
            }
            i++
        }
        return out
    }

    /**
     * True if a group followed by a repeating quantifier contains an unbounded repeat or an
     * alternation (the exponential-backtracking shapes). Bounded inner repeats like (\d{1,3},)* are
     * allowed; the match time budget still covers them.
     */
    private fun hasNestedRepetition(t: List<Tok>): Boolean {
        val stack = ArrayList<Int>()
        for (i in t.indices) {
            when (t[i]) {
                Tok.Open -> stack += i
                Tok.Close -> {
                    val start = stack.removeLastOrNull() ?: continue
                    val q = t.getOrNull(i + 1) as? Tok.Quant ?: continue
                    if (!q.repeats) continue
                    val inner = t.subList(start + 1, i)
                    if (inner.any { it is Tok.Alt || (it is Tok.Quant && it.unbounded) }) return true
                }
                else -> Unit
            }
        }
        return false
    }
}
