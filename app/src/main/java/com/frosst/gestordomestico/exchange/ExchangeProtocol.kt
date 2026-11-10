package com.frosst.gestordomestico.exchange

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

/** V1 of the shopping exchange, independent of the pairing protocol and of Android. */
enum class EntityKind { LIST, CATEGORY, PRODUCT }
data class EntityKey(val kind: EntityKind, val id: String)
data class SharedRow(
    val key: EntityKey, val parentId: String?, val name: String,
    val quantity: String = "", val notes: String = "", val purchased: Boolean = false,
    val archived: Boolean = false, val createdAt: Long = 0, val updatedAt: Long = 0
)
data class VersionedRow(val key: EntityKey, val revision: Long, val row: SharedRow?)
data class SharedSnapshot(
    val groupId: String, val serverId: String, val revision: Long, val records: List<VersionedRow>
) {
    fun live(): Map<EntityKey, SharedRow> = records.mapNotNull { r -> r.row?.let { r.key to it } }.toMap()
}
data class Mutation(val key: EntityKey, val expectedRevision: Long, val row: SharedRow?)
data class ExchangeRequest(
    val operationId: String, val serverId: String, val groupId: String,
    val baseRevision: Long, val journalCursor: Long, val bootstrap: Boolean,
    val changes: List<Mutation>
)
enum class ReplyStatus { APPLIED, CONFLICT }
enum class ConflictReason { STALE_REVISION, DELETED_ID, INVALID_HIERARCHY }
data class ExchangeConflict(val key: EntityKey, val reason: ConflictReason)
data class ExchangeReply(
    val operationId: String, val status: ReplyStatus, val snapshot: SharedSnapshot,
    val conflicts: List<ExchangeConflict> = emptyList()
)

object ExchangeRules {
    const val MAX_RECORDS = 2000
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_TEXT_BYTES = 65536
    fun uuid(value: String) {
        require(value.length == 36 && UUID.fromString(value).toString() == value) { "Invalid UUID" }
    }
    fun key(key: EntityKey) { require(key.id.isNotBlank() && key.id.length <= 128 && strictBytes(key.id).size <= 128) { "Invalid entity ID" } }
    fun row(row: SharedRow) {
        key(row.key)
        require(row.name.isNotBlank() && row.createdAt >= 0 && row.updatedAt >= 0) { "Invalid row" }
        listOf(row.name, row.quantity, row.notes).forEach {
            require(it.length <= MAX_TEXT_BYTES && strictBytes(it).size <= MAX_TEXT_BYTES) { "Text exceeds exchange limit" }
        }
        when (row.key.kind) {
            EntityKind.LIST -> require(row.parentId == null && row.quantity.isEmpty() && row.notes.isEmpty() && !row.purchased)
            EntityKind.CATEGORY -> require(!row.parentId.isNullOrBlank() && row.parentId.length <= 128 &&
                row.quantity.isEmpty() && row.notes.isEmpty() && !row.purchased && !row.archived && row.createdAt == 0L)
            EntityKind.PRODUCT -> require(!row.parentId.isNullOrBlank() && row.parentId.length <= 128 &&
                !row.archived && row.createdAt == 0L)
        }
    }
    fun hierarchy(rows: Map<EntityKey, SharedRow>) {
        require(rows.size <= MAX_RECORDS) { "Too many rows" }
        rows.forEach { (key, row) ->
            require(key == row.key); ExchangeRules.row(row)
            when (key.kind) {
                EntityKind.LIST -> Unit
                EntityKind.CATEGORY -> require(EntityKey(EntityKind.LIST, row.parentId!!) in rows) { "Missing list" }
                EntityKind.PRODUCT -> require(EntityKey(EntityKind.CATEGORY, row.parentId!!) in rows) { "Missing category" }
            }
        }
    }
    fun snapshot(value: SharedSnapshot) {
        uuid(value.groupId); uuid(value.serverId)
        require(value.revision >= 0 && value.records.size <= MAX_RECORDS)
        require(value.records.map { it.key }.toSet().size == value.records.size) { "Duplicate record" }
        value.records.forEach { r ->
            key(r.key); require(r.revision in 1..value.revision) { "Invalid row revision" }
            r.row?.let { require(it.key == r.key); row(it) }
        }
        hierarchy(value.live())
    }
    fun request(value: ExchangeRequest) {
        uuid(value.operationId); uuid(value.serverId)
        require(value.baseRevision >= 0 && value.journalCursor >= 0 && value.changes.size <= MAX_RECORDS)
        if (value.bootstrap) require(value.groupId.isEmpty() && value.baseRevision == 0L && value.changes.isEmpty())
        else uuid(value.groupId)
        require(value.changes.map { it.key }.toSet().size == value.changes.size) { "Duplicate mutation" }
        value.changes.forEach { m ->
            key(m.key); require(m.expectedRevision in 0..value.baseRevision)
            m.row?.let { require(it.key == m.key); row(it) }
        }
    }
    fun reply(value: ExchangeReply) {
        uuid(value.operationId); snapshot(value.snapshot)
        require(value.conflicts.size <= MAX_RECORDS)
        require((value.status == ReplyStatus.APPLIED) == value.conflicts.isEmpty())
        require(value.conflicts.map { it.key }.toSet().size == value.conflicts.size)
        value.conflicts.forEach { key(it.key) }
    }
    internal fun strictBytes(value: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }
}

