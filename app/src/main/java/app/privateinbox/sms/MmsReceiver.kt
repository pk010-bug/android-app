package app.privateinbox.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.privateinbox.mms.MmsPdu
import app.privateinbox.security.SafeLog

/**
 * WAP_PUSH_DELIVER (MMS notification) when we are the default SMS app. Exported only for the
 * system; protected by BROADCAST_WAP_PUSH. The push payload is untrusted: it is parsed with the
 * bounds-checked [MmsPdu] and we only act on a well-formed M-Notification.ind.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        if (data.size > 4096) return
        val pdu = try { MmsPdu.parse(data) } catch (e: MmsPdu.MalformedPdu) {
            SafeLog.w(TAG, "ignored malformed wap push")
            return
        }
        if (pdu.messageType != MmsPdu.TYPE_NOTIFICATION_IND) return
        val location = pdu.contentLocation ?: return
        val tid = pdu.transactionId ?: return
        if ((pdu.messageSize ?: 0) > MAX_MESSAGE_BYTES) {
            SafeLog.w(TAG, "mms too large; not downloading")
            return
        }
        val subId = intent.extras?.let { e ->
            listOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX")
                .firstNotNullOfOrNull { k -> e.getInt(k, -1).takeIf { it >= 0 } }
        }
        val ok = MmsTransport(context.applicationContext).download(location, tid, subId)
        SafeLog.i(TAG, "mms notification received; download requested=$ok")
    }

    private companion object {
        const val TAG = "MmsReceiver"
        const val MAX_MESSAGE_BYTES = 5L * 1024 * 1024
    }
}
