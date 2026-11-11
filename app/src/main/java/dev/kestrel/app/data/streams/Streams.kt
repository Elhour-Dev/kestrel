package dev.kestrel.app.data.streams

import dev.kestrel.app.data.innertube.at
import dev.kestrel.app.data.innertube.str
import dev.kestrel.app.data.innertube.thumb
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.net.Http
import dev.kestrel.app.data.newpipe.NpRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

data class StreamOption(
    val url: String,
    val ext: String,          // mp4, webm, m4a
    val height: Int,          // 0 for audio
    val codec: String,
    val bitrate: Int,
    val itag: Int,
    val fps: Int = 30,
) {
    val isAvc get() = codec.startsWith("avc")
    val label get() = if (height > 0) "${height}p${if (fps > 30) fps.toString() else ""}" else "${bitrate / 1000} kbps $ext"
}

data class SubtitleOption(val lang: String, val label: String, val url: String, val mime: String, val auto: Boolean)

data class Resolved(
    val video: Video,
    val description: String,
    val channelAvatar: String?,
    val subscribers: Long,
    val likes: Long,
    val uploadDate: String?,
    val videoOnly: List<StreamOption>,
    val audio: List<StreamOption>,
    val muxed: List<StreamOption>,
    val hls: String?,
    val subtitles: List<SubtitleOption>,
    val related: List<Item>,
    val viaFallback: Boolean = false,
) {
    val isLive get() = video.isLive

    /** Best video stream not above [maxHeight] (0 = best). Prefers H.264 up to 1080p. */
    fun pickVideo(maxHeight: Int): StreamOption? {
        val all = videoOnly.ifEmpty { muxed }
        if (all.isEmpty()) return null
        val limit = if (maxHeight <= 0) Int.MAX_VALUE else maxHeight
        val fit = all.filter { it.height <= limit }.ifEmpty { listOf(all.minBy { it.height }) }
        val h = fit.maxOf { it.height }
        val same = fit.filter { it.height == h }
        return same.firstOrNull { it.isAvc } ?: same.firstOrNull { it.codec.startsWith("vp9") || it.codec.startsWith("vp09") }
        ?: same.first()
    }

    fun pickAudio(): StreamOption? =
        audio.filter { it.ext == "m4a" }.maxByOrNull { it.bitrate } ?: audio.maxByOrNull { it.bitrate }

    fun qualities(): List<Int> = videoOnly.ifEmpty { muxed }.map { it.height }.distinct().sortedDescending()
}

object Streams {
    /** Forces the fallback client. Set by the startup health check when NewPipe streams fail. */
    @Volatile var preferFallback = false

    suspend fun resolve(videoId: String): Resolved {
        if (preferFallback) {
            runCatching { return Fallback.resolve(videoId) }
        }
        return try {
            fromNewPipe(NpRepo.stream(videoId))
        } catch (e: Exception) {
            try {
                Fallback.resolve(videoId)
            } catch (_: Exception) {
                throw e
            }
        }
    }

    private fun ext(f: MediaFormat?) = when (f) {
        MediaFormat.MPEG_4 -> "mp4"
        MediaFormat.WEBM -> "webm"
        MediaFormat.M4A -> "m4a"
        MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS, MediaFormat.OPUS -> "webm"
        MediaFormat.v3GPP -> "3gp"
        else -> f?.suffix ?: "mp4"
    }