/** Length-prefixed binary frames, bounded before allocation; no Java serialization. */
object ExchangeCodec {
    private const val MAGIC = 0x47445331 // GDS1
    private const val VERSION = 1
    private val order = compareBy<EntityKey> { it.kind.ordinal }.thenBy { it.id }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun requestBytes(value: ExchangeRequest): ByteArray {
        ExchangeRules.request(value)
        return encode(1) { out ->
            out.text(value.operationId); out.text(value.serverId); out.text(value.groupId)
            out.writeLong(value.baseRevision); out.writeLong(value.journalCursor); out.bool(value.bootstrap)
            out.writeInt(value.changes.size)
            value.changes.sortedWith(compareBy(order) { it.key }).forEach { m ->
                out.key(m.key); out.writeLong(m.expectedRevision); out.row(m.row)
            }
        }
    }
    fun request(bytes: ByteArray): ExchangeRequest = decode(bytes, 1) { input ->
        ExchangeRequest(input.text(36), input.text(36), input.text(36), input.readLong(),
            input.readLong(), input.bool(), List(input.count()) {
                val key = input.key(); Mutation(key, input.readLong(), input.row(key))
            }).also { ExchangeRules.request(it) }
    }
    fun snapshotBytes(value: SharedSnapshot): ByteArray {
        ExchangeRules.snapshot(value)
        return encode(2) { it.snapshot(value) }
    }
    fun snapshot(bytes: ByteArray): SharedSnapshot = decode(bytes, 2) { it.snapshot().also(ExchangeRules::snapshot) }
    fun replyBytes(value: ExchangeReply): ByteArray {
        ExchangeRules.reply(value)
        return encode(3) { out ->
            out.text(value.operationId); out.writeByte(value.status.ordinal); out.snapshot(value.snapshot)
            out.writeInt(value.conflicts.size)
            value.conflicts.forEach { out.key(it.key); out.writeByte(it.reason.ordinal) }
        }
    }
    fun reply(bytes: ByteArray): ExchangeReply = decode(bytes, 3) { input ->
        ExchangeReply(input.text(36), ReplyStatus.values()[input.ordinal(ReplyStatus.values().size)], input.snapshot(),
            List(input.count()) { ExchangeConflict(input.key(), ConflictReason.values()[input.ordinal(ConflictReason.values().size)]) }
        ).also(ExchangeRules::reply)
    }
    fun readFrame(stream: InputStream): ByteArray {
        val input = DataInputStream(stream)
        val size = input.readInt()
        require(size in 6..ExchangeRules.MAX_BYTES) { "Exchange frame exceeds limit" }
        return ByteArray(size).also { input.readFully(it) }
    }
    fun writeFrame(stream: OutputStream, bytes: ByteArray) {
        require(bytes.size in 6..ExchangeRules.MAX_BYTES)
        DataOutputStream(stream).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    private fun encode(type: Int, action: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        // Cap while writing, not only after building a potentially huge aggregate.
        val bounded = object : OutputStream() {
            override fun write(b: Int) { require(buffer.size() < ExchangeRules.MAX_BYTES); buffer.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                require(len <= ExchangeRules.MAX_BYTES - buffer.size()); buffer.write(b, off, len)
            }
        }
        DataOutputStream(bounded).use { out -> out.writeInt(MAGIC); out.writeByte(VERSION); out.writeByte(type); action(out) }
        return buffer.toByteArray()
    }
    private fun <T> decode(bytes: ByteArray, type: Int, action: (DataInputStream) -> T): T {
        require(bytes.size in 6..ExchangeRules.MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC && input.readUnsignedByte() == VERSION && input.readUnsignedByte() == type)
        return action(input).also { require(input.available() == 0) { "Trailing exchange bytes" } }
    }
    private fun DataOutputStream.text(text: String) {
        val bytes = ExchangeRules.strictBytes(text)
        require(bytes.size <= ExchangeRules.MAX_TEXT_BYTES)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(max: Int = ExchangeRules.MAX_TEXT_BYTES): String {
        val n = readInt(); require(n in 0..max && n <= available())
        val bytes = ByteArray(n); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataOutputStream.bool(value: Boolean) = writeByte(if (value) 1 else 0)
    private fun DataInputStream.bool(): Boolean = ordinal(2) == 1
    private fun DataInputStream.ordinal(size: Int): Int = readUnsignedByte().also { require(it < size) }
    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..ExchangeRules.MAX_RECORDS) }
    private fun DataOutputStream.key(key: EntityKey) { writeByte(key.kind.ordinal); text(key.id) }
    private fun DataInputStream.key(): EntityKey = EntityKey(EntityKind.values()[ordinal(3)], text(128))
    private fun DataOutputStream.row(row: SharedRow?) {
        bool(row != null)
        row?.let { r ->
            bool(r.parentId != null); r.parentId?.let { text(it) }
            text(r.name); text(r.quantity); text(r.notes); bool(r.purchased); bool(r.archived)
            writeLong(r.createdAt); writeLong(r.updatedAt)
        }
    }
    private fun DataInputStream.row(key: EntityKey): SharedRow? = if (!bool()) null else SharedRow(
        key, if (bool()) text(128) else null, text(), text(), text(), bool(), bool(), readLong(), readLong()
    )
    private fun DataOutputStream.snapshot(value: SharedSnapshot) {
        text(value.groupId); text(value.serverId); writeLong(value.revision); writeInt(value.records.size)
        value.records.sortedWith(compareBy(order) { it.key }).forEach { key(it.key); writeLong(it.revision); row(it.row) }
    }
    private fun DataInputStream.snapshot(): SharedSnapshot = SharedSnapshot(text(36), text(36), readLong(), List(count()) {
        val key = key(); VersionedRow(key, readLong(), row(key))
    })
}
