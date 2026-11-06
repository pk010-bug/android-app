package app.privateinbox.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.privateinbox.PrivateInboxApp
import app.privateinbox.security.SafeLog
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** All background work is local: import from the SMS provider, recycle-bin purge, bill reminders. */
object Workers {
    private const val IMPORT = "provider-import"
    private const val PURGE = "recycle-purge"
    private const val BILLS = "bill-reminders"

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PURGE,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PurgeWorker>(1, TimeUnit.DAYS).build(),
        )
        wm.enqueueUniquePeriodicWork(
            BILLS,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BillReminderWorker>(12, TimeUnit.HOURS).build(),
        )
    }

    /** Re-arms scheduled sends on app start (WorkManager already survives reboot; this is belt and braces). */
    fun reconcileScheduled(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "scheduled-reconcile",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduledReconcileWorker>().build(),
        )
    }

    fun importNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMPORT,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ImportWorker>().build(),
        )
    }
}

/** Copies existing SMS from the system provider into the encrypted store (incremental). */
class ImportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PrivateInboxApp).container
        var last = c.settings.lastImportedProviderId.first()
        var total = 0
        while (true) {
            val batch = c.telephony.readSince(last, BATCH)
            if (batch.isEmpty()) break
            batch.forEach { p ->
                if (p.incoming) {
                    c.messages.ingestIncoming(p.address, p.body, p.date, p.subscriptionId, p.id, read = p.read)
                } else {
                    c.messages.importOutgoing(p)
                }
                last = maxOf(last, p.id)
            }
            total += batch.size
            c.settings.setLastImportedProviderId(last)
        }
        c.settings.setImportDone(true)
        SafeLog.i(TAG, "import finished, rows=$total")
        return Result.success()
    }

    private companion object {
        const val TAG = "ImportWorker"
        const val BATCH = 300
    }
}

class PurgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PrivateInboxApp).container
        val n = c.messages.purgeExpired(c.settings.recycleBinDays.first())
        SafeLog.i("PurgeWorker", "purged=$n")
        return Result.success()
    }
}

class BillReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PrivateInboxApp).container
        if (!c.settings.billReminders.first()) return Result.success()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(3).atStartOfDay(zone).toInstant().toEpochMilli()
        c.database.billDao().dueBetween(from, to).forEach { b ->
            val due = java.time.Instant.ofEpochMilli(b.dueDate ?: return@forEach).atZone(zone).toLocalDate()
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
            val whenText = when (days) {
                0L -> "due today"
                1L -> "due tomorrow"
                else -> "due in $days days"
            }
            c.messageNotifier.showBillReminder(b.id, "Bill $whenText", b.biller, c.settings.hideOnLockScreen.first())
        }
        return Result.success()
    }
}

class ScheduledReconcileWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PrivateInboxApp).container
        app.privateinbox.sms.ScheduledSends.reconcile(applicationContext, c.database)
        return Result.success()
    }
}
