package io.github.thatsfguy.reticulum.engine

/**
 * User-selectable cadences for the engine's periodic re-announce timer.
 * `0` disables automatic announces (manual "Send announce" still works).
 */
object AnnounceIntervalPresets {
    const val DISABLED_MS: Long = 0L
    const val DEFAULT_MS: Long = 60 * 60_000L

    data class Option(val ms: Long, val label: String)

    val options: List<Option> = listOf(
        Option(DISABLED_MS, "Off (manual only)"),
        Option(5 * 60_000L, "Every 5 minutes"),
        Option(15 * 60_000L, "Every 15 minutes"),
        Option(30 * 60_000L, "Every 30 minutes"),
        Option(60 * 60_000L, "Every hour"),
        Option(2 * 60 * 60_000L, "Every 2 hours"),
        Option(4 * 60 * 60_000L, "Every 4 hours"),
    )

    fun labelFor(ms: Long): String =
        options.firstOrNull { it.ms == ms }?.label ?: "Every ${ms / 60_000} minutes"

    fun summaryFor(ms: Long): String = when (ms) {
        DISABLED_MS ->
            "Auto announce is off — tap Send announce when you want peers to see you."
        else ->
            "An announce is sent automatically ${labelFor(ms).removePrefix("Every ").lowercase()} while connected."
    }

    fun normalize(ms: Long): Long =
        options.firstOrNull { it.ms == ms }?.ms ?: DEFAULT_MS
}
