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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Text box + Send / Schedule buttons. This (with ComposeScreen) is the only UI allowed to send.
 * Flow: tap Send → confirmation dialog (default on) → the dialog's button mints a single-use
 * [UserSendConfirmation] → [SmsSender]. Scheduling ALWAYS shows a confirmation dialog, and the
 * tap there is what authorises the later background send.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageComposer(
    recipients: () -> List<String>,
    initialText: String = "",
    onSent: (conversationId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val sender = remember { SmsSender(context.applicationContext, container.messages, container.database) }
    val confirmSetting by container.settings.confirmBeforeSend.collectAsState(initial = true)
    val savedSub by container.settings.defaultSubId.collectAsState(initial = null)

    var text by rememberSaveable { mutableStateOf(initialText) }
    LaunchedEffect(initialText) { if (initialText.isNotEmpty()) text = initialText }
    var status by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var sims by remember { mutableStateOf<List<SimManager.Sim>>(emptyList()) }
    var simMenu by remember { mutableStateOf(false) }
    var chosenSub by remember { mutableStateOf<Int?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var scheduleAt by remember { mutableStateOf<Long?>(null) }
    val activeSub = chosenSub ?: savedSub
    LaunchedEffect(Unit) { sims = container.sims.activeSims() }

    val phonePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        sims = container.sims.activeSims()
        if (sims.size > 1) simMenu = true
    }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Permission granted. Tap again." else "SMS permission denied."
    }

    fun hasSmsPerm() = context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    val ready = text.isNotBlank() && recipients().isNotEmpty()

    fun doSend(confirmation: UserSendConfirmation) {
        val to = recipients()
        val body = text
        scope.launch {
            status = when (val r = sender.send(confirmation, to, body, activeSub)) {
                is SmsSender.Result.Queued -> { text = ""; onSent(r.conversationId); null }
                is SmsSender.Result.Scheduled -> null
                is SmsSender.Result.Refused -> "Not sent: ${r.reason}"
            }
        }
    }

    fun doSchedule(confirmation: UserSendConfirmation, at: Long) {
        val to = recipients()
        val body = text
        scope.launch {
            status = when (val r = sender.schedule(confirmation, to, body, at, activeSub)) {
                is SmsSender.Result.Scheduled -> { text = ""; onSent(r.conversationId); null }
                is SmsSender.Result.Queued -> null
                is SmsSender.Result.Refused -> "Not scheduled: ${r.reason}"
            }
        }
    }

    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    onValueChange = { if (it.length <= SmsSender.MAX_BODY) text = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
                )
                IconButton(enabled = ready, onClick = {
                    if (!hasSmsPerm()) smsPerm.launch(Manifest.permission.SEND_SMS) else pickDate = true
                }) { Icon(Icons.Outlined.Schedule, contentDescription = "Schedule") }
                FilledIconButton(
                    enabled = ready,
                    onClick = {
                        if (!hasSmsPerm()) {
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

    val simLabel = sims.firstOrNull { it.subscriptionId == activeSub }?.label
    fun summary() = "To: ${recipients().joinToString(", ")}" + (simLabel?.let { "\nFrom: $it" } ?: "") +
        "\n\n" + text.take(300) + if (text.length > 300) "…" else ""

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (recipients().size > 1) "Send to ${recipients().size} people?" else "Send this message?") },
            text = {
                Text(summary() + if (recipients().size > 1) "\n\nEach person gets a separate SMS; they won't see each other." else "")
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

    if (pickDate) {
        val today = LocalDate.now()
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isBefore(today) && !d.isAfter(today.plusDays(365))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDate = false
                    pickedDate = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(dateState) }
    }

    pickedDate?.let { date ->
        val soon = LocalTime.now().plusMinutes(10)
        val timeState = rememberTimePickerState(initialHour = soon.hour, initialMinute = soon.minute)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            title = { Text("Send at") },
            text = { TimePicker(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = null
                    scheduleAt = LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text("Cancel") } },
        )
    }

    scheduleAt?.let { at ->
        val whenText = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
        AlertDialog(
            onDismissRequest = { scheduleAt = null },
            title = { Text("Schedule for $whenText?") },
            text = {
                Text(
                    summary() + "\n\nThis tap authorises PrivateInbox to send exactly this text at that time. " +
                        "Delivery may be a few minutes late if the phone is asleep; if it's more than a day late it won't be sent.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scheduleAt = null
                    doSchedule(UserSendConfirmation.fromSendButton(), at)
                }) { Text("Schedule") }
            },
            dismissButton = { TextButton(onClick = { scheduleAt = null }) { Text("Cancel") } },
        )
    }
}
