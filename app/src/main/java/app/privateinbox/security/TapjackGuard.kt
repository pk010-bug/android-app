package app.privateinbox.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Anti-tapjacking for send / confirm UI. While on screen, the hosting window (activity or dialog):
 *  - drops touches delivered through another app's overlay (filterTouchesWhenObscured; matters
 *    most on Android 8-11, where untrusted overlays can still pass touches through);
 *  - on Android 12+, hides other apps' non-system overlay windows (HIDE_OVERLAY_WINDOWS).
 */
@Composable
fun TapjackGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view.rootView
        val before = root.filterTouchesWhenObscured
        root.filterTouchesWhenObscured = true
        val window: Window? = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
        if (Build.VERSION.SDK_INT >= 31) runCatching { window?.setHideOverlayWindows(true) }
        onDispose {
            root.filterTouchesWhenObscured = before
            if (Build.VERSION.SDK_INT >= 31) runCatching { window?.setHideOverlayWindows(false) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
