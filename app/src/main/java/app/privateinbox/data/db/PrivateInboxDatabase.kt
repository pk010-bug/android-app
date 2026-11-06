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
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        TransactionEntity::class,
        RuleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class PrivateInboxDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun transactionDao(): TransactionDao
    abstract fun ruleDao(): RuleDao

    companion object {
        private const val NAME = "privateinbox.db"
        private const val TAG = "PrivateInboxDatabase"

        @Volatile private var instance: PrivateInboxDatabase? = null

        fun get(context: Context): PrivateInboxDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(app: Context): PrivateInboxDatabase {
            System.loadLibrary("sqlcipher")
            val keys = DatabaseKeyManager(app)
            val passphrase = try {
                keys.getOrCreatePassphrase()
            } catch (e: DatabaseKeyManager.KeyUnavailableException) {
                // Keystore key lost (e.g. device credential reset / restore to new device).
                // The encrypted DB is unrecoverable by design. The system SMS provider remains the
                // source of truth for messages, so start a fresh encrypted cache.
                SafeLog.e(TAG, "database key unavailable, resetting local cache", e)
                app.deleteDatabase(NAME)
                keys.destroy()
                keys.getOrCreatePassphrase()
            }
            // Hand SQLCipher its own copy of the key and wipe ours.
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            passphrase.fill(0)
            return Room.databaseBuilder(app, PrivateInboxDatabase::class.java, NAME)
                .openHelperFactory(factory)
                .build()
        }
    }
}
