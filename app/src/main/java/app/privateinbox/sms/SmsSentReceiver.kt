package app.privateinbox.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.privateinbox.PrivateInboxApp
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Non-exported; only reachable through our own immutable, explicit PendingIntent. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SENT) return
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        val providerId = intent.getLongExtra(EXTRA_PROVIDER_ID, -1).takeIf { it > 0 }
        val part = intent.getIntExtra(EXTRA_PART, 0)
        val count = intent.getIntExtra(EXTRA_PART_COUNT, 1)
        val ok = resultCode == Activity.RESULT_OK
        SafeLog.i(TAG, "send status ok=$ok part=$part/$count")
        if (messageId <= 0) return
        // Mark the message on failure of any part, or success once the last part reports.
        if (!ok || part == count - 1) {
            val pending = goAsync()
            val app = context.applicationContext as PrivateInboxApp
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.container.messages.updateSendStatus(messageId, ok, providerId)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_SENT = "app.privateinbox.action.SMS_SENT"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PROVIDER_ID = "provider_id"
        const val EXTRA_PART = "part"
        const val EXTRA_PART_COUNT = "part_count"
        private const val TAG = "SmsSentReceiver"
    }
}
