package app.privateinbox.security.integrity

/**
 * HB-1: process-wide state of the integrity re-lock (pure, unit-tested in IntegrityLockGateTest).
 *
 * Lives in [IntegrityMonitor] (one per process) instead of the Activity, so a rotation or a new
 * Activity instance neither drops a pending re-authentication nor asks again for signals the user
 * already authenticated for. It is independent of the app-lock preference, so the order in which
 * the preference and an integrity result arrive can't clear a required lock.
 *
 * No loops: each new set of high-risk signals asks for authentication at most once; after a
 * successful unlock the same signals never re-lock in this process. If the phone has no usable
 * screen lock nothing is recorded as acknowledged (we only warn), so the lock still applies once
 * a screen lock becomes available.
 */
class IntegrityLockGate {
    private var acknowledged: Set<IntegritySignal> = emptySet()
    private var required = false

    /** True while a fresh authentication is owed because of high-risk integrity signals. */
    @get:Synchronized val authRequired: Boolean get() = required

    /**
     * A report with [high] high-risk signals arrived. Returns true if this call newly requires
     * authentication (the caller then shows the lock screen).
     */
    @Synchronized
    fun onHighRisk(high: Set<IntegritySignal>, canAuthenticate: Boolean): Boolean {
        if (high.isEmpty() || !canAuthenticate || acknowledged.containsAll(high)) return false
        acknowledged = acknowledged + high
        val newly = !required
        required = true
        return newly
    }

    /** The user authenticated successfully on the lock screen. */
    @Synchronized
    fun onUnlocked() {
        required = false
    }
}
