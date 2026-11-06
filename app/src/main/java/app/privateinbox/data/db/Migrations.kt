package app.privateinbox.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema migrations. SQL lives in [MigrationSql] so a JVM test can replay it against SQLite. */
object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MigrationSql.V1_TO_V2.forEach(db::execSQL)
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MigrationSql.V2_TO_V3.forEach(db::execSQL)
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MigrationSql.V3_TO_V4.forEach(db::execSQL)
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MigrationSql.V4_TO_V5.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}

object MigrationSql {
    /** 0.1.0 → 0.2.0: groups, MMS flags/parts, scheduled send. */
    val V1_TO_V2: List<String> = listOf(
        "ALTER TABLE `conversations` ADD COLUMN `isGroup` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `conversations` ADD COLUMN `members` TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE `messages` ADD COLUMN `isMms` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `messages` ADD COLUMN `mmsSubject` TEXT",
        "CREATE TABLE IF NOT EXISTS `mms_parts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`messageId` INTEGER NOT NULL, `mimeType` TEXT NOT NULL, `fileName` TEXT, `text` TEXT, " +
            "`sizeBytes` INTEGER NOT NULL, FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_mms_parts_messageId` ON `mms_parts` (`messageId`)",
        "CREATE TABLE IF NOT EXISTS `scheduled_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`recipients` TEXT NOT NULL, `body` TEXT NOT NULL, `sendAt` INTEGER NOT NULL, `subscriptionId` INTEGER, " +
            "`createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `authTag` TEXT NOT NULL, `conversationId` INTEGER)",
        "CREATE INDEX IF NOT EXISTS `index_scheduled_messages_sendAt` ON `scheduled_messages` (`sendAt`)",
        "CREATE INDEX IF NOT EXISTS `index_scheduled_messages_status` ON `scheduled_messages` (`status`)",
    )

    /** 0.2.0 → 1.0.0: scheduled MMS attachment. */
    val V2_TO_V3: List<String> = listOf(
        "ALTER TABLE `scheduled_messages` ADD COLUMN `attachmentName` TEXT",
    )

    /** 1.0.2 → 1.0.3: MMS is not auto-downloaded from unknown senders (F-04); notifications wait here. */
    val V3_TO_V4: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `pending_mms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`messageId` INTEGER NOT NULL, `contentLocation` TEXT NOT NULL, `transactionId` TEXT NOT NULL, " +
            "`subscriptionId` INTEGER, `sizeBytes` INTEGER, `state` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_mms_messageId` ON `pending_mms` (`messageId`)",
    )

    /** 1.0.3: stuck-download timeout, per-attempt tag, carrier "deferred" bookkeeping. */
    val V4_TO_V5: List<String> = listOf(
        "ALTER TABLE `pending_mms` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `pending_mms` ADD COLUMN `attempt` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `pending_mms` ADD COLUMN `deferredSent` INTEGER NOT NULL DEFAULT 0",
    )
}
