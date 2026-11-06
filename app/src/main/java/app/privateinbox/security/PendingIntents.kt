package app.privateinbox.security

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * All PendingIntents go through here:
 *  - FLAG_IMMUTABLE always (no other app can fill in or redirect them).
 *  - The wrapped Intent must be EXPLICIT (component set to one of our classes), otherwise we throw.
 */
object PendingIntents {

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, requireExplicit(context, intent), FLAGS)

    fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, requireExplicit(context, intent), FLAGS)

    private fun requireExplicit(context: Context, intent: Intent): Intent {
        val component = requireNotNull(intent.component) { "PendingIntent must wrap an explicit Intent" }
        require(component.packageName == context.packageName) { "PendingIntent must target our own package" }
        return intent
    }
}
