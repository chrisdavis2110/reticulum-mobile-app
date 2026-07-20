package io.github.thatsfguy.reticulum.transport

import kotlinx.coroutines.flow.Flow

/** One inbound UDP datagram plus the sender's IPv6 host (without %if scope). */
data class UdpDatagram(
    val data: ByteArray,
    val senderHost: String,
) {
    override fun equals(other: Any?): Boolean =
        other is UdpDatagram && data.contentEquals(other.data) && senderHost == other.senderHost

    override fun hashCode(): Int = data.contentHashCode() * 31 + senderHost.hashCode()
}

/**
 * IPv6 UDP socket with optional interface binding and multicast join.
 * Used by [AutoInterfaceTransport] for discovery + peer data.
 */
expect class UdpSocket6() {
    /** Bind to [port] on [linkLocalAddress] scoped to [interfaceName]. */
    suspend fun bind(linkLocalAddress: String, interfaceName: String, port: Int)

    /** Join [multicastAddress] on [interfaceName] for discovery receive. */
    suspend fun joinMulticast(multicastAddress: String, interfaceName: String)

    /** Send [data] to [host]:[port], optionally scoped to [interfaceName]. */
    suspend fun send(data: ByteArray, host: String, port: Int, interfaceName: String? = null)

    /** Cold flow of inbound datagrams; completes when [close] is called. */
    fun incoming(): Flow<UdpDatagram>

    suspend fun close()
}
