package app.privateinbox

import android.content.Context
import androidx.room.withTransaction
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.prefs.SettingsStore
import app.privateinbox.data.repo.MessageRepository
import app.privateinbox.data.rules.AssetRuleLoader
import app.privateinbox.data.rules.RuleRepository
import app.privateinbox.data.rules.RuleSetParser
import app.privateinbox.data.telephony.TelephonyStore
import app.privateinbox.domain.Category
import app.privateinbox.mms.MmsDownloadPolicy as MmsPolicy
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

    /** Local runtime-integrity checks (I-01): signals only, never blocks, no network. */
    val integrity by lazy { app.privateinbox.security.integrity.IntegrityMonitor(context) }
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
    suspend fun receiveMms(pdu: app.privateinbox.mms.MmsPdu.Pdu, subscriptionId: Int?, notify: Boolean = true) {
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
        if (notify) notifyIncoming(r, body.ifBlank { "Attachment" }) else app.privateinbox.widget.InboxWidget.requestUpdate(context)
    }

    /**
     * F-04 / RB-1: an MMS notification arrived. Nothing is ever downloaded here, from anyone: we
     * store a placeholder message (so the usual blocking, categories, mute and notification rules
     * apply) and, unless the sender is blocked or the location is unusable, a pending row the user
     * can act on with "Download" (unknown senders after a warning). The carrier is told "deferred"
     * (M-NotifyResp.ind, read/delivery reports refused) so it keeps the message and stops re-sending
     * the notification; that control message is sent by the system MMS service over the carrier
     * network, PrivateInbox itself has no network permission.
     *
     * HB-3: the receipt row, the placeholder and the pending row are written in ONE transaction, and
     * the receipt's primary key (subscription | transaction id | location) makes the first writer
     * win: duplicate or concurrent WAP pushes create nothing.
     */
    suspend fun receiveMmsNotification(pdu: app.privateinbox.mms.MmsPdu.Pdu, subscriptionId: Int?) {
        val location = pdu.contentLocation?.trim() ?: return
        val tid = pdu.transactionId?.take(100) ?: return
        val from = pdu.from?.takeIf { it.isNotBlank() } ?: "Unknown sender"
        val blocked = database.blockedSenderDao().isBlockedAny(app.privateinbox.domain.AddressNormalizer.lookupKeys(from))
        val downloadable = !blocked && MmsPolicy.isFetchableLocation(location)
        val dao = database.pendingMmsDao()
        val now = System.currentTimeMillis()
        val r = database.withTransaction {
            val receipt = app.privateinbox.data.db.MmsReceiptEntity(MmsPolicy.receiptKey(subscriptionId, tid, location), now)
            if (dao.insertReceipt(receipt) == -1L) return@withTransaction null // duplicate notification
            val ingested = messages.ingestIncoming(
                from, MmsPolicy.placeholderBody(pdu.messageSize, blocked), now, subscriptionId,
                providerId = null, isMms = true, mmsSubject = pdu.subject?.takeIf { it.isNotBlank() }?.take(80),
            ) ?: return@withTransaction null
            if (downloadable) {
                dao.insert(
                    app.privateinbox.data.db.PendingMmsEntity(
                        messageId = ingested.messageId, contentLocation = location, transactionId = tid,
                        subscriptionId = subscriptionId, sizeBytes = pdu.messageSize, createdAt = now, updatedAt = now,
                    ),
                )
            }
            ingested
        } ?: return
        app.privateinbox.sms.MmsTransport(context, mmsStore).notifyDeferred(tid, subscriptionId)
        if (downloadable) dao.markDeferredSent(r.messageId)
        notifyIncoming(r, PLACEHOLDER_NOTIFICATION)
    }

    /** What tapping Download on placeholder [messageId] would do, or null if nothing is pending. */
    suspend fun mmsDownloadDecision(messageId: Long): MmsPolicy.Decision? {
        val p = database.pendingMmsDao().forMessage(messageId) ?: return null
        val m = database.messageDao().getById(messageId) ?: return null
        val blocked = database.blockedSenderDao().isBlockedAny(app.privateinbox.domain.AddressNormalizer.lookupKeys(m.address))
        val known = !blocked && (database.messageDao().hasOutgoing(m.conversationId) || telephony.contactName(m.address) != null)
        return MmsPolicy.decide(blocked, known, p.sizeBytes, p.contentLocation)
    }

    /**
     * Starts the user-requested download (a tap on the placeholder; there is no automatic caller)
     * of a pending MMS through the system MMS service. Re-checks the policy (the sender may have been blocked meanwhile); an
     * unknown sender needs [confirmedUnknownSender]. A download stuck for
     * [MmsPolicy.STUCK_AFTER_MS] can be claimed again. Returns true if the system accepted it.
     */
    suspend fun startMmsDownload(messageId: Long, confirmedUnknownSender: Boolean): Boolean {
        val decision = mmsDownloadDecision(messageId) ?: return false
        val allowed = decision == MmsPolicy.Decision.ALLOW ||
            (decision == MmsPolicy.Decision.WARN_UNKNOWN_SENDER && confirmedUnknownSender)
        if (!allowed) return false
        val dao = database.pendingMmsDao()
        val now = System.currentTimeMillis()
        if (dao.claim(messageId, now, staleBefore = now - MmsPolicy.STUCK_AFTER_MS) != 1) return false // already downloading
        val p = dao.forMessage(messageId) ?: return false
        val ok = app.privateinbox.sms.MmsTransport(context, mmsStore).download(p.contentLocation, p.transactionId, p.subscriptionId, messageId, p.attempt)
        if (!ok) dao.failAttempt(messageId, p.attempt, System.currentTimeMillis())
        return ok
    }

    /**
     * Downloads with no callback for [MmsPolicy.STUCK_AFTER_MS] become retryable (app start, periodic
     * work); old notification receipts are pruned.
     */
    suspend fun resetStuckMmsDownloads(): Int {
        val now = System.currentTimeMillis()
        val dao = database.pendingMmsDao()
        dao.pruneReceipts(before = now - MmsPolicy.RECEIPT_RETENTION_MS)
        return dao.resetStuck(staleBefore = now - MmsPolicy.STUCK_AFTER_MS, now = now)
    }

    /**
     * Download attempt [attempt] for [placeholderId] finished. On success the placeholder is replaced
     * by the real message and the return value says whether the carrier was told "deferred" before
     * (null = nothing to acknowledge: a late or concurrent duplicate callback). On failure the row
     * becomes retryable (only if this is still the current attempt).
     *
     * HB-3: [app.privateinbox.data.db.PendingMmsDao.beginCompletion] lets exactly one callback
     * complete a placeholder, and the real message + placeholder removal are committed in one
     * transaction; if that fails (or the process dies) nothing is committed and the row becomes
     * retryable.
     */
    suspend fun finishMmsDownload(placeholderId: Long, pdu: app.privateinbox.mms.MmsPdu.Pdu?, subscriptionId: Int?, attempt: Int): Boolean? {
        val dao = database.pendingMmsDao()
        val row = dao.forMessage(placeholderId) ?: return null // already replaced (late duplicate callback)
        if (pdu == null) {
            if (dao.failAttempt(placeholderId, attempt, System.currentTimeMillis()) == 1 && !row.deferredSent) {
                // Row from before v1.0.3 that the carrier never heard about: keep it on the MMSC.
                app.privateinbox.sms.MmsTransport(context, mmsStore).notifyDeferred(row.transactionId, row.subscriptionId)
                dao.markDeferredSent(placeholderId)
            }
            return null
        }
        if (dao.beginCompletion(placeholderId, System.currentTimeMillis()) != 1) return null // another callback is on it
        try {
            database.withTransaction {
                // The user tapped Download and is looking at the thread: no second notification.
                receiveMms(pdu, subscriptionId, notify = false)
                messages.purge(listOf(placeholderId)) // cascades the pending row
            }
        } catch (t: Throwable) {
            runCatching { dao.failCompletion(placeholderId, System.currentTimeMillis()) }
            throw t
        }
        return row.deferredSent
    }

    private suspend fun notifyIncoming(r: MessageRepository.IngestResult, body: String) {
        app.privateinbox.widget.InboxWidget.requestUpdate(context)
        if (r.blocked || r.muted || !settings.notificationsEnabled.first()) return
        if (r.category == Category.SPAM) return
        if (r.category == Category.PROMOTIONAL && !settings.notifyPromotions.first()) return

        val hideOtp = settings.hideOtpInNotifications.first()
        val hideLock = settings.hideOnLockScreen.first()
        val plan = app.privateinbox.notifications.NotificationContent.plan(
            appLockOn = settings.appLockEnabled.first(),
            contentFreeWhenLocked = settings.contentFreeWhenLocked.first(),
            hideOtp = hideOtp,
            hasOtp = r.otpCode != null,
            suspicious = r.scam.isSuspicious,
            scamWarnings = settings.scamWarnings.first(),
            displayName = r.displayName,
            body = body,
        )
        when (plan) {
            app.privateinbox.notifications.NotificationContent.Plan.OtpWithCode ->
                otpNotifier.show(r.messageId.toInt(), r.conversationId, r.otpCode!!, hideOtp, hideLock)
            is app.privateinbox.notifications.NotificationContent.Plan.Message ->
                messageNotifier.showMessage(r.conversationId, plan.title, plan.text, plan.suspicious, hideLock)
        }
    }

    private companion object {
        const val PLACEHOLDER_NOTIFICATION = "Picture message. Open the app to download it."
    }
}
