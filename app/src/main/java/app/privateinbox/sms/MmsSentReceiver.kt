package app.privateinbox.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.privateinbox.PrivateInboxApp
import app.privateinbox.mms.MmsStore
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Non-exported. Result of a user MMS send or an M-NotifyResp ack; cleans up the scratch PDU. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        MmsStore(context.applicationContext).pduFile(intent.getStringExtra(EXTRA_FILE))?.delete()
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        val ok = resultCode == Activity.RESULT_OK
        SafeLog.i(TAG, "mms send result ok=$ok")
        if (messageId <= 0) return
        val pending = goAsync()
        val app = context.applicationContext as PrivateInboxApp
        CoroutineScope(Dispatchers.IO).launch {
            try { app.container.messages.updateSendStatus(messageId, ok) } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION = "app.privateinbox.action.MMS_SENT"
        const val EXTRA_FILE = "file"
        const val EXTRA_MESSAGE_ID = "message_id"
        private const val TAG = "MmsSentReceiver"
    }
}
