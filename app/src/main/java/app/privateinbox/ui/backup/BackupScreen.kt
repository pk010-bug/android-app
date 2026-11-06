package app.privateinbox.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.privateinbox.backup.BackupCrypto
import app.privateinbox.security.SafeLog
import app.privateinbox.ui.common.LocalContainer
import app.privateinbox.ui.common.SubScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val pwOk = password.length >= BackupCrypto.MIN_PASSWORD

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = try {
                val blob = c.backup.export(password.toCharArray())
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob) } }
                "Backup saved (${blob.size / 1024} KB). Keep the password safe: it cannot be recovered."
            } catch (e: Exception) {
                SafeLog.e("BackupScreen", "export failed", e)
                "Backup failed."
            }
            busy = false
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            status = try {
                val blob = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        val bytes = ins.readBytes()
                        require(bytes.size <= 512 * 1024 * 1024) { "file too large" }
                        bytes
                    }
                } ?: error("cannot read file")
                val r = c.backup.restore(blob, password.toCharArray())
                "Restored ${r.messages} new message(s) across ${r.conversations} conversation(s), ${r.rules} rule(s)."
            } catch (e: BackupCrypto.BadPasswordOrCorrupt) {
                "Wrong password or damaged file."
            } catch (e: Exception) {
                SafeLog.e("BackupScreen", "restore failed", e)
                "Restore failed."
            }
            busy = false
        }
    }

    SubScreen("Encrypted backup", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Backups are encrypted on this phone with your password (AES-256-GCM, PBKDF2 key) and saved only " +
                    "where you choose. PrivateInbox never uploads them. Choose a folder that isn't synced to the cloud " +
                    "if you want the file to stay offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Backup password (min ${BackupCrypto.MIN_PASSWORD})") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                confirm, { confirm = it }, label = { Text("Confirm password (for new backups)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && pwOk && password == confirm,
                onClick = { create.launch("privateinbox-${LocalDate.now()}.pibk") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create backup file") }
            OutlinedButton(
                enabled = !busy && pwOk,
                onClick = { open.launch(arrayOf("application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore from file") }
            if (busy) CircularProgressIndicator()
            status?.let { Text(it) }
        }
    }
}
