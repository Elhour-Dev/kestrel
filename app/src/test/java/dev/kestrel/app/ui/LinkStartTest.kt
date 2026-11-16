package dev.kestrel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkStartTest {
    @Test fun formats() {
        assertEquals(95_000L, linkStartMs("https://youtu.be/abcdefghijk?t=95"))
        assertEquals(95_000L, linkStartMs("https://www.youtube.com/watch?v=abcdefghijk&t=95s"))
        assertEquals(95_000L, linkStartMs("https://www.youtube.com/watch?v=abcdefghijk&t=1m35s"))
        assertEquals(3_723_000L, linkStartMs("https://www.youtube.com/watch?v=abcdefghijk&t=1h2m3s"))
        assertEquals(30_000L, linkStartMs("https://www.youtube.com/embed/abcdefghijk?start=30"))
        assertNull(linkStartMs("https://www.youtube.com/watch?v=abcdefghijk"))
        assertNull(linkStartMs("https://www.youtube.com/watch?v=abcdefghijk&list=PLt=5"))
        assertNull(linkStartMs("https://youtu.be/abcdefghijk?t=9223372036854775807"))
        assertNull(linkStartMs("https://youtu.be/abcdefghijk?t=0"))
    }
}
