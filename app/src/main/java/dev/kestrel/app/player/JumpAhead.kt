package dev.kestrel.app.player

/** One slice of YouTube's "most replayed" graph: where it starts and how much it is replayed (0..1). */
data class HeatPoint(val startMs: Long, val intensity: Float)

/** "Jump ahead": skip to the next part people replay most, from the watch page's heat-map. */
object JumpAhead {
    /** Peaks below this (the busiest part is 1.0) are not worth a jump. */
    private const val MIN_PEAK = 0.5f
    /** Ignore peaks this close to where we are now. */
    private const val MIN_GAP_MS = 15_000L

    /** Start of the next peak after [posMs], or null when there is none. */
    fun next(heat: List<HeatPoint>, posMs: Long): Long? {
        for (i in heat.indices) {
            val h = heat[i]
            if (h.startMs < posMs + MIN_GAP_MS || h.intensity < MIN_PEAK) continue
            val prev = heat.getOrNull(i - 1)?.intensity ?: 0f
            val next = heat.getOrNull(i + 1)?.intensity ?: 0f
            if (h.intensity >= prev && h.intensity >= next) return h.startMs
        }
        return null
    }
}
