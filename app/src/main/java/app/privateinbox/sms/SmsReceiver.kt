package app.privateinbox.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.privateinbox.security.SafeLog

/**
 * Receives SMS_DELIVER when PrivateInbox is the default SMS app.
 * Exported only because the system must reach it; protected by BROADCAST_SMS, which only the
 * telephony stack holds.
 *
 * TODO(sms):
 *  1. Write the message to the Telephony provider (the default app owns this duty).
 *  2. Classify with RuleEngine + OtpExtractor and store in Room.
 *  3. Post a notification through OtpNotifier (code hidden on lock screen).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Never log message content or sender, only counts.
        SafeLog.d(TAG, "sms delivered, parts=${parts.size}")
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}

/**
 * TODO(mms): MMS download/parsing is out of MVP scope. Stub keeps the SMS-role requirements met.
 * Protected by BROADCAST_WAP_PUSH.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        // Intentionally empty for MVP.
    }
}
