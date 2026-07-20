package io.github.thatsfguy.reticulum.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual object PlatformSha256 {
    actual fun hash(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        if (data.isEmpty()) {
            out.usePinned { outPin ->
                CC_SHA256(null, 0u, outPin.addressOf(0).reinterpret())
            }
        } else {
            data.usePinned { dataPin ->
                out.usePinned { outPin ->
                    CC_SHA256(
                        dataPin.addressOf(0),
                        data.size.convert(),
                        outPin.addressOf(0).reinterpret(),
                    )
                }
            }
        }
        return out
    }
}
