package app.privateinbox.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.privateinbox.domain.Category
import app.privateinbox.domain.TransactionType

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["address"], unique = true)],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised sender address / short code. */
    val address: String,
    val displayName: String? = null,
    val category: Category = Category.UNKNOWN,
    val lastMessagePreview: String = "",
    val lastMessageAt: Long = 0,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("timestamp"), Index("category")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** Row id in the system Telephony provider, if mirrored from there. */
    val providerId: Long? = null,
    val address: String,
    val body: String,
    val timestamp: Long,
    val incoming: Boolean,
    val read: Boolean = false,
    val category: Category = Category.UNKNOWN,
    /** Id of the rule that produced [category], for "why is this here?" transparency. */
    val matchedRuleId: String? = null,
    val otpCode: String? = null,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("messageId"), Index("timestamp")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long?,
    /** Amount in minor units (paise / cents) to avoid floating point. */
    val amountMinor: Long,
    val currency: String = "INR",
    val type: TransactionType,
    val merchant: String? = null,
    val accountHint: String? = null,
    val timestamp: Long,
)

/** A user-created categorisation rule; overrides bundled defaults when priorities tie. */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val category: Category,
    val description: String = "",
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** Newline-separated regex lists — kept as plain text so users can read/export them. */
    val senderPatterns: String = "",
    val bodyPatterns: String = "",
    val excludeBodyPatterns: String = "",
)
