package app.privateinbox.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.db.ScheduledMessageEntity
import app.privateinbox.data.db.ScheduledStatus
import app.privateinbox.data.repo.MessageRepository
import app.privateinbox.security.PendingIntents
import app.privateinbox.security.SafeLog

/**
 * The single place in the app that may send an SMS.
 *
 * - Immediate sends require a fresh [UserSendConfirmation] (composer Send / Confirm button).
 * - Scheduled sends require one at *scheduling* time; the row is then sealed with a Keystore
 *   HMAC ([ScheduledSendAuthorizer]) that the worker verifies before sending.
 * - Groups are sent as individual SMS to each member (no group MMS).
 * External intents can never reach this: [app.privateinbox.compose.IntentTriage] only produces drafts.
 */
class SmsSender(
    private val context: Context,
    private val repository: MessageRepository,
    private val db: PrivateInboxDatabase,
    private val authorizer: ScheduledSendAuthorizer = ScheduledSendAuthorizer(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Result {
        data class Queued(val messageId: Long, val conversationId: Long) : Result
        data class Scheduled(val scheduledId: Long, val conversationId: Long) : Result
        data class Refused(val reason: String) : Result
    }

    enum class ScheduledOutcome { SENT, FAILED, REJECTED, ALREADY_HANDLED, TOO_EARLY, MISSED }

    suspend fun send(
        confirmation: UserSendConfirmation,
        recipients: List<String>,
        body: String,
        subscriptionId: Int? = null,
    ): Result {
        if (!confirmation.consume()) return Result.Refused("send must be confirmed by a tap")
        val clean = validate(recipients, body)?.let { return it } ?: cleanRecipients(recipients)
        return dispatch(clean, body, subscriptionId)
    }

    suspend fun schedule(
        confirmation: UserSendConfirmation,
        recipients: List<String>,
        body: String,
        sendAt: Long,
        subscriptionId: Int? = null,
    ): Result {
        if (!confirmation.consume()) return Result.Refused("scheduling must be confirmed by a tap")
        val clean = validate(recipients, body)?.let { return it } ?: cleanRecipients(recipients)
        val now = clock()
        if (sendAt < now + MIN_LEAD_MS) return Result.Refused("pick a time at least a minute from now")
        if (sendAt > now + MAX_LEAD_MS) return Result.Refused("pick a time within a year")
        val conv = repository.conversationFor(clean)
        val dao = db.scheduledDao()
        val draft = ScheduledMessageEntity(
            recipients = clean.joinToString("\n"), body = body, sendAt = sendAt,
            subscriptionId = subscriptionId, createdAt = now, status = ScheduledStatus.PENDING,
            conversationId = conv.id,
        )
        val id = dao.insert(draft)
        dao.setTag(id, authorizer.sign(draft.copy(id = id)))
        ScheduledSends.enqueue(context, id, sendAt, now)
        SafeLog.i(TAG, "scheduled message for ${clean.size} recipient(s)")
        return Result.Scheduled(id, conv.id)
    }

    /** Called only by [ScheduledSendWorker]. */
    internal suspend fun sendScheduled(id: Long): ScheduledOutcome {
        val dao = db.scheduledDao()
        val row = dao.getById(id) ?: return ScheduledOutcome.ALREADY_HANDLED
        if (row.status != ScheduledStatus.PENDING) return ScheduledOutcome.ALREADY_HANDLED
        if (!authorizer.verify(row)) {
            dao.transition(id, ScheduledStatus.PENDING, ScheduledStatus.FAILED)
            SafeLog.w(TAG, "scheduled row failed integrity check; not sending")
            return ScheduledOutcome.REJECTED
        }
        val now = clock()
        if (now < row.sendAt - EARLY_SLACK_MS) return ScheduledOutcome.TOO_EARLY
        if (now > row.sendAt + STALE_MS) {
            // Phone was off/asleep far past the chosen time: don't surprise anyone with a stale text.
            dao.transition(id, ScheduledStatus.PENDING, ScheduledStatus.FAILED)
            return ScheduledOutcome.MISSED
        }
        // Atomic claim so a duplicate worker run can never double-send.
        if (dao.transition(id, ScheduledStatus.PENDING, ScheduledStatus.SENT) != 1) return ScheduledOutcome.ALREADY_HANDLED
        val recipients = row.recipients.split('\n').filter { it.isNotBlank() }
        validate(recipients, row.body)?.let {
            dao.setStatus(id, ScheduledStatus.FAILED)
            return ScheduledOutcome.FAILED
        }
        return when (dispatch(recipients, row.body, row.subscriptionId)) {
            is Result.Queued -> ScheduledOutcome.SENT
            else -> { dao.setStatus(id, ScheduledStatus.FAILED); ScheduledOutcome.FAILED }
        }
    }

    /**
     * Sends a picture message. [image] must already be in our private attachments store (copied
     * by [app.privateinbox.attachments.AttachmentImporter], which enforced the policy). It is
     * re-encoded to a metadata-free JPEG sized for the carrier before it leaves the app.
     * Multiple recipients become one group MMS. Blocking work runs on the caller's dispatcher.
     */
    suspend fun sendMms(
        confirmation: UserSendConfirmation,
        recipients: List<String>,
        body: String,
        image: java.io.File,
        subscriptionId: Int? = null,
    ): Result {
        if (!confirmation.consume()) return Result.Refused("send must be confirmed by a tap")
        val clean = cleanRecipients(recipients)
        validate(clean, body.ifBlank { "-" })?.let { return it }
        if (clean.any { r -> r.count(Char::isDigit) < 3 || r.any { it.isLetter() } }) return Result.Refused("MMS needs phone numbers")
        val manager = smsManager(subscriptionId) ?: return Result.Refused("no SmsManager")
        val maxBytes = runCatching { manager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MMS_MAX) }
            .getOrDefault(DEFAULT_MMS_MAX).coerceIn(100_000, 1_000_000)
        val raw = runCatching { image.takeIf { it.length() in 1..app.privateinbox.attachments.AttachmentPolicy.MAX_BYTES }?.readBytes() }.getOrNull()
            ?: return Result.Refused("attachment unavailable")
        val jpeg = app.privateinbox.mms.MmsImages.compressForMms(raw, maxBytes - 8_000 - body.length * 3)
            ?: return Result.Refused("couldn't shrink the image enough for MMS")

        val store = app.privateinbox.mms.MmsStore(context)
        val record = repository.recordOutgoing(clean, body, subscriptionId, isMms = true)
        val stored = store.save(jpeg, "jpg")
        repository.addMmsPart(
            app.privateinbox.data.db.MmsPartEntity(messageId = record.messageId, mimeType = "image/jpeg", fileName = stored, sizeBytes = jpeg.size.toLong()),
        )
        val parts = buildList {
            if (body.isNotBlank()) add(app.privateinbox.mms.MmsPdu.OutPart("text/plain", "text_0.txt", body.toByteArray(Charsets.UTF_8)))
            add(app.privateinbox.mms.MmsPdu.OutPart("image/jpeg", "image_0.jpg", jpeg))
        }
        val pdu = app.privateinbox.mms.MmsPdu.encodeSendReq(
            "T" + java.lang.Long.toHexString(System.nanoTime()) + record.messageId, clean, null, parts,
        )
        val file = store.newPduFile("send").apply { writeBytes(pdu) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sent = PendingIntents.broadcast(
            context,
            file.name.hashCode(),
            Intent(context, MmsSentReceiver::class.java)
                .setAction(MmsSentReceiver.ACTION)
                .putExtra(MmsSentReceiver.EXTRA_FILE, file.name)
                .putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, record.messageId),
        )
        return try {
            manager.sendMultimediaMessage(context, uri, null, null, sent)
            SafeLog.i(TAG, "queued mms (${jpeg.size / 1024} KiB) to ${clean.size} recipient(s)")
            Result.Queued(record.messageId, record.conversationId)
        } catch (e: Exception) {
            file.delete()
            repository.updateSendStatus(record.messageId, success = false)
            SafeLog.e(TAG, "mms send failed", e)
            Result.Refused("the phone rejected the MMS")
        }
    }

    private fun cleanRecipients(recipients: List<String>) =
        recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun validate(recipients: List<String>, body: String): Result.Refused? {
        val clean = cleanRecipients(recipients)
        if (clean.isEmpty()) return Result.Refused("no recipient")
        if (clean.size > MAX_RECIPIENTS) return Result.Refused("too many recipients (max $MAX_RECIPIENTS)")
        if (clean.any { !RECIPIENT.matches(it) }) return Result.Refused("invalid recipient")
        if (body.isBlank()) return Result.Refused("empty message")
        if (body.length > MAX_BODY) return Result.Refused("message too long")
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.Refused("SEND_SMS not granted")
        }
        return null
    }

    private suspend fun dispatch(recipients: List<String>, body: String, subscriptionId: Int?): Result {
        val manager = smsManager(subscriptionId) ?: return Result.Refused("no SmsManager")
        val record = repository.recordOutgoing(recipients, body, subscriptionId)
        val parts = manager.divideMessage(body)
        var anyQueued = false
        recipients.forEachIndexed { r, to ->
            val sent = ArrayList(
                parts.indices.map { i ->
                    PendingIntents.broadcast(
                        context,
                        requestCode(record.messageId, r, i),
                        Intent(context, SmsSentReceiver::class.java)
                            .setAction(SmsSentReceiver.ACTION_SENT)
                            .putExtra(SmsSentReceiver.EXTRA_MESSAGE_ID, record.messageId)
                            .putExtra(SmsSentReceiver.EXTRA_PROVIDER_ID, record.providerIds.getOrNull(r) ?: -1L)
                            .putExtra(SmsSentReceiver.EXTRA_PART, i)
                            .putExtra(SmsSentReceiver.EXTRA_PART_COUNT, parts.size),
                    )
                },
            )
            try {
                manager.sendMultipartTextMessage(to, null, parts, sent, null)
                anyQueued = true
            } catch (e: Exception) {
                repository.updateSendStatus(record.messageId, success = false, providerId = record.providerIds.getOrNull(r))
                SafeLog.e(TAG, "send failed", e)
            }
        }
        SafeLog.i(TAG, "queued sms: ${parts.size} part(s) x ${recipients.size} recipient(s)")
        return if (anyQueued) Result.Queued(record.messageId, record.conversationId)
        else Result.Refused("the phone rejected the message")
    }

    private fun smsManager(subscriptionId: Int?): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != null) base?.createForSubscriptionId(subscriptionId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionId) else SmsManager.getDefault()
        }

    companion object {
        private const val TAG = "SmsSender"
        const val MAX_BODY = 5_000
        const val MAX_RECIPIENTS = 20
        private const val DEFAULT_MMS_MAX = 300 * 1024
        const val MIN_LEAD_MS = 60_000L
        const val MAX_LEAD_MS = 366L * 24 * 3600 * 1000
        const val EARLY_SLACK_MS = 2 * 60_000L
        const val STALE_MS = 24L * 3600 * 1000
        private val RECIPIENT = Regex("^\\+?[0-9A-Za-z .()\\-]{2,32}$")
        private fun requestCode(messageId: Long, recipient: Int, part: Int) =
            (((messageId * 32 + recipient) * 64 + part) and 0x7FFFFFFF).toInt()
    }
}
