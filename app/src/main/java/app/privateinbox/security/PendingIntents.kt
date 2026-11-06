package app.privateinbox.security

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * All PendingIntents go through here:
 *  - FLAG_IMMUTABLE always (no other app can fill in or redirect them).
 *  - The wrapped Intent must be EXPLICIT (component set to one of our classes), otherwise we throw.
 *  - F-15: every PendingIntent has a [Kind] and an id. The request code puts the kind in its own
 *    range (top bits) so, e.g., an OTP notification for message 42 can never replace the "open"
 *    action of conversation 42 under FLAG_UPDATE_CURRENT. On Android 10+ the kind and the full id
 *    are also set as the Intent identifier, which makes the PendingIntents distinct even when the
 *    id doesn't fit the request-code range.
 */
object PendingIntents {

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** One range per PendingIntent purpose. [slot] must be unique and in 1..31. */
    enum class Kind(val slot: Int) {
        OTP_OPEN(1),
        OTP_COPY(2),
        MESSAGE_OPEN(3),
        BILL_OPEN(4),
        SCHEDULED_OPEN(5),
        WIDGET_OPEN(6),
        SMS_SENT(7),
        MMS_SENT(8),
        MMS_DOWNLOADED(9),
        MMS_ACK(10),
    }

    private const val ID_BITS = 26
    private const val ID_MASK = (1L shl ID_BITS) - 1

    /** Pure: request code for [kind] + [id]. Always non-negative; different kinds never collide. */
    fun requestCode(kind: Kind, id: Long): Int = (kind.slot shl ID_BITS) or (id and ID_MASK).toInt()

    /** Pure: identifier that makes two PendingIntents distinct on Android 10+ (Intent.filterEquals). */
    fun identifier(kind: Kind, id: Long): String = "${kind.name}:$id"

    fun activity(context: Context, kind: Kind, id: Long, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode(kind, id), prepare(context, kind, id, intent), FLAGS)

    fun broadcast(context: Context, kind: Kind, id: Long, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode(kind, id), prepare(context, kind, id, intent), FLAGS)

    private fun prepare(context: Context, kind: Kind, id: Long, intent: Intent): Intent {
        val component = requireNotNull(intent.component) { "PendingIntent must wrap an explicit Intent" }
        require(component.packageName == context.packageName) { "PendingIntent must target our own package" }
        if (Build.VERSION.SDK_INT >= 29) intent.identifier = identifier(kind, id)
        return intent
    }
}
