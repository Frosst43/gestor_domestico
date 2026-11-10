package com.frosst.gestordomestico.exchange

import com.frosst.gestordomestico.SyncSql

/** Same statements are executed by migration 2->3, fresh installs and the SQLite laboratory. */
object ExchangeSql {
    val tables = listOf(
        """CREATE TABLE IF NOT EXISTS gd_exchange_state (
            slot INTEGER NOT NULL PRIMARY KEY, role TEXT NOT NULL, groupId TEXT NOT NULL,
            serverId TEXT NOT NULL, revision INTEGER NOT NULL, suppress INTEGER NOT NULL
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS gd_exchange_versions (
            entityType TEXT NOT NULL, entityId TEXT NOT NULL, revision INTEGER NOT NULL,
            deleted INTEGER NOT NULL, PRIMARY KEY(entityType, entityId)
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS gd_exchange_client (
            slot INTEGER NOT NULL PRIMARY KEY, shadow BLOB, pending BLOB, conflict BLOB,
            lastOperationId TEXT NOT NULL, lastReplyHash TEXT NOT NULL
        )""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS gd_exchange_receipts (
            peerId TEXT NOT NULL, operationId TEXT NOT NULL, requestHash TEXT NOT NULL,
            reply BLOB NOT NULL, PRIMARY KEY(peerId, operationId)
        )""".trimIndent()
    )
    fun installStatements(): List<String> = tables + listOf(
        "INSERT OR IGNORE INTO gd_exchange_state VALUES (1, 'LOCAL', '', '', 0, 0)",
        "INSERT OR IGNORE INTO gd_exchange_client (slot, lastOperationId, lastReplyHash) VALUES (1, '', '')"
    ) + SyncSql.dropTriggerStatements() + SyncSql.triggerStatements(withExchangeGuard = true) + versionTriggers()

    private fun versionTriggers(): List<String> = listOf(
        "shopping_lists" to "LIST", "categories" to "CATEGORY", "products" to "PRODUCT"
    ).flatMap { (table, kind) ->
        listOf("INSERT", "UPDATE", "DELETE").map { verb ->
            val row = if (verb == "DELETE") "OLD" else "NEW"
            val deleted = if (verb == "DELETE") 1 else 0
            """CREATE TRIGGER IF NOT EXISTS gd_version_${table}_${verb.lowercase()}
                AFTER $verb ON $table
                WHEN (SELECT role FROM gd_exchange_state WHERE slot = 1) = 'HOST'
                BEGIN
                    SELECT CASE WHEN (SELECT revision FROM gd_exchange_state WHERE slot = 1) >= 9223372036854775807
                        THEN RAISE(ABORT, 'Revision exhausted') END;
                    UPDATE gd_exchange_state SET revision = revision + 1 WHERE slot = 1;
                    INSERT OR REPLACE INTO gd_exchange_versions(entityType, entityId, revision, deleted)
                    SELECT '$kind', $row.id, revision, $deleted FROM gd_exchange_state WHERE slot = 1;
                END""".trimIndent()
        }
    }
}

/** Android uses Room; the laboratory uses a persistent SQLite connection via a subprocess. */
interface ExchangeDatabase {
    fun <T> transaction(block: (ExchangeTransaction) -> T): T
}
interface ExchangeTransaction {
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
}
