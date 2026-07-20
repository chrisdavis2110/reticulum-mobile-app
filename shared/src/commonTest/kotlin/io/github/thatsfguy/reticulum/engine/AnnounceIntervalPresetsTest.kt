package io.github.thatsfguy.reticulum.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class AnnounceIntervalPresetsTest {
    @Test
    fun normalizeKnownPreset() {
        assertEquals(300_000L, AnnounceIntervalPresets.normalize(300_000L))
    }

    @Test
    fun normalizeUnknownFallsBackToDefault() {
        assertEquals(AnnounceIntervalPresets.DEFAULT_MS, AnnounceIntervalPresets.normalize(123_456L))
    }

    @Test
    fun summaryWhenDisabled() {
        assertEquals(
            "Auto announce is off — tap Send announce when you want peers to see you.",
            AnnounceIntervalPresets.summaryFor(0L),
        )
    }
}
