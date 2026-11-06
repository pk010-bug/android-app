package app.privateinbox.backup

/**
 * Strength rules for NEW backup passwords (pure, unit-tested). A backup can leave the phone and be
 * attacked offline, so the password is its only real protection. Restoring is never subject to
 * this policy: backups made under the old 8-character rule must still open.
 *
 * Deliberately simple and dependency-free (no zxcvbn): length, character variety, and rejection of
 * the patterns password crackers try first (repeats, keyboard/alphabet runs, common passwords with
 * digits or symbols tacked on).
 */
object BackupPasswordPolicy {
    const val MIN_LENGTH = 12

    /** At this length a passphrase of plain words is fine without mixing character classes. */
    const val PASSPHRASE_LENGTH = 16

    const val MIN_DISTINCT_CHARS = 6

    enum class Problem(val message: String) {
        TOO_SHORT("Use at least $MIN_LENGTH characters."),
        TOO_SIMPLE("Mix letters with numbers, symbols or spaces, or use $PASSPHRASE_LENGTH+ characters."),
        TOO_REPETITIVE("Avoid repeated characters or patterns."),
        SEQUENCE("Avoid keyboard or alphabet sequences like 'qwerty' or '123456'."),
        COMMON("This password is too common. Try a few unrelated words."),
    }

    private val SEQUENCES = listOf(
        "abcdefghijklmnopqrstuvwxyz", "0123456789", "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "qwertzuiop", "azertyuiop", "1qaz2wsx3edc", "1q2w3e4r5t6y",
    )

    private val COMMON = setOf(
        "password", "passw0rd", "p@ssword", "p@ssw0rd", "letmein", "welcome", "iloveyou", "admin",
        "qwerty", "monkey", "dragon", "football", "baseball", "sunshine", "princess", "master",
        "shadow", "superman", "trustno1", "secret", "default", "changeme", "privateinbox", "backup",
        "mypassword", "mybackup", "abc123", "india", "hello", "freedom", "whatever", "starwars",
        "computer", "samsung", "android", "google", "nothing", "loveyou", "pokemon", "cricket",
    )

    /** Null when [password] is acceptable for a new backup, else the first problem found. */
    fun check(password: CharArray): Problem? {
        if (password.size < MIN_LENGTH) return Problem.TOO_SHORT
        val lower = String(password).lowercase()
        if (lower.toSet().size < MIN_DISTINCT_CHARS || isRepeatedUnit(lower)) return Problem.TOO_REPETITIVE
        if (isCommon(lower)) return Problem.COMMON
        if (isSequence(lower)) return Problem.SEQUENCE
        val classes = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        if (classes < 2 && password.size < PASSPHRASE_LENGTH) return Problem.TOO_SIMPLE
        return null
    }

    fun isAcceptable(password: CharArray): Boolean = check(password) == null

    /** "abcabcabcabc", "aaaaaaaaaaaa", "1212121212121" */
    private fun isRepeatedUnit(s: String): Boolean =
        (1..s.length / 2).any { n -> s.length % n == 0 && s.chunked(n).toSet().size == 1 }

    /** Common password (letters part) padded with digits/symbols, e.g. "Password123!", "P@ssw0rd2026". */
    private fun isCommon(s: String): Boolean {
        val core = s.trim { !it.isLetter() && it != '@' }
        val letters = s.filter { it.isLetter() }
        return core in COMMON || letters in COMMON || COMMON.any { it.length >= 6 && s.startsWith(it) && s.drop(it.length).none { c -> c.isLetter() } }
    }

    /**
     * Whole password is a run of a keyboard row / alphabet / digits (forwards or backwards, wrapping),
     * or such a run padded with a few digits/symbols ("qwertyuiop12!").
     */
    private fun isSequence(s: String): Boolean {
        val core = s.trim { !it.isLetter() }
        return isRun(s.filter { !it.isWhitespace() }) || (core.length >= 6 && s.length - core.length <= 4 && isRun(core))
    }

    private fun isRun(s: String): Boolean = SEQUENCES.any { seq ->
        listOf(seq, seq.reversed()).any { base ->
            val cycle = base + base + base
            s.length <= cycle.length && cycle.contains(s)
        }
    }
}
