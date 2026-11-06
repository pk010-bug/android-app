package app.privateinbox.policy

import app.privateinbox.TestFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detekt-style source checks so sensitive data cannot reach logcat:
 *  - android.util.Log / println / printStackTrace only inside SafeLog.kt
 *  - SafeLog calls must not interpolate obviously sensitive identifiers
 */
class LogHygieneTest {
    private val rawLogging = Regex("""\b(android\.util\.Log\b|Log\.(v|d|i|w|e|wtf)\(|println\(|printStackTrace\(|System\.(out|err))""")
    private val sensitiveInterpolation = Regex(
        """SafeLog\.\w+\([^\n]*\$\{?\s*(body|address|sender|otp|code|phone|number|recipient|amount|text|message)\b""",
        RegexOption.IGNORE_CASE,
    )

    @Test fun noRawLoggingOutsideSafeLog() {
        val offenders = TestFiles.mainSources()
            .filter { it.name != "SafeLog.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (rawLogging.containsMatchIn(l)) "${f.name}:${i + 1}: $l" else null } }
        assertTrue("Use SafeLog instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun safeLogNeverReceivesSensitiveValues() {
        val offenders = TestFiles.mainSources()
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (sensitiveInterpolation.containsMatchIn(l)) "${f.name}:${i + 1}: $l" else null } }
        assertTrue("Sensitive value passed to SafeLog:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
