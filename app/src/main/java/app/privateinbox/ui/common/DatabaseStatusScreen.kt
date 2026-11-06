package app.privateinbox.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** What [DatabaseStatusScreen] shows. */
enum class DatabaseProblem { NONE, TEMPORARY, KEY_LOST }

/**
 * F-13 / RB-2: shown while the encrypted database opens, and when it can't be opened. Nothing is
 * ever deleted automatically: "Try again" retries, and "Reset local data" deletes the encrypted
 * local cache only after this screen's explanation, an explicit confirmation, and (where the phone
 * has a screen lock) a fresh authentication handled by the caller.
 */
@Composable
fun DatabaseStatusScreen(problem: DatabaseProblem, onRetry: () -> Unit, onReset: () -> Unit, resetError: String? = null) {
    var confirm by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (problem == DatabaseProblem.NONE) {
            CircularProgressIndicator()
            return@Column
        }
        if (problem == DatabaseProblem.TEMPORARY) {
            Text("Can't open your messages right now", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "Android's secure key storage didn't respond. Nothing was deleted. Wait a moment and try again; " +
                    "if it keeps happening, restart the phone.",
                textAlign = TextAlign.Center,
            )
        } else {
            Text("PrivateInbox can't unlock its local data", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "The key that protects PrivateInbox's encrypted local data seems to be gone (this can happen after a " +
                    "screen-lock reset or a system update). Nothing has been deleted. Try again first; only reset if it " +
                    "keeps failing.",
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onRetry) { Text("Try again") }
        OutlinedButton(onClick = { confirm = true }) { Text("Reset local data…") }
        resetError?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset PrivateInbox's local data?") },
            text = {
                Text(
                    "This permanently deletes PrivateInbox's encrypted local data: rules, categories, blocked senders, " +
                        "the recycle bin, scheduled messages, finance entries and pending picture-message downloads. " +
                        "Messages in the phone's system message store are not deleted; text messages are imported " +
                        "into PrivateInbox again. This can't be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Delete and reset") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
