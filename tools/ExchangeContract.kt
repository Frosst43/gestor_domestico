import com.frosst.gestordomestico.SyncSql
import com.frosst.gestordomestico.exchange.*
import com.frosst.gestordomestico.link.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

private class Sqlite(private val bridge: String, val path: Path) : ExchangeDatabase, Closeable {
    private val process = ProcessBuilder("python3", bridge, path.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val output = DataOutputStream(BufferedOutputStream(process.outputStream))
    private val input = DataInputStream(BufferedInputStream(process.inputStream))
    var failOn: String? = null
    private fun DataOutputStream.raw(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.raw(): ByteArray = ByteArray(readInt().also { check(it in 0..4 * 1024 * 1024) }).also { readFully(it) }
    @Synchronized private fun command(op: Int, sql: String, args: List<Any?>): List<List<Any?>> {
        failOn?.let { if (sql.contains(it)) throw IOException("Injected transaction fault") }
        output.writeByte(op); output.raw(sql.toByteArray(Charsets.UTF_8)); output.writeInt(args.size)
        args.forEach { value -> when (value) {
            null -> output.writeByte('N'.code)
            is Number -> { output.writeByte('L'.code); output.writeLong(value.toLong()) }
            is String -> { output.writeByte('S'.code); output.raw(value.toByteArray(Charsets.UTF_8)) }
            is ByteArray -> { output.writeByte('B'.code); output.raw(value) }
            else -> error("Invalid bridge argument")
        } }
        output.flush()
        if (input.readUnsignedByte() != 0) throw IOException(String(input.raw(), Charsets.UTF_8))
        return List(input.readInt()) { List(input.readInt()) { when (input.readUnsignedByte()) {
            'N'.code -> null
            'L'.code -> input.readLong()
            'S'.code -> String(input.raw(), Charsets.UTF_8)
            'B'.code -> input.raw()
            else -> error("Invalid bridge response")
        } } }
    }
    @Synchronized override fun <T> transaction(block: (ExchangeTransaction) -> T): T {
        val tx = object : ExchangeTransaction {
            override fun execute(sql: String, args: List<Any?>) { command('E'.code, sql, args) }
            override fun query(sql: String, args: List<Any?>) = command('Q'.code, sql, args)
        }
        tx.execute("BEGIN IMMEDIATE")
        try { val result = block(tx); tx.execute("COMMIT"); return result }
        catch (error: Throwable) { try { tx.execute("ROLLBACK") } catch (_: Exception) {}; throw error }
    }
    fun exec(sql: String, vararg args: Any?) = transaction { it.execute(sql, args.toList()) }
    fun rows(sql: String, vararg args: Any?) = transaction { it.query(sql, args.toList()) }
    fun number(sql: String): Long = (rows(sql).single().single() as Number).toLong()
    @Synchronized override fun close() { output.writeByte('X'.code); output.flush(); output.close(); check(process.waitFor() == 0) }
}
private val tables = listOf(
    "CREATE TABLE shopping_lists(id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, archived INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE categories(id TEXT NOT NULL PRIMARY KEY, listId TEXT NOT NULL REFERENCES shopping_lists(id) ON DELETE CASCADE, name TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE TABLE products(id TEXT NOT NULL PRIMARY KEY, categoryId TEXT NOT NULL REFERENCES categories(id) ON DELETE CASCADE, name TEXT NOT NULL, quantity TEXT NOT NULL, notes TEXT NOT NULL, purchased INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"
)
private fun initializeV2(db: Sqlite, id: String) = db.transaction { tx ->
    (tables + SyncSql.tables + SyncSql.identityInsert(id) + SyncSql.triggerStatements()).forEach { tx.execute(it) }
}
private fun migrate(db: Sqlite) = db.transaction { tx -> ExchangeSql.installStatements().forEach { tx.execute(it) } }
private fun sample(db: Sqlite) {
    db.exec("INSERT INTO shopping_lists VALUES ('l', 'Necesidades casa', 1, 10, 10)")
    db.exec("INSERT INTO categories VALUES ('c', 'l', 'Hogar', 11)")
    db.exec("INSERT INTO products VALUES ('p', 'c', 'Lampara', '2', 'Medidas: 20 cm; luz calida', 1, 12)")
}
private fun qty(db: Sqlite, text: String) = db.exec("UPDATE products SET quantity = ?, updatedAt = updatedAt + 1 WHERE id = 'p'", text)
private fun quantities(db: Sqlite) = db.rows("SELECT id, quantity FROM products ORDER BY id")
private fun journal(db: Sqlite) = db.number("SELECT COALESCE(MAX(sequence),0) FROM local_change_journal")
private fun mustFail(action: () -> Unit) {
    var failed = false
    try { action() } catch (_: Exception) { failed = true }
    check(failed) { "Expected rejection" }
}
private class DiskPeerStore(private val path: Path) : PeerStore {
    override fun load(): TrustedPeer? {
        if (!Files.exists(path)) return null
        return Files.readAllLines(path).let { TrustedPeer(it[0], it[1]) }
    }
    override fun save(peer: TrustedPeer?) {
        if (peer == null) { Files.deleteIfExists(path); return }
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(temp, listOf(peer.deviceId, peer.tokenHash))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
private class Fixture(val directory: Path, val bridge: String, val tls: SSLContext, val fingerprint: String) : Closeable {
    val hostId = UUID.randomUUID().toString()
    val clientId = UUID.randomUUID().toString()
    var hostDb = Sqlite(bridge, directory.resolve("host.sqlite"))
    var clientDb = Sqlite(bridge, directory.resolve("client.sqlite"))
    var host = ShoppingExchange(hostDb)
    var client = ShoppingExchange(clientDb)
    val peerStore = DiskPeerStore(directory.resolve("peer.txt"))
    var gate = PairingGate(hostId, peerStore)
    var server: LanLinkServer? = null
    lateinit var link: ClientLink
    private val loseReply = AtomicBoolean(false)
    var port = 0
    init {
        initializeV2(hostDb, hostId); sample(hostDb); migrate(hostDb)
        initializeV2(clientDb, clientId); migrate(clientDb)
        host.enableHost(); start()
        val invitation = gate.invite(link.endpoint)
        LanLinkClient.pair(invitation, link, allowLoopback = true)
        check(sync().applied)
    }
    private fun start() {
        val listener = LanLinkServer(tls, gate, fingerprint, allowLoopback = true, onExchange = { peer, payload ->
            val result = ExchangeCodec.replyBytes(host.receive(peer, ExchangeCodec.request(payload)))
            if (loseReply.compareAndSet(true, false)) throw IOException("Simulated lost reply after commit")
            result
        })
        val endpoint = listener.start("127.0.0.1", port)
        port = endpoint.port
        val token = if (::link.isInitialized) link.token else LinkSecrets.newToken()
        link = ClientLink(endpoint, clientId, token, true)
        server = listener
    }
    fun transmit(request: ExchangeRequest): ExchangeReply = ExchangeCodec.reply(
        LanLinkClient.exchangePurchases(link, ExchangeCodec.requestBytes(request), allowLoopback = true))
    fun sync(): ShoppingExchange.Outcome = client.accept(transmit(client.prepare(hostId)))
    fun dropNextReply() { loseReply.set(true) }
    fun restart() {
        server!!.close(); server = null
        hostDb.close(); clientDb.close()
        hostDb = Sqlite(bridge, directory.resolve("host.sqlite")); clientDb = Sqlite(bridge, directory.resolve("client.sqlite"))
        host = ShoppingExchange(hostDb); client = ShoppingExchange(clientDb)
        gate = PairingGate(hostId, DiskPeerStore(directory.resolve("peer.txt")))
        start()
    }
    override fun close() { server?.close(); hostDb.close(); clientDb.close() }
}

fun main(args: Array<String>) {
    val root = Path.of(args[0]); val bridge = args[1]
    val keys = KeyStore.getInstance("PKCS12")
    Files.newInputStream(Path.of(args[2])).use { keys.load(it, args[3].toCharArray()) }
    val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, args[3].toCharArray()) }
    val tls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, null, null) }
    val fingerprint = LinkSecrets.digest(keys.getCertificate("test").encoded)
    var passed = 0
    fun test(name: String, action: () -> Unit) { action(); passed++; println("PASS $passed: $name") }
    fun fixture(name: String, action: (Fixture) -> Unit) {
        val dir = Files.createDirectory(root.resolve(name))
        Fixture(dir, bridge, tls, fingerprint).use(action)
    }
    test("codec: roundtrip, Unicode, duplicate keys, corrupt/oversized frames, trailing bytes") {
        val id = UUID.randomUUID().toString()
        val row = SharedRow(EntityKey(EntityKind.LIST, "id"), null, "Hogar \u00f1 \uD83D\uDE00", createdAt = 1, updatedAt = 2)
        val request = ExchangeRequest(id, id, id, 0, 0, false, listOf(Mutation(row.key, 0, row)))
        check(ExchangeCodec.request(ExchangeCodec.requestBytes(request)) == request)
        mustFail { ExchangeCodec.request(ExchangeCodec.requestBytes(request) + byteArrayOf(0)) }
        mustFail { ExchangeCodec.requestBytes(request.copy(changes = request.changes + request.changes)) }
        mustFail { ExchangeCodec.requestBytes(request.copy(changes = listOf(Mutation(row.key, 1, row)))) }
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(Int.MAX_VALUE) }.toByteArray()
        mustFail { LinkPayload.read(ByteArrayInputStream(bytes)) }
        val huge = row.copy(name = "x".repeat(65537))
        mustFail { ExchangeCodec.requestBytes(request.copy(changes = listOf(Mutation(row.key, 0, huge)))) }
    }
    test("migration 2->3 preserves purchases, device identity and journal; atomic activation") {
        val db = Sqlite(bridge, root.resolve("migration.sqlite"))
        db.use {
            val id = UUID.randomUUID().toString(); initializeV2(db, id); sample(db)
            val before = quantities(db); val cursor = journal(db); migrate(db)
            check(before == quantities(db) && cursor == journal(db))
            val engine = ShoppingExchange(db)
            check(engine.status().role == "LOCAL")
            val snapshot = engine.enableHost()
            check(snapshot.serverId == id && snapshot.records.size == 3 && quantities(db) == before)
        }
    }
    test("TLS bootstrap transfers archived list, category, purchased item and notes; no journal echo") {
        fixture("bootstrap") { f ->
            check(quantities(f.hostDb) == quantities(f.clientDb))
            check(f.clientDb.number("SELECT archived FROM shopping_lists WHERE id='l'") == 1L)
            check(f.clientDb.number("SELECT purchased FROM products WHERE id='p'") == 1L)
            check(journal(f.hostDb) == 3L && journal(f.clientDb) == 0L)
            check(f.client.status() == ShoppingExchange.Status("CLIENT", false, false))
        }
    }
    test("bidirectional disjoint changes converge; incoming writes do not enter the local journal") {
        fixture("bidirectional") { f ->
            qty(f.hostDb, "5")
            f.clientDb.exec("INSERT INTO products VALUES ('p2','c','Cargador','1','USB-C',0,20)")
            val h = journal(f.hostDb); val c = journal(f.clientDb)
            check(f.sync().applied && quantities(f.hostDb) == quantities(f.clientDb))
            check(journal(f.hostDb) == h && journal(f.clientDb) == c)
        }
    }
    test("lost TLS acknowledgment + full restart: same durable request, same receipt, no duplicate application") {
        fixture("lost-ack") { f ->
            qty(f.clientDb, "7")
            val request = f.client.prepare(f.hostId)
            f.dropNextReply(); mustFail { f.transmit(request) }
            val revision = f.hostDb.number("SELECT revision FROM gd_exchange_state")
            check(f.client.status().pending)
            f.restart()
            check(f.client.prepare(f.hostId) == request)
            val reply = f.transmit(request)
            check(f.client.accept(reply).applied && f.client.accept(reply).applied)
            check(f.hostDb.number("SELECT revision FROM gd_exchange_state") == revision)
            check(quantities(f.hostDb) == quantities(f.clientDb))
        }
    }
    test("same-item concurrent edits produce a persistent conflict without overwriting either version") {
        fixture("conflict") { f ->
            qty(f.clientDb, "CLIENT"); qty(f.hostDb, "HOST")
            val beforeClient = quantities(f.clientDb); val beforeHost = quantities(f.hostDb)
            val outcome = f.sync()
            check(outcome.conflict && quantities(f.clientDb) == beforeClient && quantities(f.hostDb) == beforeHost)
            f.restart(); check(f.client.status().conflict && f.client.status().pending)
            mustFail { f.client.prepare(f.hostId) }
        }
    }
    test("deleted parents/tombstones cannot be resurrected by offline edits; cascaded removals propagate") {
        fixture("delete-conflict") { f ->
            qty(f.clientDb, "OFFLINE")
            f.hostDb.exec("DELETE FROM shopping_lists WHERE id='l'")
            check(f.sync().conflict)
            check(f.hostDb.number("SELECT COUNT(*) FROM shopping_lists") == 0L)
            check(f.clientDb.number("SELECT COUNT(*) FROM products") == 1L)
        }
        fixture("delete-pull") { f ->
            f.hostDb.exec("DELETE FROM shopping_lists WHERE id='l'")
            check(f.sync().applied && f.clientDb.number("SELECT COUNT(*) FROM products") == 0L)
            check(journal(f.clientDb) == 0L)
        }
    }
    test("parent deletion cannot erase an unseen server child; whole batch is rejected") {
        fixture("new-child") { f ->
            f.clientDb.exec("DELETE FROM categories WHERE id='c'")
            f.hostDb.exec("INSERT INTO products VALUES ('new','c','Nuevo','1','',0,21)")
            check(f.sync().conflict && f.hostDb.number("SELECT COUNT(*) FROM products") == 2L)
        }
    }
    test("edits made while a request is in flight survive and are sent in the next request") {
        fixture("in-flight") { f ->
            qty(f.clientDb, "7"); val request = f.client.prepare(f.hostId)
            qty(f.clientDb, "8")
            val outcome = f.client.accept(f.transmit(request))
            check(outcome.applied && outcome.localChangesRemain)
            check(f.clientDb.rows("SELECT quantity FROM products WHERE id='p'").single().single() == "8")
            check(f.sync().applied && quantities(f.hostDb) == quantities(f.clientDb))
        }
    }
    test("fault before receipt insert rolls back data, revisions and suppression; retry succeeds") {
        fixture("rollback") { f ->
            qty(f.clientDb, "4"); val request = f.client.prepare(f.hostId)
            val old = quantities(f.hostDb); val revision = f.hostDb.number("SELECT revision FROM gd_exchange_state")
            f.hostDb.failOn = "INSERT INTO gd_exchange_receipts"
            mustFail { f.host.receive(f.clientId, request) }
            f.hostDb.failOn = null
            check(quantities(f.hostDb) == old && f.hostDb.number("SELECT revision FROM gd_exchange_state") == revision)
            check(f.hostDb.number("SELECT suppress FROM gd_exchange_state") == 0L)
            check(f.client.accept(f.transmit(request)).applied)
        }
    }
    test("wrong group, reused operation ID and nonempty first client are rejected without data loss") {
        fixture("bounds") { f ->
            qty(f.clientDb, "9"); val request = f.client.prepare(f.hostId)
            mustFail { f.host.receive(f.clientId, request.copy(groupId = UUID.randomUUID().toString())) }
            val reply = f.transmit(request); check(f.client.accept(reply).applied)
            mustFail { f.host.receive(f.clientId, request.copy(journalCursor = request.journalCursor + 1)) }
            val extra = Sqlite(bridge, f.directory.resolve("nonempty.sqlite"))
            extra.use { initializeV2(extra, UUID.randomUUID().toString()); sample(extra); migrate(extra)
                val engine = ShoppingExchange(extra)
                mustFail { engine.prepare(f.hostId) }
                check(engine.status().role == "LOCAL" && extra.number("SELECT COUNT(*) FROM products") == 1L)
            }
        }
    }
    test("shopping endpoint enforces certificate, credential and revocation before touching data") {
        fixture("auth") { f ->
            qty(f.clientDb, "3"); val payload = ExchangeCodec.requestBytes(f.client.prepare(f.hostId))
            val revision = f.hostDb.number("SELECT revision FROM gd_exchange_state")
            mustFail { LanLinkClient.exchangePurchases(f.link.copy(token = LinkSecrets.newToken()), payload, allowLoopback = true) }
            mustFail { LanLinkClient.exchangePurchases(f.link.copy(endpoint = f.link.endpoint.copy(fingerprint = "0".repeat(64))), payload, allowLoopback = true) }
            f.gate.revoke()
            mustFail { LanLinkClient.exchangePurchases(f.link, payload, allowLoopback = true) }
            check(f.hostDb.number("SELECT revision FROM gd_exchange_state") == revision)
        }
    }
    println("TOTAL: $passed/12 PASS; Kotlin engine + real SQLite files + TLS loopback; NOT Android/device validation")
}
