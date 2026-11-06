package app.privateinbox.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.privateinbox.data.prefs.ThemeMode
import app.privateinbox.sms.DefaultSmsApp
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.lock.AppLock
import app.privateinbox.work.Workers
import kotlinx.coroutines.launch

private val privacyPoints = listOf(
    "No internet permission" to "PrivateInbox cannot open a network connection. Android enforces this, not just our promise.",
    "On-device only" to "Messages, categories, OTPs and spending data live in this app's private storage.",
    "Encrypted storage" to "The local database is encrypted (SQLCipher, AES-256) with a key protected by the Android Keystore.",
    "No trackers" to "No analytics, ads, crash reporters, Firebase or Google Play Services.",
    "No cloud backup" to "App data is excluded from Android cloud backup and device transfer. Backups are files you create and keep yourself, encrypted with your password.",
    "Nothing sends by itself" to "Links and shares from other apps only fill in a draft. A message is sent only when you tap Send.",
    "No direct calls" to "PrivateInbox has no permission to place calls. The call button opens your phone's dialer, and you press call there.",
    "Safer attachments" to "Shared images are checked, size-limited, and copied into private storage. Files from file:// links are refused.",
    "Readable rules" to "Categories come from plain regex rules you can inspect and edit.",
)

enum class SettingsDest { RULES, BLOCKED, RECYCLE, ARCHIVED, BACKUP, SCHEDULED }

@Composable
fun SettingsScreen(onNavigate: (SettingsDest) -> Unit) {
    val context = LocalContext.current
    val c = LocalContainer.current
    val s = c.settings
    val scope = rememberCoroutineScope()
    val appLock by s.appLockEnabled.collectAsState(initial = false)
    val timeout by s.lockTimeoutSec.collectAsState(initial = 30)
    val hideOtp by s.hideOtpInNotifications.collectAsState(initial = true)
    val confirmSend by s.confirmBeforeSend.collectAsState(initial = true)
    val notif by s.notificationsEnabled.collectAsState(initial = true)
    val promo by s.notifyPromotions.collectAsState(initial = false)
    val bills by s.billReminders.collectAsState(initial = true)
    val scam by s.scamWarnings.collectAsState(initial = true)
    val widgetNames by s.widgetShowNames.collectAsState(initial = false)
    val days by s.recycleBinDays.collectAsState(initial = 30)
    val theme by s.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    var isDefault by remember { mutableStateOf(DefaultSmsApp.isDefault(context)) }
    var lockError by remember { mutableStateOf<String?>(null) }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = DefaultSmsApp.isDefault(context)
        if (isDefault) Workers.importNow(context)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 16.dp))

        Section("Default SMS app")
        ListItem(
            headlineContent = { Text(if (isDefault) "PrivateInbox is your default SMS app" else "Not the default SMS app") },
            supportingContent = { Text(if (isDefault) "Receiving and sending are enabled." else "Read-only mode: you can browse, but not receive or send.") },
            trailingContent = {
                if (!isDefault) TextButton(onClick = { DefaultSmsApp.requestRoleIntent(context)?.let(role::launch) }) { Text("Set") }
            },
        )

        Section("Security")
        Toggle("App lock", "Fingerprint / face / device PIN to open. Also blocks screenshots of sensitive screens.", appLock) { on ->
            val activity = context as? FragmentActivity
            if (on && (activity == null || !AppLock.available(activity))) {
                lockError = "Set up a screen lock on this phone first."
            } else {
                lockError = null
                scope.launch { s.setAppLock(on); app.privateinbox.widget.InboxWidget.requestUpdate(context) }
            }
        }
        lockError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (appLock) {
            Choice("Lock after leaving the app", timeoutLabel(timeout), listOf(0, 30, 60, 300, 900).map { it to timeoutLabel(it) }) {
                scope.launch { s.setLockTimeoutSec(it) }
            }
        }
        Toggle("Hide OTPs in notifications", "Keep codes out of notification text entirely (use Copy). Also blocks screenshots of OTP, finance and chat screens.", hideOtp) {
            scope.launch { s.setHideOtpInNotifications(it) }
        }
        Toggle("Confirm before sending", "Show a final check (recipient, SIM, text) before any message is sent.", confirmSend) {
            scope.launch { s.setConfirmBeforeSend(it) }
        }

        Section("Notifications")
        Toggle("Message notifications", null, notif) { scope.launch { s.setNotificationsEnabled(it) } }
        Toggle("Notify for promotions", "Off by default: promotions arrive silently.", promo) { scope.launch { s.setNotifyPromotions(it) } }
        Toggle("Bill reminders", "Remind me 3 days before a detected due date.", bills) { scope.launch { s.setBillReminders(it) } }
        Toggle("Scam warnings", "Flag risky messages in notifications and conversations.", scam) { scope.launch { s.setScamWarnings(it) } }
        Toggle(
            "Show names on home-screen widget",
            "Off: the widget shows unread counts only. Names are always hidden while app lock is on; message text and codes are never shown.",
            widgetNames,
        ) { scope.launch { s.setWidgetShowNames(it); app.privateinbox.widget.InboxWidget.requestUpdate(context) } }

        Section("Inbox")
        Nav("Smart inbox rules", "See and edit how messages are sorted") { onNavigate(SettingsDest.RULES) }
        Nav("Blocked senders", null) { onNavigate(SettingsDest.BLOCKED) }
        Nav("Archived conversations", null) { onNavigate(SettingsDest.ARCHIVED) }
        Nav("Recycle bin", "Deleted messages are kept for $days days") { onNavigate(SettingsDest.RECYCLE) }
        Choice("Keep deleted messages for", "$days days", listOf(7, 30, 90).map { it to "$it days" }) {
            scope.launch { s.setRecycleBinDays(it) }
        }

        Section("Appearance")
        Choice("Theme", theme.name.lowercase().replaceFirstChar(Char::uppercase), ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
            scope.launch { s.setThemeMode(it) }
        }

        Section("Data")
        Nav("Scheduled messages", "Review or cancel texts waiting to be sent") { onNavigate(SettingsDest.SCHEDULED) }
        Nav("Encrypted backup & restore", "Save a password-protected file you control") { onNavigate(SettingsDest.BACKUP) }
        Nav("Re-scan phone messages", "Import anything new from the system SMS store") { Workers.importNow(context) }
        Nav("Re-apply rules to all messages", null) { scope.launch { c.messages.recategorizeAll(); c.messages.rebuildFinance() } }

        Section("Privacy")
        Card(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
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
    }
}

private fun timeoutLabel(sec: Int) = when (sec) {
    0 -> "Immediately"
    in 1..59 -> "$sec seconds"
    else -> "${sec / 60} min"
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun Nav(title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@Composable
private fun <T> Choice(title: String, current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(current) },
            modifier = Modifier.clickable { open = true },
        )
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(v) }) }
        }
    }
}
