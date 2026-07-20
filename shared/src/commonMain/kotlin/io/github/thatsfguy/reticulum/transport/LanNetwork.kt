package io.github.thatsfguy.reticulum.transport

import io.github.thatsfguy.reticulum.transport.auto.LanInterface

/**
 * Enumerate link-local IPv6 interfaces suitable for AutoInterface.
 * Filters mirror upstream `DARWIN_IGNORE_IFS` / `ANDROID_IGNORE_IFS`.
 */
expect object LanNetwork {
    fun listLinkLocalInterfaces(): List<LanInterface>
}
