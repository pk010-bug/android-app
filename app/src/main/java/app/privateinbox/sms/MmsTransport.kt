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
 * mandatory carrier acknowledgements (M-NotifyResp.ind "retrieved" / "deferred",
 * M-Acknowledge.ind after a deferred retrieval). All carrier traffic is done by the system MMS service
 * (it owns the mobile-data connection); PrivateInbox itself holds no INTERNET permission.
 *
 * SendPathTest pins this file: the only PDUs it may hand to sendMultimediaMessage are the
 * acknowledgements built here by [MmsPdu.encodeNotifyResp] / [MmsPdu.encodeAcknowledgeInd].
 */
class MmsTransport(private val context: Context, private val store: MmsStore = MmsStore(context)) {

    /** Only via AppContainer.startMmsDownload, after the policy check (F-04). [attempt] tags the callback. */
    fun download(contentLocation: String, transactionId: String, subscriptionId: Int?, placeholderMessageId: Long, attempt: Int): Boolean {
        if (!app.privateinbox.mms.MmsDownloadPolicy.isFetchableLocation(contentLocation)) return false
        val manager = manager(subscriptionId) ?: return false
        val file = store.newPduFile("dl")
        val uri = FileProvider.getUriForFile(context, authority(), file)
        val done = PendingIntents.broadcast(
            context,
            PendingIntents.Kind.MMS_DOWNLOADED,
            file.name.hashCode().toLong() and 0xFFFFFFFFL, // one per attempt (unique scratch file)
            Intent(context, MmsDownloadedReceiver::class.java)
                .setAction(MmsDownloadedReceiver.ACTION)
                .putExtra(MmsDownloadedReceiver.EXTRA_FILE, file.name)
                .putExtra(MmsDownloadedReceiver.EXTRA_TID, transactionId.take(100))
                .putExtra(MmsDownloadedReceiver.EXTRA_SUB, subscriptionId ?: -1)
                .putExtra(MmsDownloadedReceiver.EXTRA_PLACEHOLDER, placeholderMessageId)
                .putExtra(MmsDownloadedReceiver.EXTRA_ATTEMPT, attempt),
        )
        return try {
            manager.downloadMultimediaMessage(context, contentLocation.trim(), uri, null, done)
            true
        } catch (e: Exception) {
            file.delete()
            SafeLog.e(TAG, "mms download request failed", e)
            false
        }
    }

    /**
     * Tells the MMSC we retrieved the message (and that read/delivery reports are NOT allowed):
     * M-NotifyResp.ind "retrieved" after an immediate download, M-Acknowledge.ind if the carrier was
     * told "deferred" first.
     */
    fun acknowledge(transactionId: String, subscriptionId: Int?, afterDeferred: Boolean = false) =
        sendControl(
            if (afterDeferred) MmsPdu.encodeAcknowledgeInd(transactionId) else MmsPdu.encodeNotifyResp(transactionId, MmsPdu.STATUS_RETRIEVED),
            subscriptionId,
        )

    /** M-NotifyResp.ind "deferred": the message stays on the MMSC until the user downloads it. */
    fun notifyDeferred(transactionId: String, subscriptionId: Int?) =
        sendControl(MmsPdu.encodeNotifyResp(transactionId, MmsPdu.STATUS_DEFERRED), subscriptionId)

    private fun sendControl(pdu: ByteArray, subscriptionId: Int?) {
        val manager = manager(subscriptionId) ?: return
        val file = store.newPduFile("ack")
        file.writeBytes(pdu)
        val uri = FileProvider.getUriForFile(context, authority(), file)
        val done = PendingIntents.broadcast(
            context,
            PendingIntents.Kind.MMS_ACK,
            file.name.hashCode().toLong() and 0xFFFFFFFFL,
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
