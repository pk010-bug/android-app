package app.privateinbox

/** F-03: pure re-lock decision for MainActivity.onStart (unit-tested in RelockPolicyTest). */
object RelockPolicy {
    /**
     * [lockEnabled] null means the settings haven't loaded yet: lock (fail closed). The lock screen
     * is only shown when app lock is actually on, so this never bothers users without app lock.
     */
    fun shouldRelock(lockEnabled: Boolean?, timeoutMs: Long, awayMs: Long): Boolean = when (lockEnabled) {
        null -> true
        false -> false
        true -> awayMs >= timeoutMs
    }

    /**
     * HB-1: whether the lock screen is shown. The app-lock part ([appLockOn] and [locked]) and the
     * integrity part ([integrityAuthRequired], process-wide, see IntegrityLockGate) are independent,
     * so loading the app-lock preference (which may clear [locked]) can never cancel an integrity
     * lock, whichever arrives first. An integrity lock is only enforced while the phone can
     * authenticate ([canAuthenticate]); otherwise the user would be locked out with no way in
     * (documented fail-open, HB-4), and the warning dialog is shown instead.
     */
    fun showLockScreen(appLockOn: Boolean, locked: Boolean, integrityAuthRequired: Boolean, canAuthenticate: Boolean): Boolean =
        (appLockOn && locked) || (integrityAuthRequired && canAuthenticate)
}
