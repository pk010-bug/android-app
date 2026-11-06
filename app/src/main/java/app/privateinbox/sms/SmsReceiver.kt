package app.privateinbox.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.privateinbox.PrivateInboxApp
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives SMS_DELIVER when PrivateInbox is the default SMS app.
 * Exported only because the system must reach it; protected by BROADCAST_SMS, which only the
 * telephony stack holds.
 *
 * Pipeline: join parts → write to system provider → classify/OTP/scam/finance → encrypted Room
 * → notification (respecting block, mute, hide-OTP settings).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val address = parts.first().displayOriginatingAddress ?: parts.first().originatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
        val timestamp = System.currentTimeMillis()
        val subId = intent.extras?.let { e ->
            listOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX")
                .firstNotNullOfOrNull { k -> e.getInt(k, -1).takeIf { it >= 0 } }
        }

        val pending = goAsync()
        val app = context.applicationContext as PrivateInboxApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.receiveIncoming(address, body, timestamp, subId)
            } catch (e: Exception) {
                SafeLog.e(TAG, "failed to process incoming sms", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}

/**
 * TODO(mms): MMS download/parsing is not implemented. Stub keeps the SMS-role requirements met.
 * Protected by BROADCAST_WAP_PUSH.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        // Intentionally empty for now.
    }
}
