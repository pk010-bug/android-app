package app.privateinbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.privateinbox.domain.Category
import app.privateinbox.domain.MessageStatus
import kotlinx.coroutines.flow.Flow

/** Effective category: user override wins over rules. */
private const val EFFECTIVE_CATEGORY = "COALESCE(categoryOverride, category)"

@Dao
interface ConversationDao {
    @Query(
        "SELECT * FROM conversations WHERE archived = 0 AND lastMessageAt > 0 " +
            "AND addressKey NOT IN (SELECT addressKey FROM blocked_senders) " +
            "ORDER BY pinned DESC, lastMessageAt DESC",
    )
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        "SELECT * FROM conversations WHERE archived = 0 AND lastMessageAt > 0 AND $EFFECTIVE_CATEGORY = :category " +
            "AND addressKey NOT IN (SELECT addressKey FROM blocked_senders) " +
            "ORDER BY pinned DESC, lastMessageAt DESC",
    )
    fun observeByCategory(category: Category): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE archived = 1 ORDER BY lastMessageAt DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT $EFFECTIVE_CATEGORY AS category, SUM(unreadCount) AS unread FROM conversations WHERE archived = 0 GROUP BY $EFFECTIVE_CATEGORY")
    fun observeUnreadByCategory(): Flow<List<CategoryUnread>>

    @Query("SELECT * FROM conversations WHERE unreadCount > 0 AND archived = 0 AND muted = 0 ORDER BY lastMessageAt DESC LIMIT :limit")
    suspend fun recentUnread(limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE addressKey = :key LIMIT 1")
    suspend fun getByKey(key: String): ConversationEntity?

    @Query("SELECT * FROM conversations")
    suspend fun getAll(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(conversation: ConversationEntity): Long

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: Long, muted: Boolean)

    @Query("UPDATE conversations SET categoryOverride = :category WHERE id = :id")
    suspend fun setCategoryOverride(id: Long, category: Category?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

data class CategoryUnread(val category: Category, val unread: Int)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND deletedAt IS NULL ORDER BY timestamp ASC")
    fun observeConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE otpCode IS NOT NULL AND deletedAt IS NULL AND timestamp >= :since ORDER BY timestamp DESC")
    fun observeRecentOtps(since: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE scamScore >= :minScore AND deletedAt IS NULL ORDER BY timestamp DESC LIMIT 100")
    fun observeSuspicious(minScore: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeRecycleBin(): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE deletedAt IS NULL AND (body LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR address LIKE '%' || :q || '%' ESCAPE '\\') ORDER BY timestamp DESC LIMIT 200",
    )
    fun search(q: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND deletedAt IS NULL ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestVisible(conversationId: Long): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND deletedAt IS NULL AND incoming = 1 AND read = 0")
    suspend fun unreadCount(conversationId: Long): Int

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE providerId = :providerId AND incoming = :incoming AND isMms = 0 LIMIT 1")
    suspend fun getByProviderId(providerId: Long, incoming: Boolean): MessageEntity?

    @Query("SELECT * FROM messages WHERE deletedAt IS NULL")
    suspend fun getAllVisible(): List<MessageEntity>

    @Query("SELECT * FROM messages")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun getDeletedBefore(before: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET category = :category, matchedRuleId = :ruleId WHERE id = :id")
    suspend fun updateCategory(id: Long, category: Category, ruleId: String?)

    @Query("UPDATE messages SET status = :status, providerId = COALESCE(:providerId, providerId) WHERE id = :id")
    suspend fun updateStatus(id: Long, status: MessageStatus, providerId: Long?)

    @Query("UPDATE messages SET read = 1 WHERE conversationId = :conversationId AND read = 0")
    suspend fun markConversationRead(conversationId: Long)

    @Query("UPDATE messages SET deletedAt = :at WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<Long>, at: Long)

    @Query("UPDATE messages SET deletedAt = :at WHERE conversationId = :conversationId AND deletedAt IS NULL")
    suspend fun softDeleteConversation(conversationId: Long, at: Long)

    @Query("UPDATE messages SET deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<Long>)
}

@Dao
interface TransactionDao {
    @Query(
        "SELECT t.* FROM transactions t JOIN messages m ON m.id = t.messageId " +
            "WHERE m.deletedAt IS NULL ORDER BY t.timestamp DESC",
    )
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transaction: TransactionEntity): Long

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface BillDao {
    @Query(
        "SELECT b.* FROM bills b LEFT JOIN messages m ON m.id = b.messageId " +
            "WHERE (m.id IS NULL OR m.deletedAt IS NULL) ORDER BY b.paid ASC, b.dueDate IS NULL, b.dueDate ASC",
    )
    fun observeAll(): Flow<List<BillEntity>>

    @Query("SELECT * FROM bills WHERE paid = 0 AND dueDate IS NOT NULL AND dueDate BETWEEN :from AND :to")
    suspend fun dueBetween(from: Long, to: Long): List<BillEntity>

    @Query("SELECT * FROM bills")
    suspend fun getAll(): List<BillEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bill: BillEntity): Long

    @Upsert
    suspend fun upsert(bill: BillEntity)

    @Query("UPDATE bills SET paid = :paid WHERE id = :id")
    suspend fun setPaid(id: Long, paid: Boolean)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY priority DESC")
    suspend fun getAll(): List<RuleEntity>

    @Upsert
    suspend fun upsert(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BlockedSenderDao {
    @Query("SELECT * FROM blocked_senders ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BlockedSenderEntity>>

    @Query("SELECT * FROM blocked_senders")
    suspend fun getAll(): List<BlockedSenderEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_senders WHERE addressKey = :key)")
    suspend fun isBlocked(key: String): Boolean

    @Upsert
    suspend fun upsert(entity: BlockedSenderEntity)

    @Query("DELETE FROM blocked_senders WHERE addressKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface MmsPartDao {
    @Query("SELECT * FROM mms_parts WHERE messageId = :messageId ORDER BY id")
    suspend fun forMessage(messageId: Long): List<MmsPartEntity>

    @Query("SELECT p.* FROM mms_parts p JOIN messages m ON m.id = p.messageId WHERE m.conversationId = :conversationId ORDER BY p.id")
    fun observeForConversation(conversationId: Long): Flow<List<MmsPartEntity>>

    @Query("SELECT * FROM mms_parts WHERE messageId IN (:messageIds)")
    suspend fun forMessages(messageIds: List<Long>): List<MmsPartEntity>

    @Insert
    suspend fun insert(part: MmsPartEntity): Long

    @Query("SELECT * FROM mms_parts")
    suspend fun getAll(): List<MmsPartEntity>
}

@Dao
interface ScheduledDao {
    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' ORDER BY sendAt ASC")
    fun observePending(): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING' AND conversationId = :conversationId ORDER BY sendAt ASC")
    fun observePendingFor(conversationId: Long): Flow<List<ScheduledMessageEntity>>

    @Query("SELECT * FROM scheduled_messages WHERE status = 'PENDING'")
    suspend fun getPending(): List<ScheduledMessageEntity>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun getById(id: Long): ScheduledMessageEntity?

    @Insert
    suspend fun insert(row: ScheduledMessageEntity): Long

    @Query("UPDATE scheduled_messages SET authTag = :tag WHERE id = :id")
    suspend fun setTag(id: Long, tag: String)

    @Query("UPDATE scheduled_messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: ScheduledStatus)

    /** Atomic claim: returns 1 only for the single caller that moved it out of [from]. */
    @Query("UPDATE scheduled_messages SET status = :to WHERE id = :id AND status = :from")
    suspend fun transition(id: Long, from: ScheduledStatus, to: ScheduledStatus): Int

    @Query("SELECT * FROM scheduled_messages WHERE status IN ('FAILED', 'CANCELLED') AND attachmentName IS NOT NULL")
    suspend fun getFailedWithAttachment(): List<ScheduledMessageEntity>

    @Query("UPDATE scheduled_messages SET attachmentName = NULL WHERE id = :id")
    suspend fun clearAttachment(id: Long)

    @Query("DELETE FROM scheduled_messages WHERE status != 'PENDING' AND sendAt < :before")
    suspend fun deleteFinishedBefore(before: Long): Int
}
