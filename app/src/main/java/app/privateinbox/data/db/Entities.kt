package app.privateinbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.privateinbox.domain.Category
import app.privateinbox.domain.MessageStatus
import app.privateinbox.domain.TransactionType

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["addressKey"], unique = true), Index("lastMessageAt")],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised grouping key, see AddressNormalizer. */
    val addressKey: String,
    /** Most recent raw address (what we reply to). */
    val address: String,
    val displayName: String? = null,
    val category: Category = Category.UNKNOWN,
    /** If set, user pinned this conversation to a category; overrides rules. */
    val categoryOverride: Category? = null,
    val lastMessagePreview: String = "",
    val lastMessageAt: Long = 0,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    /** Highest scam score among recent incoming messages (0-100). */
    val scamScore: Int = 0,
    /** User-created group: SMS is sent to each member individually (no MMS group yet). */
    @ColumnInfo(defaultValue = "0") val isGroup: Boolean = false,
    /** Newline-separated member addresses for groups; empty for 1:1. */
    @ColumnInfo(defaultValue = "") val members: String = "",
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
    indices = [
        Index("conversationId"),
        Index("timestamp"),
        Index("category"),
        Index("deletedAt"),
        Index(value = ["providerId", "incoming"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** Row id in the system Telephony provider (sms table), if mirrored from there. */
    val providerId: Long? = null,
    val address: String,
    val body: String,
    val timestamp: Long,
    val incoming: Boolean,
    val read: Boolean = false,
    val status: MessageStatus = if (incoming) MessageStatus.RECEIVED else MessageStatus.PENDING,
    val subscriptionId: Int? = null,
    val category: Category = Category.UNKNOWN,
    /** Id of the rule that produced [category], for "why is this here?" transparency. */
    val matchedRuleId: String? = null,
    val otpCode: String? = null,
    val scamScore: Int = 0,
    /** Comma-separated reason ids from ScamDetector. */
    val scamReasons: String = "",
    /** Recycle bin: non-null means soft-deleted at this time. */
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val isMms: Boolean = false,
    val mmsSubject: String? = null,
)

/** One part of an MMS (image or text). Files live in noBackupFilesDir/mms, never shared storage. */
@Entity(
    tableName = "mms_parts",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class MmsPartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val mimeType: String,
    /** File name inside the private mms directory (no paths), or null for text parts. */
    val fileName: String? = null,
    val text: String? = null,
    val sizeBytes: Long = 0,
)

/**
 * Download state of an MMS whose notification arrived but whose content was not fetched yet.
 * COMPLETING (stored as text, so no schema change): a downloaded PDU is being turned into the real
 * message; it lets exactly one completion callback win (HB-3).
 */
enum class PendingMmsState { WAITING, DOWNLOADING, FAILED, COMPLETING }

/**
 * F-04: an incoming MMS notification (M-Notification.ind) that has NOT been downloaded. MMS content
 * is fetched only when the user taps Download on the placeholder message [messageId] (never
 * automatically, from anyone); never for blocked senders, and only after a warning for unknown
 * senders. [contentLocation] is the sender-supplied MMSC URL; the system MMS
 * service (not this app) fetches it.
 *
 * v5: [updatedAt] is when [state] last changed (a DOWNLOADING row older than
 * MmsDownloadPolicy.STUCK_AFTER_MS can be retried), [attempt] tags each download so a late
 * callback from an earlier attempt can't overwrite a newer one, and [deferredSent] records that
 * the carrier was told "deferred" (so the final ack is M-Acknowledge.ind).
 */
@Entity(
    tableName = "pending_mms",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"], unique = true)],
)
data class PendingMmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val contentLocation: String,
    val transactionId: String,
    val subscriptionId: Int? = null,
    val sizeBytes: Long? = null,
    val state: PendingMmsState = PendingMmsState.WAITING,
    val createdAt: Long,
    @androidx.room.ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val attempt: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val deferredSent: Boolean = false,
)

/**
 * HB-3 (v6): one row per MMS notification already handled, keyed by
 * [app.privateinbox.mms.MmsDownloadPolicy.receiptKey] (subscription | transaction id | content
 * location). Inserting it is the atomic "first time we see this notification" claim, done in the
 * same transaction as the placeholder message, so duplicate or concurrent WAP pushes can never
 * create a second placeholder or a second download. Pruned after
 * [app.privateinbox.mms.MmsDownloadPolicy.RECEIPT_RETENTION_MS].
 */
@Entity(tableName = "mms_receipts")
data class MmsReceiptEntity(
    @PrimaryKey val dedupKey: String,
    val createdAt: Long,
)

enum class ScheduledStatus { PENDING, SENT, FAILED, CANCELLED }

/**
 * A message the user scheduled. [authTag] is an HMAC (AndroidKeyStore key) over the exact
 * recipients/body/time, created only when the user tapped "Schedule"; the worker refuses to
 * send anything whose tag does not verify.
 */
@Entity(tableName = "scheduled_messages", indices = [Index("sendAt"), Index("status")])
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Newline-separated recipients. */
    val recipients: String,
    val body: String,
    val sendAt: Long,
    val subscriptionId: Int? = null,
    val createdAt: Long,
    val status: ScheduledStatus = ScheduledStatus.PENDING,
    val authTag: String = "",
    val conversationId: Long? = null,
    /** Pre-compressed JPEG in the private mms store for a scheduled MMS; its SHA-256 is in [authTag]. */
    val attachmentName: String? = null,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"], unique = true), Index("timestamp")],
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
    val balanceMinor: Long? = null,
    val sender: String = "",
    val timestamp: Long,
)

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"], unique = true), Index("dueDate")],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long?,
    val biller: String,
    val amountMinor: Long? = null,
    /** Due date as epoch millis at local midnight, or null if not found. */
    val dueDate: Long? = null,
    val paid: Boolean = false,
    val createdAt: Long,
)

/** A user-created categorisation rule; user rules always outrank bundled defaults. */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val category: Category,
    val description: String = "",
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** Newline-separated regex lists, kept as plain text so users can read/export them. */
    val senderPatterns: String = "",
    val bodyPatterns: String = "",
    val excludeBodyPatterns: String = "",
)

@Entity(tableName = "blocked_senders")
data class BlockedSenderEntity(
    @PrimaryKey val addressKey: String,
    val address: String,
    val createdAt: Long,
)
