package app.privateinbox.calls

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Calls policy: PrivateInbox never places calls itself and does NOT hold CALL_PHONE.
 * The call button opens the system dialer pre-filled (ACTION_DIAL); the user presses call there.
 */
object Dialer {

    private val DIALABLE = Regex("^\\+?[0-9]{3,15}$")

    /** Returns a dialable number, or null for alphanumeric senders like "AX-HDFCBK". */
    fun normalize(address: String): String? {
        val cleaned = address.trim().replace(Regex("[\\s().\\-]"), "")
        return cleaned.takeIf { DIALABLE.matches(it) }
    }

    fun dialIntent(number: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))

    /** @return false if the number is not dialable or no dialer exists. */
    fun openDialer(context: Context, address: String): Boolean {
        val number = normalize(address) ?: return false
        return try {
            context.startActivity(dialIntent(number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
