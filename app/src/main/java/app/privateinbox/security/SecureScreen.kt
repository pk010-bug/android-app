package app.privateinbox.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Window-level FLAG_SECURE (blocks screenshots, screen recording and the Recents thumbnail).
 *
 * Two independent sources can ask for it:
 *  - [setAppWide]: the whole activity, while app lock is on (MainActivity starts secure and only
 *    relaxes once settings say lock is off, so nothing leaks before DataStore loads);
 *  - [SecureScreen]: individual sensitive screens (OTP / finance / conversation) when
 *    "hide OTPs" is on.
 * The flag is cleared only when neither wants it, so leaving a secure screen never drops the
 * app-wide protection.
 */
object SecureWindow {
    private val appWide = java.util.WeakHashMap<Activity, Boolean>()
    private val screens = java.util.WeakHashMap<Activity, Int>()

    fun setAppWide(activity: Activity, on: Boolean) {
        appWide[activity] = on
        apply(activity)
    }

    internal fun push(activity: Activity) { screens[activity] = (screens[activity] ?: 0) + 1; apply(activity) }
    internal fun pop(activity: Activity) { screens[activity] = ((screens[activity] ?: 1) - 1).coerceAtLeast(0); apply(activity) }

    fun isSecure(activity: Activity): Boolean = appWide[activity] == true || (screens[activity] ?: 0) > 0

    private fun apply(activity: Activity) {
        val secure = isSecure(activity)
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Belt and braces on Android 13+: no app-switcher snapshot of our UI while lock is on.
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(appWide[activity] != true)
    }
}

/** Requests FLAG_SECURE while this composable is on screen and [enabled] is true. */
@Composable
fun SecureScreen(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(enabled, activity) {
        if (enabled) SecureWindow.push(activity)
        onDispose { if (enabled) SecureWindow.pop(activity) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
