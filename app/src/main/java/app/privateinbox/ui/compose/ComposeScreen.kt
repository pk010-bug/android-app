package app.privateinbox.ui.compose

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.privateinbox.contacts.ContactLookup
import app.privateinbox.sms.SmsSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A draft handed to the composer. [attachmentNames] are copies already in private storage;
 * [sharedUris] are URIs another app shared that passed the source check but have NOT been
 * read yet: they are only copied when the user taps "Attach".
 */
data class Draft(
    val recipients: List<String> = emptyList(),
    val body: String = "",
    val attachmentNames: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
    val sharedUris: List<String> = emptyList(),
    /** Subset of [sharedUris] the sending app actually granted us (FLAG_GRANT_READ_URI_PERMISSION). */
    val grantedSharedUris: Set<String> = emptySet(),
)

/** A recipient chip: [number] is what we send to; [name] is display only. */
data class Recipient(val number: String, val name: String? = null)

private val TYPED_NUMBER = Regex("^\\+?[0-9 ().\\-]{3,32}$")

/**
 * New-message screen. External apps can prefill [draft]; sending only happens through
 * [MessageComposer]'s Send button (+ confirmation dialog). More than one recipient makes a
 * user group (individual SMS to each).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(draft: Draft, onBack: () -> Unit, onSent: (Long) -> Unit) {
    val context = LocalContext.current
    val lookup = remember { ContactLookup(context.applicationContext) }
    val chips = remember(draft) { mutableStateListOf<Recipient>().apply { draft.recipients.forEach { add(Recipient(it)) } } }
    var typed by rememberSaveable(draft) { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<ContactLookup.Suggestion>>(emptyList()) }
    var canSuggest by remember { mutableStateOf(lookup.canSuggest()) }
    val draftImage = remember(draft) {
        draft.attachmentNames.firstOrNull()?.let { app.privateinbox.attachments.AttachmentImporter(context.applicationContext).file(it) }
    }
    var sharedImage by remember(draft) { mutableStateOf<java.io.File?>(null) }
    var sharedState by remember(draft) { mutableStateOf(if (draft.sharedUris.isEmpty()) "" else "pending") }
    val composeScope = androidx.compose.runtime.rememberCoroutineScope()

    fun add(r: Recipient) {
        if (chips.size >= SmsSender.MAX_RECIPIENTS) return
        val digits = r.number.filter(Char::isDigit)
        if (chips.none { it.number.filter(Char::isDigit) == digits }) chips += r
        typed = ""
    }
    fun commitTyped() { typed.trim().takeIf { TYPED_NUMBER.matches(it) }?.let { add(Recipient(it)) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            lookup.resolvePicked(uri)?.let { add(Recipient(it.number, it.name)) }
        }
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canSuggest = it }

    LaunchedEffect(typed, canSuggest) {
        suggestions = if (canSuggest && typed.length >= 2) withContext(Dispatchers.IO) { lookup.suggest(typed) } else emptyList()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(if (chips.size > 1) "New group message" else "New message") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
        )
        Column(Modifier.padding(horizontal = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            draft.notices.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { r ->
                    InputChip(
                        selected = false,
                        onClick = { chips.remove(r) },
                        label = { Text(r.name?.let { "$it · ${r.number}" } ?: r.number) },
                        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove") },
                    )
                }
            }
            OutlinedTextField(
                typed,
                { v ->
                    if (v.endsWith(",") || v.endsWith(";")) { typed = v.dropLast(1); commitTyped() } else typed = v.take(40)
                },
                label = { Text(if (chips.isEmpty()) "To (name or number)" else "Add another") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitTyped() }),
                trailingIcon = {
                    IconButton(onClick = {
                        runCatching { picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                    }) { Icon(Icons.Outlined.Contacts, contentDescription = "Pick contact") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            suggestions.forEach { s ->
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(listOfNotNull(s.label, s.number).joinToString(" · ")) },
                    modifier = Modifier.clickable { add(Recipient(s.number, s.name)) },
                )
            }
            if (!canSuggest && typed.isNotEmpty() && !TYPED_NUMBER.matches(typed)) {
                TextButton(onClick = { contactsPerm.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text("Allow contact suggestions (stays on this phone)")
                }
            }
            if (chips.size > 1) {
                Text(
                    "Group: each person gets their own SMS. Replies arrive in your 1:1 threads with them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (draft.attachmentNames.size > 1 || draft.sharedUris.size > 1) {
                Text(
                    "${maxOf(draft.attachmentNames.size, draft.sharedUris.size)} images were shared; only the first can be attached (one photo per MMS).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sharedState == "pending" || sharedState == "failed") {
                Text(
                    if (sharedState == "failed") "The shared photo couldn't be attached (not a supported image, over 5 MB, or access expired)."
                    else "Another app shared a photo with this draft. It hasn't been opened yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (sharedState == "pending") {
                    TextButton(onClick = {
                        sharedState = "loading"
                        composeScope.launch {
                            val imported = withContext(Dispatchers.IO) {
                                app.privateinbox.attachments.AttachmentImporter(context.applicationContext)
                                    .import(draft.sharedUris.first(), grantedByCaller = draft.sharedUris.first() in draft.grantedSharedUris)
                            }
                            sharedImage = imported?.file
                            sharedState = if (imported == null) "failed" else "done"
                        }
                    }) { Text("Attach shared photo") }
                }
            }
        }
        MessageComposer(
            recipients = {
                val pending = typed.trim().takeIf { TYPED_NUMBER.matches(it) }
                (chips.map { it.number } + listOfNotNull(pending)).distinct()
            },
            initialText = draft.body,
            initialAttachment = sharedImage ?: draftImage,
            onSent = onSent,
        )
    }
}
