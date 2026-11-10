package com.frosst.gestordomestico.link

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** No DNS, public IPs, wildcard binds or implicit Internet fallback in this phase. */
object LanPolicy {
    fun address(value: String, allowLoopback: Boolean = false): String {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) }) { "IPv4 local no valida" }
        val octets = parts.map { it.toInt() }
        require(octets.all { it in 0..255 }) { "IPv4 local no valida" }
        require(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (allowLoopback && value == "127.0.0.1")) { "Solo se permite una direccion privada LAN" }
        return value
    }
    fun id(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value) { "Identidad no valida" }
        return value
    }
    fun hex(value: String): String {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "Credencial o huella no valida" }
        return value
    }
}

object LinkSecrets {
    private val random = SecureRandom()
    fun newToken(): String = hex(ByteArray(32).also { random.nextBytes(it) })
    fun digest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun tokenHash(token: String): String = digest(LanPolicy.hex(token).toByteArray(Charsets.US_ASCII))
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII)
    )
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class LinkEndpoint(val host: String, val port: Int, val serverId: String, val fingerprint: String) {
    fun checked(allowLoopback: Boolean = false): LinkEndpoint = apply {
        LanPolicy.address(host, allowLoopback)
        require(port in 1024..65535) { "Puerto no valido" }
        LanPolicy.id(serverId)
        LanPolicy.hex(fingerprint)
    }
}

/** The invitation is sensitive. Only encode() is allowed to reveal its secret to the UI. */
data class LinkInvitation(val endpoint: LinkEndpoint, val secret: String, val expiresAt: Long) {
    fun encode(): String = listOf("GD-LAN-1", endpoint.host, endpoint.port, endpoint.serverId,
        endpoint.fingerprint, secret, expiresAt).joinToString("|")
    override fun toString(): String = "LinkInvitation(secret=<redacted>, expiresAt=$expiresAt)"
    companion object {
        fun parse(text: String, allowLoopback: Boolean = false): LinkInvitation {
            require(text.length <= 512) { "Invitacion demasiado larga" }
            val p = text.trim().split('|')
            require(p.size == 7 && p[0] == "GD-LAN-1") { "Invitacion no compatible" }
            val endpoint = LinkEndpoint(p[1], p[2].toInt(), p[3], p[4]).checked(allowLoopback)
            val expires = p[6].toLong()
            require(expires > 0) { "Caducidad no valida" }
            return LinkInvitation(endpoint, LanPolicy.hex(p[5]), expires)
        }
    }
}

data class ClientLink(val endpoint: LinkEndpoint, val deviceId: String, val token: String,
                      val confirmed: Boolean = false) {
    override fun toString(): String = "ClientLink(deviceId=$deviceId, token=<redacted>, confirmed=$confirmed)"
}

data class TrustedPeer(val deviceId: String, val tokenHash: String)

/** Implementations must persist before returning and must fail rather than silently reset. */
interface PeerStore {
    fun load(): TrustedPeer?
    fun save(peer: TrustedPeer?)
}

/** Length-prefixed, bounded messages. No shopping contents or Java object serialization. */
object LinkWire {
    private const val MAGIC = 0x47444c31
    private const val MAX_FRAME = 4096
    fun write(stream: OutputStream, fields: List<String>) {
        require(fields.size in 1..8 && fields.all { it.length <= 512 }) { "Mensaje fuera de limites" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeByte(fields.size)
            fields.forEach { out.writeUTF(it) }
        }
        val bytes = buffer.toByteArray()
        require(bytes.size <= MAX_FRAME) { "Mensaje demasiado grande" }
        DataOutputStream(stream).apply { writeInt(MAGIC); writeInt(bytes.size); write(bytes); flush() }
    }
    fun read(stream: InputStream): List<String> {
        val input = DataInputStream(stream)
        require(input.readInt() == MAGIC) { "Protocolo no compatible" }
        val size = input.readInt()
        require(size in 1..MAX_FRAME) { "Mensaje demasiado grande" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val fields = DataInputStream(ByteArrayInputStream(bytes))
        val count = fields.readUnsignedByte()
        require(count in 1..8) { "Mensaje no valido" }
        val result = List(count) { fields.readUTF().also { require(it.length <= 512) } }
        require(fields.available() == 0) { "Contenido adicional no permitido" }
        return result
    }
}
