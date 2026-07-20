package io.github.thatsfguy.reticulum.transport

import java.security.MessageDigest

actual object PlatformSha256 {
    actual fun hash(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
