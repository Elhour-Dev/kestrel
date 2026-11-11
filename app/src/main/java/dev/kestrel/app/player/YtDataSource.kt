package dev.kestrel.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.streams.Fallback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * HTTP source for YouTube media. googlevideo "videoplayback" URLs are fetched in small
 * ranged POST requests (like YouTube's own players), which avoids throttling and 403s.
 */
@UnstableApi
class YtDataSource : BaseDataSource(true) {
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = YtDataSource()
    }

    companion object {
        private const val CHUNK = 2L * 1024 * 1024
        private val POST_BODY = byteArrayOf(0x78, 0)

        fun userAgentFor(url: String): String {
            val c = Regex("[?&]c=([A-Z_]+)").find(url)?.groupValues?.get(1)
            return when (c) {
                "VISIONOS" -> runCatching { YoutubeParsingHelper.getVisionOsUserAgent(null) }.getOrDefault(Http.BROWSER_UA)
                "ANDROID_VR" -> Fallback.VR_UA
                else -> Http.BROWSER_UA
            }
        }

        fun isPlayback(url: String) = url.contains("googlevideo.com/videoplayback?")

        /** Builds a ranged request the same way the player does. Shared with the downloader. */
        fun rangedRequest(url: String, start: Long, endInclusive: Long, rn: Int): Request {
            val c = Regex("[?&]c=([A-Z_]+)").find(url)?.groupValues?.get(1)
            val b = Request.Builder()
                .url("$url&range=$start-$endInclusive&rn=$rn")
                .header("User-Agent", userAgentFor(url))
                .post(POST_BODY.toRequestBody())
            if (c == null || c.startsWith("WEB")) {
                b.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
            }
            return b.build()
        }
    }

    private var uri: Uri? = null
    private var url = ""
    private var position = 0L
    private var end = -1L
    private var chunkEnd = 0L
    private var chunked = false
    private var response: Response? = null
    private var stream: InputStream? = null
    private var rn = 1
    private var opened = false

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        url = dataSpec.uri.toString()
        position = dataSpec.position
        chunked = isPlayback(url)
        val result: Long
        if (chunked) {
            val clen = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            end = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length
                clen != null -> clen
                else -> -1
            }
            openChunk(dataSpec)
            result = if (end >= 0) end - position else C.LENGTH_UNSET.toLong()
        } else {
            val b = Request.Builder().url(url).header("User-Agent", Http.BROWSER_UA)
            if (position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val last = if (dataSpec.length != C.LENGTH_UNSET.toLong()) (position + dataSpec.length - 1).toString() else ""
                b.header("Range", "bytes=$position-$last")
            }
            val resp = Http.mediaClient.newCall(b.build()).execute()
            check(resp, dataSpec)
            response = resp
            stream = resp.body.byteStream()
            val len = resp.body.contentLength()
            end = if (len >= 0) position + len else -1
            result = if (len >= 0) len else C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return result
    }

    private fun check(resp: Response, spec: DataSpec) {
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            resp.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code, msg, null, emptyMap(), spec, ByteArray(0),
            )
        }
    }

    private fun openChunk(spec: DataSpec) {
        closeResponse()
        val last = if (end >= 0) min(position + CHUNK, end) - 1 else position + CHUNK - 1
        val resp = try {
            Http.mediaClient.newCall(rangedRequest(url, position, last, rn++)).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(e, spec, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        check(resp, spec)
        response = resp
        stream = resp.body.byteStream()
        chunkEnd = last + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end in 0..position) return C.RESULT_END_OF_INPUT
        val s = stream ?: return C.RESULT_END_OF_INPUT
        var want = length.toLong()
        if (end >= 0) want = min(want, end - position)
        if (chunked) want = min(want, chunkEnd - position)
        val n = try {
            s.read(buffer, offset, want.toInt())
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException(e, DataSpec(uri!!), androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        if (n == -1) {
            if (chunked && position == chunkEnd && (end < 0 || position < end)) {
                openChunk(DataSpec(uri!!))
                return read(buffer, offset, length)
            }
            return C.RESULT_END_OF_INPUT
        }
        position += n
        bytesTransferred(n)
        if (chunked && position == chunkEnd && (end < 0 || position < end)) {
            // Next read opens the following chunk.
            closeResponse()
            stream = EmptyStream
        }
        return n
    }

    private object EmptyStream : InputStream() {
        override fun read(): Int = -1
        override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    }

    private fun closeResponse() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
    }

    override fun close() {
        closeResponse()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
