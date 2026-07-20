package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.transport.auto.LanInterface
import io.github.thatsfguy.reticulum.transport.cinterop.ifaddrs.freeifaddrs
import io.github.thatsfguy.reticulum.transport.cinterop.ifaddrs.getifaddrs
import io.github.thatsfguy.reticulum.transport.cinterop.ifaddrs.ifaddrs
import io.github.thatsfguy.reticulum.transport.cinterop.ifaddrs.reticulum_read_in6_addr
import io.github.thatsfguy.reticulum.transport.cinterop.ifaddrs.sockaddr_in6
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET6

private val DARWIN_IGNORED = setOf("awdl0", "llw0", "lo0", "en5", "lo")

@OptIn(ExperimentalForeignApi::class)
actual object LanNetwork {
    actual fun listLinkLocalInterfaces(): List<LanInterface> {
        val out = mutableListOf<LanInterface>()
        memScoped {
            val ifap = allocPointerTo<ifaddrs>()
            if (getifaddrs(ifap.ptr) != 0) return emptyList()
            try {
                var ptr = ifap.value
                while (ptr != null) {
                    val ifa = ptr.pointed
                    val name = ifa.ifa_name?.toKString() ?: ""
                    val addrPtr = ifa.ifa_addr
                    if (name !in DARWIN_IGNORED && addrPtr != null) {
                        val sin6 = addrPtr.reinterpret<sockaddr_in6>().pointed
                        if (sin6.sin6_family.toInt() == AF_INET6) {
                            val addrBytes = readIpv6Bytes(addrPtr.reinterpret<sockaddr_in6>())
                            val host = formatIpv6(addrBytes)
                            if (host.startsWith("fe80:", ignoreCase = true)) {
                                val normalized = host.lowercase()
                                    .replace(Regex("fe80:[0-9a-f]*::"), "fe80::")
                                out += LanInterface(name, normalized)
                            }
                        }
                    }
                    ptr = ifa.ifa_next
                }
            } finally {
                freeifaddrs(ifap.value)
            }
        }
        return out.distinctBy { it.name }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readIpv6Bytes(sa: kotlinx.cinterop.CPointer<sockaddr_in6>): ByteArray {
    val out = ByteArray(16)
    out.usePinned { pin ->
        reticulum_read_in6_addr(sa, pin.addressOf(0).reinterpret<UByteVar>())
    }
    return out
}

private fun formatIpv6(bytes: ByteArray): String {
    val parts = (0 until 8).map { i ->
        val hi = bytes[i * 2].toInt() and 0xFF
        val lo = bytes[i * 2 + 1].toInt() and 0xFF
        ((hi shl 8) or lo).toString(16)
    }
    return parts.joinToString(":")
}
