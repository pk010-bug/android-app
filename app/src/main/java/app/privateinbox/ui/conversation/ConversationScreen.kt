package app.privateinbox.ui.conversation

import androidx.compose.foundation.clickable

import androidx.compose.ui.graphics.asImageBitmap

import androidx.compose.ui.draw.clip

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
import app.privateinbox.ui.compose.ScheduledStrip
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
    val mmsParts by remember(conversationId) { container.database.mmsPartDao().observeForConversation(conversationId) }
        .collectAsState(initial = emptyList())
    val partsByMessage = remember(mmsParts) { mmsParts.groupBy { it.messageId } }
    val pendingMms by remember(conversationId) { container.database.pendingMmsDao().observeForConversation(conversationId) }
        .collectAsState(initial = emptyList())
    val pendingByMessage = remember(pendingMms) { pendingMms.associateBy { it.messageId } }
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
    val isGroup = c?.isGroup == true

    SubScreen(
        title = c?.displayName ?: address ?: "Conversation",
        onBack = onBack,
        actions = {
            // Opens the system dialer pre-filled (ACTION_DIAL). We never place calls ourselves.
            IconButton(enabled = dialable != null && !isGroup, onClick = { dialable?.let { Dialer.openDialer(context, it) } }) {
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
                    if (!c.isGroup) DropdownMenuItem(text = { Text("Block sender") }, onClick = { act { container.messages.block(c.id); onBack() } })
                    val custom = remember(menu) { container.messageNotifier.hasCustomChannel(c.id) }
                    DropdownMenuItem(
                        text = { Text(if (custom) "Notification sound & settings…" else "Custom notification sound…") },
                        onClick = {
                            menu = false
                            if (!custom) {
                                val label = c.displayName ?: if (c.isGroup) "Group conversation" else "Conversation ••${c.address.filter(Char::isDigit).takeLast(4)}"
                                container.messageNotifier.createCustomChannel(c.id, label)
                            }
                            runCatching { context.startActivity(container.messageNotifier.channelSettingsIntent(c.id)) }
                        },
                    )
                    if (custom) {
                        DropdownMenuItem(text = { Text("Use default notifications") }, onClick = {
                            menu = false
                            container.messageNotifier.deleteCustomChannel(c.id)
                        })
                    }
                    DropdownMenuItem(text = { Text("Delete conversation") }, onClick = {
                        container.messageNotifier.deleteCustomChannel(c.id)
                        act { container.messages.deleteConversation(c.id); onBack() }
                    })
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
                items(msgs, key = { it.id }) { m -> Bubble(m, partsByMessage[m.id].orEmpty(), pendingByMessage[m.id]) }
            }
            ScheduledStrip(conversationId)
            if (c != null && c.isGroup) {
                val members = c.members.split('\n').filter { it.isNotBlank() }
                MessageComposer(recipients = { members })
            } else if (canReply) {
                MessageComposer(recipients = { listOfNotNull(address) })
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
private fun Bubble(
    m: MessageEntity,
    parts: List<app.privateinbox.data.db.MmsPartEntity> = emptyList(),
    pending: app.privateinbox.data.db.PendingMmsEntity? = null,
) {
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
            m.mmsSubject?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            parts.filter { it.fileName != null }.forEach { if (it.mimeType.startsWith("image/")) MmsImage(it) else MediaChip(it) }
            // F-07: copying a selection goes through SensitiveClipboard (flagged sensitive, auto-cleared).
            if (m.body.isNotEmpty()) app.privateinbox.ui.common.SensitiveSelectionContainer { Text(m.body, style = MaterialTheme.typography.bodyLarge) }
            pending?.let { PendingMmsAction(m.id, it) }
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

/**
 * F-04: an MMS that has not been downloaded. Download happens on this tap; unknown senders get
 * a warning first, blocked senders are refused (the policy is re-checked in AppContainer). A
 * download with no answer for MmsDownloadPolicy.STUCK_AFTER_MS offers Retry.
 */
@Composable
private fun PendingMmsAction(messageId: Long, p: app.privateinbox.data.db.PendingMmsEntity) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var warn by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    fun start(confirmed: Boolean) {
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.startMmsDownload(messageId, confirmed) }
            note = if (ok) null else "Couldn't start the download. Check that mobile data is on, then try again."
        }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.state, p.updatedAt) {
        now = System.currentTimeMillis()
        if (p.state == app.privateinbox.data.db.PendingMmsState.DOWNLOADING || p.state == app.privateinbox.data.db.PendingMmsState.COMPLETING) {
            val left = p.updatedAt + app.privateinbox.mms.MmsDownloadPolicy.STUCK_AFTER_MS - now
            if (left > 0) kotlinx.coroutines.delay(left + 1_000)
            now = System.currentTimeMillis()
        }
    }
    val ui = app.privateinbox.mms.MmsDownloadPolicy.uiState(p.state, p.updatedAt, now)
    if (ui == app.privateinbox.mms.MmsDownloadPolicy.UiState.DOWNLOADING) {
        Text("Downloading…", style = MaterialTheme.typography.labelMedium)
    } else {
        androidx.compose.material3.OutlinedButton(onClick = {
            scope.launch {
                val decision = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.mmsDownloadDecision(messageId) }
                note = null
                when (decision) {
                    app.privateinbox.mms.MmsDownloadPolicy.Decision.ALLOW -> start(confirmed = false)
                    app.privateinbox.mms.MmsDownloadPolicy.Decision.WARN_UNKNOWN_SENDER -> warn = true
                    app.privateinbox.mms.MmsDownloadPolicy.Decision.REFUSE_BLOCKED -> note = "This sender is blocked. Unblock them to download."
                    app.privateinbox.mms.MmsDownloadPolicy.Decision.REFUSE_TOO_LARGE -> note = "Too large to download (over 5 MB)."
                    app.privateinbox.mms.MmsDownloadPolicy.Decision.REFUSE_BAD_LOCATION, null -> note = "This message can't be downloaded."
                }
            }
        }) { Text(if (ui == app.privateinbox.mms.MmsDownloadPolicy.UiState.RETRY) "Download failed. Retry" else "Download") }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (warn) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { warn = false },
            title = { Text("Download from an unknown sender?") },
            text = {
                Text(
                    "This sender isn't in your contacts and you've never messaged them. Downloading fetches the " +
                        "message through your carrier, which can tell the sender your number is active, and the " +
                        "content may be unwanted or harmful. Only download if you expect it.",
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { warn = false; start(confirmed = true) }) { Text("Download") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { warn = false }) { Text("Cancel") } },
        )
    }
}

