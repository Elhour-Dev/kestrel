package dev.kestrel.app

import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.newpipe.NpDownloader
import okhttp3.Request
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo

class ProbeTest {
    @Test
    fun probe() {
        NewPipe.init(NpDownloader)
        val yt = ServiceList.YouTube
        val search = SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery("lofi hip hop"))
        val first = search.relatedItems.filterIsInstance<StreamInfoItem>().first()
        println("search ok: ${search.relatedItems.size} items, first=${first.name} ${first.url}")
        val info = StreamInfo.getInfo(yt, first.url)
        println("dash=${info.dashMpdUrl} hls=${info.hlsUrl}")
        println("muxed=${info.videoStreams.size} videoOnly=${info.videoOnlyStreams.size} audio=${info.audioStreams.size}")
        info.videoOnlyStreams.take(3).forEach { println(" v ${it.resolution} ${it.format} ${it.deliveryMethod} ${it.content.take(160)}") }
        info.audioStreams.take(3).forEach { println(" a ${it.averageBitrate} ${it.format} ${it.deliveryMethod} ${it.content.take(160)}") }
        val a = info.audioStreams.first()
        val req = Request.Builder().url(a.content).header("User-Agent", Http.BROWSER_UA).header("Range", "bytes=0-65535").build()
        Http.client.newCall(req).execute().use { println("audio fetch: ${it.code} ${it.body.bytes().size} bytes") }
        val v = info.videoOnlyStreams.first()
        val req2 = Request.Builder().url(v.content).header("User-Agent", Http.BROWSER_UA).header("Range", "bytes=0-65535").build()
        Http.client.newCall(req2).execute().use { println("video fetch: ${it.code} ${it.body.bytes().size} bytes") }
    }
}
