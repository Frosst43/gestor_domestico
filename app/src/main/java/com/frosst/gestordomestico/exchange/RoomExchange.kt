package com.frosst.gestordomestico.exchange

import android.database.Cursor
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Callable

@Entity(tableName = "gd_exchange_state")
data class ExchangeStateEntity(@PrimaryKey val slot: Int, val role: String, val groupId: String,
    val serverId: String, val revision: Long, val suppress: Int)
@Entity(tableName = "gd_exchange_versions", primaryKeys = ["entityType", "entityId"])
data class ExchangeVersionEntity(val entityType: String, val entityId: String, val revision: Long, val deleted: Int)
@Entity(tableName = "gd_exchange_client")
data class ExchangeClientEntity(@PrimaryKey val slot: Int, val shadow: ByteArray?, val pending: ByteArray?,
    val conflict: ByteArray?, val lastOperationId: String, val lastReplyHash: String)
@Entity(tableName = "gd_exchange_receipts", primaryKeys = ["peerId", "operationId"])
data class ExchangeReceiptEntity(val peerId: String, val operationId: String, val requestHash: String, val reply: ByteArray)

object ExchangeRoomSchema {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) { ExchangeSql.installStatements().forEach { db.execSQL(it) } }
    }
    val onCreate = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            ExchangeSql.installStatements().forEach { db.execSQL(it) }
        }
    }
}

/** Run on Dispatchers.IO. Room coordinates both transaction atomicity and UI invalidation. */
class RoomExchangeDatabase(private val room: RoomDatabase) : ExchangeDatabase {
    override fun <T> transaction(block: (ExchangeTransaction) -> T): T = room.runInTransaction(Callable {
        val db = room.openHelper.writableDatabase
        block(object : ExchangeTransaction {
            override fun execute(sql: String, args: List<Any?>) { db.execSQL(sql, args.toTypedArray()) }
            override fun query(sql: String, args: List<Any?>): List<List<Any?>> = db.query(sql, args.toTypedArray()).use { cursor ->
                val rows = mutableListOf<List<Any?>>()
                while (cursor.moveToNext()) rows.add(List(cursor.columnCount) { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
                        else -> error("Unexpected SQLite value type")
                    }
                })
                rows
            }
        })
    })
}
