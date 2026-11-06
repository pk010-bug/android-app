package app.privateinbox.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable, versioned backup schema (independent of Room entity layout). */
@Serializable
data class BackupPayload(
    val format: Int = 1,
    val createdAt: Long,
    val conversations: List<BConversation>,
    val messages: List<BMessage>,
    val rules: List<BRule>,
    val blocked: List<BBlocked>,
    val paidBillMessageIds: List<Long> = emptyList(),
)

@Serializable
data class BConversation(
    val id: Long,
    val addressKey: String,
    val address: String,
    val displayName: String? = null,
    val categoryOverride: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
)

@Serializable
data class BMessage(
    val id: Long,
    val conversationId: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val incoming: Boolean,
    val read: Boolean,
    val status: String,
    val subscriptionId: Int? = null,
    val deletedAt: Long? = null,
)

@Serializable
data class BRule(
    val id: String,
    val category: String,
    val description: String = "",
    val priority: Int = 0,
    val enabled: Boolean = true,
    val senderPatterns: String = "",
    val bodyPatterns: String = "",
    val excludeBodyPatterns: String = "",
)

@Serializable
data class BBlocked(val addressKey: String, val address: String, val createdAt: Long)

object BackupJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(p: BackupPayload): ByteArray = json.encodeToString(BackupPayload.serializer(), p).toByteArray(Charsets.UTF_8)
    fun decode(b: ByteArray): BackupPayload = json.decodeFromString(BackupPayload.serializer(), b.toString(Charsets.UTF_8))
}
