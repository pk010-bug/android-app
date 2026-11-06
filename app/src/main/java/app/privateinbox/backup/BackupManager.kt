package app.privateinbox.backup

import androidx.room.withTransaction
import app.privateinbox.data.db.BlockedSenderEntity
import app.privateinbox.data.db.ConversationEntity
import app.privateinbox.data.db.MessageEntity
import app.privateinbox.data.db.MmsPartEntity
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.db.RuleEntity
import app.privateinbox.data.repo.MessageRepository
import app.privateinbox.data.rules.RuleRepository
import app.privateinbox.domain.Category
import app.privateinbox.domain.MessageStatus

/**
 * Builds and restores encrypted backups. Files are written only where the user picks via the
 * system file picker (Storage Access Framework). Nothing is uploaded anywhere by this app.
 */
class BackupManager(
    private val db: PrivateInboxDatabase,
    private val rules: RuleRepository,
    private val messages: MessageRepository,
    private val media: MediaIo = MediaIo.NONE,
) {
    /** Access to the private MMS media store (kept abstract so this stays testable). */
    interface MediaIo {
        fun read(name: String?): ByteArray?
        fun save(bytes: ByteArray, extension: String): String
        fun delete(name: String)

        companion object {
            val NONE = object : MediaIo {
                override fun read(name: String?): ByteArray? = null
                override fun save(bytes: ByteArray, extension: String) = throw UnsupportedOperationException()
                override fun delete(name: String) = Unit
            }
        }
    }

    data class RestoreStats(val conversations: Int, val messages: Int, val rules: Int, val media: Int = 0, val paidBills: Int = 0)

    suspend fun export(password: CharArray): ByteArray {
        BackupPasswordPolicy.check(password)?.let { throw IllegalArgumentException(it.message) }
        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            conversations = db.conversationDao().getAll().map {
                BConversation(it.id, it.addressKey, it.address, it.displayName, it.categoryOverride?.name, it.pinned, it.archived, it.muted)
            },
            messages = db.messageDao().getAll().map {
                BMessage(
                    it.id, it.conversationId, it.address, it.body, it.timestamp, it.incoming, it.read, it.status.name,
                    it.subscriptionId, it.deletedAt, it.isMms, it.mmsSubject,
                )
            },
            mmsParts = db.mmsPartDao().getAll().mapNotNull { p ->
                val bytes = p.fileName?.let { media.read(it) }
                if (p.fileName != null && bytes == null) return@mapNotNull null // file gone; skip
                BMmsPart(p.messageId, p.mimeType, p.text, bytes?.let { java.util.Base64.getEncoder().encodeToString(it) })
            },
            rules = db.ruleDao().getAll().map {
                BRule(it.id, it.category.name, it.description, it.priority, it.enabled, it.senderPatterns, it.bodyPatterns, it.excludeBodyPatterns)
            },
            blocked = db.blockedSenderDao().getAll().map { BBlocked(it.addressKey, it.address, it.createdAt) },
            paidBillMessageIds = db.billDao().getAll().filter { it.paid }.mapNotNull { it.messageId },
        )
        return BackupCrypto.encrypt(BackupJson.encode(payload), password)
    }

    /** Merges a backup into the current data (existing messages are kept; duplicates skipped). */
    suspend fun restore(blob: ByteArray, password: CharArray): RestoreStats {
        val p = BackupJson.decode(BackupCrypto.decrypt(blob, password))
        require(p.format in 1..2) { "unsupported backup format" }
        var newMsgs = 0
        var newMedia = 0
        val msgIdMap = HashMap<Long, Long>()
        val newMessageIds = HashSet<Long>()
        val writtenFiles = ArrayList<String>()
        try { db.withTransaction {
            p.rules.forEach { r ->
                val cat = Category.fromName(r.category) ?: return@forEach
                if (RuleRepository.validate(r.senderPatterns, r.bodyPatterns, r.excludeBodyPatterns) != null) return@forEach
                db.ruleDao().upsert(RuleEntity(r.id, cat, r.description, r.priority, r.enabled, r.senderPatterns, r.bodyPatterns, r.excludeBodyPatterns))
            }
            p.blocked.forEach { db.blockedSenderDao().upsert(BlockedSenderEntity(it.addressKey, it.address, it.createdAt)) }

            val idMap = HashMap<Long, Long>()
            p.conversations.forEach { bc ->
                val existing = db.conversationDao().getByKey(bc.addressKey)
                val id = existing?.id ?: db.conversationDao().insert(
                    ConversationEntity(
                        addressKey = bc.addressKey, address = bc.address, displayName = bc.displayName,
                        categoryOverride = bc.categoryOverride?.let(Category::fromName),
                        pinned = bc.pinned, archived = bc.archived, muted = bc.muted,
                    ),
                )
                idMap[bc.id] = id
            }
            val existing = db.messageDao().getAll().associateBy { Triple(it.conversationId, it.timestamp, it.body.hashCode()) }
            p.messages.forEach { bm ->
                val convId = idMap[bm.conversationId] ?: return@forEach
                existing[Triple(convId, bm.timestamp, bm.body.hashCode())]?.let { msgIdMap[bm.id] = it.id; return@forEach }
                val id = db.messageDao().insert(
                    MessageEntity(
                        conversationId = convId, address = bm.address, body = bm.body.take(20_000), timestamp = bm.timestamp,
                        incoming = bm.incoming, read = bm.read,
                        status = runCatching { MessageStatus.valueOf(bm.status) }.getOrDefault(MessageStatus.SENT),
                        subscriptionId = bm.subscriptionId, deletedAt = bm.deletedAt,
                        isMms = bm.isMms, mmsSubject = bm.mmsSubject?.take(200),
                    ),
                )
                if (id > 0) { newMsgs++; msgIdMap[bm.id] = id; newMessageIds += id }
            }
            // Media only for messages created by this restore (never duplicates onto existing ones).
            p.mmsParts.take(MAX_PARTS).forEach { bp ->
                val target = msgIdMap[bp.messageId]?.takeIf { it in newMessageIds } ?: return@forEach
                val part = BackupMedia.decode(bp) ?: return@forEach
                val name = part.bytes?.let { media.save(it, part.extension!!).also(writtenFiles::add) }
                db.mmsPartDao().insert(
                    MmsPartEntity(
                        messageId = target, mimeType = part.mimeType, fileName = name, text = bp.text?.take(5_000),
                        sizeBytes = part.bytes?.size?.toLong() ?: 0,
                    ),
                )
                if (name != null) newMedia++
            }
        } } catch (e: Exception) {
            writtenFiles.forEach(media::delete) // transaction rolled back: don't leave orphans
            throw e
        }
        rules.invalidate()
        // Re-derive categories, OTPs, scam scores and finance from the restored text.
        messages.reanalyzeAll()
        // Paid-bill state is user data that can't be re-derived: re-apply it via the id map.
        val paidLocal = p.paidBillMessageIds.mapNotNull { msgIdMap[it] }.toHashSet()
        var paid = 0
        db.billDao().getAll().filter { !it.paid && it.messageId in paidLocal }.forEach { db.billDao().setPaid(it.id, true); paid++ }
        return RestoreStats(p.conversations.size, newMsgs, p.rules.size, newMedia, paid)
    }

    private companion object { const val MAX_PARTS = 20_000 }
}

/** Pure validation of a backed-up MMS part (unit-tested): media must re-pass MediaPolicy. */
object BackupMedia {
    data class Decoded(val mimeType: String, val bytes: ByteArray?, val extension: String?)

    fun decode(p: BMmsPart): Decoded? {
        val b64 = p.data ?: return if (p.text != null) Decoded("text/plain", null, null) else null
        if (b64.length > app.privateinbox.attachments.MediaPolicy.MAX_BYTES * 4 / 3 + 8) return null
        val bytes = try { java.util.Base64.getDecoder().decode(b64) } catch (_: IllegalArgumentException) { return null }
        return when (val d = app.privateinbox.attachments.MediaPolicy.check(bytes)) {
            is app.privateinbox.attachments.MediaPolicy.Decision.Accept -> Decoded(d.type.mime, bytes, d.type.extension)
            is app.privateinbox.attachments.MediaPolicy.Decision.Reject -> null
        }
    }
}
