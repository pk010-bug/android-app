package app.privateinbox.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.privateinbox.data.repo.MessageRepository
import app.privateinbox.ui.EmptyState
import app.privateinbox.ui.common.Format
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.SubScreen
import kotlinx.coroutines.flow.flowOf

/** Full-text search over the encrypted local store. Queries never leave the device. */
@Composable
fun SearchScreen(onOpenConversation: (Long) -> Unit, onBack: () -> Unit) {
    val dao = LocalContainer.current.database.messageDao()
    val hideOtpPreviews by LocalContainer.current.settings.hideOtpInNotifications.collectAsState(initial = true)
    var q by rememberSaveable { mutableStateOf("") }
    val trimmed = q.trim()
    val results by remember(trimmed) {
        if (trimmed.length < 2) flowOf(emptyList()) else dao.search(MessageRepository.escapeLike(trimmed))
    }.collectAsState(initial = emptyList())

    SubScreen("Search", onBack) {
        OutlinedTextField(
            q,
            { q = it.take(100) },
            placeholder = { Text("Search messages and senders") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (trimmed.length >= 2 && results.isEmpty()) EmptyState("No matches.")
        LazyColumn {
            items(results, key = { it.id }) { m ->
                ListItem(
                    headlineContent = { Text(m.address) },
                    supportingContent = { Text(app.privateinbox.security.OtpMask.maybeMask(m.body, hideOtpPreviews), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text(Format.shortTime(m.timestamp)) },
                    modifier = Modifier.clickable { onOpenConversation(m.conversationId) },
                )
                HorizontalDivider()
            }
        }
    }
}
