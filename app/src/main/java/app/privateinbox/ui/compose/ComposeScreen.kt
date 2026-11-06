package app.privateinbox.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A draft handed to the composer. Attachments are already copied into private storage. */
data class Draft(
    val recipients: List<String> = emptyList(),
    val body: String = "",
    val attachmentNames: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
)

/**
 * New-message screen. External apps can prefill [draft]; sending only happens through
 * [MessageComposer]'s Send button (+ confirmation dialog).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(draft: Draft, onBack: () -> Unit, onSent: (Long) -> Unit) {
    var to by rememberSaveable(draft) { mutableStateOf(draft.recipients.joinToString(", ")) }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text("New message") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            draft.notices.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                to,
                { to = it },
                label = { Text("To (phone number)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.recipients.size > 1) {
                Text("Group sending isn't supported yet; the first recipient will be used.", style = MaterialTheme.typography.bodySmall)
            }
            if (draft.attachmentNames.isNotEmpty()) {
                Text(
                    "${draft.attachmentNames.size} image(s) attached (copied privately). MMS sending is not available yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        MessageComposer(
            recipient = { to.split(',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() } },
            initialText = draft.body,
            onSent = onSent,
        )
    }
}
