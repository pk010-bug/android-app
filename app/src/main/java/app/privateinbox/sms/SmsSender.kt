package app.privateinbox.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import app.privateinbox.security.PendingIntents
import app.privateinbox.security.SafeLog

/**
 * The single place in the app that may send an SMS.
 *
 * Requires a fresh [UserSendConfirmation] (created by the composer's Send button). External
 * intents can never reach this: [app.privateinbox.compose.IntentTriage] only produces drafts.
 * MMS sending is not implemented in the MVP.
 */
class SmsSender(private val context: Context) {

    sealed interface Result {
        data object Queued : Result
        data class Refused(val reason: String) : Result
    }

    fun send(confirmation: UserSendConfirmation, recipient: String, body: String): Result {
        if (!confirmation.consume()) return Result.Refused("send must be confirmed by a tap")
        if (body.isBlank()) return Result.Refused("empty message")
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.Refused("SEND_SMS not granted")
        }
        val manager: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return Result.Refused("no SmsManager")

        val parts = manager.divideMessage(body)
        val sent = ArrayList(
            parts.indices.map { i ->
                PendingIntents.broadcast(
                    context,
                    REQUEST_SENT + i,
                    Intent(context, SmsSentReceiver::class.java).setAction(SmsSentReceiver.ACTION_SENT),
                )
            },
        )
        manager.sendMultipartTextMessage(recipient, null, parts, sent, null)
        SafeLog.i(TAG, "queued sms with ${parts.size} part(s)")
        // TODO(sms): write to Telephony provider "sent" box + Room once persistence lands.
        return Result.Queued
    }

    private companion object {
        const val TAG = "SmsSender"
        const val REQUEST_SENT = 1_000
    }
}
