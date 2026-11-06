package app.privateinbox.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.privateinbox.calls.Dialer
import app.privateinbox.data.db.MessageEntity
import app.privateinbox.data.scam.ScamDetector
import app.privateinbox.domain.AddressNormalizer
import app.privateinbox.domain.Category
import app.privateinbox.domain.MessageStatus
import app.privateinbox.security.SecureScreen
import app.privateinbox.security.SensitiveClipboard
import app.privateinbox.ui.common.Format
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.Pill
import app.privateinbox.ui.common.SubScreen
import app.privateinbox.ui.common.categoryColor
import app.privateinbox.ui.compose.MessageComposer
import kotlinx.coroutines.launch

@Composable
fun ConversationScreen(conversationId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val secure by container.settings.secureScreens.collectAsState(initial = true)
    SecureScreen(enabled = secure)

    val conv by remember(conversationId) { container.database.conversationDao().observeById(conversationId) }.collectAsState(initial = null)
    val msgs by remember(conversationId) { container.database.messageDao().observeConversation(conversationId) }.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1)
        container.messages.markConversationRead(conversationId)
        container.messageNotifier.cancelConversation(conversationId)
    }
    var menu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    val c = conv
    val address = c?.address
    val dialable = address?.let(Dialer::normalize)
    val canReply = address != null && AddressNormalizer.isPhoneNumber(address)

    SubScreen(
        title = c?.displayName ?: address ?: "Conversation",
        onBack = onBack,
        actions = {
            // Opens the system dialer pre-filled (ACTION_DIAL). We never place calls ourselves.
            IconButton(enabled = dialable != null, onClick = { dialable?.let { Dialer.openDialer(context, it) } }) {
                Icon(Icons.Outlined.Call, contentDescription = "Open in dialer")
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fun act(block: suspend () -> Unit) {
                    menu = false
                    scope.launch { block() }
                }
                if (c != null) {
                    DropdownMenuItem(text = { Text(if (c.muted) "Unmute" else "Mute notifications") }, onClick = { act { container.messages.setMuted(c.id, !c.muted) } })
                    DropdownMenuItem(text = { Text("Move to category…") }, onClick = { menu = false; moveMenu = true })
                    DropdownMenuItem(text = { Text("Block sender") }, onClick = { act { container.messages.block(c.id); onBack() } })
                    DropdownMenuItem(text = { Text("Delete conversation") }, onClick = { act { container.messages.deleteConversation(c.id); onBack() } })
                }
            }
            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                Category.entries.forEach { cat ->
                    DropdownMenuItem(text = { Text(cat.label) }, onClick = {
                        moveMenu = false
                        scope.launch { container.messages.moveToCategory(conversationId, cat) }
                    })
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (c != null && c.scamScore >= ScamDetector.SUSPICIOUS) {
                ScamBanner(msgs.lastOrNull { it.incoming && it.scamScore >= ScamDetector.SUSPICIOUS })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(msgs, key = { it.id }) { m -> Bubble(m) }
            }
            if (canReply) {
                MessageComposer(recipient = { address })
            } else {
                Text(
                    "This sender doesn't accept replies.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ScamBanner(m: MessageEntity?) {
    val reasons = m?.let { ScamDetector.reasonsFromString(it.scamReasons) }.orEmpty()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
                Text("  Possible scam. Don't tap links, share codes, or pay.", style = MaterialTheme.typography.titleSmall)
            }
            reasons.forEach { Text("• ${it.explanation}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: MessageEntity) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf(false) }
    val mine = !m.incoming
    if (why) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { why = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { why = false }) { Text("OK") } },
            title = { Text("Why ${m.category.label}?") },
            text = {
                Text(
                    when (m.matchedRuleId) {
                        null -> "No rule matched, so it is Unknown."
                        "blocked" -> "You blocked this sender."
                        "scam-heuristics" -> "Scam checks flagged it: " +
                            ScamDetector.reasonsFromString(m.scamReasons).joinToString("; ") { it.explanation }
                        else -> "Matched rule \"${m.matchedRuleId}\". You can edit rules in Settings → Smart inbox rules."
                    },
                )
            },
        )
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(12.dp),
        ) {
            SelectionContainer { Text(m.body, style = MaterialTheme.typography.bodyLarge) }
            m.otpCode?.let { code ->
                AssistChip(
                    onClick = { SensitiveClipboard.copy(context, "OTP", code) },
                    label = { Text("Copy code $code") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Format.fullTime(m.timestamp), style = MaterialTheme.typography.labelSmall)
                if (m.incoming) Pill(m.category.label, categoryColor(m.category))
                if (mine) {
                    Text(
                        when (m.status) {
                            MessageStatus.PENDING -> "Sending…"
                            MessageStatus.FAILED -> "Failed"
                            MessageStatus.DELIVERED -> "Delivered"
                            else -> "Sent"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.status == MessageStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Copy text") }, onClick = {
                menu = false
                SensitiveClipboard.copy(context, "Message", m.body)
            })
            DropdownMenuItem(text = { Text("Why is this here?") }, onClick = { menu = false; why = true })
            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                menu = false
                scope.launch { container.messages.deleteMessages(listOf(m.id)) }
            })
        }
    }
}
