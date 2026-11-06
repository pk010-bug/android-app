package app.privateinbox.data.db

import app.privateinbox.TestFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Builds the shipped v1 schema from app/schemas/.../1.json, applies MigrationSql, and checks the
 * result is structurally identical to a fresh v2 database built from 2.json (columns, types,
 * nullability, defaults, PKs, indices, foreign keys). Also checks existing rows survive.
 */
class MigrationSchemaTest {
    private fun schema(v: Int): JsonObject = Json.parseToJsonElement(
        TestFiles.module("schemas/app.privateinbox.data.db.PrivateInboxDatabase/$v.json").readText(),
    ).jsonObject["database"]!!.jsonObject

    private fun create(conn: Connection, s: JsonObject) {
        conn.createStatement().use { st ->
            s["entities"]!!.jsonArray.forEach { e ->
                val o = e.jsonObject
                val table = o["tableName"]!!.jsonPrimitive.content
                st.execute(o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                o["indices"]?.jsonArray?.forEach { i ->
                    st.execute(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    private fun describe(conn: Connection): String = buildString {
        val tables = conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
        tables.forEach { t ->
            appendLine("TABLE $t")
            fun q(sql: String, cols: List<String>) = conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) rows += cols.joinToString("|") { rs.getString(it) ?: "null" }
                    rows.sorted().forEach { appendLine("  $it") }
                }
            }
            q("PRAGMA table_info(`$t`)", listOf("name", "type", "notnull", "dflt_value", "pk"))
            q("PRAGMA foreign_key_list(`$t`)", listOf("table", "from", "to", "on_delete"))
            q("SELECT name, sql FROM sqlite_master WHERE type='index' AND tbl_name='$t' AND sql IS NOT NULL", listOf("name", "sql"))
        }
    }

    @Test fun v1ToV2MatchesFreshV2() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(1))
            migrated.createStatement().use {
                it.execute("INSERT INTO conversations (id, addressKey, address, category, lastMessagePreview, lastMessageAt, unreadCount, pinned, archived, muted, scamScore) VALUES (1,'K','A','PERSONAL','hi',5,0,0,0,0,0)")
                it.execute("INSERT INTO messages (id, conversationId, address, body, timestamp, incoming, read, status, category, scamScore, scamReasons) VALUES (1,1,'A','hi',5,1,0,'RECEIVED','PERSONAL',0,'')")
            }
            MigrationSql.V1_TO_V2.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(2))
                assertEquals(describe(fresh), describe(migrated))
            }
            migrated.createStatement().use { st ->
                st.executeQuery("SELECT body, isMms FROM messages WHERE id = 1").use { rs ->
                    rs.next()
                    assertEquals("hi", rs.getString(1))
                    assertEquals(0, rs.getInt(2))
                }
                st.executeQuery("SELECT isGroup, members FROM conversations WHERE id = 1").use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                    assertEquals("", rs.getString(2))
                }
            }
        }
    }

    @Test fun v2ToV3MatchesFreshV3() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(2))
            migrated.createStatement().use {
                it.execute("INSERT INTO scheduled_messages (id, recipients, body, sendAt, createdAt, status, authTag) VALUES (1,'5551234','hi',9,1,'PENDING','tag')")
            }
            MigrationSql.V2_TO_V3.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(3))
                assertEquals(describe(fresh), describe(migrated))
            }
            migrated.createStatement().use { st ->
                st.executeQuery("SELECT authTag, attachmentName FROM scheduled_messages WHERE id = 1").use { rs ->
                    rs.next()
                    assertEquals("tag", rs.getString(1))
                    assertEquals(null, rs.getString(2))
                }
            }
        }
    }

    @Test fun v3ToV4MatchesFreshV4() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(3))
            migrated.createStatement().use {
                it.execute("INSERT INTO conversations (id, addressKey, address, category, lastMessagePreview, lastMessageAt, unreadCount, pinned, archived, muted, scamScore, isGroup, members) VALUES (1,'K','A','PERSONAL','hi',5,0,0,0,0,0,0,'')")
                it.execute("INSERT INTO messages (id, conversationId, address, body, timestamp, incoming, read, status, category, scamScore, scamReasons, isMms) VALUES (1,1,'A','hi',5,1,0,'RECEIVED','PERSONAL',0,'',0)")
            }
            MigrationSql.V3_TO_V4.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(4))
                assertEquals(describe(fresh), describe(migrated))
            }
            migrated.createStatement().use { st ->
                st.executeQuery("SELECT body FROM messages WHERE id = 1").use { rs -> rs.next(); assertEquals("hi", rs.getString(1)) }
                st.executeQuery("SELECT COUNT(*) FROM pending_mms").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            }
        }
    }

    @Test fun v1ChainToV4MatchesFreshV4() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(1))
            (MigrationSql.V1_TO_V2 + MigrationSql.V2_TO_V3 + MigrationSql.V3_TO_V4).forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(4))
                assertEquals(describe(fresh), describe(migrated))
            }
        }
    }

    @Test fun v1ChainToV3MatchesFreshV3() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(1))
            (MigrationSql.V1_TO_V2 + MigrationSql.V2_TO_V3).forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(3))
                assertEquals(describe(fresh), describe(migrated))
            }
        }
    }

    @Test fun v4ToV5MatchesFreshV5AndKeepsPendingRows() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(4))
            migrated.createStatement().use {
                it.execute("INSERT INTO conversations (id, addressKey, address, category, lastMessagePreview, lastMessageAt, unreadCount, pinned, archived, muted, scamScore, isGroup, members) VALUES (1,'K','A','PERSONAL','hi',5,0,0,0,0,0,0,'')")
                it.execute("INSERT INTO messages (id, conversationId, address, body, timestamp, incoming, read, status, category, scamScore, scamReasons, isMms) VALUES (1,1,'A','hi',5,1,0,'RECEIVED','PERSONAL',0,'',1)")
                it.execute("INSERT INTO pending_mms (id, messageId, contentLocation, transactionId, subscriptionId, sizeBytes, state, createdAt) VALUES (1,1,'http://mmsc.example/x','T1',NULL,10,'DOWNLOADING',5)")
            }
            MigrationSql.V4_TO_V5.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(5))
                assertEquals(describe(fresh), describe(migrated))
            }
            migrated.createStatement().use { st ->
                st.executeQuery("SELECT state, updatedAt, attempt, deferredSent FROM pending_mms WHERE id = 1").use { rs ->
                    rs.next()
                    assertEquals("DOWNLOADING", rs.getString(1))
                    assertEquals(0L, rs.getLong(2)) // => immediately counts as stuck, so it becomes retryable
                    assertEquals(0, rs.getInt(3))
                    assertEquals(0, rs.getInt(4))
                }
            }
        }
    }

    @Test fun v1ChainToV5MatchesFreshV5() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(1))
            (MigrationSql.V1_TO_V2 + MigrationSql.V2_TO_V3 + MigrationSql.V3_TO_V4 + MigrationSql.V4_TO_V5).forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(5))
                assertEquals(describe(fresh), describe(migrated))
            }
        }
    }

    @Test fun v5ToV6MatchesFreshV6AndBackfillsReceipts() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(5))
            migrated.createStatement().use {
                it.execute("INSERT INTO conversations (id, addressKey, address, category, lastMessagePreview, lastMessageAt, unreadCount, pinned, archived, muted, scamScore, isGroup, members) VALUES (1,'K','A','PERSONAL','hi',5,0,0,0,0,0,0,'')")
                it.execute("INSERT INTO messages (id, conversationId, address, body, timestamp, incoming, read, status, category, scamScore, scamReasons, isMms) VALUES (1,1,'A','hi',5,1,0,'RECEIVED','PERSONAL',0,'',1)")
                it.execute("INSERT INTO messages (id, conversationId, address, body, timestamp, incoming, read, status, category, scamScore, scamReasons, isMms) VALUES (2,1,'A','hi',6,1,0,'RECEIVED','PERSONAL',0,'',1)")
                it.execute("INSERT INTO pending_mms (id, messageId, contentLocation, transactionId, subscriptionId, sizeBytes, state, createdAt, updatedAt, attempt, deferredSent) VALUES (1,1,'http://mmsc.example/x','T1',NULL,10,'WAITING',5,5,0,1)")
                it.execute("INSERT INTO pending_mms (id, messageId, contentLocation, transactionId, subscriptionId, sizeBytes, state, createdAt, updatedAt, attempt, deferredSent) VALUES (2,2,'http://mmsc.example/y','T2',3,10,'FAILED',6,6,1,1)")
            }
            MigrationSql.V5_TO_V6.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(6))
                assertEquals(describe(fresh), describe(migrated))
            }
            val keys = migrated.createStatement().use { st ->
                st.executeQuery("SELECT dedupKey FROM mms_receipts ORDER BY dedupKey").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
            // The SQL backfill must produce exactly what the app computes for a re-sent notification.
            assertEquals(
                listOf(
                    app.privateinbox.mms.MmsDownloadPolicy.receiptKey(3, "T2", "http://mmsc.example/y"),
                    app.privateinbox.mms.MmsDownloadPolicy.receiptKey(null, "T1", "http://mmsc.example/x"),
                ).sorted(),
                keys,
            )
            // Pending rows survive untouched.
            migrated.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM pending_mms").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) }
            }
            // The receipt key is a primary key: a duplicate notification can't be claimed twice.
            migrated.createStatement().use { st ->
                assertEquals(0, st.executeUpdate("INSERT OR IGNORE INTO mms_receipts (dedupKey, createdAt) VALUES ('-1|T1|http://mmsc.example/x', 9)"))
            }
        }
    }

    @Test fun v1ChainToV6MatchesFreshV6() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
            create(migrated, schema(1))
            (MigrationSql.V1_TO_V2 + MigrationSql.V2_TO_V3 + MigrationSql.V3_TO_V4 + MigrationSql.V4_TO_V5 + MigrationSql.V5_TO_V6)
                .forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
            DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                create(fresh, schema(6))
                assertEquals(describe(fresh), describe(migrated))
            }
        }
    }

    @Test fun v3AndV4ChainToV6MatchesFreshV6() {
        listOf(
            3 to (MigrationSql.V3_TO_V4 + MigrationSql.V4_TO_V5 + MigrationSql.V5_TO_V6),
            4 to (MigrationSql.V4_TO_V5 + MigrationSql.V5_TO_V6),
        ).forEach { (from, steps) ->
            DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
                create(migrated, schema(from))
                steps.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }
                DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
                    create(fresh, schema(6))
                    assertEquals("from v$from", describe(fresh), describe(migrated))
                }
            }
        }
    }

    @Test fun migrationsChainToCurrentVersion() {
        val latest = schema(6)["version"]!!.jsonPrimitive.content.toInt()
        assertEquals(latest, Migrations.ALL.maxOf { it.endVersion })
    }
}
