package app.privateinbox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.privateinbox.data.prefs.SettingsStore
import app.privateinbox.ui.ScreenColumn
import kotlinx.coroutines.launch

private val privacyPoints = listOf(
    "No internet permission" to "PrivateInbox cannot open a network connection. Android enforces this, not just our promise.",
    "On-device only" to "Messages, categories, OTPs and spending data live in this app's private storage.",
    "No trackers" to "No analytics, ads, crash reporters, Firebase or Google Play Services.",
    "No cloud backup" to "App data is excluded from Android cloud backup and device transfer.",
    "Readable rules" to "Categories come from plain JSON regex rules you can inspect and edit.",
    "Encrypted storage" to "The local database is encrypted (SQLCipher, AES-256) with a key protected by the Android Keystore.",
    "Nothing sends by itself" to "Links and shares from other apps only fill in a draft. A message is sent only when you tap Send.",
    "No direct calls" to "PrivateInbox has no permission to place calls. The call button opens your phone's dialer, and you press call there.",
    "Safer attachments" to "Shared images are checked, size-limited, and copied into private storage. Files from file:// links are refused.",
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val appLock by store.appLockEnabled.collectAsState(initial = false)
    val hideOtp by store.hideOtpInNotifications.collectAsState(initial = true)

    ScreenColumn(title = "Settings") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    ListItem(
                        headlineContent = { Text("Your data never leaves this phone") },
                        leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    )
                    privacyPoints.forEach { (title, body) ->
                        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            ListItem(
                headlineContent = { Text("App lock") },
                supportingContent = {
                    Text("Require fingerprint / device PIN to open (coming soon). Also blocks screenshots of sensitive screens.")
                },
                trailingContent = {
                    Switch(checked = appLock, onCheckedChange = { scope.launch { store.setAppLock(it) } })
                },
            )
            ListItem(
                headlineContent = { Text("Hide OTPs in notifications") },
                supportingContent = {
                    Text("Keep codes out of notification text entirely (use Copy). Also blocks screenshots of OTP, finance and chat screens.")
                },
                trailingContent = {
                    Switch(checked = hideOtp, onCheckedChange = { scope.launch { store.setHideOtpInNotifications(it) } })
                },
            )
        }
    }
}
