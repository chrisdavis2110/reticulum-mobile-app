package io.github.thatsfguy.reticulum.transport

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.posix.AF_INET6
import platform.posix.AI_NUMERICHOST
import platform.posix.IPPROTO_IPV6
import platform.posix.IPV6_JOIN_GROUP
import platform.posix.NI_NUMERICHOST
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.if_nametoindex
import platform.posix.ipv6_mreq
import platform.posix.memcpy
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socklen_tVar

@OptIn(ExperimentalForeignApi::class)
actual class UdpSocket6 actual constructor() {
    @kotlin.concurrent.Volatile
    private var fd: Int = -1

    actual suspend fun bind(linkLocalAddress: String, interfaceName: String, port: Int) =
        withContext(Dispatchers.Default) {
            fd = openSocket("$linkLocalAddress%$interfaceName", port, bind = true)
        }

    actual suspend fun joinMulticast(multicastAddress: String, interfaceName: String) =
        withContext(Dispatchers.Default) {
            val sock = fd
            if (sock < 0) error("UdpSocket6 not bound")
            val idx = if_nametoindex(interfaceName)
            if (idx == 0u) error("if_nametoindex($interfaceName) failed")
            memScoped {
                val mreq = alloc<ipv6_mreq>()
                val addrBytes = parseIpv6Literal(multicastAddress)
                addrBytes.usePinned { pin ->
                    memcpy(mreq.ipv6mr_multiaddr.ptr, pin.addressOf(0), 16.convert())
                }
                mreq.ipv6mr_interface = idx
                val rc = setsockopt(
                    sock,
                    IPPROTO_IPV6,
                    IPV6_JOIN_GROUP,
                    mreq.ptr,
                    sizeOf<ipv6_mreq>().convert(),
                )
                if (rc != 0) error("IPV6_JOIN_GROUP errno=$errno")
            }
        }

    actual suspend fun send(
        data: ByteArray,
        host: String,
        port: Int,
        interfaceName: String?,
    ) = withContext(Dispatchers.Default) {
        val scoped = if (interfaceName != null) "$host%$interfaceName" else host
        val sock = if (fd >= 0) fd else openSocket("::", 0, bind = true)
        val owned = fd < 0
        try {
            sendToAddress(sock, scoped, port, data)
        } finally {
            if (owned) close(sock)
        }
    }

    actual fun incoming(): Flow<UdpDatagram> = callbackFlow {
        val sock = fd
        if (sock < 0) error("UdpSocket6 not bound")
        val buf = ByteArray(2048)
        memScoped {
            val storage = alloc<sockaddr_storage>()
            val addrLen = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_storage>().convert() }
            while (true) {
                val n = buf.usePinned { pin ->
                    recvfrom(
                        sock,
                        pin.addressOf(0).reinterpret<ByteVar>(),
                        buf.size.convert(),
                        0,
                        storage.ptr.reinterpret<sockaddr>(),
                        addrLen.ptr,
                    )
                }
                if (n <= 0L) break
                val host = nameFromSockaddr(storage) ?: continue
                trySend(UdpDatagram(buf.copyOf(n.toInt()), host))
            }
        }
        close()
        awaitClose { }
    }.flowOn(Dispatchers.Default)

    actual suspend fun close() = withContext(Dispatchers.Default) {
        val s = fd
        if (s >= 0) {
            fd = -1
            close(s)
        }
    }

    private fun openSocket(hostPortScope: String, port: Int, bind: Boolean): Int = memScoped {
        val hints = alloc<addrinfo>().apply {
            ai_family = AF_INET6
            ai_socktype = SOCK_DGRAM
            ai_flags = AI_NUMERICHOST
        }
        val resPtr = allocPointerTo<addrinfo>()
        val rc = getaddrinfo(hostPortScope, port.toString(), hints.ptr, resPtr.ptr)
        if (rc != 0) error("getaddrinfo($hostPortScope:$port) rc=$rc")
        try {
            val ai = resPtr.value!!.pointed
            val sock = socket(AF_INET6, SOCK_DGRAM, 0)
            if (sock < 0) error("socket() errno=$errno")
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            if (bind) {
                if (platform.posix.bind(sock, ai.ai_addr, ai.ai_addrlen) != 0) {
                    val e = errno
                    close(sock)
                    error("bind errno=$e")
                }
            }
            sock
        } finally {
            freeaddrinfo(resPtr.value)
        }
    }

    private fun sendToAddress(sock: Int, hostScope: String, port: Int, data: ByteArray) {
        memScoped {
            val hints = alloc<addrinfo>().apply {
                ai_family = AF_INET6
                ai_socktype = SOCK_DGRAM
            }
            val resPtr = allocPointerTo<addrinfo>()
            val rc = getaddrinfo(hostScope, port.toString(), hints.ptr, resPtr.ptr)
            if (rc != 0) error("getaddrinfo($hostScope:$port) rc=$rc")
            try {
                val ai = resPtr.value!!.pointed
                data.usePinned { pin ->
                    val n = sendto(
                        sock,
                        pin.addressOf(0).reinterpret<ByteVar>(),
                        data.size.convert(),
                        0,
                        ai.ai_addr,
                        ai.ai_addrlen,
                    )
                    if (n < 0) error("sendto errno=$errno")
                }
            } finally {
                freeaddrinfo(resPtr.value)
            }
        }
    }

    private fun nameFromSockaddr(storage: sockaddr_storage): String? = memScoped {
        val hostBuf = allocArray<ByteVar>(64)
        val rc = getnameinfo(
            storage.ptr.reinterpret(),
            sizeOf<sockaddr_storage>().convert(),
            hostBuf,
            64u,
            null,
            0u,
            NI_NUMERICHOST,
        )
        if (rc != 0) return null
        hostBuf.toKString().substringBefore('%').lowercase()
    }

    private fun parseIpv6Literal(host: String): ByteArray {
        val parts = host.split(':')
        val words = IntArray(8)
        var idx = 0
        for (part in parts) {
            if (part.isEmpty()) continue
            words[idx++] = part.toInt(16)
        }
        return ByteArray(16) { i ->
            val word = words[i / 2]
            if (i % 2 == 0) ((word shr 8) and 0xFF).toByte() else (word and 0xFF).toByte()
        }
    }
}
