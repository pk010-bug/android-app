package app.privateinbox.ui.alerts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.privateinbox.data.prefs.SettingsStore
import app.privateinbox.security.SecureScreen
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.ScreenColumn

@Composable
fun AlertsScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val secure by settings.secureScreens.collectAsState(initial = true)
    SecureScreen(enabled = secure)

    ScreenColumn(title = "Alerts") {
        // TODO(alerts): recent OTPs (MessageDao.observeRecentOtps) and upcoming bill reminders.
        EmptyState("Recent OTPs and bill reminders will show up here.")
    }
}
