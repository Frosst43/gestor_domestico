package com.frosst.gestordomestico.exchange

import java.util.UUID

/** All receipts, shadows and outboxes are durable and committed with the associated data. */
class ShoppingExchange(private val database: ExchangeDatabase) {
    data class Status(val role: String, val pending: Boolean, val conflict: Boolean)
    data class Outcome(val applied: Boolean, val conflict: Boolean, val localChangesRemain: Boolean)
    private data class State(val role: String, val groupId: String, val serverId: String, val revision: Long, val suppress: Long)
    private data class ClientState(val shadow: ByteArray?, val pending: ByteArray?, val conflict: ByteArray?,
                                   val lastOperationId: String, val lastReplyHash: String)
    fun status(): Status = database.transaction { tx ->
        val state = tx.state(); val client = tx.client()
        Status(state.role, client.pending != null, client.conflict != null)
    }

    /** Explicit local action; enabling a host never erases or replaces existing purchases. */
    fun enableHost(): SharedSnapshot = database.transaction { tx ->
        val state = tx.state()
        check(state.role == "LOCAL" || state.role == "HOST") { "This database is already a client" }
        if (state.role == "LOCAL") {
            val rows = tx.readRows()
            val identity = tx.identity()
            tx.execute("UPDATE gd_exchange_state SET role = 'HOST', groupId = ?, serverId = ? WHERE slot = 1",
                listOf(UUID.randomUUID().toString(), identity))
            rows.keys.sortedWith(keyOrder).forEach { key ->
                tx.execute("UPDATE gd_exchange_state SET revision = revision + 1 WHERE slot = 1")
                tx.execute("INSERT INTO gd_exchange_versions(entityType, entityId, revision, deleted) " +
                    "SELECT ?, ?, revision, 0 FROM gd_exchange_state WHERE slot = 1", listOf(key.kind.name, key.id))
            }
        }
        tx.snapshot().also { ExchangeCodec.snapshotBytes(it) } // Size failure rolls back activation.
    }

    /** Save the exact request before any network IO. A failed call must retry these same bytes. */
    fun prepare(serverId: String): ExchangeRequest = database.transaction { tx ->
        ExchangeRules.uuid(serverId)
        check(serverId != tx.identity()) { "Cannot sync to this same installation" }
        val state = tx.state(); val client = tx.client()
        check(client.conflict == null) { "A conflict needs explicit review; purchases have been preserved" }
        client.pending?.let {
            return@transaction ExchangeCodec.request(it).also { request -> check(request.serverId == serverId) }
        }
        val current = tx.readRows()
        val shadow = client.shadow?.let(ExchangeCodec::snapshot)
        if (state.role == "LOCAL") {
            check(current.isEmpty()) { "First connection requires an empty client; export existing purchases first" }
            check(shadow == null)
            tx.execute("UPDATE gd_exchange_state SET role = 'CLIENT', serverId = ? WHERE slot = 1", listOf(serverId))
        } else check(state.role == "CLIENT" && state.serverId == serverId) { "Different server or role" }
        val changes = if (shadow == null) emptyList() else differences(current, shadow)
        val request = ExchangeRequest(UUID.randomUUID().toString(), serverId, shadow?.groupId.orEmpty(),
            shadow?.revision ?: 0, tx.journalCursor(), shadow == null, changes)
        tx.execute("UPDATE gd_exchange_client SET pending = ? WHERE slot = 1", listOf(ExchangeCodec.requestBytes(request)))
        request
    }

