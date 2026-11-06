package app.privateinbox

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.privateinbox.data.prefs.ThemeMode
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.lock.LockScreen
import app.privateinbox.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.privateinbox.attachments.AttachmentImporter
import app.privateinbox.attachments.UriDecision
import app.privateinbox.compose.AndroidIntentReader
import app.privateinbox.compose.IntentTriage
import app.privateinbox.compose.TriageResult
import app.privateinbox.navigation.DeepLinks
import app.privateinbox.notifications.OtpNotifier
import app.privateinbox.security.SafeLog
import app.privateinbox.security.SecureWindow
import app.privateinbox.security.integrity.IntegrityReport
import app.privateinbox.security.integrity.IntegrityRisk
import app.privateinbox.ui.lock.AppLock
import app.privateinbox.ui.compose.Draft
import app.privateinbox.ui.navigation.PrivateInboxNavHost
import app.privateinbox.ui.theme.PrivateInboxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only exported activity (launcher + the SENDTO/SEND filters the default-SMS role requires).
 * Every incoming Intent is treated as untrusted: it can open a validated route or prefill a
 * draft, never send anything.
 */
class MainActivity : FragmentActivity() {

    private val pendingDraft = mutableStateOf<Draft?>(null)
    private val pendingRoute = mutableStateOf<String?>(null)
    private val locked = mutableStateOf(true)
    /**
     * HB-1: mirrors the process-wide [app.privateinbox.security.integrity.IntegrityLockGate]: shows
     * the lock screen even if app lock is off. Independent of [locked], so loading the app-lock
     * preference can't clear it. Initialised in onCreate (application isn't attached yet here).
     */
    private val integrityLock = mutableStateOf(false)
    /** Whether the phone can authenticate right now (refreshed on create/start and on each high-risk report). */
    private val canAuthenticate = mutableStateOf(false)
    private val integrityWarning = mutableStateOf(false)
    private val resetError = mutableStateOf<String?>(null)
    private var backgroundedAt = 0L

    /**
     * F-03: in-memory copy of the lock settings, kept current by a collector started in onCreate,
     * so onStart can decide to re-lock synchronously (before the first frame is drawn) instead of
     * in a coroutine that may run after content is already visible. null = not loaded yet.
     */
    @Volatile private var lockEnabledNow: Boolean? = null
    @Volatile private var lockTimeoutMsNow: Long = 30_000L

    /**
     * F-13 / RB-2: the encrypted database is opened off the main thread. A failure shows a retry
     * screen (and, for a key that looks lost, a user-confirmed reset); nothing is deleted automatically.
     */
    private val dbState = mutableStateOf<DbState>(DbState.Opening)

    private sealed interface DbState {
        data object Opening : DbState
        data object Ready : DbState
        data object Unavailable : DbState
        data object KeyLost : DbState
    }