/** Downsampled thumbnail; tap for a full-screen viewer. Both windows are FLAG_SECURE. */
@Composable
private fun MmsImage(part: app.privateinbox.data.db.MmsPartEntity) {
    val container = LocalContainer.current
    var bitmap by remember(part.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(part.id) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.mmsStore.file(part.fileName)?.let { app.privateinbox.mms.MmsImages.decode(it, 720) }
                ?.asImageBitmap()
        }
    }
    val b = bitmap
    if (b == null) {
        Text("Photo", style = MaterialTheme.typography.labelMedium)
        return
    }
    androidx.compose.foundation.Image(
        b,
        contentDescription = "Photo",
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = true },
        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
    )
    if (open) MmsViewer(part) { open = false }
}


@Composable
private fun MmsViewer(part: app.privateinbox.data.db.MmsPartEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    var bitmap by remember(part.id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(part.id) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.mmsStore.file(part.fileName)?.let { app.privateinbox.mms.MmsImages.decode(it, 2048) }
                ?.asImageBitmap()
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn,
        ),
    ) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                androidx.compose.foundation.Image(
                    it, contentDescription = "Photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }
    }
}

/**
 * Non-image media (video / audio / contact card): never rendered or auto-opened. One explicit tap
 * plus a confirmation hands a short-lived private copy to an app the user picks.
 */
@Composable
private fun MediaChip(part: app.privateinbox.data.db.MmsPartEntity) {
    val context = LocalContext.current
    val container = LocalContainer.current
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val kind = when {
        part.mimeType.startsWith("video/") -> "Video"
        part.mimeType.startsWith("audio/") -> "Audio"
        part.mimeType.contains("vcard") -> "Contact card"
        else -> "File"
    }
    val size = if (part.sizeBytes >= 1_048_576) "%.1f MB".format(part.sizeBytes / 1_048_576.0) else "${(part.sizeBytes / 1024).coerceAtLeast(1)} KB"
    AssistChip(onClick = { confirm = true }, label = { Text("$kind · $size · Open…") })
    if (error) Text("No app on this phone can open it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Open $kind with another app?") },
            text = { Text("The app you choose will be able to read this ${kind.lowercase()}. PrivateInbox never opens attachments automatically.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = false
                    error = !app.privateinbox.mms.MediaOpener.open(context, container.mmsStore, part.fileName)
                }) { Text("Choose app") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
