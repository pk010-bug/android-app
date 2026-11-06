package app.privateinbox.ui.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.privateinbox.data.db.ConversationEntity
import app.privateinbox.data.scam.ScamDetector
import app.privateinbox.domain.Category
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.common.Avatar
import app.privateinbox.ui.common.Format
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.categoryColor
import kotlinx.coroutines.launch

@Composable
fun InboxScreen(
    onOpenConversation: (Long) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onArchived: () -> Unit,
) {
    val container = LocalContainer.current
    val dao = container.database.conversationDao()
    var selected by rememberSaveable { mutableStateOf<Category?>(null) }
    val flow = remember(selected) { selected?.let(dao::observeByCategory) ?: dao.observeAll() }
    val threads by flow.collectAsState(initial = null)
    val unread by remember { dao.observeUnreadByCategory() }.collectAsState(initial = emptyList())
    val importDone by remember { container.settings.importDone }.collectAsState(initial = true)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Inbox", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onArchived) { Icon(Icons.Outlined.Archive, contentDescription = "Archived") }
                IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                item { FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("All") }) }
                items(Category.entries) { c ->
                    val n = unread.firstOrNull { it.category == c }?.unread ?: 0
                    FilterChip(
                        selected = selected == c,
                        onClick = { selected = c },
                        label = { Text(if (n > 0) "${c.label} · $n" else c.label) },
                    )
                }
            }
            when {
                threads == null -> Unit
                threads!!.isEmpty() -> EmptyState(
                    if (!importDone) "Importing your messages on this device…" else "No ${selected?.label?.lowercase() ?: ""} messages.",
                )
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(threads!!, key = { it.id }) { t ->
                        ConversationRow(t, onClick = { onOpenConversation(t.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCompose,
            icon = { Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null) },
            text = { Text("New") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(t: ConversationEntity, onClick: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val hideOtp by remember { container.settings.hideOtpInNotifications }.collectAsState(initial = true)
    val cat = t.categoryOverride ?: t.category
    val name = t.displayName ?: t.address
    Box {
        ListItem(
            leadingContent = { Avatar(name, categoryColor(cat)) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (t.pinned) Icon(Icons.Outlined.PushPin, null, Modifier.padding(end = 2.dp))
                    Text(
                        name,
                        fontWeight = if (t.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (t.muted) Icon(Icons.Outlined.NotificationsOff, "Muted")
                    if (t.scamScore >= ScamDetector.SUSPICIOUS) Icon(Icons.Outlined.Warning, "Possible scam", tint = MaterialTheme.colorScheme.error)
                }
            },
            supportingContent = { Text(app.privateinbox.security.OtpMask.maybeMask(t.lastMessagePreview, hideOtp), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(Format.shortTime(t.lastMessageAt), style = MaterialTheme.typography.labelSmall)
                    if (t.unreadCount > 0) {
                        BadgedBox(badge = { Badge { Text("${t.unreadCount}") } }) {}
                    }
                }
            },
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            val m = container.messages
            fun act(block: suspend () -> Unit) {
                menu = false
                scope.launch { block() }
            }
            DropdownMenuItem(text = { Text(if (t.pinned) "Unpin" else "Pin") }, onClick = { act { m.setPinned(t.id, !t.pinned) } })
            DropdownMenuItem(text = { Text(if (t.muted) "Unmute" else "Mute") }, onClick = { act { m.setMuted(t.id, !t.muted) } })
            DropdownMenuItem(text = { Text(if (t.archived) "Unarchive" else "Archive") }, onClick = { act { m.setArchived(t.id, !t.archived) } })
            DropdownMenuItem(text = { Text("Mark as read") }, onClick = { act { m.markConversationRead(t.id) } })
            DropdownMenuItem(text = { Text("Block sender") }, onClick = { act { m.block(t.id) } })
            DropdownMenuItem(text = { Text("Move to recycle bin") }, onClick = { act { m.deleteConversation(t.id) } })
        }
    }
}

/** Archived conversations list. */
@Composable
fun ArchivedScreen(onOpenConversation: (Long) -> Unit, onBack: () -> Unit) {
    val dao = LocalContainer.current.database.conversationDao()
    val threads by remember { dao.observeArchived() }.collectAsState(initial = emptyList())
    app.privateinbox.ui.common.SubScreen("Archived", onBack) {
        if (threads.isEmpty()) {
            EmptyState("Nothing archived.")
        } else {
            LazyColumn { items(threads, key = { it.id }) { ConversationRow(it) { onOpenConversation(it.id) } } }
        }
    }
}

