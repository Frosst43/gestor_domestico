package com.frosst.gestordomestico

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Entity(tableName = "local_sync_identity")
data class LocalSyncIdentity(@PrimaryKey val slot: Int = 1, val deviceId: String)

@Entity(
    tableName = "local_change_journal",
    indices = [Index(value = ["entityType", "entityId"])]
)
data class LocalChange(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val parentId: String?,
    val name: String?,
    val quantity: String?,
    val notes: String?,
    val purchased: Boolean?,
    val archived: Boolean?,
    val createdAt: Long?,
    val updatedAt: Long
)

/** Local read page, NOT a network message or proof of delivery. */
data class LocalChangeBatch(
    val deviceId: String,
    val after: Long,
    val through: Long,
    val changes: List<LocalChange>
) {
    val nextCursor: Long get() = changes.lastOrNull()?.sequence ?: after
    val hasMore: Boolean get() = nextCursor < through
}

@Dao
interface SyncJournalDao {
    @Query("SELECT deviceId FROM local_sync_identity WHERE slot = 1")
    suspend fun deviceId(): String

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM local_change_journal")
    suspend fun latestSequence(): Long

    @Query("SELECT * FROM local_change_journal WHERE sequence > :after AND sequence <= :through ORDER BY sequence LIMIT :limit")
    suspend fun readPage(after: Long, through: Long, limit: Int): List<LocalChange>

    @Transaction
    suspend fun pendingPage(after: Long, limit: Int = 100): LocalChangeBatch {
        require(after >= 0) { "Negative cursor" }
        require(limit in 1..200) { "Page size must be between 1 and 200" }
        val through = latestSequence()
        require(after <= through) { "Cursor belongs to a newer or different journal" }
        return LocalChangeBatch(deviceId(), after, through, readPage(after, through, limit))
    }
}

object SyncJournalSchema {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Room provides the migration transaction. No destructive fallback.
            SyncSql.tables.forEach { db.execSQL(it) }
            db.execSQL(SyncSql.identityInsert(UUID.randomUUID().toString()))
            SyncSql.seedStatements().forEach { db.execSQL(it) }
            SyncSql.triggerStatements().forEach { db.execSQL(it) }
        }
    }

    val onCreate = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Room has already created all entity tables for a fresh installation.
            db.execSQL(SyncSql.identityInsert(UUID.randomUUID().toString()))
            SyncSql.triggerStatements().forEach { db.execSQL(it) }
        }
    }
}
