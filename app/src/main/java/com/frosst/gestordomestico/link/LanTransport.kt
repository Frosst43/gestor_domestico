package com.frosst.gestordomestico.link

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

private val daemonThreads = ThreadFactory { job -> Thread(job, "gestor-lan").apply { isDaemon = true } }
private val clientDeadline: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreads)
private val suites = setOf(
    "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
)
private fun protocols(supported: Array<String>) = supported.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
private fun ciphers(supported: Array<String>) = supported.filter { it in suites }.toTypedArray()
private fun SSLSocket.harden() {
    enabledProtocols = protocols(supportedProtocols).also { check(it.isNotEmpty()) }
    enabledCipherSuites = ciphers(supportedCipherSuites).also { check(it.isNotEmpty()) }
    soTimeout = 5_000
}

/** Explicitly cancel the sockets as well as the coroutine that initiated them. */
class LinkCall : Closeable {
    private val cancelled = AtomicBoolean(false)
    private val sockets = CopyOnWriteArrayList<Closeable>()
    fun track(socket: Closeable) {
        sockets.add(socket)
        if (cancelled.get()) { socket.close(); throw IOException("Operacion cancelada") }
    }
    override fun close() {
        cancelled.set(true)
        sockets.forEach { try { it.close() } catch (_: IOException) { } }
        sockets.clear()
    }
}

/** Pin transferred out-of-band in an invitation, never learned from an untrusted connection. */
fun pinnedClientContext(fingerprint: String): SSLContext {
    LanPolicy.hex(fingerprint)
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("No se aceptan certificados de cliente")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !LinkSecrets.same(LinkSecrets.digest(chain[0].encoded), fingerprint)) {
                throw CertificateException("El certificado no corresponde a la invitacion")
            }
            chain[0].checkValidity()
        }
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
}

/** Connection test only: no read/write endpoint for shopping data is exposed. */
class LanLinkServer(
    private val tls: SSLContext,
    private val gate: PairingGate,
    private val fingerprint: String,
    private val allowLoopback: Boolean = false,
    private val onPaired: () -> Unit = {}
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<SSLSocket, Boolean>())
    private val deadlines = Executors.newSingleThreadScheduledExecutor(daemonThreads)
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), daemonThreads)
    @Volatile private var listener: SSLServerSocket? = null

    @Synchronized
    fun start(host: String, port: Int = 38473): LinkEndpoint {
        check(listener == null && !closed.get()) { "El servidor no puede arrancarse dos veces" }
        LanPolicy.address(host, allowLoopback)
        require(port in 1024..65535 || (allowLoopback && port == 0)) { "Puerto no valido" }
        LanPolicy.hex(fingerprint)
        val socket = tls.serverSocketFactory.createServerSocket() as SSLServerSocket
        try {
            socket.reuseAddress = true
            socket.enabledProtocols = protocols(socket.supportedProtocols).also { check(it.isNotEmpty()) }
            socket.enabledCipherSuites = ciphers(socket.supportedCipherSuites).also { check(it.isNotEmpty()) }
            socket.needClientAuth = false // Pairing credential authenticates the client INSIDE pinned TLS.
            socket.bind(InetSocketAddress(InetAddress.getByName(host), port), 4)
            listener = socket
            daemonThreads.newThread { acceptLoop(socket) }.start()
            return LinkEndpoint(host, socket.localPort, gate.serverId, fingerprint).checked(allowLoopback)
        } catch (error: Exception) { socket.close(); close(); throw error }
    }

    private fun acceptLoop(listener: SSLServerSocket) {
        try {
            while (!closed.get()) {
                val socket = listener.accept() as SSLSocket
                sockets.add(socket)
                if (closed.get()) { socket.close(); sockets.remove(socket); break }
                try {
                    // Includes queue time, handshake and reads: no unlimited slow connections.
                    val timeout = deadlines.schedule({ try { socket.close() } catch (_: IOException) {} }, 10, TimeUnit.SECONDS)
                    try {
                        workers.execute {
                            try { handle(socket) } finally {
                                timeout.cancel(false)
                                try { socket.close() } catch (_: IOException) {}
                                sockets.remove(socket)
                            }
                        }
                    } catch (error: RejectedExecutionException) {
                        timeout.cancel(false); socket.close(); sockets.remove(socket)
                    }
                } catch (_: RejectedExecutionException) { socket.close(); sockets.remove(socket) }
            }
        } catch (_: IOException) { close() }
    }

    private fun handle(socket: SSLSocket) {
        try {
            socket.harden()
            socket.startHandshake()
            val request = LinkWire.read(socket.inputStream)
            val paired = request.size == 4 && request[0] == "PAIR" &&
                gate.pair(request[1], request[2], request[3])
            val allowed = paired || (request.size == 3 && request[0] == "PROBE" &&
                gate.authorized(request[1], request[2]))
            LinkWire.write(socket.outputStream, if (allowed) listOf("OK", gate.serverId, request[1]) else listOf("DENIED"))
            if (paired) onPaired()
        } catch (_: Exception) {
            // No credential, invitation or database contents are logged or reflected to the peer.
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        gate.cancelInvitation()
        try { listener?.close() } catch (_: IOException) {}
        sockets.forEach { try { it.close() } catch (_: IOException) {} }
        sockets.clear()
        workers.shutdownNow()
        deadlines.shutdownNow()
    }
}

class LinkDenied : IOException("El servidor no ha autorizado este dispositivo")

object LanLinkClient {
    fun pair(invitation: LinkInvitation, client: ClientLink, call: LinkCall = LinkCall(),
             allowLoopback: Boolean = false): String {
        require(invitation.endpoint == client.endpoint) { "Invitacion y destino distintos" }
        LanPolicy.hex(invitation.secret)
        return exchange(client, listOf("PAIR", client.deviceId, client.token, invitation.secret), call, allowLoopback)
    }
    fun probe(client: ClientLink, call: LinkCall = LinkCall(), allowLoopback: Boolean = false): String =
        exchange(client, listOf("PROBE", client.deviceId, client.token), call, allowLoopback)

    private fun exchange(client: ClientLink, fields: List<String>, call: LinkCall, allowLoopback: Boolean): String {
        val endpoint = client.endpoint.checked(allowLoopback)
        LanPolicy.id(client.deviceId); LanPolicy.hex(client.token)
        require(client.deviceId != endpoint.serverId) { "No puedes vincular el movil consigo mismo" }
        val timeout = clientDeadline.schedule({ call.close() }, 10, TimeUnit.SECONDS)
        try {
            val raw = Socket()
            call.track(raw)
            raw.connect(InetSocketAddress(endpoint.host, endpoint.port), 5_000)
            val tls = pinnedClientContext(endpoint.fingerprint).socketFactory
                .createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
            call.track(tls)
            tls.harden()
            tls.startHandshake() // Certificate pin MUST pass before writing the secret/token.
            LinkWire.write(tls.outputStream, fields)
            val reply = LinkWire.read(tls.inputStream)
            if (reply != listOf("OK", endpoint.serverId, client.deviceId)) throw LinkDenied()
            return tls.session.protocol
        } finally { timeout.cancel(false); call.close() }
    }
}
