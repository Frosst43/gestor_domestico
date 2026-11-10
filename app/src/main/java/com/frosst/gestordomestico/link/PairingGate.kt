package com.frosst.gestordomestico.link

import java.util.concurrent.TimeUnit

/** One authorized client for this first LAN stage. Never replaces a peer silently. */
class PairingGate(
    val serverId: String,
    private val store: PeerStore,
    private val monotonicMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
) {
    private data class Window(val secretHash: String, val deadline: Long, var failures: Int = 0, var accepted: TrustedPeer? = null)
    private var window: Window? = null
    init { LanPolicy.id(serverId) }
    @Synchronized
    fun invite(endpoint: LinkEndpoint, wallClockMillis: Long = System.currentTimeMillis()): LinkInvitation {
        require(endpoint.serverId == serverId) { "Identidad de servidor incorrecta" }
        check(store.load() == null) { "Revoca el cliente anterior antes de invitar a otro" }
        val secret = LinkSecrets.newToken()
        window = Window(LinkSecrets.tokenHash(secret), monotonicMillis() + 300_000)
        require(wallClockMillis in 1..(Long.MAX_VALUE - 300_000)) { "Reloj no valido" }
        return LinkInvitation(endpoint, secret, wallClockMillis + 300_000)
    }
    @Synchronized
    fun pair(deviceId: String, token: String, invitationSecret: String): Boolean {
        val w = window ?: return false
        if (monotonicMillis() >= w.deadline || w.failures >= 5) { window = null; return false }
        val valid = try {
            LanPolicy.id(deviceId); LanPolicy.hex(token); LanPolicy.hex(invitationSecret)
            deviceId != serverId && LinkSecrets.same(w.secretHash, LinkSecrets.tokenHash(invitationSecret))
        } catch (_: IllegalArgumentException) { false }
        if (!valid) { w.failures++; if (w.failures >= 5) window = null; return false }
        val proposed = TrustedPeer(deviceId, LinkSecrets.tokenHash(token))
        val existing = store.load()
        if (existing != null) return w.accepted == proposed && existing == proposed
        store.save(proposed); w.accepted = proposed
        return true
    }
    @Synchronized
    fun authorized(deviceId: String, token: String): Boolean {
        val peer = store.load() ?: return false
        return try {
            LanPolicy.id(deviceId)
            peer.deviceId == deviceId && LinkSecrets.same(peer.tokenHash, LinkSecrets.tokenHash(token))
        } catch (_: IllegalArgumentException) { false }
    }
    /** Serialize commit authorization against revocation; never hold this lock while reading sockets. */
    @Synchronized
    fun <T> authorizedAction(deviceId: String, token: String, action: () -> T): T {
        check(authorized(deviceId, token)) { "Authorization revoked" }
        return action()
    }
    @Synchronized fun cancelInvitation() { window = null }
    @Synchronized fun revoke() { store.save(null); window = null }
}
