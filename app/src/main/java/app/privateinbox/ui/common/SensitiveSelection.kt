package app.privateinbox.ui.common

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import app.privateinbox.security.SensitiveClipboard

/**
 * A [SelectionContainer] whose "Copy" goes through [SensitiveClipboard] (F-07): the clip is flagged
 * sensitive (hidden from the clipboard preview on Android 13+) and cleared again after
 * [SensitiveClipboard.MESSAGE_CLEAR_MS], like the "Copy text" menu item.
 */
@Composable
fun SensitiveSelectionContainer(label: String = "Message", content: @Composable () -> Unit) {
    val context = LocalContext.current
    val system = LocalClipboardManager.current
    val sensitive = remember(system, label) {
        object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) {
                SensitiveClipboard.copy(context, label, annotatedString.text)
            }

            override fun getText(): AnnotatedString? = system.getText()
        }
    }
    CompositionLocalProvider(LocalClipboardManager provides sensitive) {
        SelectionContainer { content() }
    }
}
