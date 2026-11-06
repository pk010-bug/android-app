package app.privateinbox.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.privateinbox.PrivateInboxApp
import app.privateinbox.mms.MmsPdu
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WAP_PUSH_DELIVER (MMS notification) when we are the default SMS app. Exported only for the
 * system; protected by BROADCAST_WAP_PUSH. The push payload is untrusted: it is parsed with the
 * bounds-checked [MmsPdu] and we only act on a well-formed M-Notification.ind.
 *
 * F-04: the MMS is NOT downloaded here. We record a placeholder + pending row; the content is
 * fetched (by the system MMS service) only when the user taps Download, never for blocked senders,
 * and only after a warning for unknown senders. See [app.privateinbox.mms.MmsDownloadPolicy].
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        // Untrusted, remotely-triggered input: nothing may crash the process.
        try {
            handle(context, intent)
        } catch (t: Throwable) {
            SafeLog.w(TAG, "wap push dropped: ${t.javaClass.simpleName}")
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val data = intent.getByteArrayExtra("data") ?: return
        if (data.size > 4096) return
        val pdu = try { MmsPdu.parse(data) } catch (e: MmsPdu.MalformedPdu) {
            SafeLog.w(TAG, "ignored malformed wap push")
            return
        }
        if (pdu.messageType != MmsPdu.TYPE_NOTIFICATION_IND) return
        if (pdu.contentLocation == null || pdu.transactionId == null) return
        val subId = intent.extras?.let { e ->
            listOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX")
                .firstNotNullOfOrNull { k -> e.getInt(k, -1).takeIf { it >= 0 } }
        }
        val app = context.applicationContext as PrivateInboxApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.receiveMmsNotification(pdu, subId)
                SafeLog.i(TAG, "mms notification stored; waiting for user to download")
            } catch (t: Throwable) {
                SafeLog.w(TAG, "mms notification dropped: ${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MmsReceiver"
    }
}
