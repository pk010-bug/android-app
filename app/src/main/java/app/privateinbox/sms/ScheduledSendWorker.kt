package app.privateinbox.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.privateinbox.PrivateInboxApp
import app.privateinbox.security.SafeLog

/**
 * Fires a scheduled message. It carries only the row id; [SmsSender.sendScheduled] re-verifies
 * the Keystore tag minted when the user tapped Schedule, then claims the row atomically.
 */
class ScheduledSendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        if (id <= 0) return Result.failure()
        val c = (applicationContext as PrivateInboxApp).container
        val outcome = SmsSender(applicationContext, c.messages, c.database).sendScheduled(id)
        SafeLog.i(TAG, "scheduled send outcome=$outcome")
        if (outcome == SmsSender.ScheduledOutcome.FAILED || outcome == SmsSender.ScheduledOutcome.REJECTED) {
            c.messageNotifier.showScheduledFailure(id)
        }
        return if (outcome == SmsSender.ScheduledOutcome.TOO_EARLY) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_ID = "scheduled_id"
        private const val TAG = "ScheduledSendWorker"
    }
}
