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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
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
}
