package app.privateinbox.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.privateinbox.PrivateInboxApp
import app.privateinbox.mms.MmsPdu
import app.privateinbox.mms.MmsStore
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Non-exported; reached only via our immutable explicit PendingIntent from [MmsTransport.download]. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val store = MmsStore(context.applicationContext)
        val file = store.pduFile(intent.getStringExtra(EXTRA_FILE)) ?: return
        val tid = intent.getStringExtra(EXTRA_TID)
        val subId = intent.getIntExtra(EXTRA_SUB, -1).takeIf { it >= 0 }
        val ok = resultCode == Activity.RESULT_OK
        val pending = goAsync()
        val app = context.applicationContext as PrivateInboxApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!ok || !file.isFile || file.length() !in 1..MmsStore.MAX_PDU_BYTES.toLong()) {
                    SafeLog.w(TAG, "mms download failed ok=$ok")
                    return@launch
                }
                val pdu = MmsPdu.parse(file.readBytes())
                if (pdu.messageType != MmsPdu.TYPE_RETRIEVE_CONF) return@launch
                app.container.receiveMms(pdu, subId)
                tid?.let { MmsTransport(context.applicationContext, store).acknowledge(it, subId) }
            } catch (e: MmsPdu.MalformedPdu) {
                SafeLog.w(TAG, "downloaded mms was malformed")
            } catch (t: Throwable) {
                // Includes OutOfMemoryError from hostile content: never crash on a received MMS.
                SafeLog.w(TAG, "failed to process mms: ${t.javaClass.simpleName}")
            } finally {
                runCatching { file.delete() }
                runCatching { store.sweepPdus() }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "app.privateinbox.action.MMS_DOWNLOADED"
        const val EXTRA_FILE = "file"
        const val EXTRA_TID = "tid"
        const val EXTRA_SUB = "sub"
        private const val TAG = "MmsDownloaded"
    }
}