    /** Called only after TLS and pairing authorization. No IO is performed inside the transaction. */
    fun receive(authenticatedDeviceId: String, request: ExchangeRequest): ExchangeReply = database.transaction { tx ->
        ExchangeRules.uuid(authenticatedDeviceId); ExchangeRules.request(request)
        val state = tx.state()
        check(state.role == "HOST" && state.serverId == tx.identity() && state.serverId == request.serverId)
        check(authenticatedDeviceId != state.serverId)
        val hash = ExchangeCodec.digest(ExchangeCodec.requestBytes(request))
        val existing = tx.query("SELECT requestHash, reply FROM gd_exchange_receipts WHERE peerId = ? AND operationId = ?",
            listOf(authenticatedDeviceId, request.operationId)).singleOrNull()
        if (existing != null) {
            check(existing[0] == hash) { "Operation ID reused with different contents" }
            return@transaction ExchangeCodec.reply(existing[1] as ByteArray)
        }
        val before = tx.snapshot()
        val byKey = before.records.associateBy { it.key }
        if (!request.bootstrap) {
            check(request.groupId == before.groupId) { "Different shared group" }
            check(request.baseRevision <= before.revision) { "Server revision is older than client" }
        }
        val conflicts = request.changes.mapNotNull { mutation ->
            val previous = byKey[mutation.key]
            val reason = when {
                (previous?.revision ?: 0) != mutation.expectedRevision -> ConflictReason.STALE_REVISION
                previous != null && previous.row == null && mutation.row != null -> ConflictReason.DELETED_ID
                else -> null
            }
            reason?.let { ExchangeConflict(mutation.key, it) }
        }.toMutableList()
        val target = before.live().toMutableMap()
        request.changes.forEach { if (it.row == null) target.remove(it.key) else target[it.key] = it.row }
        if (conflicts.isEmpty()) {
            try { ExchangeRules.hierarchy(target) }
            catch (_: IllegalArgumentException) {
                check(request.changes.isNotEmpty())
                conflicts.add(ExchangeConflict(request.changes.first().key, ConflictReason.INVALID_HIERARCHY))
            }
        }
        if (conflicts.isEmpty()) tx.replaceDifference(before.live(), target)
        val reply = ExchangeReply(request.operationId,
            if (conflicts.isEmpty()) ReplyStatus.APPLIED else ReplyStatus.CONFLICT, tx.snapshot(), conflicts)
        val bytes = ExchangeCodec.replyBytes(reply)
        val quota = tx.query("SELECT COUNT(*), COALESCE(SUM(length(reply)), 0) FROM gd_exchange_receipts").single()
        check((quota[0] as Number).toLong() < 2048 && (quota[1] as Number).toLong() + bytes.size <= 64L * 1024 * 1024) {
            "Receipt storage limit reached; no data or receipts were removed"
        }
        tx.execute("INSERT INTO gd_exchange_receipts(peerId, operationId, requestHash, reply) VALUES (?, ?, ?, ?)",
            listOf(authenticatedDeviceId, request.operationId, hash, bytes))
        reply // The outer transaction commits BEFORE the network sends this acknowledgment.
    }

