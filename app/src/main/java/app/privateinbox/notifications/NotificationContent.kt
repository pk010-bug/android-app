package app.privateinbox.notifications

/**
 * What an incoming-message notification may show (pure; unit-tested in NotificationContentTest).
 *
 *  - F-08: app lock on + "content-free notifications" on: no sender, no text, no code, no Copy
 *    action. Only "New message"; reading it needs the app (and therefore the app lock).
 *  - "Hide OTPs" on: a message with a code says it contains one instead of showing it.
 *  - Otherwise the usual OTP notification (code + Copy) or the message text.
 */
object NotificationContent {
    sealed interface Plan {
        /** Dedicated OTP notification with the code and a Copy action. */
        data object OtpWithCode : Plan
        data class Message(val title: String, val text: String, val suspicious: Boolean) : Plan
    }

    const val CONTENT_FREE_TITLE = "New message"
    const val CONTENT_FREE_TEXT = "Open PrivateInbox to read it."
    const val HIDDEN_CODE_TEXT = "Contains a code. Open to view."

    fun plan(
        appLockOn: Boolean,
        contentFreeWhenLocked: Boolean,
        hideOtp: Boolean,
        hasOtp: Boolean,
        suspicious: Boolean,
        scamWarnings: Boolean,
        displayName: String,
        body: String,
    ): Plan = when {
        appLockOn && contentFreeWhenLocked -> Plan.Message(CONTENT_FREE_TITLE, CONTENT_FREE_TEXT, suspicious = false)
        hasOtp && !suspicious -> Plan.OtpWithCode
        else -> Plan.Message(
            title = displayName,
            text = if (hideOtp && hasOtp) HIDDEN_CODE_TEXT else body,
            suspicious = suspicious && scamWarnings,
        )
    }
}
