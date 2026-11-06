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
}
