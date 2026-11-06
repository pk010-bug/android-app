package app.privateinbox.ui.blocked

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.common.Format
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.SubScreen
import kotlinx.coroutines.launch

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val blocked by remember { c.database.blockedSenderDao().observeAll() }.collectAsState(initial = emptyList())
    SubScreen("Blocked senders", onBack) {
        Text(
            "Messages from blocked senders are kept silently in Spam and never notify you.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        if (blocked.isEmpty()) EmptyState("No blocked senders.")
        LazyColumn {
            items(blocked, key = { it.addressKey }) { b ->
                ListItem(
                    headlineContent = { Text(b.address) },
                    supportingContent = { Text("Blocked ${Format.date(b.createdAt)}") },
                    trailingContent = {
                        TextButton(onClick = { scope.launch { c.messages.unblock(b.addressKey) } }) { Text("Unblock") }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
