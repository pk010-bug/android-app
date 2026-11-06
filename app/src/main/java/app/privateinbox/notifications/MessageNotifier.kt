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
 * Notifications for regular messages and bill reminders.
 * Lock screen only ever shows the public version ("New message"), never sender or text.
 */
class MessageNotifier(private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BILLS, "Bill reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
    }

    // ---- Per-conversation channels (sound / vibration / importance chosen in system settings) --

    fun conversationChannelId(conversationId: Long) = "conv_$conversationId"

    fun hasCustomChannel(conversationId: Long): Boolean =
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(conversationChannelId(conversationId)) != null

    /**
     * Creates a channel for one conversation, inheriting the private lock-screen policy. [label]
     * appears only in system notification settings; callers pass a contact name or a masked number.
     */
    fun createCustomChannel(conversationId: Long, label: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels()
        val ch = NotificationChannel(conversationChannelId(conversationId), label.take(40), NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            group = null
            if (Build.VERSION.SDK_INT >= 30) setConversationId(CHANNEL_MESSAGES, conversationId.toString())
        }
        nm.createNotificationChannel(ch)
    }

    fun deleteCustomChannel(conversationId: Long) {
        context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(conversationChannelId(conversationId))
    }

    /** System screen where the user picks the sound etc. for that conversation. */
    fun channelSettingsIntent(conversationId: Long): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, conversationChannelId(conversationId))

    fun showMessage(conversationId: Long, title: String, text: String, suspicious: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels()
        val open = openRoute("conversation/$conversationId", conversationId.toInt())
        val public = NotificationCompat.Builder(context, if (hasCustomChannel(conversationId)) conversationChannelId(conversationId) else CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("New message")
            .build()
        val channel = if (hasCustomChannel(conversationId)) conversationChannelId(conversationId) else CHANNEL_MESSAGES
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(if (suspicious) "Possible scam · $title" else title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(TAG_MESSAGE, conversationId.toInt(), n)
    }

    fun showBillReminder(id: Long, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels()
        val n = NotificationCompat.Builder(context, CHANNEL_BILLS)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_BILLS)
                    .setSmallIcon(R.drawable.ic_stat_lock)
                    .setContentTitle("Bill reminder")
                    .build(),
            )
            .setContentIntent(openRoute("alerts", 7_000_000 + id.toInt()))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(TAG_BILL, id.toInt(), n)
    }

    /** Content-free: says a scheduled message didn't go out; details are inside the locked app. */
    fun showScheduledFailure(id: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels()
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("Scheduled message not sent")
            .setContentText("Open PrivateInbox to review it.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openRoute("scheduled", 8_000_000 + id.toInt()))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(TAG_SCHEDULED, id.toInt(), n)
    }

    fun cancelConversation(conversationId: Long) {
        NotificationManagerCompat.from(context).cancel(TAG_MESSAGE, conversationId.toInt())
    }

    private fun openRoute(route: String, requestCode: Int) = PendingIntents.activity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .setAction(OtpNotifier.ACTION_OPEN_ROUTE)
            .putExtra(DeepLinks.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    )

    companion object {
        private const val TAG_SCHEDULED = "scheduled"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_BILLS = "bills"
        private const val TAG_MESSAGE = "msg"
        private const val TAG_BILL = "bill"
    }
}
