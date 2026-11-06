package app.privateinbox

import android.content.Context
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.prefs.SettingsStore
import app.privateinbox.data.repo.MessageRepository
import app.privateinbox.data.rules.AssetRuleLoader
import app.privateinbox.data.rules.RuleRepository
import app.privateinbox.data.rules.RuleSetParser
import app.privateinbox.data.telephony.TelephonyStore
import app.privateinbox.domain.Category
import app.privateinbox.notifications.MessageNotifier
import app.privateinbox.notifications.OtpNotifier
import app.privateinbox.sms.SimManager
import kotlinx.coroutines.flow.first

/** Manual DI. Everything is local; nothing here talks to a network. */
class AppContainer(private val context: Context) {
    val settings by lazy { SettingsStore(context) }
    val database by lazy { PrivateInboxDatabase.get(context) }
    val telephony by lazy { TelephonyStore(context) }
    val sims by lazy { SimManager(context) }
    val rules by lazy {
        val text = context.assets.open(AssetRuleLoader.DEFAULT_RULES_ASSET).bufferedReader().use { it.readText() }
        RuleRepository(RuleSetParser.parse(text), database.ruleDao())
    }
    val mmsStore by lazy { app.privateinbox.mms.MmsStore(context) }
    val messages by lazy {
        MessageRepository(database, rules, telephony).apply {
            deleteMediaFiles = { names -> names.forEach(mmsStore::delete) }
            mediaFile = mmsStore::file
            onUnreadChanged = { app.privateinbox.widget.InboxWidget.requestUpdate(context) }
        }
    }
    val backup by lazy {
        app.privateinbox.backup.BackupManager(
            database, rules, messages,
            object : app.privateinbox.backup.BackupManager.MediaIo {
                override fun read(name: String?) = mmsStore.file(name)?.readBytes()
                override fun save(bytes: ByteArray, extension: String) = mmsStore.save(bytes, extension)
                override fun delete(name: String) = mmsStore.delete(name)
            },
        )
    }
    val messageNotifier by lazy { MessageNotifier(context) }
    val otpNotifier by lazy { OtpNotifier(context) }

    /** Full receive pipeline, used by SmsReceiver. */
    suspend fun receiveIncoming(address: String, body: String, timestamp: Long, subscriptionId: Int?) {
        val providerId = telephony.insertIncoming(address, body, timestamp, subscriptionId)
        val r = messages.ingestIncoming(address, body, timestamp, subscriptionId, providerId) ?: return
        notifyIncoming(r, body)
    }

    /**
     * Incoming MMS (already downloaded by the system MMS service). Images are re-checked with
     * [app.privateinbox.attachments.AttachmentPolicy] (magic bytes + size) before touching disk;
     * anything else becomes a text placeholder. Group MMS is kept in the sender's 1:1 thread and
     * tagged, because without READ_PHONE_NUMBERS (deliberately not requested) we can't tell which
     * recipient is us.
     */
    suspend fun receiveMms(pdu: app.privateinbox.mms.MmsPdu.Pdu, subscriptionId: Int?) {
        val from = pdu.from?.takeIf { it.isNotBlank() } ?: "Unknown sender"
        val timestamp = pdu.dateSeconds?.let { it * 1000 }?.takeIf { it in 1..System.currentTimeMillis() + 86_400_000 }
            ?: System.currentTimeMillis()
        val texts = ArrayList<String>()
        val parts = ArrayList<app.privateinbox.data.db.MmsPartEntity>()
        pdu.parts.take(20).forEach { p ->
            when {
                p.isText -> p.text().take(5_000).takeIf { it.isNotBlank() }?.let { texts += it }
                p.contentType == "application/smil" -> Unit
                else -> when (val d = app.privateinbox.attachments.MediaPolicy.check(p.data)) {
                    is app.privateinbox.attachments.MediaPolicy.Decision.Accept -> {
                        val name = mmsStore.save(p.data, d.type.extension)
                        parts += app.privateinbox.data.db.MmsPartEntity(
                            messageId = 0, mimeType = d.type.mime, fileName = name, sizeBytes = p.data.size.toLong(),
                        )
                    }
                    is app.privateinbox.attachments.MediaPolicy.Decision.Reject ->
                        texts += "[attachment not shown: ${d.reason}]"
                }
            }
        }
        val recipients = (pdu.to + pdu.cc).distinct().size
        val subject = listOfNotNull(
            if (recipients > 1) "Group MMS ($recipients people)" else null,
            pdu.subject?.takeIf { it.isNotBlank() }?.take(80),
        ).joinToString(" · ").ifEmpty { null }
        val body = texts.joinToString("\n")
        val r = messages.ingestIncoming(
            from, body, timestamp, subscriptionId, providerId = null,
            isMms = true, mmsSubject = subject, mmsParts = parts,
        )
        if (r == null) {
            parts.forEach { mmsStore.delete(it.fileName) }
            return
        }
        messages.mirrorMms(r.messageId, from = from, to = (pdu.to + pdu.cc).distinct())
        notifyIncoming(r, body.ifBlank { "Attachment" })
    }

    private suspend fun notifyIncoming(r: MessageRepository.IngestResult, body: String) {
        app.privateinbox.widget.InboxWidget.requestUpdate(context)
        if (r.blocked || r.muted || !settings.notificationsEnabled.first()) return
        if (r.category == Category.SPAM) return
        if (r.category == Category.PROMOTIONAL && !settings.notifyPromotions.first()) return

        val hideOtp = settings.hideOtpInNotifications.first()
        if (r.otpCode != null && !r.scam.isSuspicious) {
            otpNotifier.show(r.messageId.toInt(), r.conversationId, r.otpCode, hideOtp)
        } else {
            val text = if (hideOtp && r.otpCode != null) "Contains a code. Open to view." else body
            val warn = r.scam.isSuspicious && settings.scamWarnings.first()
            messageNotifier.showMessage(r.conversationId, r.displayName, text, warn)
        }
    }
}
