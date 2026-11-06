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
}
