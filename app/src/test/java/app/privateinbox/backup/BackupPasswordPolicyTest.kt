package app.privateinbox.backup

import app.privateinbox.backup.BackupPasswordPolicy.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPasswordPolicyTest {
    private fun check(p: String) = BackupPasswordPolicy.check(p.toCharArray())

    @Test fun acceptsReasonablePasswordsAndPassphrases() {
        listOf(
            "correct horse",
            "Tr0ub4dor&3xyz",
            "purple monkey dishwasher",
            "blue-kettle-91-river",
            "mangoes in july rain",
            "Zq7!vLp2#rX9",
        ).forEach { assertNull(it, check(it)) }
    }

    @Test fun rejectsShortPasswords() {
        listOf("", "password", "Tr0ub4dor&3", "elevenchars").forEach { assertEquals(it, Problem.TOO_SHORT, check(it)) }
    }

    @Test fun rejectsRepetitionAndLowVariety() {
        listOf("aaaaaaaaaaaa", "abcabcabcabc", "121212121212", "aabbaabbaabb", "Aa1Aa1Aa1Aa1").forEach {
            assertEquals(it, Problem.TOO_REPETITIVE, check(it))
        }
    }

    @Test fun rejectsCommonPasswordsWithPadding() {
        listOf("password1234", "Password123!", "P@ssw0rd2026", "!!letmein2026", "iloveyou1234", "privateinbox1").forEach {
            assertEquals(it, Problem.COMMON, check(it))
        }
    }

    @Test fun rejectsSequences() {
        listOf("abcdefghijkl", "123456789012", "asdfghjklasd", "poiuytrewq12", "qwertyuiop12", "zyxwvutsrqpo").forEach {
            assertEquals(it, Problem.SEQUENCE, check(it))
        }
    }

    @Test fun singleClassNeedsPassphraseLength() {
        assertEquals(Problem.TOO_SIMPLE, check("bluekettlerv"))
        assertEquals(Problem.TOO_SIMPLE, check("839201746352"))
        assertNull(check("bluekettleriverstone"))
    }
}
