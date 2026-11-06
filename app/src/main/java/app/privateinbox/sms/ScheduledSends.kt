package app.privateinbox.sms

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.db.ScheduledStatus
import java.util.concurrent.TimeUnit

/**
 * Scheduling plumbing that does NOT send: enqueue/cancel/reconcile WorkManager jobs.
 * WorkManager persists jobs across reboots (its own boot receiver), so nothing of ours listens
 * for BOOT_COMPLETED. Delivery time is best-effort (Doze may delay it by minutes).
 */
object ScheduledSends {
    private fun workName(id: Long) = "scheduled-send-$id"

    fun enqueue(context: Context, id: Long, sendAt: Long, now: Long = System.currentTimeMillis()) {
        val req = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setInitialDelay((sendAt - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ScheduledSendWorker.KEY_ID to id))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, req)
    }

    /** Cancelling is always safe, so it needs no confirmation token. */
    suspend fun cancel(context: Context, db: PrivateInboxDatabase, id: Long) {
        db.scheduledDao().transition(id, ScheduledStatus.PENDING, ScheduledStatus.CANCELLED)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    /** Re-enqueue any pending rows whose job is missing (e.g. after "clear cache" or an update). */
    suspend fun reconcile(context: Context, db: PrivateInboxDatabase) {
        db.scheduledDao().getPending().forEach { enqueue(context, it.id, it.sendAt) }
        db.scheduledDao().deleteFinishedBefore(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
    }

    const val TAG = "scheduled-send"
}
