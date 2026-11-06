package app.privateinbox.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required by the default-SMS role for "respond via message" (quick reply typed by the user on
 * the incoming-call screen). Protected by SEND_RESPOND_VIA_MESSAGE, held only by the system phone
 * app.
 *
 * MVP: deliberately a no-op, so there is still no send path outside our own composer.
 * TODO(sms): if implemented later, it must only send text the user typed in the system
 *  quick-reply UI, and must go through the same audit as SmsSender.
 */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
