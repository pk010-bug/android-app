package app.privateinbox.ui.compose

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.privateinbox.sms.SimManager
import app.privateinbox.sms.SmsSender
import app.privateinbox.sms.UserSendConfirmation
import app.privateinbox.ui.common.LocalContainer
import kotlinx.coroutines.launch

/**
 * Text box + Send button. This (with ComposeScreen) is the only UI allowed to send.
 * Flow: tap Send → optional confirmation dialog (default on) → the dialog's Send button mints a
 * single-use [UserSendConfirmation] → [SmsSender].
 */
@Composable
fun MessageComposer(
    recipient: () -> String?,
    initialText: String = "",
    onSent: (conversationId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val sender = remember { SmsSender(context.applicationContext, container.messages) }
    val confirmSetting by container.settings.confirmBeforeSend.collectAsState(initial = true)
    val savedSub by container.settings.defaultSubId.collectAsState(initial = null)

    var text by rememberSaveable { mutableStateOf(initialText) }
    LaunchedEffect(initialText) { if (initialText.isNotEmpty()) text = initialText }
    var status by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var sims by remember { mutableStateOf<List<SimManager.Sim>>(emptyList()) }
    var simMenu by remember { mutableStateOf(false) }
    var chosenSub by remember { mutableStateOf<Int?>(null) }
    val activeSub = chosenSub ?: savedSub
    LaunchedEffect(Unit) { sims = container.sims.activeSims() }

    val phonePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        sims = container.sims.activeSims()
        if (sims.size > 1) simMenu = true
    }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Permission granted. Tap Send again." else "SMS permission denied."
    }

    fun doSend(confirmation: UserSendConfirmation) {
        val to = recipient()?.trim().orEmpty()
        val body = text
        scope.launch {
            status = when (val r = sender.send(confirmation, to, body, activeSub)) {
                is SmsSender.Result.Queued -> {
                    text = ""
                    onSent(r.conversationId)
                    null
                }
                is SmsSender.Result.Refused -> "Not sent: ${r.reason}"
            }
        }
    }

    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sims.size > 1 || !container.sims.hasPermission()) {
                    TextButton(onClick = {
                        if (!container.sims.hasPermission()) phonePerm.launch(Manifest.permission.READ_PHONE_STATE) else simMenu = true
                    }) {
                        Icon(Icons.Outlined.SimCard, contentDescription = "Choose SIM")
                        Text(sims.firstOrNull { it.subscriptionId == activeSub }?.let { "${it.slotIndex + 1}" } ?: "")
                    }
                    DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
                        sims.forEach { sim ->
                            DropdownMenuItem(text = { Text(sim.label) }, onClick = {
                                chosenSub = sim.subscriptionId
                                simMenu = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 5_000) text = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                FilledIconButton(
                    enabled = text.isNotBlank() && !recipient().isNullOrBlank(),
                    onClick = {
                        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            smsPerm.launch(Manifest.permission.SEND_SMS)
                        } else if (confirmSetting) {
                            confirming = true
                        } else {
                            // A human tapped Send (confirmation dialog disabled by the user).
                            doSend(UserSendConfirmation.fromSendButton())
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }

    if (confirming) {
        val simLabel = sims.firstOrNull { it.subscriptionId == activeSub }?.label
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Send this message?") },
            text = {
                Text(
                    "To: ${recipient().orEmpty()}" + (simLabel?.let { "\nFrom: $it" } ?: "") +
                        "\n\n" + text.take(300) + if (text.length > 300) "…" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    doSend(UserSendConfirmation.fromSendButton())
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
