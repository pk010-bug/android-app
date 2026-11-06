package app.privateinbox.backup

import androidx.room.withTransaction
import app.privateinbox.data.db.BlockedSenderEntity
import app.privateinbox.data.db.ConversationEntity
import app.privateinbox.data.db.MessageEntity
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
) {
    data class RestoreStats(val conversations: Int, val messages: Int, val rules: Int)

    suspend fun export(password: CharArray, iterations: Int = BackupCrypto.DEFAULT_ITERATIONS): ByteArray {
        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            conversations = db.conversationDao().getAll().map {
                BConversation(it.id, it.addressKey, it.address, it.displayName, it.categoryOverride?.name, it.pinned, it.archived, it.muted)
            },
            messages = db.messageDao().getAll().map {
                BMessage(it.id, it.conversationId, it.address, it.body, it.timestamp, it.incoming, it.read, it.status.name, it.subscriptionId, it.deletedAt)
            },
            rules = db.ruleDao().getAll().map {
                BRule(it.id, it.category.name, it.description, it.priority, it.enabled, it.senderPatterns, it.bodyPatterns, it.excludeBodyPatterns)
            },
            blocked = db.blockedSenderDao().getAll().map { BBlocked(it.addressKey, it.address, it.createdAt) },
            paidBillMessageIds = db.billDao().getAll().filter { it.paid }.mapNotNull { it.messageId },
        )
        return BackupCrypto.encrypt(BackupJson.encode(payload), password, iterations)
    }

    /** Merges a backup into the current data (existing messages are kept; duplicates skipped). */
    suspend fun restore(blob: ByteArray, password: CharArray): RestoreStats {
        val p = BackupJson.decode(BackupCrypto.decrypt(blob, password))
        require(p.format == 1) { "unsupported backup format" }
        var newMsgs = 0
        db.withTransaction {
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
            val existingKeys = db.messageDao().getAll().map { Triple(it.conversationId, it.timestamp, it.body.hashCode()) }.toHashSet()
            p.messages.forEach { bm ->
                val convId = idMap[bm.conversationId] ?: return@forEach
                if (Triple(convId, bm.timestamp, bm.body.hashCode()) in existingKeys) return@forEach
                val id = db.messageDao().insert(
                    MessageEntity(
                        conversationId = convId, address = bm.address, body = bm.body.take(20_000), timestamp = bm.timestamp,
                        incoming = bm.incoming, read = bm.read,
                        status = runCatching { MessageStatus.valueOf(bm.status) }.getOrDefault(MessageStatus.SENT),
                        subscriptionId = bm.subscriptionId, deletedAt = bm.deletedAt,
                    ),
                )
                if (id > 0) newMsgs++
            }
        }
        rules.invalidate()
        // Re-derive categories, OTPs, scam scores and finance from the restored text.
        messages.reanalyzeAll()
        return RestoreStats(p.conversations.size, newMsgs, p.rules.size)
    }
}