    fun fromNewPipe(info: StreamInfo): Resolved {
        val id = NpRepo.videoId(info.url) ?: info.id
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val video = Video(
            id = id,
            title = info.name,
            channelName = info.uploaderName.orEmpty(),
            channelId = NpRepo.channelId(info.uploaderUrl),
            thumbnail = NpRepo.bestImage(info.thumbnails) ?: dev.kestrel.app.data.model.thumbFor(id),
            durationSec = info.duration,
            views = info.viewCount,
            uploaded = info.textualUploadDate,
            isLive = live,
            isShort = info.isShortFormContent,
        )
        val progressive = { s: org.schabi.newpipe.extractor.stream.Stream ->
            s.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP && s.isUrl
        }
        return Resolved(
            video = video,
            description = info.description?.content.orEmpty(),
            channelAvatar = NpRepo.smallImage(info.uploaderAvatars),
            subscribers = info.uploaderSubscriberCount,
            likes = info.likeCount,
            uploadDate = info.textualUploadDate,
            videoOnly = info.videoOnlyStreams.filter(progressive).map {
                StreamOption(it.content, ext(it.format), it.height, it.codec.orEmpty(), it.bitrate, it.itag, it.fps)
            },
            audio = info.audioStreams.filter(progressive).filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" || info.audioStreams.none { a -> a.audioTrackType?.name == "ORIGINAL" } }.map {
                StreamOption(it.content, ext(it.format), 0, it.codec.orEmpty(), it.averageBitrate * 1000, it.itag)
            },
            muxed = info.videoStreams.filter(progressive).map {
                StreamOption(it.content, ext(it.format), it.height, it.codec.orEmpty(), it.bitrate, it.itag, it.fps)
            },
            hls = info.hlsUrl?.takeIf { it.isNotBlank() },
            subtitles = info.subtitles
                .filter { it.format == MediaFormat.VTT || it.format == MediaFormat.TTML }
                .groupBy { it.languageTag + it.isAutoGenerated }
                .map { (_, list) -> list.firstOrNull { it.format == MediaFormat.VTT } ?: list.first() }
                .map {
                    SubtitleOption(
                        it.languageTag, it.displayLanguageName + if (it.isAutoGenerated) " (auto)" else "",
                        it.content, if (it.format == MediaFormat.VTT) "text/vtt" else "application/ttml+xml", it.isAutoGenerated,
                    )
                },
            related = NpRepo.related(info),
        )
    }
}

/** Second way to get streams: YouTube's VR app client, which serves plain URLs. */
object Fallback {
    const val VR_VERSION = "1.65.10"
    const val VR_UA = "com.google.android.apps.youtube.vr.oculus/$VR_VERSION (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    suspend fun resolve(videoId: String): Resolved = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "ANDROID_VR")
                    put("clientVersion", VR_VERSION)
                    put("deviceMake", "Oculus")
                    put("deviceModel", "Quest 3")
                    put("androidSdkVersion", 32)
                    put("osName", "Android")
                    put("osVersion", "12L")
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", VR_UA)
            .header("X-Youtube-Client-Name", "28")
            .header("X-Youtube-Client-Version", VR_VERSION)
            .build()
        val r = Http.client.newCall(req).execute().use { Json.parseToJsonElement(it.body.string()) as JsonObject }
        val status = r.at("playabilityStatus").str("status")
        if (status != "OK") error(r.at("playabilityStatus").str("reason") ?: "Video not playable ($status)")
        val d = r.obj("videoDetails")
        val fmts = (r.at("streamingData.adaptiveFormats") as? JsonArray).orEmpty().map { it as JsonObject }
            .filter { it.str("url") != null }
        fun opt(f: JsonObject): StreamOption {
            val mime = f.str("mimeType").orEmpty()
            val codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty()
            val ext = when {
                mime.startsWith("audio/mp4") -> "m4a"
                mime.contains("webm") -> "webm"
                else -> "mp4"
            }
            return StreamOption(
                f.str("url")!!, ext, f.str("height")?.toIntOrNull() ?: 0, codec,
                f.str("bitrate")?.toIntOrNull() ?: 0, f.str("itag")?.toIntOrNull() ?: 0, f.str("fps")?.toIntOrNull() ?: 30,
            )
        }
        val live = d.str("isLive") == "true"
        val video = Video(
            id = videoId,
            title = d.str("title").orEmpty(),
            channelName = d.str("author").orEmpty(),
            channelId = d.str("channelId"),
            thumbnail = d?.obj("thumbnail").thumb() ?: dev.kestrel.app.data.model.thumbFor(videoId),
            durationSec = d.str("lengthSeconds")?.toLongOrNull() ?: -1,
            views = d.str("viewCount")?.toLongOrNull() ?: -1,
            isLive = live,
        )
        Resolved(
            video = video,
            description = d.str("shortDescription").orEmpty(),
            channelAvatar = null, subscribers = -1, likes = -1, uploadDate = null,
            videoOnly = fmts.filter { it.str("mimeType")?.startsWith("video") == true }.map(::opt),
            audio = fmts.filter { it.str("mimeType")?.startsWith("audio") == true }.map(::opt),
            muxed = emptyList(),
            hls = r.at("streamingData").str("hlsManifestUrl"),
            subtitles = emptyList(),
            related = emptyList(),
            viaFallback = true,
        )
    }

    private fun JsonObject?.obj(k: String) = this?.get(k) as? JsonObject
}
