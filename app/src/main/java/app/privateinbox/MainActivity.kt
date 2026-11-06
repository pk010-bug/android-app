package app.privateinbox

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
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
import app.privateinbox.compose.AndroidIntentReader
import app.privateinbox.compose.IntentTriage
import app.privateinbox.compose.TriageResult
import app.privateinbox.navigation.DeepLinks
import app.privateinbox.notifications.OtpNotifier
import app.privateinbox.security.SafeLog
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
    private var backgroundedAt = 0L

    private val container get() = (application as PrivateInboxApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        lifecycleScope.launch { locked.value = container.settings.appLockEnabled.first() }
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
                            lockOn == true && locked.value -> LockScreen(onUnlocked = { locked.value = false })
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
        lifecycleScope.launch {
            val s = container.settings
            if (!s.appLockEnabled.first()) return@launch
            val timeoutMs = s.lockTimeoutSec.first() * 1000L
            if (SystemClock.elapsedRealtime() - backgroundedAt >= timeoutMs) locked.value = true
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
        when (val result = IntentTriage.triage(AndroidIntentReader.read(intent), importer.ownAuthorities)) {
            TriageResult.OpenApp -> Unit
            is TriageResult.Ignore -> SafeLog.w(TAG, "ignored external intent: ${result.reason}")
            is TriageResult.Prefill -> lifecycleScope.launch {
                val prefill = result.prefill
                // Copy accepted attachments into private storage right away.
                val imported = withContext(Dispatchers.IO) { prefill.attachmentUris.mapNotNull(importer::import) }
                val notices = buildList {
                    if (prefill.autoSendIgnored) add("The other app asked to send automatically. Review and tap Send yourself.")
                    val rejected = prefill.rejectedAttachments.size + (prefill.attachmentUris.size - imported.size)
                    if (rejected > 0) add("$rejected attachment(s) were refused for safety.")
                }
                pendingDraft.value = Draft(
                    recipients = prefill.recipients,
                    body = prefill.body,
                    attachmentNames = imported.map { it.file.name },
                    notices = notices,
                )
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
