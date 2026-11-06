package app.privateinbox.mms

/**
 * F-04: when an incoming MMS may be fetched (pure, unit-tested).
 *
 * MMS content is NEVER downloaded automatically (RB-1), not even from contacts: a download happens
 * only when the user taps Download on the placeholder message, and then:
 *  - never for a blocked sender (fetching would tell the sender's MMSC the number is live, and
 *    pulls attacker-chosen media onto the phone);
 *  - for an unknown sender (not a contact, and no message ever sent to them) only after a warning;
 *  - never above [MAX_BYTES] or for a content location that isn't an http(s) URL.
 * The fetch itself is done by the system MMS service over the carrier's MMS APN; PrivateInbox holds
 * no network permission. Many carriers' MMSCs only speak plain http; that is outside our control.
 */
object MmsDownloadPolicy {
    const val MAX_BYTES = 5L * 1024 * 1024

    /** A download with no callback after this long is treated as failed and can be retried. */
    const val STUCK_AFTER_MS = 10L * 60 * 1000

    /** How long a handled-notification receipt is kept for de-duplication (HB-3). */
    const val RECEIPT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * HB-3: identity of one MMS notification: subscription, transaction id (as stored, max 100
     * chars) and trimmed content location. Must match the back-fill in MigrationSql.V5_TO_V6.
     * Stored only in the encrypted database, never logged.
     */
    fun receiptKey(subscriptionId: Int?, transactionId: String, contentLocation: String): String =
        "${subscriptionId ?: -1}|${transactionId.take(100)}|${contentLocation.trim()}"

    /** What the placeholder shows. */
    enum class UiState { DOWNLOAD, DOWNLOADING, RETRY }

    fun uiState(state: app.privateinbox.data.db.PendingMmsState, updatedAt: Long, now: Long): UiState = when (state) {
        app.privateinbox.data.db.PendingMmsState.WAITING -> UiState.DOWNLOAD
        app.privateinbox.data.db.PendingMmsState.FAILED -> UiState.RETRY
        app.privateinbox.data.db.PendingMmsState.DOWNLOADING,
        app.privateinbox.data.db.PendingMmsState.COMPLETING,
        -> if (now - updatedAt >= STUCK_AFTER_MS) UiState.RETRY else UiState.DOWNLOADING
    }

    enum class Decision {
        /** Known sender: download right away on tap (still only on tap). */
        ALLOW,

        /** Unknown sender: confirm with a warning first. */
        WARN_UNKNOWN_SENDER,
        REFUSE_BLOCKED,
        REFUSE_TOO_LARGE,
        REFUSE_BAD_LOCATION,
    }

    fun decide(blocked: Boolean, knownSender: Boolean, sizeBytes: Long?, contentLocation: String): Decision = when {
        blocked -> Decision.REFUSE_BLOCKED
        !isFetchableLocation(contentLocation) -> Decision.REFUSE_BAD_LOCATION
        sizeBytes != null && sizeBytes > MAX_BYTES -> Decision.REFUSE_TOO_LARGE
        !knownSender -> Decision.WARN_UNKNOWN_SENDER
        else -> Decision.ALLOW
    }

    fun isFetchableLocation(location: String): Boolean {
        val l = location.trim()
        val scheme = l.substringBefore("://", "").lowercase()
        return (scheme == "http" || scheme == "https") && l.length in 12..2048 && l.none { it.isWhitespace() || it.code < 0x20 }
    }

    /** Placeholder text stored as the message body until the user downloads the MMS. */
    fun placeholderBody(sizeBytes: Long?, blocked: Boolean): String = when {
        blocked -> "Picture message from a blocked sender. Not downloaded."
        sizeBytes != null && sizeBytes > 0 -> "Picture message (${(sizeBytes + 1023) / 1024} KB). Not downloaded yet."
        else -> "Picture message. Not downloaded yet."
    }
}
