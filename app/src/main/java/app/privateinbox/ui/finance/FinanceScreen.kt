package app.privateinbox.ui.finance

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
fun FinanceScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val secure by settings.secureScreens.collectAsState(initial = true)
    SecureScreen(enabled = secure)

    ScreenColumn(title = "Finance") {
        // TODO(finance): parse bank/UPI SMS into TransactionEntity and show monthly totals.
        EmptyState("Spending insights from bank SMS are coming after the inbox MVP.\nParsing happens entirely on-device.")
    }
}
