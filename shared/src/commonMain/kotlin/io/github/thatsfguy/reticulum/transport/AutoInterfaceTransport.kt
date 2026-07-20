package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.transport.auto.AutoInterfaceProtocol
import io.github.thatsfguy.reticulum.transport.auto.LanInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reticulum AutoInterface — discovers peers on the local LAN via IPv6
 * multicast and exchanges raw Reticulum packets over UDP (port 42671).
 * Interops with desktop / rnsd nodes running upstream
 * `type = AutoInterface`.
 */
class AutoInterfaceTransport(
    private val scope: CoroutineScope,
    private val groupId: String = AutoInterfaceProtocol.DEFAULT_GROUP_ID,
    private val txLogger: (String) -> Unit = {},
    /** When true, stretch peer timeout like upstream does on Android. */
    private val androidPeerTimeout: Boolean = false,
) : Transport {

    private val _state = MutableStateFlow(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state

    private val _incoming = MutableSharedFlow<IncomingPacket>(
        replay = 0,
        extraBufferCapacity = 128,
    )
    override val incoming: Flow<IncomingPacket> = _incoming.asSharedFlow()

    private val peers = mutableMapOf<String, Peer>()
    private val peersMutex = Mutex()
    private val writeMutex = Mutex()
    private val recentHashesMutex = Mutex()
    private val recentHashes = LinkedHashMap<String, Long>()
    private var adopted: List<LanInterface> = emptyList()
    private var jobs = mutableListOf<Job>()

    private data class Peer(
        val address: String,
        val interfaceName: String,
        var lastHeardMs: Long,
        var lastOutboundMs: Long,
    )

    private val peeringTimeoutMs: Long
        get() = if (androidPeerTimeout) {
            (AutoInterfaceProtocol.PEERING_TIMEOUT_MS * AutoInterfaceProtocol.ANDROID_PEERING_TIMEOUT_MULTIPLIER).toLong()
        } else {
            AutoInterfaceProtocol.PEERING_TIMEOUT_MS
        }

    // @Throws — connect raises when no link-local IPv6 interfaces are
    // available (Wi‑Fi off). Swift calls via `try await`.
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    override suspend fun connect() {
        if (_state.value == TransportState.Connected || _state.value == TransportState.Connecting) return
        _state.value = TransportState.Connecting
        adopted = LanNetwork.listLinkLocalInterfaces()
        if (adopted.isEmpty()) {
            txLogger("AutoInterface: no link-local IPv6 interfaces — is Wi‑Fi on?")
            _state.value = TransportState.Error
            error("No link-local IPv6 interfaces available for AutoInterface")
        }
        txLogger("AutoInterface: adopting ${adopted.size} interface(s): ${adopted.joinToString { it.name }}")
        jobs.forEach { it.cancel() }
        jobs.clear()
        adopted.forEach { iface ->
            jobs += scope.launch { multicastDiscoveryLoop(iface) }
            jobs += scope.launch { multicastReceiveLoop(iface) }
            jobs += scope.launch { unicastDiscoveryLoop(iface) }
            jobs += scope.launch { dataReceiveLoop(iface) }
        }
        jobs += scope.launch { peerMaintenanceLoop() }
        delay((AutoInterfaceProtocol.ANNOUNCE_INTERVAL_MS * 1.2).toLong())
        _state.value = TransportState.Connected
        txLogger("AutoInterface: online (${adopted.size} if(s), group=$groupId)")
    }

    override suspend fun disconnect() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        peersMutex.withLock { peers.clear() }
        _state.value = TransportState.Disconnected
        txLogger("AutoInterface: disconnected")
    }

    override suspend fun send(packet: ByteArray) {
        if (_state.value != TransportState.Connected) error("AutoInterface not connected")
        val snapshot = peersMutex.withLock { peers.values.toList() }
        if (snapshot.isEmpty()) {
            txLogger("AutoInterface: send ${packet.size}B dropped — no peers discovered yet")
            return
        }
        writeMutex.withLock {
            for (peer in snapshot) {
                runCatching {
                    udpSend(peer.interfaceName, packet, peer.address, AutoInterfaceProtocol.DATA_PORT)
                }.onFailure {
                    txLogger("AutoInterface: send to ${peer.address} failed: ${it.message}")
                }
            }
        }
    }

    private suspend fun udpSend(ifname: String, data: ByteArray, host: String, port: Int) {
        val sock = UdpSocket6()
        try {
            sock.send(data, host, port, ifname)
        } finally {
            sock.close()
        }
    }

    private suspend fun multicastDiscoveryLoop(iface: LanInterface) {
        val mcast = AutoInterfaceProtocol.multicastDiscoveryAddress(groupId)
        val token = AutoInterfaceProtocol.discoveryToken(groupId, iface.linkLocalAddress)
        while (scope.isActive && _state.value == TransportState.Connected) {
            runCatching {
                udpSend(iface.name, token, mcast, AutoInterfaceProtocol.DISCOVERY_PORT)
            }
            delay(AutoInterfaceProtocol.ANNOUNCE_INTERVAL_MS)
        }
    }

    private suspend fun multicastReceiveLoop(iface: LanInterface) {
        val mcast = AutoInterfaceProtocol.multicastDiscoveryAddress(groupId)
        val sock = UdpSocket6()
        try {
            sock.bind(iface.linkLocalAddress, iface.name, AutoInterfaceProtocol.DISCOVERY_PORT)
            sock.joinMulticast(mcast, iface.name)
            sock.incoming().collect { dg ->
                handleDiscoveryPacket(dg, iface.name)
            }
        } finally {
            sock.close()
        }
    }

    private suspend fun unicastDiscoveryLoop(iface: LanInterface) {
        val sock = UdpSocket6()
        try {
            sock.bind(iface.linkLocalAddress, iface.name, AutoInterfaceProtocol.UNICAST_DISCOVERY_PORT)
            sock.incoming().collect { dg ->
                handleDiscoveryPacket(dg, iface.name)
            }
        } finally {
            sock.close()
        }
    }

    private suspend fun dataReceiveLoop(iface: LanInterface) {
        val sock = UdpSocket6()
        try {
            sock.bind(iface.linkLocalAddress, iface.name, AutoInterfaceProtocol.DATA_PORT)
            sock.incoming().collect { dg ->
                if (!shouldAcceptPacket(dg.data)) return@collect
                addOrRefreshPeer(dg.senderHost, iface.name)
                _incoming.tryEmit(IncomingPacket(packet = dg.data, rssi = null, snr = null))
            }
        } finally {
            sock.close()
        }
    }

    private suspend fun handleDiscoveryPacket(dg: UdpDatagram, ifname: String) {
        val sender = normalizeHost(dg.senderHost)
        if (adopted.any { it.linkLocalAddress == sender }) return // our own echo
        if (!AutoInterfaceProtocol.validatePeeringPacket(dg.data, groupId, sender)) return
        addOrRefreshPeer(sender, ifname)
        txLogger("AutoInterface: peer discovered $sender on $ifname")
    }

    private suspend fun peerMaintenanceLoop() {
        while (scope.isActive && _state.value == TransportState.Connected) {
            delay(AutoInterfaceProtocol.PEER_JOB_INTERVAL_MS)
            val now = nowMs()
            val expired = peersMutex.withLock {
                val dead = peers.filterValues { now - it.lastHeardMs > peeringTimeoutMs }.keys.toList()
                dead.forEach { peers.remove(it) }
                dead
            }
            if (expired.isNotEmpty()) {
                txLogger("AutoInterface: peer(s) timed out: ${expired.joinToString()}")
            }
            val reverse = peersMutex.withLock { peers.values.toList() }
            for (peer in reverse) {
                if (now - peer.lastOutboundMs > AutoInterfaceProtocol.REVERSE_PEERING_INTERVAL_MS) {
                    peer.lastOutboundMs = now
                    val token = AutoInterfaceProtocol.discoveryToken(groupId, peer.address)
                    runCatching {
                    udpSend(
                        peer.interfaceName,
                        token,
                        peer.address,
                        AutoInterfaceProtocol.UNICAST_DISCOVERY_PORT,
                    )
                    }
                }
            }
        }
    }

    private suspend fun addOrRefreshPeer(address: String, ifname: String) {
        val now = nowMs()
        peersMutex.withLock {
            val existing = peers[address]
            if (existing != null) {
                existing.lastHeardMs = now
            } else {
                peers[address] = Peer(address, ifname, now, now)
            }
        }
    }

    /** Short TTL dedup when multiple interfaces hear the same packet. */
    private suspend fun shouldAcceptPacket(data: ByteArray): Boolean {
        val n = minOf(data.size, 32)
        val key = buildString(n * 2) {
            for (i in 0 until n) {
                append((data[i].toInt() and 0xFF).toString(16).padStart(2, '0'))
            }
        }
        val now = nowMs()
        return recentHashesMutex.withLock {
            val iter = recentHashes.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (now - entry.value > 750L) iter.remove()
            }
            if (recentHashes.containsKey(key)) {
                false
            } else {
                recentHashes[key] = now
                true
            }
        }
    }

    private fun normalizeHost(host: String): String {
        var h = host.substringBefore('%')
        h = h.replace(Regex("fe80:[0-9a-f]*::", RegexOption.IGNORE_CASE), "fe80::")
        return h.lowercase()
    }

    private fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
