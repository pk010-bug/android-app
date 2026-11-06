package app.privateinbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
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
class MainActivity : ComponentActivity() {

    private val pendingDraft = mutableStateOf<Draft?>(null)
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        // TODO(security): gate content behind BiometricPrompt when app lock is enabled
        //  (requires FragmentActivity; see ui/settings).
        setContent {
            PrivateInboxTheme {
                PrivateInboxNavHost(
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
