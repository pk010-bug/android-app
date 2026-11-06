package app.privateinbox.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.privateinbox.security.SafeLog

/** Non-exported; only reachable through our own immutable, explicit PendingIntent. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        SafeLog.i(TAG, "send status result=$resultCode")
        // TODO(sms): update message status in Room.
    }

    companion object {
        const val ACTION_SENT = "app.privateinbox.action.SMS_SENT"
        private const val TAG = "SmsSentReceiver"
    }
}
