package app.privateinbox.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import app.privateinbox.data.repo.MessageRepository
import app.privateinbox.security.PendingIntents
import app.privateinbox.security.SafeLog

/**
 * The single place in the app that may send an SMS.
 *
 * Requires a fresh [UserSendConfirmation] (created by the composer's Send / Confirm button).
 * External intents can never reach this: [app.privateinbox.compose.IntentTriage] only produces
 * drafts. MMS sending is not implemented yet.
 */
class SmsSender(
    private val context: Context,
    private val repository: MessageRepository,
) {

    sealed interface Result {
        data class Queued(val messageId: Long, val conversationId: Long) : Result
        data class Refused(val reason: String) : Result
    }

    suspend fun send(
        confirmation: UserSendConfirmation,
        recipient: String,
        body: String,
        subscriptionId: Int? = null,
    ): Result {
        if (!confirmation.consume()) return Result.Refused("send must be confirmed by a tap")
        if (body.isBlank()) return Result.Refused("empty message")
        if (body.length > MAX_BODY) return Result.Refused("message too long")
        if (!RECIPIENT.matches(recipient.trim())) return Result.Refused("invalid recipient")
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.Refused("SEND_SMS not granted")
        }
        val manager = smsManager(subscriptionId) ?: return Result.Refused("no SmsManager")

        val (messageId, conversationId) = repository.recordOutgoing(recipient.trim(), body, subscriptionId)
        val parts = manager.divideMessage(body)
        val sent = ArrayList(
            parts.indices.map { i ->
                PendingIntents.broadcast(
                    context,
                    requestCode(messageId, i),
                    Intent(context, SmsSentReceiver::class.java)
                        .setAction(SmsSentReceiver.ACTION_SENT)
                        .putExtra(SmsSentReceiver.EXTRA_MESSAGE_ID, messageId)
                        .putExtra(SmsSentReceiver.EXTRA_PART, i)
                        .putExtra(SmsSentReceiver.EXTRA_PART_COUNT, parts.size),
                )
            },
        )
        return try {
            manager.sendMultipartTextMessage(recipient.trim(), null, parts, sent, null)
            SafeLog.i(TAG, "queued sms with ${parts.size} part(s)")
            Result.Queued(messageId, conversationId)
        } catch (e: Exception) {
            repository.updateSendStatus(messageId, success = false)
            SafeLog.e(TAG, "send failed", e)
            Result.Refused("the phone rejected the message")
        }
    }

    private fun smsManager(subscriptionId: Int?): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != null) base?.createForSubscriptionId(subscriptionId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
        }

    private companion object {
        const val TAG = "SmsSender"
        const val MAX_BODY = 5_000
        val RECIPIENT = Regex("^\\+?[0-9A-Za-z .()\\-]{2,32}$")
        fun requestCode(messageId: Long, part: Int) = ((messageId * 16 + part) and 0x7FFFFFFF).toInt()
    }
}
