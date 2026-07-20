package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException

actual class UdpSocket6 actual constructor() {
    private var socket: MulticastSocket? = null

    actual suspend fun bind(linkLocalAddress: String, interfaceName: String, port: Int) =
        withContext(Dispatchers.IO) {
            val sock = MulticastSocket(null)
            sock.reuseAddress = true
            val ni = NetworkInterface.getByName(interfaceName)
                ?: error("NetworkInterface $interfaceName not found")
            val addr = Inet6Address.getByName("$linkLocalAddress%$interfaceName")
            sock.bind(InetSocketAddress(addr, port))
            sock.networkInterface = ni
            socket = sock
        }

    actual suspend fun joinMulticast(multicastAddress: String, interfaceName: String) =
        withContext(Dispatchers.IO) {
            val sock = socket ?: error("UdpSocket6 not bound")
            val ni = NetworkInterface.getByName(interfaceName)
                ?: error("NetworkInterface $interfaceName not found")
            val group = Inet6Address.getByName(multicastAddress)
            sock.joinGroup(InetSocketAddress(group, 0), ni)
        }

    actual suspend fun send(
        data: ByteArray,
        host: String,
        port: Int,
        interfaceName: String?,
    ) = withContext(Dispatchers.IO) {
        val sock = socket ?: run {
            // Ephemeral outbound socket (multicast announce / reverse peering).
            val s = MulticastSocket(null)
            s.reuseAddress = true
            if (interfaceName != null) {
                s.networkInterface = NetworkInterface.getByName(interfaceName)
            }
            s
        }
        val target = if (interfaceName != null) {
            Inet6Address.getByName("$host%$interfaceName")
        } else {
            Inet6Address.getByName(host)
        }
        val packet = DatagramPacket(data, data.size, target, port)
        try {
            sock.send(packet)
        } finally {
            if (socket == null) sock.close()
        }
    }

    actual fun incoming(): Flow<UdpDatagram> = callbackFlow {
        val sock = socket ?: error("UdpSocket6 not bound")
        val buf = ByteArray(2048)
        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
            } catch (_: SocketException) {
                break
            }
            val host = (packet.address as? Inet6Address)?.hostAddress?.substringBefore('%')
                ?: packet.address.hostAddress.substringBefore('%')
            trySend(UdpDatagram(packet.data.copyOf(packet.length), host.lowercase()))
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    actual suspend fun close() = withContext(Dispatchers.IO) {
        socket?.close()
        socket = null
    }
}