    private val container get() = (application as PrivateInboxApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Secure by default until settings load; then app-wide FLAG_SECURE follows the app-lock switch
        // (covers Inbox, Search, Archived, Recycle bin, Compose and every other screen + Recents).
        SecureWindow.setAppWide(this, true)
        lifecycleScope.launch { container.settings.appLockEnabled.collect { SecureWindow.setAppWide(this@MainActivity, it) } }
        if (savedInstanceState == null) handle(intent)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { AttachmentImporter(applicationContext).sweep() } }
        // HB-1: the integrity lock lives in the process-wide gate, so this can't clear it.
        integrityLock.value = container.integrity.lockGate.authRequired
        canAuthenticate.value = AppLock.available(this)
        lifecycleScope.launch { locked.value = container.settings.appLockEnabled.first() }
        lifecycleScope.launch { container.settings.appLockEnabled.collect { lockEnabledNow = it } }
        lifecycleScope.launch { container.settings.lockTimeoutSec.collect { lockTimeoutMsNow = it * 1000L } }
        openDatabase()
        checkIntegrity(force = true)
        setContent {
            val settings = container.settings
            val theme by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            // null until DataStore loads, so we never flash content before deciding to lock.
            val lockOn by remember { settings.appLockEnabled.map<Boolean, Boolean?> { it } }.collectAsState(initial = null)
            val onboarded by remember { settings.onboardingDone.map<Boolean, Boolean?> { it } }.collectAsState(initial = null)
            val scope = rememberCoroutineScope()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            CompositionLocalProvider(LocalContainer provides container) {
                PrivateInboxTheme(darkTheme = dark) {
                    Surface {
                        when {
                            lockOn == null || onboarded == null -> Unit
                            RelockPolicy.showLockScreen(lockOn == true, locked.value, integrityLock.value, canAuthenticate.value) ->
                                LockScreen(onUnlocked = {
                                    locked.value = false
                                    container.integrity.lockGate.onUnlocked()
                                    integrityLock.value = false
                                })
                            dbState.value != DbState.Ready -> app.privateinbox.ui.common.DatabaseStatusScreen(
                                problem = when (dbState.value) {
                                    DbState.Unavailable -> app.privateinbox.ui.common.DatabaseProblem.TEMPORARY
                                    DbState.KeyLost -> app.privateinbox.ui.common.DatabaseProblem.KEY_LOST
                                    else -> app.privateinbox.ui.common.DatabaseProblem.NONE
                                },
                                onRetry = ::openDatabase,
                                onReset = ::resetLocalData,
                                resetError = resetError.value,
                            )
                            onboarded == false -> OnboardingScreen(onDone = { scope.launch { settings.setOnboardingDone(true) } })
                            else -> PrivateInboxNavHost(
                                pendingDraft = pendingDraft.value,
                                pendingRoute = pendingRoute.value,
                                onExternalConsumed = {
                                    pendingDraft.value = null
                                    pendingRoute.value = null
                                },
                            )
                        }
                        if (integrityWarning.value) {
                            // One-time, non-blocking: the app keeps working after OK.
                            AlertDialog(
                                onDismissRequest = { integrityWarning.value = false },
                                confirmButton = { TextButton(onClick = { integrityWarning.value = false }) { Text("OK") } },
                                title = { Text("Security check") },
                                text = { Text(INTEGRITY_WARNING) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (backgroundedAt == 0L) return
        canAuthenticate.value = AppLock.available(this)
        integrityLock.value = container.integrity.lockGate.authRequired
        // F-03: synchronous, from the in-memory settings (unknown yet => treat as locked).
        if (RelockPolicy.shouldRelock(lockEnabledNow, lockTimeoutMsNow, SystemClock.elapsedRealtime() - backgroundedAt)) {
            locked.value = true
        }
        checkIntegrity(force = false)
    }

    private fun openDatabase() {
        if (dbState.value == DbState.Ready) return
        dbState.value = DbState.Opening
        lifecycleScope.launch {
            dbState.value = withContext(Dispatchers.IO) {
                // RB-1: the v1.0.3 auto-download preference is obsolete; drop any stored value.
                runCatching { container.settings.dropLegacyMmsAutoDownload() }
                try {
                    container.database.openHelper.writableDatabase // opens + migrates now, off the main thread
                    runCatching { container.resetStuckMmsDownloads() } // stuck "Downloading…" becomes retryable
                    DbState.Ready
                } catch (e: app.privateinbox.data.db.PrivateInboxDatabase.Companion.DatabaseKeyLostException) {
                    SafeLog.w(TAG, "database key unavailable; waiting for the user")
                    DbState.KeyLost
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Transient Keystore failure, or anything else: keep the file, offer retry.
                    SafeLog.w(TAG, "database unavailable: ${e.javaClass.simpleName}")
                    DbState.Unavailable
                }
            }
        }
    }

    /**
     * RB-2: the user confirmed "Reset local data" on the recovery screen. Re-verifies them (where
     * the phone has a screen lock), then deletes the encrypted local cache and re-imports SMS from
     * the system store. This is the only path that deletes the database.
     */
    private fun resetLocalData() {
        resetError.value = null
        AppLock.requireFreshAuth(this, "Reset PrivateInbox's local data", onError = { resetError.value = it }) {
            lifecycleScope.launch {
                val previous = dbState.value
                dbState.value = DbState.Opening
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        app.privateinbox.data.db.PrivateInboxDatabase.resetAfterUserConfirmation(applicationContext)
                        container.settings.setLastImportedProviderId(0L)
                        container.settings.setImportDone(false)
                    }.isSuccess
                }
                if (!ok) {
                    resetError.value = "Couldn't reset. Nothing else was changed; try again."
                    dbState.value = previous
                    return@launch
                }
                dbState.value = DbState.Unavailable // let openDatabase run
                openDatabase()
                app.privateinbox.work.Workers.importNow(applicationContext)
            }
        }
    }

    /**
     * I-01: local integrity checks off the main thread (throttled in IntegrityMonitor). High risk in a
     * release build (tampered package, hooking framework, debugger) re-locks the app once per new
     * signal set, so continuing needs a fresh strong authentication, and shows a one-time warning.
     * Root alone is informational (Settings). Never blocks: without a screen lock we only warn.
     */
    private fun checkIntegrity(force: Boolean) {
        lifecycleScope.launch {
            val report = container.integrity.refresh(force)
            if (report.risk == IntegrityRisk.HIGH) onHighRisk(report)
        }
    }

    private suspend fun onHighRisk(report: IntegrityReport) {
        val high = report.highRiskSignals
        // HB-1: process-wide, once per new signal set; only when the phone can authenticate.
        val canAuth = AppLock.available(this)
        canAuthenticate.value = canAuth
        val gate = container.integrity.lockGate
        gate.onHighRisk(high, canAuth)
        integrityLock.value = gate.authRequired
        val key = report.key(high)
        val s = container.settings
        if (s.integrityWarnedSignals.first() != key) {
            s.setIntegrityWarnedSignals(key)
            integrityWarning.value = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.action == OtpNotifier.ACTION_OPEN_ROUTE) {
            // Any app can send this action to an exported activity, so validate strictly.
            pendingRoute.value = DeepLinks.validate(intent.getStringExtra(DeepLinks.EXTRA_ROUTE))
            return
        }
        val importer = AttachmentImporter(applicationContext)
        when (val result = IntentTriage.triage(AndroidIntentReader.read(intent), importer.ownAuthorities, importer.currentUserId)) {
            TriageResult.OpenApp -> Unit
            is TriageResult.Ignore -> SafeLog.w(TAG, "ignored external intent: ${result.reason}")
            is TriageResult.Prefill -> {
                val prefill = result.prefill
                // URIs the sender granted us (the system validated these against the sender when it
                // delivered the Intent). Nothing is copied here: the user taps "Attach" in the draft.
                val granted = grantedUris(intent)
                val usable = prefill.attachmentUris.filter { importer.check(it, it in granted) == UriDecision.Accept }
                val notices = buildList {
                    if (prefill.autoSendIgnored) add("The other app asked to send automatically. Review and tap Send yourself.")
                    val rejected = prefill.rejectedAttachments.size + (prefill.attachmentUris.size - usable.size)
                    if (rejected > 0) add("$rejected attachment(s) were refused for safety.")
                }
                pendingDraft.value = Draft(
                    recipients = prefill.recipients,
                    body = prefill.body,
                    sharedUris = usable,
                    // Carry the real grant decision to the "Attach" tap instead of assuming one there.
                    grantedSharedUris = usable.filter { it in granted }.toSet(),
                    notices = notices,
                    external = true,
                )
            }
        }
    }

    private fun grantedUris(intent: Intent): Set<String> {
        if (intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return emptySet()
        return runCatching {
            buildSet {
                intent.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.toString()?.let(::add) }
                intent.dataString?.let(::add)
            }
        }.getOrDefault(emptySet())
    }

    private companion object {
        const val TAG = "MainActivity"
        const val INTEGRITY_WARNING =
            "PrivateInbox found signs that it is being inspected or modified on this phone (a hooking tool, " +
                "a debugger, or an app signature that doesn't match the official release). Your messages never " +
                "leave this phone, but PrivateInbox locked itself and will ask for your screen lock before " +
                "sensitive actions. If you didn't expect this, reinstall PrivateInbox from a source you trust."
    }
}
