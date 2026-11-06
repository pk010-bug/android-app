package app.privateinbox.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.privateinbox.data.scam.ScamDetector
import app.privateinbox.security.SecureScreen
import app.privateinbox.security.SensitiveClipboard
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.common.Format
import app.privateinbox.ui.common.LocalContainer
import kotlinx.coroutines.launch

/** OTPs from the last 24 h, upcoming bills, and suspicious messages. */
@Composable
fun AlertsScreen(onOpenConversation: (Long) -> Unit) {
    val context = LocalContext.current
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val secure by c.settings.secureScreens.collectAsState(initial = true)
    SecureScreen(enabled = secure)
    val since = remember { System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
    val otps by remember { c.database.messageDao().observeRecentOtps(since) }.collectAsState(initial = emptyList())
    val bills by remember { c.database.billDao().observeAll() }.collectAsState(initial = emptyList())
    val suspicious by remember { c.database.messageDao().observeSuspicious(ScamDetector.SUSPICIOUS) }.collectAsState(initial = emptyList())

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Alerts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        item { Section("Recent codes (24 h)") }
        if (otps.isEmpty()) item { EmptyState("No recent codes.") }
        items(otps, key = { "o" + it.id }) { m ->
            ListItem(
                headlineContent = { Text(m.otpCode.orEmpty(), style = MaterialTheme.typography.titleLarge) },
                supportingContent = { Text("${m.address} · ${Format.shortTime(m.timestamp)}") },
                trailingContent = {
                    IconButton(onClick = { SensitiveClipboard.copy(context, "OTP", m.otpCode.orEmpty()) }) {
                        Icon(Icons.Outlined.ContentCopy, "Copy code")
                    }
                },
                modifier = Modifier.clickable { onOpenConversation(m.conversationId) },
            )
        }
        item { Section("Bills & dues") }
        if (bills.isEmpty()) item { EmptyState("No bills detected yet.") }
        items(bills, key = { "b" + it.id }) { b ->
            ListItem(
                headlineContent = {
                    Text(b.biller, textDecoration = if (b.paid) TextDecoration.LineThrough else null)
                },
                supportingContent = {
                    Text(listOfNotNull(b.amountMinor?.let(Format::money), b.dueDate?.let { "due ${Format.date(it)}" } ?: "no due date").joinToString(" · "))
                },
                trailingContent = {
                    Checkbox(b.paid, onCheckedChange = { p -> scope.launch { c.database.billDao().setPaid(b.id, p) } })
                },
            )
            HorizontalDivider()
        }
        item { Section("Suspicious messages") }
        if (suspicious.isEmpty()) item { EmptyState("Nothing suspicious. Nice.") }
        items(suspicious, key = { "s" + it.id }) { m ->
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text("${m.address} · risk ${m.scamScore}") },
                supportingContent = { Text(m.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.clickable { onOpenConversation(m.conversationId) },
            )
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}
