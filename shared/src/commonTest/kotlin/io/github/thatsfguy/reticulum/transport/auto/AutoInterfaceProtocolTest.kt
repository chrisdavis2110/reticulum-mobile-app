package io.github.thatsfguy.reticulum.transport.auto

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoInterfaceProtocolTest {

    @Test
    fun multicastDiscoveryAddress_matchesUpstreamReticulumGroup() {
        // From RNS/Interfaces/AutoInterface.py with group_id "reticulum".
        assertEquals(
            "ff12:0:eac4:d70b:fb1c:16e4:5e39:485e:31e1",
            AutoInterfaceProtocol.multicastDiscoveryAddress("reticulum"),
        )
    }

    @Test
    fun discoveryToken_is32Bytes() {
        val token = AutoInterfaceProtocol.discoveryToken("reticulum", "fe80::1")
        assertEquals(32, token.size)
    }
}