    /** Preserve edits made during a network call; never replace all client data unconditionally. */
    fun accept(reply: ExchangeReply): Outcome = database.transaction { tx ->
        ExchangeRules.reply(reply)
        val state = tx.state(); val client = tx.client()
        check(state.role == "CLIENT" && state.serverId == reply.snapshot.serverId)
        val replyBytes = ExchangeCodec.replyBytes(reply)
        val replyHash = ExchangeCodec.digest(replyBytes)
        if (client.pending == null) {
            check(client.lastOperationId == reply.operationId && client.lastReplyHash == replyHash) { "Unexpected reply" }
            return@transaction Outcome(true, false, differences(tx.readRows(), reply.snapshot).isNotEmpty())
        }
        val request = ExchangeCodec.request(client.pending)
        check(request.operationId == reply.operationId && request.serverId == reply.snapshot.serverId)
        val shadow = client.shadow?.let(ExchangeCodec::snapshot)
        if (shadow != null) check(shadow.groupId == reply.snapshot.groupId && reply.snapshot.revision >= shadow.revision)
        else check(request.bootstrap)
        if (reply.status == ReplyStatus.CONFLICT) {
            tx.execute("UPDATE gd_exchange_client SET conflict = ? WHERE slot = 1", listOf(replyBytes))
            return@transaction Outcome(false, true, true)
        }
        val sent = (shadow?.live() ?: emptyMap()).toMutableMap()
        request.changes.forEach { if (it.row == null) sent.remove(it.key) else sent[it.key] = it.row }
        val local = tx.readRows()
        val remote = reply.snapshot.live()
        check(request.changes.all { remote[it.key] == it.row }) { "Acknowledgment does not contain the submitted changes" }
        val merged = remote.toMutableMap()
        var collision = false
        (sent.keys + local.keys).forEach { key ->
            if (local[key] != sent[key]) {
                if (remote[key] != sent[key] && local[key] != remote[key]) collision = true
                if (local[key] == null) merged.remove(key) else merged[key] = local.getValue(key)
            }
        }
        // Structural conflicts include a server-deleted parent with a newer local child.
        try { ExchangeRules.hierarchy(merged) } catch (_: IllegalArgumentException) { collision = true }
        if (collision) {
            tx.execute("UPDATE gd_exchange_client SET conflict = ? WHERE slot = 1", listOf(replyBytes))
            return@transaction Outcome(false, true, true)
        }
        tx.replaceDifference(local, merged)
        tx.execute("UPDATE gd_exchange_client SET shadow = ?, pending = NULL, conflict = NULL, " +
            "lastOperationId = ?, lastReplyHash = ? WHERE slot = 1",
            listOf(ExchangeCodec.snapshotBytes(reply.snapshot), reply.operationId, replyHash))
        tx.execute("UPDATE gd_exchange_state SET groupId = ?, revision = ? WHERE slot = 1",
            listOf(reply.snapshot.groupId, reply.snapshot.revision))
        Outcome(true, false, differences(merged, reply.snapshot).isNotEmpty())
    }

