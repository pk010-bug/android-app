package app.privateinbox.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * F-13: shown while the encrypted database opens, and if the Android Keystore is temporarily
 * unavailable. Nothing is deleted in that case; "Try again" simply retries.
 */
@Composable
fun DatabaseStatusScreen(unavailable: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!unavailable) {
            CircularProgressIndicator()
            return@Column
        }
        Text("Can't open your messages right now", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            "Android's secure key storage didn't respond. Your messages are safe and nothing was deleted. " +
                "Wait a moment and try again; if it keeps happening, restart the phone.",
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text("Try again") }
    }
}
