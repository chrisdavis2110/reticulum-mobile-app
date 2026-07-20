package io.github.thatsfguy.reticulum.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EphemeralManualLabelTest {
    @Test
    fun placeholdersAreEphemeral() {
        assertTrue(isEphemeralManualLabel("(via URL bar)"))
        assertTrue(isEphemeralManualLabel("(via cross-node link)"))
        assertTrue(isEphemeralManualLabel("(via shared link)"))
        assertTrue(isEphemeralManualLabel("(QR scan)"))
    }

    @Test
    fun realNicknamesAreDurable() {
        assertFalse(isEphemeralManualLabel("Alice"))
        assertFalse(isEphemeralManualLabel("Bob's node"))
        assertNull(durableUserLabel("(via URL bar)"))
        assertNull(durableUserLabel(""))
        assertEquals("Alice", durableUserLabel("Alice"))
    }

    @Test
    fun effectiveDisplayNamePrefersUserLabelUnlessCleared() {
        val withPlaceholder = StoredDestination(
            hash = "a".repeat(32),
            identityHash = "",
            publicKey = ByteArray(0),
            destHash = ByteArray(16),
            nameHash = ByteArray(0),
            ratchetPub = null,
            displayName = "Real Node",
            appName = "nomadnetwork.node",
            appLabel = "Nomad Network node",
            telemetry = null,
            lat = null,
            lon = null,
            appDataHex = "",
            lastSeen = 0,
            rssi = null,
            favorite = false,
            source = "manual",
            userLabel = "(via URL bar)",
        )
        assertEquals("(via URL bar)", withPlaceholder.effectiveDisplayName)

        val afterAnnounce = withPlaceholder.copy(userLabel = null)
        assertEquals("Real Node", afterAnnounce.effectiveDisplayName)
    }
}