    private fun differences(current: Map<EntityKey, SharedRow>, shadow: SharedSnapshot): List<Mutation> {
        val versions = shadow.records.associateBy { it.key }
        val live = shadow.live()
        return (current.keys + live.keys).sortedWith(keyOrder).filter { current[it] != live[it] }
            .map { Mutation(it, versions[it]?.revision ?: 0, current[it]) }
    }
    private fun ExchangeTransaction.identity(): String =
        (query("SELECT deviceId FROM local_sync_identity WHERE slot = 1").single()[0] as String).also(ExchangeRules::uuid)
    private fun ExchangeTransaction.journalCursor(): Long =
        (query("SELECT COALESCE(MAX(sequence), 0) FROM local_change_journal").single()[0] as Number).toLong()
    private fun ExchangeTransaction.state(): State {
        val row = query("SELECT role, groupId, serverId, revision, suppress FROM gd_exchange_state WHERE slot = 1").single()
        check((row[4] as Number).toLong() == 0L) { "Incomplete suppression state" }
        return State(row[0] as String, row[1] as String, row[2] as String, (row[3] as Number).toLong(), 0)
    }
    private fun ExchangeTransaction.client(): ClientState {
        val row = query("SELECT shadow, pending, conflict, lastOperationId, lastReplyHash FROM gd_exchange_client WHERE slot = 1").single()
        return ClientState(row[0] as ByteArray?, row[1] as ByteArray?, row[2] as ByteArray?, row[3] as String, row[4] as String)
    }
    private fun ExchangeTransaction.snapshot(): SharedSnapshot {
        val state = state(); check(state.role == "HOST")
        val live = readRows()
        val records = query("SELECT entityType, entityId, revision, deleted FROM gd_exchange_versions LIMIT 2001").map { values ->
            val key = EntityKey(EntityKind.valueOf(values[0] as String), values[1] as String)
            val deleted = (values[3] as Number).toInt() == 1
            val row = live[key]
            check(deleted == (row == null)) { "Revision table disagrees with shopping data" }
            VersionedRow(key, (values[2] as Number).toLong(), row)
        }
        check(records.filter { it.row != null }.map { it.key }.toSet() == live.keys) { "Missing revision metadata" }
        return SharedSnapshot(state.groupId, state.serverId, state.revision, records).also(ExchangeRules::snapshot)
    }
    private fun ExchangeTransaction.readRows(): Map<EntityKey, SharedRow> {
        val lists = query("SELECT id, name, archived, createdAt, updatedAt FROM shopping_lists LIMIT 2001").map {
            SharedRow(EntityKey(EntityKind.LIST, it[0] as String), null, it[1] as String,
                archived = (it[2] as Number).toInt() == 1, createdAt = (it[3] as Number).toLong(), updatedAt = (it[4] as Number).toLong())
        }
        val categories = query("SELECT id, listId, name, updatedAt FROM categories LIMIT 2001").map {
            SharedRow(EntityKey(EntityKind.CATEGORY, it[0] as String), it[1] as String, it[2] as String,
                updatedAt = (it[3] as Number).toLong())
        }
        val products = query("SELECT id, categoryId, name, quantity, notes, purchased, updatedAt FROM products LIMIT 2001").map {
            SharedRow(EntityKey(EntityKind.PRODUCT, it[0] as String), it[1] as String, it[2] as String,
                it[3] as String, it[4] as String, (it[5] as Number).toInt() == 1, updatedAt = (it[6] as Number).toLong())
        }
        return (lists + categories + products).associateBy { it.key }.also(ExchangeRules::hierarchy)
    }
    private fun ExchangeTransaction.replaceDifference(before: Map<EntityKey, SharedRow>, after: Map<EntityKey, SharedRow>) {
        ExchangeRules.hierarchy(after)
        execute("UPDATE gd_exchange_state SET suppress = 1 WHERE slot = 1")
        try {
            after.keys.sortedWith(keyOrder).filter { before[it] != after[it] }.forEach { key ->
                val row = after.getValue(key)
                val exists = key in before
                when (key.kind) {
                    EntityKind.LIST -> {
                        val args = listOf(row.name, if (row.archived) 1L else 0L, row.createdAt, row.updatedAt, key.id)
                        execute(if (exists) "UPDATE shopping_lists SET name=?, archived=?, createdAt=?, updatedAt=? WHERE id=?"
                            else "INSERT INTO shopping_lists(name, archived, createdAt, updatedAt, id) VALUES (?,?,?,?,?)", args)
                    }
                    EntityKind.CATEGORY -> {
                        val args = listOf(row.parentId, row.name, row.updatedAt, key.id)
                        execute(if (exists) "UPDATE categories SET listId=?, name=?, updatedAt=? WHERE id=?"
                            else "INSERT INTO categories(listId, name, updatedAt, id) VALUES (?,?,?,?)", args)
                    }
                    EntityKind.PRODUCT -> {
                        val args = listOf(row.parentId, row.name, row.quantity, row.notes, if (row.purchased) 1L else 0L, row.updatedAt, key.id)
                        execute(if (exists) "UPDATE products SET categoryId=?, name=?, quantity=?, notes=?, purchased=?, updatedAt=? WHERE id=?"
                            else "INSERT INTO products(categoryId, name, quantity, notes, purchased, updatedAt, id) VALUES (?,?,?,?,?,?,?)", args)
                    }
                }
            }
            // Move/update surviving children BEFORE removing old parents (avoids FK cascade loss).
            (before.keys - after.keys).sortedWith(keyOrder.reversed()).forEach { key ->
                execute("DELETE FROM ${table(key.kind)} WHERE id = ?", listOf(key.id))
            }
            check(readRows() == after) { "Applied data differs from the intended result" }
        } finally { execute("UPDATE gd_exchange_state SET suppress = 0 WHERE slot = 1") }
    }
    private fun table(kind: EntityKind): String = when (kind) {
        EntityKind.LIST -> "shopping_lists"
        EntityKind.CATEGORY -> "categories"
        EntityKind.PRODUCT -> "products"
    }
    private val keyOrder = compareBy<EntityKey> { it.kind.ordinal }.thenBy { it.id }
}
