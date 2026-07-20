package io.github.thatsfguy.reticulum.transport.auto

import io.github.thatsfguy.reticulum.transport.PlatformSha256

/**
 * Wire constants and helpers for upstream RNS [AutoInterface] interop.
 * Mirrors `RNS/Interfaces/AutoInterface.py` defaults (group_id
 * `"reticulum"`, discovery port 29716, data port 42671).
 */
object AutoInterfaceProtocol {
    const val DEFAULT_GROUP_ID = "reticulum"
    const val DISCOVERY_PORT = 29_716
    const val UNICAST_DISCOVERY_PORT = DISCOVERY_PORT + 1
    const val DATA_PORT = 42_671

    const val ANNOUNCE_INTERVAL_MS = 1_600L
    const val PEER_JOB_INTERVAL_MS = 4_000L
    const val PEERING_TIMEOUT_MS = 22_000L
    /** Android upstream multiplies by 1.25 — applied in [AutoInterfaceTransport]. */
    const val ANDROID_PEERING_TIMEOUT_MULTIPLIER = 1.25
    const val REVERSE_PEERING_INTERVAL_MS = (ANNOUNCE_INTERVAL_MS * 3.25).toLong()

    /** Temporary multicast scope link — ff12:… per upstream defaults. */
    private const val MULTICAST_ADDRESS_TYPE = "1"
    private const val DISCOVERY_SCOPE = "2"

    /** 32-byte SHA-256 peering / discovery token. */
    fun discoveryToken(groupId: String, linkLocalAddress: String): ByteArray =
        PlatformSha256.hash((groupId.encodeToByteArray()) + linkLocalAddress.encodeToByteArray())

    /** IPv6 multicast discovery address derived from [groupId]. */
    fun multicastDiscoveryAddress(groupId: String = DEFAULT_GROUP_ID): String {
        val g = PlatformSha256.hash(groupId.encodeToByteArray())
        val gt = buildString {
            append('0')
            for (i in 1 until 14 step 2) {
                append(':')
                append(((g[i].toInt() and 0xFF) + (g[i - 1].toInt() and 0xFF shl 8)).toString(16).padStart(2, '0'))
            }
        }
        return "ff$MULTICAST_ADDRESS_TYPE$DISCOVERY_SCOPE:$gt"
    }

    fun validatePeeringPacket(data: ByteArray, groupId: String, senderLinkLocal: String): Boolean {
        if (data.size < 32) return false
        val expected = discoveryToken(groupId, senderLinkLocal)
        return data.copyOfRange(0, 32).contentEquals(expected)
    }
}

/** One adopted link-local interface (Wi‑Fi / Ethernet). */
data class LanInterface(
    val name: String,
    val linkLocalAddress: String,
)
