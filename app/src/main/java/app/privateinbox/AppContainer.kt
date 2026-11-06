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
     * F-04: an MMS notification arrived. We store a placeholder message (so the usual blocking,
     * categories, mute and notification rules apply) plus a pending row. Then:
     *  - blocked sender: no pending row, never downloaded;
     *  - known sender (contact / someone you've texted) with "Auto-download MMS from contacts" on:
     *    downloaded right away through the same policy-checked [startMmsDownload];
     *  - otherwise the carrier is told "deferred" (so it stops re-sending the WAP push) and the
     *    user downloads with a tap (unknown senders after a warning).
     */
    suspend fun receiveMmsNotification(pdu: app.privateinbox.mms.MmsPdu.Pdu, subscriptionId: Int?) {
        val location = pdu.contentLocation ?: return
        val tid = pdu.transactionId?.take(100) ?: return
        val pending = database.pendingMmsDao()
        if (pending.existsTransaction(tid)) return // carriers re-send the same WAP push
        val from = pdu.from?.takeIf { it.isNotBlank() } ?: "Unknown sender"
        val blocked = database.blockedSenderDao().isBlockedAny(app.privateinbox.domain.AddressNormalizer.lookupKeys(from))
        val r = messages.ingestIncoming(
            from, MmsPolicy.placeholderBody(pdu.messageSize, blocked), System.currentTimeMillis(), subscriptionId,
            providerId = null, isMms = true, mmsSubject = pdu.subject?.takeIf { it.isNotBlank() }?.take(80),
        ) ?: return
        val transport = app.privateinbox.sms.MmsTransport(context, mmsStore)
        if (blocked || !MmsPolicy.isFetchableLocation(location)) {
            transport.notifyDeferred(tid, subscriptionId) // never fetched; stop the re-sends
            notifyIncoming(r, PLACEHOLDER_NOTIFICATION)
            return
        }
        val now = System.currentTimeMillis()
        pending.insert(
            app.privateinbox.data.db.PendingMmsEntity(
                messageId = r.messageId, contentLocation = location.trim(), transactionId = tid,
                subscriptionId = subscriptionId, sizeBytes = pdu.messageSize, createdAt = now, updatedAt = now,
            ),
        )
        val decision = mmsDownloadDecision(r.messageId)
        val auto = decision != null && MmsPolicy.autoDownload(settings.autoDownloadMmsFromContacts.first(), decision)
        if (auto && startMmsDownload(r.messageId, confirmedUnknownSender = false)) {
            return // the downloaded message is notified when it arrives (or the failure is, see finishMmsDownload)
        }
        deferAndNotify(r.messageId, tid, subscriptionId, r)
    }

    private suspend fun deferAndNotify(messageId: Long, tid: String, subscriptionId: Int?, r: MessageRepository.IngestResult?) {
        app.privateinbox.sms.MmsTransport(context, mmsStore).notifyDeferred(tid, subscriptionId)
        database.pendingMmsDao().markDeferredSent(messageId)
        if (r != null) notifyIncoming(r, PLACEHOLDER_NOTIFICATION) else notifyPlaceholder(messageId)
    }

    /** Notification for a placeholder whose auto-download failed (re-reads the message for the rules). */
    private suspend fun notifyPlaceholder(messageId: Long) {
        val m = database.messageDao().getById(messageId) ?: return
        val c = database.conversationDao().getById(m.conversationId) ?: return
        if (c.muted || c.category == Category.SPAM || !settings.notificationsEnabled.first()) return
        val lockOn = settings.appLockEnabled.first() && settings.contentFreeWhenLocked.first()
        messageNotifier.showMessage(
            c.id,
            if (lockOn) app.privateinbox.notifications.NotificationContent.CONTENT_FREE_TITLE else (c.displayName ?: c.address),
            if (lockOn) app.privateinbox.notifications.NotificationContent.CONTENT_FREE_TEXT else PLACEHOLDER_NOTIFICATION,
            suspicious = false,
            hideOnLockScreen = settings.hideOnLockScreen.first(),
        )
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
     * Starts a download of a pending MMS through the system MMS service (user tap, or auto-download
     * for a known sender). Re-checks the policy (the sender may have been blocked meanwhile); an
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

    /** Downloads with no callback for [MmsPolicy.STUCK_AFTER_MS] become retryable (app start, periodic work). */
    suspend fun resetStuckMmsDownloads(): Int {
        val now = System.currentTimeMillis()
        return database.pendingMmsDao().resetStuck(staleBefore = now - MmsPolicy.STUCK_AFTER_MS, now = now)
    }

    /**
     * Download attempt [attempt] for [placeholderId] finished. On success the placeholder is replaced
     * by the real message and the return value says whether the carrier was told "deferred" before
     * (null = nothing to acknowledge, e.g. a late duplicate). On failure the row becomes retryable
     * (only if this is still the current attempt), and an auto-download that failed is reported to
     * the carrier as deferred and notified like any undownloaded MMS.
     */
    suspend fun finishMmsDownload(placeholderId: Long, pdu: app.privateinbox.mms.MmsPdu.Pdu?, subscriptionId: Int?, attempt: Int): Boolean? {
        val dao = database.pendingMmsDao()
        val row = dao.forMessage(placeholderId) ?: return null // already replaced (late duplicate callback)
        if (pdu == null) {
            if (dao.failAttempt(placeholderId, attempt, System.currentTimeMillis()) == 1 && !row.deferredSent) {
                deferAndNotify(placeholderId, row.transactionId, row.subscriptionId, null)
            }
            return null
        }
        // Tapped downloads: the user is looking at it, no second notification.
        receiveMms(pdu, subscriptionId, notify = !row.deferredSent)
        messages.purge(listOf(placeholderId)) // cascades the pending row
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
