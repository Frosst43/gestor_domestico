package com.frosst.gestordomestico.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

data class LinkDiskState(val peer: TrustedPeer? = null, val client: ClientLink? = null)

/** Atomic, authenticated storage. Missing/corrupt keys are errors, not permission to re-pair. */
class AndroidLinkStore(context: Context) : PeerStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "lan-link-v1.bin"))
    private val keyAlias = "gestor.lan.storage.v1"
    private val aad = "gestor-lan-state-v1".toByteArray(Charsets.US_ASCII)
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        if (!create) throw IOException("No se puede abrir el almacen de vinculacion")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun state(): LinkDiskState {
        val stream = try { file.openRead() } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            return LinkDiskState()
        }
        val bytes = stream.use { source ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (true) {
                val count = source.read(chunk)
                if (count < 0) break
                require(buffer.size() + count <= 16_384) { "Almacen de vinculacion demasiado grande" }
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 1) { "Almacen de vinculacion no compatible" }
        val iv = ByteArray(12).also { input.readFully(it) }
        val encrypted = ByteArray(input.available()).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv)); cipher.updateAAD(aad)
        val data = DataInputStream(ByteArrayInputStream(cipher.doFinal(encrypted)))
        val peer = if (data.readBoolean()) TrustedPeer(LanPolicy.id(data.readUTF()), LanPolicy.hex(data.readUTF())) else null
        val client = if (data.readBoolean()) {
            val endpoint = LinkEndpoint(data.readUTF(), data.readInt(), data.readUTF(), data.readUTF()).checked()
            ClientLink(endpoint, LanPolicy.id(data.readUTF()), LanPolicy.hex(data.readUTF()), data.readBoolean())
        } else null
        require(data.available() == 0 && !(peer != null && client != null)) { "Estado de vinculacion no valido" }
        return LinkDiskState(peer, client)
    }

    @Synchronized override fun load(): TrustedPeer? = state().peer
    @Synchronized override fun save(peer: TrustedPeer?) {
        val old = state()
        require(peer == null || old.client == null) { "Este movil ya es cliente" }
        write(old.copy(peer = peer))
    }
    @Synchronized fun saveClient(client: ClientLink?) {
        val old = state()
        require(client == null || old.peer == null) { "Este movil ya es servidor de otro dispositivo" }
        write(old.copy(client = client))
    }
    @Synchronized fun forget() { write(LinkDiskState()) }

    private fun write(state: LinkDiskState) {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            out.writeBoolean(state.peer != null)
            state.peer?.let { out.writeUTF(LanPolicy.id(it.deviceId)); out.writeUTF(LanPolicy.hex(it.tokenHash)) }
            out.writeBoolean(state.client != null)
            state.client?.let {
                it.endpoint.checked()
                out.writeUTF(it.endpoint.host); out.writeInt(it.endpoint.port)
                out.writeUTF(it.endpoint.serverId); out.writeUTF(it.endpoint.fingerprint)
                out.writeUTF(LanPolicy.id(it.deviceId)); out.writeUTF(LanPolicy.hex(it.token))
                out.writeBoolean(it.confirmed)
            }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        val content = ByteArrayOutputStream()
        DataOutputStream(content).use { out ->
            out.writeInt(1); out.write(cipher.iv); out.write(cipher.doFinal(plain.toByteArray()))
        }
        val output = file.startWrite()
        try { output.write(content.toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}

/** Device key remains in AndroidKeyStore. Certificate fingerprint is sent out-of-band. */
object AndroidLinkTls {
    private const val ALIAS = "gestor.lan.server.v1"
    @Synchronized fun server(): Pair<SSLContext, String> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=Gestor Domestico LAN"))
                .setCertificateSerialNumber(BigInteger(127, SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 315_360_000_000L))
                .build())
            generator.generateKeyPair()
        }
        val cert = store.getCertificate(ALIAS) as X509Certificate
        cert.checkValidity()
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
        return context to LinkSecrets.digest(cert.encoded)
    }
}
