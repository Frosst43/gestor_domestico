package com.frosst.gestordomestico

import java.util.UUID

/** SQL shared by Room creation/migration and the desktop SQLite contract check. */
object SyncSql {
    val tables = listOf(
        """CREATE TABLE IF NOT EXISTS `local_sync_identity` (
            `slot` INTEGER NOT NULL PRIMARY KEY,
            `deviceId` TEXT NOT NULL
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS `local_change_journal` (
            `sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `entityType` TEXT NOT NULL,
            `entityId` TEXT NOT NULL,
            `operation` TEXT NOT NULL,
            `parentId` TEXT,
            `name` TEXT,
            `quantity` TEXT,
            `notes` TEXT,
            `purchased` INTEGER,
            `archived` INTEGER,
            `createdAt` INTEGER,
            `updatedAt` INTEGER NOT NULL
        )""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_local_change_journal_entityType_entityId` " +
            "ON `local_change_journal` (`entityType`, `entityId`)"
    )
    private data class Source(val table: String, val kind: String, val parent: String?, val fields: Set<String>)
    private val sources = listOf(
        Source("shopping_lists", "LIST", null, setOf("name", "archived", "createdAt", "updatedAt")),
        Source("categories", "CATEGORY", "listId", setOf("name", "updatedAt")),
        Source("products", "PRODUCT", "categoryId", setOf("name", "quantity", "notes", "purchased", "updatedAt"))
    )
    private val fields = listOf("name", "quantity", "notes", "purchased", "archived", "createdAt", "updatedAt")
    private val columns = "entityType, entityId, operation, parentId, " + fields.joinToString(", ")
    fun identityInsert(deviceId: String): String {
        require(UUID.fromString(deviceId).toString() == deviceId) { "Invalid device UUID" }
        return "INSERT OR IGNORE INTO local_sync_identity (slot, deviceId) VALUES (1, '$deviceId')"
    }
    private fun values(source: Source, row: String, deleted: Boolean): String {
        val action = if (deleted) "DELETE" else "UPSERT"
        val parent = source.parent?.let { "$row.`$it`" } ?: "NULL"
        val snapshot = fields.map { field ->
            if (field in source.fields && (!deleted || field == "updatedAt")) "$row.`$field`" else "NULL"
        }
        return "'${source.kind}', $row.id, '$action', $parent, " + snapshot.joinToString(", ")
    }
    fun seedStatements(): List<String> = sources.map { source ->
        "INSERT INTO local_change_journal ($columns) SELECT " +
            values(source, "existing", false) + " FROM `${source.table}` AS existing ORDER BY existing.id"
    }
    fun dropTriggerStatements(): List<String> = sources.flatMap { source ->
        listOf("insert", "update", "delete").map { "DROP TRIGGER IF EXISTS `gd_journal_${source.table}_$it`" }
    }
    /** Suppression is transaction-local in practice: SQLite serializes writers. */
    fun triggerStatements(withExchangeGuard: Boolean = false): List<String> = sources.flatMap { source ->
        listOf("INSERT", "UPDATE", "DELETE").map { verb ->
            val deleted = verb == "DELETE"
            val row = if (deleted) "OLD" else "NEW"
            val guard = if (withExchangeGuard) "WHEN (SELECT suppress FROM gd_exchange_state WHERE slot = 1) = 0" else ""
            """CREATE TRIGGER IF NOT EXISTS `gd_journal_${source.table}_${verb.lowercase()}`
                AFTER $verb ON `${source.table}`
                $guard
                BEGIN
                    SELECT CASE WHEN NOT EXISTS (
                        SELECT 1 FROM local_sync_identity WHERE slot = 1
                    ) THEN RAISE(ABORT, 'Missing local sync identity') END;
                    INSERT INTO local_change_journal ($columns)
                    VALUES (${values(source, row, deleted)});
                END""".trimIndent()
        }
    }
}
