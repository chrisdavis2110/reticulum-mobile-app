package io.github.thatsfguy.reticulum.platform

/**
 * RNode LoRa radio configuration. Values are sent verbatim to the RNode
 * via KISS commands when the BLE link comes up. Adjust to match the
 * local mesh you're joining — wrong freq/BW/SF means no one hears you.
 *
 * Defaults: 914.875 MHz, 125 kHz BW, SF 7, CR 4/6, +22 dBm TX.
 * Change these in Settings → Radio config; a fresh install picks them up
 * automatically.
 */
data class RadioConfig(
    val frequencyHz: Long = 914_875_000L,
    val bandwidthHz: Long = 125_000L,
    val spreadingFactor: Int = 7,    // 7..12
    val codingRate: Int = 6,         // 5..8 (4/5 .. 4/8)
    val txPowerDbm: Int = 22,        // -9..22 typical
)
