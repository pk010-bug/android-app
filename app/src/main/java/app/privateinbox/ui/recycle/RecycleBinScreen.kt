package app.privateinbox.ui.recycle

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.common.Format
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.SubScreen
import kotlinx.coroutines.launch

@Composable
fun RecycleBinScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val items by remember { c.database.messageDao().observeRecycleBin() }.collectAsState(initial = emptyList())
    val days by c.settings.recycleBinDays.collectAsState(initial = 30)
    var confirmEmpty by remember { mutableStateOf(false) }

    SubScreen("Recycle bin", onBack, actions = {
        if (items.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty") }
    }) {
        Text(
            "Deleted messages are kept for $days days, then removed for good (also from the phone's SMS store).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        if (items.isEmpty()) EmptyState("Recycle bin is empty.")
        LazyColumn {
            items(items, key = { it.id }) { m ->
                ListItem(
                    headlineContent = { Text(m.address) },
                    supportingContent = { Text(m.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    overlineContent = { Text("Deleted ${Format.date(m.deletedAt ?: 0)}") },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { scope.launch { c.messages.restoreMessages(listOf(m.id)) } }) { Text("Restore") }
                            TextButton(onClick = { scope.launch { c.messages.purge(listOf(m.id)) } }) { Text("Delete") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty recycle bin?") },
            text = { Text("${items.size} message(s) will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    scope.launch { c.messages.purge(items.map { it.id }) }
                }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}
