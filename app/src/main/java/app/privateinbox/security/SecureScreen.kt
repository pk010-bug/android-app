package app.privateinbox.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Applies FLAG_SECURE (blocks screenshots, screen recording, and the Recents thumbnail) while
 * this composable is on screen and [enabled] is true. Used on OTP / finance / conversation screens
 * when the user turned on app-lock or "hide OTPs".
 */
@Composable
fun SecureScreen(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled, activity) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (enabled) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
