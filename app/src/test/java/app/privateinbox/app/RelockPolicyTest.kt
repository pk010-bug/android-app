package app.privateinbox.app

import app.privateinbox.RelockPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelockPolicyTest {
    @Test fun decision() {
        assertTrue("unknown settings fail closed", RelockPolicy.shouldRelock(null, 30_000, 0))
        assertFalse(RelockPolicy.shouldRelock(false, 0, 999_999))
        assertTrue(RelockPolicy.shouldRelock(true, 30_000, 30_000))
        assertFalse(RelockPolicy.shouldRelock(true, 30_000, 29_999))
        assertTrue(RelockPolicy.shouldRelock(true, 0, 0))
    }

    @Test fun onStartRelocksSynchronously() {
        // F-03: the decision must not be inside a coroutine launched from onStart.
        val src = app.privateinbox.TestFiles.mainSources().single { it.name == "MainActivity.kt" }.readText()
        val onStart = src.substringAfter("override fun onStart()").substringBefore("private fun openDatabase")
        assertTrue(onStart.contains("RelockPolicy.shouldRelock("))
        assertFalse("no async relock", onStart.contains("launch"))
    }

    @Test fun integrityLockSurvivesSettingsLoadInEitherOrder() {
        // HB-1: simulate MainActivity: `locked` follows the app-lock preference load; the integrity
        // requirement lives in the process-wide gate.
        val high = setOf(app.privateinbox.security.integrity.IntegritySignal.FRIDA_LIBRARY)
        for (integrityFirst in listOf(true, false)) {
            val gate = app.privateinbox.security.integrity.IntegrityLockGate()
            var locked = true
            val loadSettings = { appLockOn: Boolean -> locked = appLockOn }
            if (integrityFirst) { gate.onHighRisk(high, canAuthenticate = true); loadSettings(false) } else { loadSettings(false); gate.onHighRisk(high, canAuthenticate = true) }
            assertFalse(locked)
            assertTrue("order integrityFirst=$integrityFirst", RelockPolicy.showLockScreen(appLockOn = false, locked = locked, integrityAuthRequired = gate.authRequired, canAuthenticate = true))
            gate.onUnlocked(); locked = false
            assertFalse(RelockPolicy.showLockScreen(false, locked, gate.authRequired, true))
            // Same signals again (next resume / rotation): no relock loop.
            gate.onHighRisk(high, canAuthenticate = true)
            assertFalse(RelockPolicy.showLockScreen(false, locked, gate.authRequired, true))
        }
    }

    @Test fun appLockOffNoIntegrityMeansNoLockScreen() {
        assertFalse(RelockPolicy.showLockScreen(appLockOn = false, locked = true, integrityAuthRequired = false, canAuthenticate = true))
        assertTrue(RelockPolicy.showLockScreen(appLockOn = true, locked = true, integrityAuthRequired = false, canAuthenticate = true))
        assertFalse(RelockPolicy.showLockScreen(appLockOn = true, locked = false, integrityAuthRequired = false, canAuthenticate = true))
    }

    @Test fun integrityLockNeverLocksOutAPhoneWithoutScreenLock() {
        // Documented fail-open (HB-4): without a usable screen lock there is no way to unlock.
        assertFalse(RelockPolicy.showLockScreen(appLockOn = false, locked = true, integrityAuthRequired = true, canAuthenticate = false))
    }

    @Test fun mainActivityUsesTheProcessGate() {
        val src = app.privateinbox.TestFiles.mainSources().single { it.name == "MainActivity.kt" }.readText()
        assertTrue(src.contains("RelockPolicy.showLockScreen("))
        assertTrue(src.contains("integrityLock.value = container.integrity.lockGate.authRequired"))
        assertTrue(src.contains("lockGate.onUnlocked()"))
        assertFalse("per-Activity acknowledgement reset on rotation", src.contains("acknowledgedHighRisk"))
        assertFalse("settings load must not decide the integrity lock", Regex("""integrityLock\.value\s*=\s*false[^\n]*\n?[^\n]*appLockEnabled""").containsMatchIn(src))
    }
}
