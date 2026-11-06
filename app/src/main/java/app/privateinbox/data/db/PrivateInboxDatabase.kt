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
        MmsReceiptEntity::class,
    ],
    version = 6,
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
         * Thrown (and NOT cached) when the Keystore failed, possibly transiently: nothing was deleted,
         * and the next call tries again. The UI shows a "try again" screen; receivers leave the
         * message in the system SMS store, which the provider import picks up later.
         */
        class DatabaseTemporarilyUnavailableException(cause: Throwable) :
            IllegalStateException("encrypted database temporarily unavailable", cause)

        /**
         * RB-2: thrown (and NOT cached) when the database key looks permanently lost. Nothing is
         * deleted: the encrypted file stays on disk until the user, after reading what a reset
         * means, confirms [resetAfterUserConfirmation]. Until then the app shows a recovery screen.
         */
        class DatabaseKeyLostException(cause: Throwable?) :
            IllegalStateException("encrypted database key unavailable", cause)

        fun get(context: Context): PrivateInboxDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private const val ATTEMPTS = 3

        private fun build(app: Context): PrivateInboxDatabase {
            System.loadLibrary("sqlcipher")
            val keys = DatabaseKeyManager(app)
            val passphrase = obtainPassphrase(
                load = keys::getOrCreatePassphrase,
                wrappedKeyExists = keys.hasWrappedKey(),
                databaseExists = app.getDatabasePath(NAME).exists(),
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
         * RB-2: the ONLY code path that deletes the encrypted database (and its wrapped key). Called
         * from the recovery screen after the user explicitly confirmed (and re-authenticated where
         * a screen lock exists). Messages stay in the system SMS store; local-only data (rules,
         * categories, recycle bin, scheduled messages, finance entries) is lost.
         */
        fun resetAfterUserConfirmation(context: Context) = synchronized(this) {
            val app = context.applicationContext
            runCatching { instance?.close() }
            instance = null
            app.deleteDatabase(NAME)
            DatabaseKeyManager(app).destroy()
            SafeLog.w(TAG, "encrypted local cache reset by the user")
        }

        /**
         * Pure (unit-tested). A database file without its wrapped key can never be opened again, and
         * creating a new key would only hide that, so it is reported as a lost key instead.
         */
        internal fun obtainPassphrase(
            load: () -> ByteArray,
            wrappedKeyExists: Boolean,
            databaseExists: Boolean,
            sleep: (Long) -> Unit = Thread::sleep,
            log: (String) -> Unit = { SafeLog.w(TAG, it) },
        ): ByteArray {
            if (databaseExists && !wrappedKeyExists) throw DatabaseKeyLostException(null)
            return passphraseWithRetry(load, sleep, log)
        }

        /**
         * Pure retry policy (unit-tested): possibly-transient failures are retried [ATTEMPTS] times
         * with a short backoff and then surface as [DatabaseTemporarilyUnavailableException]; a
         * definite loss surfaces as [DatabaseKeyLostException]. Neither deletes anything.
         */
        internal fun passphraseWithRetry(
            load: () -> ByteArray,
            sleep: (Long) -> Unit = Thread::sleep,
            log: (String) -> Unit = { SafeLog.w(TAG, it) },
        ): ByteArray {
            var last: Throwable? = null
            repeat(ATTEMPTS) { attempt ->
                try {
                    return load()
                } catch (e: DatabaseKeyManager.KeyUnavailableException) {
                    log("database key unavailable; waiting for the user to decide")
                    throw DatabaseKeyLostException(e)
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
