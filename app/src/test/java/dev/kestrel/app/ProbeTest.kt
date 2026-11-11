package dev.kestrel.app

import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.newpipe.NpDownloader
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfo

class ProbeTest {
    private fun fetch(url: String, ua: String, post: Boolean, rangeParam: Boolean): String {
        val u = if (rangeParam) "$url&range=0-65535&rn=1" else url
        val b = Request.Builder().url(u).header("User-Agent", ua)
        if (!rangeParam) b.header("Range", "bytes=0-65535")
        if (post) b.post(byteArrayOf(0x78, 0).toRequestBody())
        return Http.client.newCall(b.build()).execute().use { "${it.code} ${it.body.bytes().size}" }
    }

    @Test
    fun probe() {
        NewPipe.init(NpDownloader)
        val yt = ServiceList.YouTube
        val info = StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        println("muxed=${info.videoStreams.size} videoOnly=${info.videoOnlyStreams.size} audio=${info.audioStreams.size} hls=${info.hlsUrl.take(40)}")
        val a = info.audioStreams.first()
        val c = Regex("[?&]c=([A-Z_]+)").find(a.content)?.groupValues?.get(1)
        println("client c=$c itag=${a.itag} delivery=${a.deliveryMethod}")
        val vision = YoutubeParsingHelper.getVisionOsUserAgent(null)
        for (ua in listOf(Http.BROWSER_UA, vision)) for (post in listOf(false, true)) for (rp in listOf(false, true)) {
            println("ua=${ua.take(25)} post=$post rangeParam=$rp -> ${fetch(a.content, ua, post, rp)}")
        }
        info.videoStreams.forEach { println(" muxed ${it.resolution} ${it.format}") }
        info.videoOnlyStreams.forEach { println(" vo ${it.resolution} ${it.format} itag=${it.itag} ${it.codec}") }
        info.audioStreams.forEach { println(" au ${it.averageBitrate} ${it.format} itag=${it.itag} lang=${it.audioLocale}") }
        println("subs=${info.subtitles.map { it.languageTag + ":" + it.format }}")
    }
}
