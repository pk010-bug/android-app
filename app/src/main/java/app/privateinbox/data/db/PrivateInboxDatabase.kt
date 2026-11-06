package app.privateinbox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.privateinbox.security.DatabaseKeyManager
import app.privateinbox.security.SafeLog
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Single on-device database, encrypted at rest with SQLCipher (AES-256).
 * The passphrase is random, wrapped by an AndroidKeyStore key (see [DatabaseKeyManager]),
 * and stored in noBackupFilesDir. The DB is also excluded from all backups.
 *
 * v1 shipped (0.1.0 sideload). Every schema change MUST add a Migration below; there is no
 * destructive fallback because this is user data. Migrations are verified against the exported
 * schemas in app/schemas (see MigrationSchemaTest).
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        TransactionEntity::class,
        RuleEntity::class,
        BillEntity::class,
        BlockedSenderEntity::class,
        MmsPartEntity::class,
        ScheduledMessageEntity::class,
        PendingMmsEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PrivateInboxDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun transactionDao(): TransactionDao
    abstract fun ruleDao(): RuleDao
    abstract fun billDao(): BillDao
    abstract fun blockedSenderDao(): BlockedSenderDao
    abstract fun mmsPartDao(): MmsPartDao
    abstract fun scheduledDao(): ScheduledDao
    abstract fun pendingMmsDao(): PendingMmsDao

    companion object {
        private const val NAME = "privateinbox.db"
        private const val TAG = "PrivateInboxDatabase"

        @Volatile private var instance: PrivateInboxDatabase? = null

        /**
         * Thrown (and NOT cached) when the Keystore failed transiently: nothing was deleted, and the
         * next call tries again. The UI shows a "try again" screen; receivers leave the message in
         * the system SMS store, which the provider import picks up later.
         */
        class DatabaseTemporarilyUnavailableException(cause: Throwable) :
            IllegalStateException("encrypted database temporarily unavailable", cause)

        fun get(context: Context): PrivateInboxDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private const val ATTEMPTS = 3

        private fun build(app: Context): PrivateInboxDatabase {
            System.loadLibrary("sqlcipher")
            val keys = DatabaseKeyManager(app)
            val passphrase = passphraseWithRetry(
                load = keys::getOrCreatePassphrase,
                onPermanentLoss = { e ->
                    // F-13: only a PERMANENT key loss (key invalidated / deleted, tag mismatch,
                    // corrupt wrapped file; e.g. after a credential reset or restore to a new phone)
                    // wipes. The encrypted DB is unrecoverable then; the system SMS provider remains
                    // the source of truth for messages, so start a fresh encrypted cache.
                    SafeLog.e(TAG, "database key permanently lost, resetting local cache", e)
                    app.deleteDatabase(NAME)
                    keys.destroy()
                    keys.getOrCreatePassphrase()
                },
            )
            // Hand SQLCipher its own copy of the key and wipe ours.
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            passphrase.fill(0)
            return Room.databaseBuilder(app, PrivateInboxDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .addMigrations(*Migrations.ALL)
                .build()
        }

        /**
         * Pure retry policy (unit-tested): transient failures are retried [ATTEMPTS] times with a
         * short backoff and then surface as [DatabaseTemporarilyUnavailableException]; only
         * [DatabaseKeyManager.KeyUnavailableException] reaches [onPermanentLoss].
         */
        internal fun passphraseWithRetry(
            load: () -> ByteArray,
            onPermanentLoss: (DatabaseKeyManager.KeyUnavailableException) -> ByteArray,
            sleep: (Long) -> Unit = Thread::sleep,
            log: (String) -> Unit = { SafeLog.w(TAG, it) },
        ): ByteArray {
            var last: Throwable? = null
            repeat(ATTEMPTS) { attempt ->
                try {
                    return load()
                } catch (e: DatabaseKeyManager.KeyUnavailableException) {
                    return onPermanentLoss(e)
                } catch (e: DatabaseKeyManager.KeyTemporarilyUnavailableException) {
                    last = e
                    log("database key temporarily unavailable (attempt ${attempt + 1})")
                    if (attempt < ATTEMPTS - 1) sleep(200L shl attempt)
                }
            }
            throw DatabaseTemporarilyUnavailableException(last!!)
        }
    }
}
