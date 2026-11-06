package app.privateinbox.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.privateinbox.MainActivity
import app.privateinbox.R
import app.privateinbox.navigation.DeepLinks
import app.privateinbox.security.PendingIntents

/**
 * OTP notifications.
 *  - The OTP digits are never shown on the lock screen:
 *      - "Hide OTPs" ON (default): VISIBILITY_SECRET, so nothing at all is shown on a locked
 *        screen, and the code is left out of the notification text entirely (Copy only).
 *      - "Hide OTPs" OFF: VISIBILITY_PRIVATE plus a public version with no code, so the lock
 *        screen shows "Verification code received" and the digits only appear once unlocked.
 *  - Content + action PendingIntents are immutable and explicit (see PendingIntents).
 *  - The "Copy" action goes to a non-exported receiver that marks the clip sensitive.
 */
class OtpNotifier(private val context: Context) {

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "One-time passwords", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Verification codes from SMS. Codes are hidden on the lock screen."
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** [conversationId] must be a real Room id; it is validated again when the tap arrives. */
    fun show(notificationId: Int, conversationId: Long, code: String, hideCode: Boolean, hideOnLockScreen: Boolean = true) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()

        val open = PendingIntents.activity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_ROUTE)
                .putExtra(DeepLinks.EXTRA_ROUTE, "conversation/$conversationId")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        val copy = PendingIntents.broadcast(
            context,
            notificationId,
            Intent(context, CopyOtpReceiver::class.java)
                .setAction(CopyOtpReceiver.ACTION_COPY)
                .putExtra(CopyOtpReceiver.EXTRA_CODE, code)
                .putExtra(CopyOtpReceiver.EXTRA_NOTIFICATION_ID, notificationId),
        )

        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("Verification code received")
            .setContentText("Unlock to view")
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(if (hideCode) "Verification code received" else "Code: $code")
            .setContentText(if (hideCode) "Tap Copy to copy it securely" else "Tap Copy to copy")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(
                if (hideCode || hideOnLockScreen) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE,
            )
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .addAction(0, "Copy", copy)
            .setAutoCancel(true)
            .setTimeoutAfter(OTP_TTL_MS)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    companion object {
        const val CHANNEL_ID = "otp"
        const val ACTION_OPEN_ROUTE = "app.privateinbox.action.OPEN_ROUTE"
        const val OTP_TTL_MS = 10 * 60 * 1000L
    }
}
