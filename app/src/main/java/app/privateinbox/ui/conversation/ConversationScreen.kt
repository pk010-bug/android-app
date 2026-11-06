package app.privateinbox.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.privateinbox.calls.Dialer
import app.privateinbox.data.prefs.SettingsStore
import app.privateinbox.security.SecureScreen
import app.privateinbox.ui.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(conversationId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val secure by settings.secureScreens.collectAsState(initial = true)
    SecureScreen(enabled = secure)

    // TODO(ui): load the real address from ConversationDao.getById(conversationId).
    val address: String? = null
    val dialable = address?.let(Dialer::normalize)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Conversation #$conversationId") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                // Opens the system dialer pre-filled (ACTION_DIAL). We never place calls ourselves.
                IconButton(enabled = dialable != null, onClick = { dialable?.let { Dialer.openDialer(context, it) } }) {
                    Icon(Icons.Outlined.Call, contentDescription = "Open in dialer")
                }
            },
        )
        // TODO(ui): observe MessageDao.observeConversation(conversationId) + composer row.
        EmptyState("Messages will appear here.\nEverything stays on this device.")
    }
}
