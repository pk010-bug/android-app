package app.privateinbox.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.privateinbox.data.db.ScheduledMessageEntity
import app.privateinbox.sms.ScheduledSends
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.SubScreen
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun fmt(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

@Composable
private fun ScheduledRow(row: ScheduledMessageEntity, showRecipients: Boolean) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, contentDescription = null)
            Column(Modifier.weight(1f).padding(8.dp)) {
                Text("Scheduled · ${fmt(row.sendAt)}", style = MaterialTheme.typography.labelMedium)
                if (showRecipients) Text(row.recipients.replace("\n", ", "), style = MaterialTheme.typography.bodySmall)
                if (row.attachmentName != null) Text("[photo]", style = MaterialTheme.typography.labelSmall)
                if (row.body.isNotEmpty()) Text(row.body, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { scope.launch { ScheduledSends.cancel(context.applicationContext, container.database, row.id) } }) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel scheduled message")
            }
        }
    }
}

/** Pending scheduled messages for one conversation, shown above its composer. */
@Composable
fun ScheduledStrip(conversationId: Long) {
    val container = LocalContainer.current
    val rows by remember(conversationId) { container.database.scheduledDao().observePendingFor(conversationId) }
        .collectAsState(initial = emptyList())
    if (rows.isEmpty()) return
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { ScheduledRow(it, showRecipients = false) }
    }
}

/** Settings → Scheduled messages. */
@Composable
fun ScheduledScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val rows by remember { container.database.scheduledDao().observePending() }.collectAsState(initial = emptyList())
    SubScreen(title = "Scheduled messages", onBack = onBack) {
        if (rows.isEmpty()) {
            Text("Nothing scheduled. Use the clock button next to Send.", Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { ScheduledRow(it, showRecipients = true) }
            }
        }
    }
}
