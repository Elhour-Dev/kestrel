package dev.kestrel.app

import dev.kestrel.app.data.SponsorBlock
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.streams.Fallback
import dev.kestrel.app.data.streams.Streams
import dev.kestrel.app.player.YtDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Live checks against YouTube and SponsorBlock. CI runs these daily after updating
 * NewPipeExtractor; a release is only published when they all pass.
 */
class SmokeTest {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() = NpRepo.init("US", "en")
        const val VIDEO = "jNQXAC9IVRw"
    }

    @Test
    fun search() = runBlocking {
        val page = NpRepo.search("music")
        println("search: ${page.items.size} items")
        assertTrue("search returned nothing", page.items.isNotEmpty())
    }

    /** NewPipe first, then the backup client, like the app. A bot check on the CI network skips the test. */
    private fun resolve() = runBlocking {
        try {
            Streams.resolve(VIDEO)
        } catch (e: Exception) {
            val botCheck = generateSequence<Throwable>(e) { it.cause }.any { it.javaClass.simpleName.contains("NotBot") || it.message?.contains("not a bot") == true }
            org.junit.Assume.assumeFalse("YouTube bot check on this network: ${e.message}", botCheck)
            throw e
        }
    }

    @Test
    fun stream() = runBlocking {
        val r = resolve()
        println("stream via ${if (r.viaFallback) "backup client" else "NewPipe"}")
        println("stream: ${r.videoOnly.size} video, ${r.audio.size} audio")
        assertTrue("no audio streams", r.audio.isNotEmpty())
        assertTrue("no video streams", r.videoOnly.isNotEmpty() || r.muxed.isNotEmpty())
    }

    private fun fetch(url: String): Pair<Int, Int> =
        Http.client.newCall(YtDataSource.rangedRequest(url, 0, 256 * 1024 - 1, 1)).execute().use { it.code to it.body.bytes().size }

    @Test
    fun download() = runBlocking {
        // Same ranged request the player and downloader use; falls back to the backup client like the app.
        var (code, bytes) = fetch(resolve().pickAudio()!!.url)
        if (code == 403) {
            val backup = runCatching { Fallback.resolve(VIDEO) }.getOrNull()
            org.junit.Assume.assumeTrue("YouTube blocked both clients on this network", backup != null)
            val r = fetch(backup!!.pickAudio()!!.url); code = r.first; bytes = r.second
        }
        println("download: HTTP $code, $bytes bytes")
        assertTrue("HTTP $code", code in 200..299)
        assertTrue("download too small: $bytes", bytes >= 64 * 1024)
    }

    @Test
    fun sponsorBlock() = runBlocking {
        // Popular video with long-standing segments; an empty list is still a working API.
        val segs = SponsorBlock.segments("dQw4w9WgXcQ", listOf("sponsor", "selfpromo", "intro", "outro", "interaction", "preview", "music_offtopic"))
        println("sponsorblock: ${segs.size} segments")
        assertTrue(segs.all { it.endMs > it.startMs })
    }
}
