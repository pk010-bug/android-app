package app.privateinbox.domain

/** A single SMS as seen by the domain layer (independent of Room / Telephony). */
data class SmsMessage(
    val id: Long = 0,
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val incoming: Boolean = true,
)

/** Result of running the rule engine on a message. */
data class Classification(
    val category: Category,
    /** Id of the rule that matched, or null when nothing matched (UNKNOWN). */
    val ruleId: String?,
)

/** A one-time password found in a message. */
data class OtpMatch(
    val code: String,
    /** Index range of the code inside the message body, for highlighting. */
    val range: IntRange,
)

enum class TransactionType { DEBIT, CREDIT }

enum class MessageStatus { RECEIVED, PENDING, SENT, FAILED, DELIVERED }
