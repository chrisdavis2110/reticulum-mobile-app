package io.github.thatsfguy.reticulum.transport

/** Platform SHA-256 for wire-protocol helpers (AutoInterface peering tokens). */
expect object PlatformSha256 {
    fun hash(data: ByteArray): ByteArray
}
