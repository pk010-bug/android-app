package app.privateinbox.ui.compose

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.privateinbox.sms.SmsSender
import app.privateinbox.sms.UserSendConfirmation

/** A draft handed to the composer. Attachments are already copied into private storage. */
data class Draft(
    val recipients: List<String> = emptyList(),
    val body: String = "",
    val attachmentNames: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
)

/**
 * The only screen that can send. External apps can prefill [draft]; sending happens only from
 * the Send button's onClick, which mints a single-use [UserSendConfirmation].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(draft: Draft, onBack: () -> Unit) {
    val context = LocalContext.current
    val sender = remember { SmsSender(context.applicationContext) }
    var to by rememberSaveable(draft) { mutableStateOf(draft.recipients.joinToString(", ")) }
    var body by rememberSaveable(draft) { mutableStateOf(draft.body) }
    var status by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Permission granted. Tap Send again." else "SMS permission denied."
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("New message") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            draft.notices.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (draft.attachmentNames.isNotEmpty()) {
                Text(
                    "${draft.attachmentNames.size} image(s) attached (copied privately). MMS sending is not available yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                enabled = to.isNotBlank() && body.isNotBlank(),
                onClick = {
                    if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.SEND_SMS)
                        return@Button
                    }
                    // A human tapped Send: this is the only place a confirmation is created.
                    val confirmation = UserSendConfirmation.fromSendButton()
                    val recipient = to.split(',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    status = when (val r = recipient?.let { sender.send(confirmation, it, body) }) {
                        SmsSender.Result.Queued -> "Sending…"
                        is SmsSender.Result.Refused -> "Not sent: ${r.reason}"
                        null -> "Add a recipient"
                    }
                },
            ) { Text("Send") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
