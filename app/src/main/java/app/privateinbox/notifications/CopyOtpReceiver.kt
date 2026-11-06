package app.privateinbox.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.privateinbox.security.SensitiveClipboard

/** Non-exported. Reached only via our immutable explicit PendingIntent from OtpNotifier. */
class CopyOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { it.length in 4..8 && it.all(Char::isDigit) } ?: return
        SensitiveClipboard.copy(context, "OTP", code)
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (id != -1) NotificationManagerCompat.from(context).cancel(id)
    }

    companion object {
        const val ACTION_COPY = "app.privateinbox.action.COPY_OTP"
        const val EXTRA_CODE = "code"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
