package app.privateinbox.sms

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import app.privateinbox.mms.MmsPdu
import app.privateinbox.mms.MmsStore
import app.privateinbox.security.PendingIntents
import app.privateinbox.security.SafeLog

/**
 * Protocol-level MMS plumbing that is NOT a user message: downloading an incoming MMS and the
 * mandatory M-NotifyResp acknowledgement. All carrier traffic is done by the system MMS service
 * (it owns the mobile-data connection); PrivateInbox itself holds no INTERNET permission.
 *
 * SendPathTest pins this file: the only PDU it may hand to sendMultimediaMessage is one built by
 * [MmsPdu.encodeNotifyResp] here.
 */
class MmsTransport(private val context: Context, private val store: MmsStore = MmsStore(context)) {

    fun download(contentLocation: String, transactionId: String, subscriptionId: Int?): Boolean {
        if (!contentLocation.startsWith("http://") && !contentLocation.startsWith("https://")) return false
        val manager = manager(subscriptionId) ?: return false
        val file = store.newPduFile("dl")
        val uri = FileProvider.getUriForFile(context, authority(), file)
        val done = PendingIntents.broadcast(
            context,
            file.name.hashCode(),
            Intent(context, MmsDownloadedReceiver::class.java)
                .setAction(MmsDownloadedReceiver.ACTION)
                .putExtra(MmsDownloadedReceiver.EXTRA_FILE, file.name)
                .putExtra(MmsDownloadedReceiver.EXTRA_TID, transactionId.take(100))
                .putExtra(MmsDownloadedReceiver.EXTRA_SUB, subscriptionId ?: -1),
        )
        return try {
            manager.downloadMultimediaMessage(context, contentLocation, uri, null, done)
            true
        } catch (e: Exception) {
            file.delete()
            SafeLog.e(TAG, "mms download request failed", e)
            false
        }
    }

    /** Tells the MMSC we retrieved the message (and that read/delivery reports are NOT allowed). */
    fun acknowledge(transactionId: String, subscriptionId: Int?) {
        val manager = manager(subscriptionId) ?: return
        val file = store.newPduFile("ack")
        file.writeBytes(MmsPdu.encodeNotifyResp(transactionId))
        val uri = FileProvider.getUriForFile(context, authority(), file)
        val done = PendingIntents.broadcast(
            context,
            file.name.hashCode(),
            Intent(context, MmsSentReceiver::class.java)
                .setAction(MmsSentReceiver.ACTION)
                .putExtra(MmsSentReceiver.EXTRA_FILE, file.name),
        )
        try {
            manager.sendMultimediaMessage(context, uri, null, null, done)
        } catch (e: Exception) {
            file.delete()
            SafeLog.e(TAG, "mms ack failed", e)
        }
    }

    private fun authority() = "${context.packageName}.fileprovider"

    private fun manager(subscriptionId: Int?): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != null) base?.createForSubscriptionId(subscriptionId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
        }

    private companion object { const val TAG = "MmsTransport" }
}
