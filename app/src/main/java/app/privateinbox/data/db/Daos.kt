package app.privateinbox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.privateinbox.domain.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE archived = 0 ORDER BY pinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        "SELECT * FROM conversations WHERE archived = 0 AND category = :category " +
            "ORDER BY pinned DESC, lastMessageAt DESC",
    )
    fun observeByCategory(category: Category): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Delete
    suspend fun delete(conversation: ConversationEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE otpCode IS NOT NULL ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentOtps(limit: Int = 20): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET category = :category, matchedRuleId = :ruleId WHERE id = :id")
    suspend fun updateCategory(id: Long, category: Category, ruleId: String?)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE type = 'DEBIT' AND timestamp >= :since")
    fun observeSpentSince(since: Long): Flow<Long>

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY priority DESC")
    suspend fun getEnabled(): List<RuleEntity>

    @Upsert
    suspend fun upsert(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: String)
}
