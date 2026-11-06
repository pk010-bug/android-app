package app.privateinbox.security.integrity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityLockGateTest {
    private val frida = setOf(IntegritySignal.FRIDA_LIBRARY)
    private val tracer = setOf(IntegritySignal.TRACER_ATTACHED)

    @Test fun locksOncePerNewSignalSet() {
        val g = IntegrityLockGate()
        assertFalse(g.authRequired)
        assertTrue(g.onHighRisk(frida, canAuthenticate = true))
        assertTrue(g.authRequired)
        assertFalse("already pending", g.onHighRisk(frida, canAuthenticate = true))
        g.onUnlocked()
        assertFalse(g.authRequired)
        assertFalse("acknowledged: no loop", g.onHighRisk(frida, canAuthenticate = true))
        assertFalse(g.authRequired)
        assertTrue("a new signal asks again", g.onHighRisk(frida + tracer, canAuthenticate = true))
        assertTrue(g.authRequired)
    }

    @Test fun pendingRequirementIsNotClearedByLaterReports() {
        val g = IntegrityLockGate()
        g.onHighRisk(frida, true)
        g.onHighRisk(emptySet(), true) // e.g. a later clean report
        assertTrue("only a successful unlock clears it", g.authRequired)
    }

    @Test fun withoutScreenLockNothingIsAcknowledged() {
        val g = IntegrityLockGate()
        assertFalse(g.onHighRisk(frida, canAuthenticate = false))
        assertFalse(g.authRequired)
        // Once the phone can authenticate, the same signals still require fresh auth.
        assertTrue(g.onHighRisk(frida, canAuthenticate = true))
    }
}
