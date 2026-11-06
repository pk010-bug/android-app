package app.privateinbox.security.integrity

import android.content.Context
import android.os.SystemClock
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs [IntegrityChecker] off the main thread, at most once per [MIN_INTERVAL_MS] unless forced,
 * and publishes the latest report for the UI. Logs only coarse categories (never paths, package
 * names or other device details), and only when the result changes.
 */
class IntegrityMonitor internal constructor(private val checker: IntegrityChecker) {

    constructor(context: Context) : this(IntegrityChecker(AndroidIntegrityEnv(context.applicationContext)))

    private val mutex = Mutex()
    private var lastRunAt = 0L
    private val _latest = MutableStateFlow<IntegrityReport?>(null)
    val latest: StateFlow<IntegrityReport?> = _latest.asStateFlow()

    suspend fun refresh(force: Boolean = false): IntegrityReport = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            _latest.value?.let { cached -> if (!force && now - lastRunAt < MIN_INTERVAL_MS) return@withLock cached }
            val report = try {
                checker.check()
            } catch (t: Throwable) {
                // A check must never crash the app; treat as "nothing found" for this run.
                SafeLog.e(TAG, "integrity check failed", t)
                IntegrityReport(emptySet(), CertStatus.UNAVAILABLE, InstallerKind.UNKNOWN, isDebugBuild = false)
            }
            lastRunAt = now
            val previous = _latest.value
            if (previous == null || previous.signals != report.signals || previous.certStatus != report.certStatus) {
                SafeLog.i(TAG, "integrity risk=${report.risk} categories=${report.categories} cert=${report.certStatus} installer=${report.installer}")
            }
            _latest.value = report
            report
        }
    }

    private companion object {
        const val TAG = "Integrity"
        const val MIN_INTERVAL_MS = 15_000L
    }
}
