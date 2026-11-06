package app.privateinbox.data.repo

import androidx.room.withTransaction
import app.privateinbox.data.db.BillEntity
import app.privateinbox.data.db.BlockedSenderEntity
import app.privateinbox.data.db.ConversationEntity
import app.privateinbox.data.db.MessageEntity
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.db.TransactionEntity
import app.privateinbox.data.finance.BillParser
import app.privateinbox.data.finance.TransactionParser
import app.privateinbox.data.otp.OtpExtractor
import app.privateinbox.data.rules.RuleRepository
import app.privateinbox.data.scam.ScamDetector
import app.privateinbox.data.telephony.TelephonyStore
import app.privateinbox.domain.AddressNormalizer
import app.privateinbox.domain.Category
import app.privateinbox.domain.MessageStatus
import app.privateinbox.domain.SmsMessage
import java.time.Instant
import java.time.ZoneId

/**
 * Single write path for messages. Everything here runs on-device against the encrypted DB.
 */
class MessageRepository(
    private val db: PrivateInboxDatabase,
    private val rules: RuleRepository,
    private val telephony: TelephonyStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()

    data class IngestResult(
        val messageId: Long,
        val conversationId: Long,
        val category: Category,
        val otpCode: String?,
        val scam: ScamDetector.Result,
        val muted: Boolean,
        val blocked: Boolean,
        val displayName: String,
    )

    /** Store an incoming SMS. Returns null if it was a duplicate. */
    suspend fun ingestIncoming(
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int?,
        providerId: Long?,
        read: Boolean = false,
        isMms: Boolean = false,
        mmsSubject: String? = null,
        mmsParts: List<app.privateinbox.data.db.MmsPartEntity> = emptyList(),
    ): IngestResult? {
        val key = AddressNormalizer.key(address)
        val blocked = db.blockedSenderDao().isBlockedAny(AddressNormalizer.lookupKeys(address))
        val engine = rules.engine()
        val classification = engine.classify(SmsMessage(address = address, body = body, timestampMillis = timestamp))
        val otp = OtpExtractor.extract(body)?.code
        val scam = ScamDetector.analyze(body, AddressNormalizer.isPhoneNumber(address))
        val category = when {
            blocked -> Category.SPAM
            scam.isLikelyScam -> Category.SPAM
            otp != null && classification.category == Category.UNKNOWN -> Category.OTP
            else -> classification.category
        }
        val ruleId = when {
            blocked -> "blocked"
            scam.isLikelyScam -> "scam-heuristics"
            else -> classification.ruleId
        }

        return db.withTransaction {
            if (providerId != null && messages.getByProviderId(providerId, true) != null) return@withTransaction null
            val conv = getOrCreateConversation(key, address)
            val id = messages.insert(
                MessageEntity(
                    conversationId = conv.id,
                    providerId = providerId,
                    address = address,
                    body = body,
                    timestamp = timestamp,
                    incoming = true,
                    read = read,
                    status = MessageStatus.RECEIVED,
                    subscriptionId = subscriptionId,
                    category = category,
                    matchedRuleId = ruleId,
                    otpCode = otp,
                    scamScore = scam.score,
                    scamReasons = ScamDetector.reasonsToString(scam.signals),
                    isMms = isMms,
                    mmsSubject = mmsSubject,
                ),
            )
            if (id <= 0) return@withTransaction null
            mmsParts.forEach { db.mmsPartDao().insert(it.copy(messageId = id)) }
            val updated = conv.copy(
                address = address,
                displayName = conv.displayName ?: telephony.contactName(address),
                category = category,
                lastMessagePreview = preview(body.ifBlank { if (isMms) "Attachment" else "" }),
                lastMessageAt = maxOf(conv.lastMessageAt, timestamp),
                unreadCount = if (read) conv.unreadCount else conv.unreadCount + 1,
                scamScore = maxOf(conv.scamScore, scam.score),
            )
            conversations.upsert(updated)
            extractFinance(id, address, body, timestamp, category)
            IngestResult(
                messageId = id,
                conversationId = conv.id,
                category = updated.categoryOverride ?: category,
                otpCode = otp,
                scam = scam,
                muted = conv.muted,
                blocked = blocked,
                displayName = updated.displayName ?: address,
            )
        }
    }

    /** What [recordOutgoing] created: one visible message, plus one provider row per recipient. */
    data class OutgoingRecord(val messageId: Long, val conversationId: Long, val providerIds: List<Long?>)

    /** Conversation for these recipients (a user group when more than one), created if needed. */
    suspend fun conversationFor(recipients: List<String>): ConversationEntity {
        val clean = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { AddressNormalizer.key(it) }
        require(clean.isNotEmpty()) { "no recipients" }
        if (clean.size == 1) return getOrCreateConversation(AddressNormalizer.key(clean[0]), clean[0])
        val key = groupKey(clean)
        conversations.getByKey(key)?.let { return it }
        val id = conversations.insert(
            ConversationEntity(addressKey = key, address = clean.joinToString(", "), isGroup = true, members = clean.joinToString("\n")),
        )
        return requireNotNull(conversations.getById(id))
    }

    /** Record an outgoing SMS (1:1 or user group) before handing it to the radio. */
    suspend fun recordOutgoing(
        recipients: List<String>,
        body: String,
        subscriptionId: Int?,
        isMms: Boolean = false,
    ): OutgoingRecord {
        val now = clock()
        val clean = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { AddressNormalizer.key(it) }
        require(clean.isNotEmpty()) { "no recipients" }
        // The system provider gets one row per recipient so other apps see a normal history.
        val providerIds = if (isMms) clean.map { null } else clean.map { telephony.insertOutgoing(it, body, now, subscriptionId) }
        return db.withTransaction {
            val conv = conversationFor(clean)
            val id = messages.insert(
                MessageEntity(
                    conversationId = conv.id,
                    providerId = if (clean.size == 1) providerIds[0] else null,
                    address = clean.joinToString(", "),
                    body = body,
                    timestamp = now,
                    incoming = false,
                    read = true,
                    status = MessageStatus.PENDING,
                    subscriptionId = subscriptionId,
                    category = conv.categoryOverride ?: conv.category,
                    isMms = isMms,
                ),
            )
            val address = if (conv.isGroup) conv.address else clean[0]
            conversations.upsert(conv.copy(address = address, lastMessagePreview = preview(body), lastMessageAt = now))
            OutgoingRecord(id, conv.id, providerIds)
        }
    }

    suspend fun addMmsPart(part: app.privateinbox.data.db.MmsPartEntity) = db.mmsPartDao().insert(part)

    /** Resolves a stored MMS media name to its private file (set by AppContainer). */
    var mediaFile: (String?) -> java.io.File? = { null }

    /** Invoked after any change that affects unread counts (used to refresh the widget). */
    var onUnreadChanged: () -> Unit = {}

    /**
     * Mirrors one of our MMS messages into the system MMS store and remembers the provider id.
     * Our encrypted copy remains the source of truth for the UI.
     */
    suspend fun mirrorMms(messageId: Long, from: String?, to: List<String>) {
        val m = messages.getById(messageId) ?: return
        if (!m.isMms || m.providerId != null) return
        val parts = db.mmsPartDao().forMessage(messageId).map {
            TelephonyStore.MmsMirrorPart(it.mimeType, it.text, mediaFile(it.fileName))
        }.filter { it.text != null || it.file != null }
        val all = (if (m.body.isNotBlank()) listOf(TelephonyStore.MmsMirrorPart("text/plain", m.body)) else emptyList()) + parts
        val id = telephony.insertMms(
            incoming = m.incoming, from = from, to = to, subject = m.mmsSubject?.takeUnless { it.startsWith("Group MMS") },
            date = m.timestamp, read = m.read, subscriptionId = m.subscriptionId, parts = all,
        ) ?: return
        messages.updateStatus(messageId, m.status, id)
    }

    /** Imports an already-sent message from the provider. */
    suspend fun importOutgoing(p: TelephonyStore.ProviderSms) {
        db.withTransaction {
            if (messages.getByProviderId(p.id, false) != null) return@withTransaction
            val conv = getOrCreateConversation(AddressNormalizer.key(p.address), p.address)
            messages.insert(
                MessageEntity(
                    conversationId = conv.id, providerId = p.id, address = p.address, body = p.body,
                    timestamp = p.date, incoming = false, read = true, status = MessageStatus.SENT,
                    subscriptionId = p.subscriptionId, category = conv.category,
                ),
            )
            if (p.date >= conv.lastMessageAt) {
                conversations.upsert(conv.copy(lastMessagePreview = preview(p.body), lastMessageAt = p.date))
            }
        }
    }

    /**
     * Applies a radio result. A group message is FAILED if any recipient failed: a later success
     * from another recipient never overwrites FAILED.
     */
    suspend fun updateSendStatus(messageId: Long, success: Boolean, providerId: Long? = null) {
        val msg = messages.getById(messageId) ?: return
        if (msg.isMms) {
            msg.providerId?.let { telephony.markMmsSent(it, success) }
        } else {
            (providerId ?: msg.providerId)?.let { telephony.markSent(it, success) }
        }
        if (success && msg.status == MessageStatus.FAILED) return
        messages.updateStatus(messageId, if (success) MessageStatus.SENT else MessageStatus.FAILED, null)
    }

    suspend fun markConversationRead(conversationId: Long) {
        val unread = messages.getAllVisible()
            .filter { it.conversationId == conversationId && !it.read && it.providerId != null }
        messages.markConversationRead(conversationId)
        conversations.markRead(conversationId)
        telephony.markRead(unread.filter { !it.isMms }.mapNotNull { it.providerId })
        telephony.markMmsRead(unread.filter { it.isMms }.mapNotNull { it.providerId })
        if (unread.isNotEmpty()) onUnreadChanged()
    }

    // ---- Recycle bin ------------------------------------------------------------------------

    suspend fun deleteMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        val convIds = ids.mapNotNull { messages.getById(it)?.conversationId }.toSet()
        messages.softDelete(ids, clock())
        convIds.forEach { refreshConversation(it) }
    }

    suspend fun deleteConversation(conversationId: Long) {
        messages.softDeleteConversation(conversationId, clock())
        refreshConversation(conversationId)
        onUnreadChanged()
    }

    suspend fun restoreMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        messages.restore(ids)
        ids.mapNotNull { messages.getById(it)?.conversationId }.toSet().forEach { refreshConversation(it) }
    }

    /** Called with the bare file names of MMS media that must be deleted from disk. */
    var deleteMediaFiles: (List<String>) -> Unit = {}

    /** Permanently removes messages (and their provider rows when we are default app, and MMS files). */
    suspend fun purge(ids: List<Long>) {
        val rows = ids.mapNotNull { messages.getById(it) }
        db.mmsPartDao().forMessages(rows.filter { it.isMms }.map { it.id }).mapNotNull { it.fileName }.let(deleteMediaFiles)
        rows.forEach { m -> m.providerId?.let { if (m.isMms) telephony.deleteMms(it) else telephony.delete(it) } }
        messages.hardDelete(rows.map { it.id })
        rows.map { it.conversationId }.toSet().forEach { refreshConversation(it) }
    }

    suspend fun purgeExpired(retentionDays: Int): Int {
        val cutoff = clock() - retentionDays * DAY_MS
        val expired = messages.getDeletedBefore(cutoff)
        purge(expired.map { it.id })
        return expired.size
    }

    // ---- Block / mute / categories ----------------------------------------------------------

    suspend fun block(conversationId: Long) {
        val conv = conversations.getById(conversationId) ?: return
        db.blockedSenderDao().upsert(BlockedSenderEntity(conv.addressKey, conv.address, clock()))
    }

    suspend fun unblock(addressKey: String) = db.blockedSenderDao().delete(addressKey)

    suspend fun setMuted(conversationId: Long, muted: Boolean) = conversations.setMuted(conversationId, muted)

    suspend fun setPinned(conversationId: Long, pinned: Boolean) = conversations.setPinned(conversationId, pinned)

    suspend fun setArchived(conversationId: Long, archived: Boolean) = conversations.setArchived(conversationId, archived)

    /** "Move to category": override this conversation and add a sender rule for future messages. */
    suspend fun moveToCategory(conversationId: Long, category: Category) {
        val conv = conversations.getById(conversationId) ?: return
        conversations.setCategoryOverride(conversationId, category)
        rules.save(RuleRepository.senderRule(conv.addressKey, category))
    }

    /** Re-run rules over every message, e.g. after the user edits rules. */
    suspend fun recategorizeAll() {
        val engine = rules.engine()
        val all = messages.getAllVisible()
        db.withTransaction {
            all.filter { it.incoming }.forEach { m ->
                val c = engine.classify(SmsMessage(address = m.address, body = m.body, timestampMillis = m.timestamp))
                val cat = when {
                    m.matchedRuleId == "blocked" -> Category.SPAM
                    m.scamScore >= ScamDetector.LIKELY_SCAM -> Category.SPAM
                    m.otpCode != null && c.category == Category.UNKNOWN -> Category.OTP
                    else -> c.category
                }
                if (cat != m.category) messages.updateCategory(m.id, cat, c.ruleId)
            }
            conversations.getAll().forEach { refreshConversation(it.id) }
        }
    }

    /** Recompute preview/time/unread/category for a conversation from its visible messages. */
    suspend fun refreshConversation(conversationId: Long) {
        val conv = conversations.getById(conversationId) ?: return
        val latest = messages.latestVisible(conversationId)
        val latestIncomingCategory = messages.getAllVisible()
            .filter { it.conversationId == conversationId && it.incoming }
            .maxByOrNull { it.timestamp }?.category
        conversations.upsert(
            conv.copy(
                lastMessagePreview = latest?.let { preview(it.body) }.orEmpty(),
                lastMessageAt = latest?.timestamp ?: 0,
                unreadCount = messages.unreadCount(conversationId),
                category = latestIncomingCategory ?: conv.category,
            ),
        )
    }

    private suspend fun getOrCreateConversation(key: String, address: String): ConversationEntity {
        conversations.getByKey(key)?.let { return it }
        val id = conversations.insert(ConversationEntity(addressKey = key, address = address))
        return requireNotNull(conversations.getById(id))
    }

    private suspend fun extractFinance(messageId: Long, address: String, body: String, timestamp: Long, category: Category) {
        if (category == Category.SPAM || category == Category.PERSONAL || category == Category.OTP) return
        TransactionParser.parse(body)?.let { t ->
            db.transactionDao().upsert(
                TransactionEntity(
                    messageId = messageId, amountMinor = t.amountMinor, currency = t.currency, type = t.type,
                    merchant = t.merchant, accountHint = t.accountHint, balanceMinor = t.balanceMinor,
                    sender = AddressNormalizer.key(address), timestamp = timestamp,
                ),
            )
        }
        val received = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        BillParser.parse(body, received)?.let { b ->
            db.billDao().insert(
                BillEntity(
                    messageId = messageId,
                    biller = "${AddressNormalizer.key(address)} · ${b.kind}",
                    amountMinor = b.amountMinor,
                    dueDate = b.dueDate?.let { BillParser.toEpochMillis(it) },
                    createdAt = timestamp,
                ),
            )
        }
    }

    /** Full re-analysis (OTP, scam, rules, finance), used after restoring a backup. */
    suspend fun reanalyzeAll() {
        db.withTransaction {
            messages.getAll().filter { it.incoming }.forEach { m ->
                val otp = OtpExtractor.extract(m.body)?.code
                val scam = ScamDetector.analyze(m.body, AddressNormalizer.isPhoneNumber(m.address))
                messages.upsert(m.copy(otpCode = otp, scamScore = scam.score, scamReasons = ScamDetector.reasonsToString(scam.signals)))
            }
        }
        recategorizeAll()
        rebuildFinance()
    }

    /** Re-extract transactions/bills from all messages (after an import or restore). */
    suspend fun rebuildFinance() {
        db.withTransaction {
            db.transactionDao().clear()
            messages.getAllVisible().filter { it.incoming }.forEach {
                extractFinance(it.id, it.address, it.body, it.timestamp, it.category)
            }
        }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        private fun preview(body: String) = body.replace('\n', ' ').take(140)

        /** Escapes LIKE wildcards for MessageDao.search. */
        /** Order-independent key for a user group; can never collide with a phone/alpha key. */
        fun groupKey(members: List<String>) =
            "group:" + members.map { AddressNormalizer.key(it) }.distinct().sorted().joinToString(",")

        fun escapeLike(q: String) = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
