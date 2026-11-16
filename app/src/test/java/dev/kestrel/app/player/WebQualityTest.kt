package dev.kestrel.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebQualityTest {
    private val levels = listOf("hd1080", "hd720", "large", "medium", "small", "tiny", "auto")

    @Test fun picksLevel() {
        assertEquals("hd1080", WebHost.pickLevel(levels, 0))
        assertEquals("hd720", WebHost.pickLevel(levels, 720))
        assertEquals("hd1080", WebHost.pickLevel(levels, 2160))
        assertEquals("large", WebHost.pickLevel(levels, 600))
        assertEquals("tiny", WebHost.pickLevel(listOf("medium", "tiny"), 100))
        assertNull(WebHost.pickLevel(listOf("auto"), 720))
    }
}
