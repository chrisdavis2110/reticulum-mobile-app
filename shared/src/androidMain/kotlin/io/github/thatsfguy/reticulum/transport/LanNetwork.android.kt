package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.transport.auto.LanInterface
import java.net.Inet6Address
import java.net.NetworkInterface

private val IGNORED = setOf("lo", "lo0", "dummy0", "tun0")

actual object LanNetwork {
    actual fun listLinkLocalInterfaces(): List<LanInterface> {
        val out = mutableListOf<LanInterface>()
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in ifaces) {
            val name = iface.name ?: continue
            if (name in IGNORED) continue
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (addr !is Inet6Address) continue
                val host = addr.hostAddress?.substringBefore('%') ?: continue
                if (!host.startsWith("fe80:", ignoreCase = true)) continue
                val normalized = host.lowercase().replace(Regex("fe80:[0-9a-f]*::"), "fe80::")
                out += LanInterface(name, normalized)
                break
            }
        }
        return out.distinctBy { it.name }
    }
}
