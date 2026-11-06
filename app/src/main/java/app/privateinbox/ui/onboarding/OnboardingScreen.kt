package app.privateinbox.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.privateinbox.sms.DefaultSmsApp
import app.privateinbox.work.Workers

private val points = listOf(
    "No internet permission: Android itself stops this app from going online.",
    "Messages are sorted on this phone with rules you can read and change.",
    "Your local copy is encrypted. No cloud backup, no trackers, no ads.",
    "Nothing is ever sent unless you tap Send.",
)

/**
 * First-run flow: explain privacy, ask to become the default SMS app (needed to receive and
 * send), then request contacts/notifications and start the local import.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Workers.importNow(context)
        onDone()
    }
    val extraPerms = buildList {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        perms.launch(extraPerms)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Shield, null, Modifier.size(64.dp).padding(top = 24.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Welcome to PrivateInbox", style = MaterialTheme.typography.headlineMedium)
        Text("A smart SMS inbox that never leaves your phone.", style = MaterialTheme.typography.bodyLarge)
        points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
        Text(
            "To receive and send messages, Android requires PrivateInbox to be your default SMS app. " +
                "You can switch back any time in system settings.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                val intent = DefaultSmsApp.requestRoleIntent(context)
                if (intent != null) role.launch(intent) else perms.launch(extraPerms)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Set as default SMS app") }
        OutlinedButton(onClick = { perms.launch(extraPerms) }, modifier = Modifier.fillMaxWidth()) {
            Text("Not now: read-only mode")
        }
    }
}
