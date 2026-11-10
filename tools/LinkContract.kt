import com.frosst.gestordomestico.link.*
import java.io.*
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.*

private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
    val error = runCatching(block).exceptionOrNull()
    check(error is T) { "Expected ${T::class.java.simpleName}, got ${error?.javaClass?.simpleName ?: "success"}" }
}
private class MemoryPeers : PeerStore {
    var peer: TrustedPeer? = null
    var failWrite = false
    override fun load() = peer
    override fun save(peer: TrustedPeer?) {
        if (failWrite) throw IOException("simulated durable-write failure")
        this.peer = peer
    }
}
private class FilePeers(private val file: File) : PeerStore {
    @Synchronized override fun load(): TrustedPeer? {
        if (!file.exists()) return null
        return file.readLines().let { TrustedPeer(it[0], it[1]) }
    }
    @Synchronized override fun save(peer: TrustedPeer?) {
        if (peer == null) { if (file.exists()) check(file.delete()); return }
        file.writeText("${peer.deviceId}\n${peer.tokenHash}\n")
    }
}
private fun id() = UUID.randomUUID().toString()
private fun tls(file: File): Pair<SSLContext, String> {
    val password = "test-only-fixture".toCharArray()
    val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
    val cert = store.getCertificate("lan") as X509Certificate
    return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, SecureRandom()) } to
        LinkSecrets.digest(cert.encoded)
}
private fun rawRequest(client: ClientLink, fields: List<String>, version: String? = null): List<String> {
    val socket = pinnedClientContext(client.endpoint.fingerprint).socketFactory
        .createSocket(client.endpoint.host, client.endpoint.port) as SSLSocket
    socket.use {
        it.soTimeout = 3000
        it.enabledProtocols = if (version == null) arrayOf("TLSv1.3", "TLSv1.2") else arrayOf(version)
        it.startHandshake()
        LinkWire.write(it.outputStream, fields)
        return LinkWire.read(it.inputStream)
    }
}
fun main(args: Array<String>) {
    var count = 0
    fun scenario(name: String, block: () -> Unit) {
        block(); count++; println("PASS $count: $name")
    }
    val security = tls(File(args[0]))
    val serverId = id()
    val endpoint = LinkEndpoint("192.168.1.20", 38473, serverId, security.second)
    scenario("invitation validation, LAN-only policy and redacted secrets") {
        val peers = MemoryPeers()
        val invite = PairingGate(serverId, peers).invite(endpoint)
        check(LinkInvitation.parse(invite.encode()) == invite)
        check(!invite.toString().contains(invite.secret))
        check(!ClientLink(endpoint, id(), invite.secret).toString().contains(invite.secret))
        listOf("8.8.8.8", "0.0.0.0", "127.0.0.1", "192.168.01.2", "host.example", "169.254.1.1").forEach {
            rejects<IllegalArgumentException> { LanPolicy.address(it) }
        }
        rejects<IllegalArgumentException> { LinkInvitation.parse(invite.encode() + "|extra") }
        rejects<IllegalArgumentException> { LinkInvitation.parse("x".repeat(513)) }
    }
    scenario("monotonic expiry and five-failure pairing lockout") {
        var now = 1000L
        val store = MemoryPeers()
        val gate = PairingGate(serverId, store) { now }
        val first = gate.invite(endpoint)
        now += 300_000
        check(!gate.pair(id(), LinkSecrets.newToken(), first.secret))
        now++
        val second = gate.invite(endpoint)
        repeat(5) { check(!gate.pair(id(), LinkSecrets.newToken(), LinkSecrets.newToken())) }
        check(!gate.pair(id(), LinkSecrets.newToken(), second.secret))
        check(store.peer == null)
    }
    scenario("durable-write failure, idempotent retry and single-client authorization") {
        val store = MemoryPeers()
        val gate = PairingGate(serverId, store)
        val invite = gate.invite(endpoint)
        val clientId = id()
        val token = LinkSecrets.newToken()
        store.failWrite = true
        rejects<IOException> { gate.pair(clientId, token, invite.secret) }
        check(store.peer == null)
        store.failWrite = false
        check(gate.pair(clientId, token, invite.secret))
        check(gate.pair(clientId, token, invite.secret))
        check(!gate.pair(id(), LinkSecrets.newToken(), invite.secret))
        check(gate.authorized(clientId, token))
        check(!gate.authorized(clientId, LinkSecrets.newToken()))
        check(!gate.authorized(id(), token))
        check(store.peer!!.tokenHash != token)
        gate.revoke()
        check(!gate.authorized(clientId, token))
        check(!gate.pair(clientId, token, invite.secret))
    }
    scenario("bounded framing rejects oversized, incomplete and extra data") {
        val valid = ByteArrayOutputStream().also { LinkWire.write(it, listOf("PROBE", id(), LinkSecrets.newToken())) }.toByteArray()
        check(LinkWire.read(ByteArrayInputStream(valid))[0] == "PROBE")
        val bad = valid.copyOf().also { it[4] = 0x7f; it[5] = 0xff.toByte(); it[6] = 0xff.toByte(); it[7] = 0xff.toByte() }
        rejects<IllegalArgumentException> { LinkWire.read(ByteArrayInputStream(bad)) }
        rejects<EOFException> { LinkWire.read(ByteArrayInputStream(valid.copyOf(9))) }
        rejects<IllegalArgumentException> { LinkWire.write(ByteArrayOutputStream(), listOf("x".repeat(513))) }
    }
    val peerFile = File(args[1], "peer-fixture.txt")
    val store = FilePeers(peerFile)
    val gate = PairingGate(serverId, store)
    val server = LanLinkServer(security.first, gate, security.second, allowLoopback = true)
    val actualEndpoint = server.start("127.0.0.1", 0)
    val invitation = gate.invite(actualEndpoint)
    val client = ClientLink(actualEndpoint, id(), LinkSecrets.newToken())
    try {
        scenario("real TLS pairing and probes with TLS 1.3 and TLS 1.2") {
            check(LanLinkClient.pair(invitation, client, allowLoopback = true) in setOf("TLSv1.3", "TLSv1.2"))
            check(LanLinkClient.probe(client, allowLoopback = true) in setOf("TLSv1.3", "TLSv1.2"))
            check(rawRequest(client, listOf("PROBE", client.deviceId, client.token), "TLSv1.2")[0] == "OK")
            check(LanLinkClient.pair(invitation, client, allowLoopback = true).startsWith("TLS"))
        }
        scenario("wrong certificate, token, device and unsupported SYNC are rejected") {
            rejects<SSLException> { LanLinkClient.probe(client.copy(endpoint = actualEndpoint.copy(fingerprint = "0".repeat(64))), allowLoopback = true) }
            rejects<LinkDenied> { LanLinkClient.probe(client.copy(token = LinkSecrets.newToken()), allowLoopback = true) }
            rejects<LinkDenied> { LanLinkClient.probe(client.copy(deviceId = id()), allowLoopback = true) }
            check(rawRequest(client, listOf("SYNC", client.deviceId, client.token)) == listOf("DENIED"))
            Socket(actualEndpoint.host, actualEndpoint.port).use { plain ->
                plain.soTimeout = 3000
                LinkWire.write(plain.outputStream, listOf("PROBE", client.deviceId, client.token))
                check(runCatching { LinkWire.read(plain.inputStream) }.isFailure)
            }
            check(LanLinkClient.probe(client, allowLoopback = true).startsWith("TLS"))
        }
    } finally { server.close() }
    val restartedGate = PairingGate(serverId, FilePeers(peerFile))
    val restartedServer = LanLinkServer(security.first, restartedGate, security.second, allowLoopback = true)
    restartedServer.start(actualEndpoint.host, actualEndpoint.port)
    try {
        scenario("restart retains authorized peer; old invitation cannot enroll another") {
            check(LanLinkClient.probe(client, allowLoopback = true).startsWith("TLS"))
            rejects<LinkDenied> { LanLinkClient.pair(invitation, client.copy(deviceId = id()), allowLoopback = true) }
            val cancelled = LinkCall().also { it.close() }
            rejects<IOException> { LanLinkClient.probe(client, cancelled, allowLoopback = true) }
        }
        scenario("revocation rejects an already persisted client over TLS") {
            restartedGate.revoke()
            rejects<LinkDenied> { LanLinkClient.probe(client, allowLoopback = true) }
            check(!peerFile.exists())
        }
    } finally { restartedServer.close() }
    check(count == 8)
    println("RESULT: 8/8 PASS - JVM loopback TLS contract only")
    println("ANDROID_BUILD=PENDING; ANDROID_KEYSTORE=PENDING; DEVICE_CHECK=PENDING; SHOPPING_SYNC=NOT_IMPLEMENTED")
}
