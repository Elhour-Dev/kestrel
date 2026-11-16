package dev.kestrel.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JumpAheadTest {
    private fun heat(vararg v: Float) = v.mapIndexed { i, x -> HeatPoint(i * 10_000L, x) }

    @Test fun picksNextPeakAhead() {
        val h = heat(0.9f, 0.2f, 0.3f, 0.8f, 0.4f, 1f, 0.1f)
        assertEquals(30_000L, JumpAhead.next(h, 0))
        assertEquals(50_000L, JumpAhead.next(h, 30_000))
    }

    @Test fun skipsLowPeaksAndNearbyOnes() {
        val h = heat(0.1f, 0.3f, 0.2f, 0.6f, 0.1f)
        assertEquals(30_000L, JumpAhead.next(h, 0))
        assertNull(JumpAhead.next(h, 20_000))
    }

    @Test fun emptyHeatMap() = assertNull(JumpAhead.next(emptyList(), 0))
}
