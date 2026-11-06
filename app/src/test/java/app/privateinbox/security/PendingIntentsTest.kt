package app.privateinbox.security

import app.privateinbox.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingIntentsTest {
    @Test fun kindsHaveUniqueSlotsInRange() {
        val slots = PendingIntents.Kind.entries.map { it.slot }
        assertEquals(slots.size, slots.toSet().size)
        assertTrue(slots.all { it in 1..31 })
    }

    @Test fun requestCodesNeverCollideAcrossKinds() {
        // F-15: message 42's OTP notification vs conversation 42's notification, bill 42, etc.
        val ids = listOf(0L, 1L, 42L, 7_000_000L, 8_000_042L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)
        PendingIntents.Kind.entries.forEach { a ->
            PendingIntents.Kind.entries.filter { it != a }.forEach { b ->
                ids.forEach { x -> ids.forEach { y -> assertNotEquals("$a/$x vs $b/$y", PendingIntents.requestCode(a, x), PendingIntents.requestCode(b, y)) } }
            }
        }
        ids.forEach { id -> PendingIntents.Kind.entries.forEach { assertTrue(PendingIntents.requestCode(it, id) >= 0) } }
    }

    @Test fun identifiersCarryKindAndFullId() {
        assertEquals("OTP_OPEN:42", PendingIntents.identifier(PendingIntents.Kind.OTP_OPEN, 42))
        assertNotEquals(PendingIntents.identifier(PendingIntents.Kind.OTP_OPEN, 42), PendingIntents.identifier(PendingIntents.Kind.MESSAGE_OPEN, 42))
    }

    @Test fun everyCallSiteNamesAKind() {
        val offenders = TestFiles.mainSources().filter { it.name != "PendingIntents.kt" }.flatMap { f ->
            val text = f.readText()
            Regex("""PendingIntents\.(activity|broadcast)\(\s*context\s*,\s*([^,\s]+)""").findAll(text)
                .filter { !it.groupValues[2].startsWith("PendingIntents.Kind.") && it.groupValues[2] != "kind" }
                .map { "${f.name}: ${it.value}" }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun smsPartIdsAreUnique() {
        val ids = (1L..3L).flatMap { m -> (0..3).flatMap { r -> (0..5).map { p -> app.privateinbox.sms.SmsSender.partId(m, r, p) } } }
        assertEquals(ids.size, ids.toSet().size)
    }
}
