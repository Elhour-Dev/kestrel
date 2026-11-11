package dev.kestrel.app.data.streams

import dev.kestrel.app.data.innertube.at
import dev.kestrel.app.data.innertube.str
import dev.kestrel.app.data.innertube.thumb
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.net.Http
import dev.kestrel.app.player.JsSolver
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
        // Signed-in links get through YouTube's bot check on networks where signed-out ones don't.
        val signedIn = runCatching { dev.kestrel.app.Graph.account.session.value != null }.getOrDefault(false)
        if (preferFallback || signedIn) {
            runCatching { return Fallback.resolve(videoId) }
        }
        return try {
            val r = fixLinks(fromNewPipe(NpRepo.stream(videoId)))
            if (!linksWork(r)) throw java.io.IOException("Stream links refused")
            r
        } catch (e: Exception) {
            try {
                Fallback.resolve(videoId)
            } catch (_: Exception) {
                throw e
            }
        }
    }

    /** Fetches the first few KB of one link to check YouTube accepts it. */
    private suspend fun linksWork(r: Resolved): Boolean = withContext(Dispatchers.IO) {
        if (r.video.isLive || !JsSolver.available) return@withContext true
        val url = r.pickAudio()?.url ?: r.pickVideo(360)?.url ?: return@withContext true
        runCatching {
            Http.client.newCall(dev.kestrel.app.player.YtDataSource.rangedRequest(url, 0, 4095, 1)).execute().use { it.isSuccessful }
        }.getOrDefault(true) // network trouble: let the player retry instead
    }

    @Volatile private var npCheck: Pair<Long, Boolean>? = null

    /** True when NewPipe can't decode "n" (its links then carry the raw value and get HTTP 403). */
    private fun newPipeNBroken(videoId: String): Boolean {
        val now = System.currentTimeMillis()
        npCheck?.takeIf { now - it.first < 60 * 60 * 1000L }?.let { return it.second }
        val broken = runCatching {
            org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                .getUrlWithThrottlingParameterDeobfuscated(videoId, "https://rr1---sn-test.googlevideo.com/videoplayback?n=kestrelTestValue1&x=1")
        }.isFailure
        npCheck = now to broken
        return broken
    }

    /** Decodes "n" in NewPipe's links with our own solver when NewPipe's decoder is broken. */
    suspend fun fixLinks(r: Resolved): Resolved {
        if (r.viaFallback || !JsSolver.available) return r
        val ns = (r.videoOnly + r.audio + r.muxed).mapNotNull { JsSolver.nOf(it.url) }
        if (ns.isEmpty() || !withContext(Dispatchers.IO) { newPipeNBroken(r.video.id) }) return r
        val solved = runCatching { JsSolver.solve(ns, emptyList()) }.getOrNull() ?: return r
        fun fix(o: StreamOption) = JsSolver.nOf(o.url)?.let { solved.n[it] }?.let { o.copy(url = JsSolver.withN(o.url, it)) } ?: o
        return r.copy(videoOnly = r.videoOnly.map(::fix), audio = r.audio.map(::fix), muxed = r.muxed.map(::fix))
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
            description = NpRepo.plain(info.description?.content),
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

/**
 * Second way to get streams, used when NewPipe is blocked (for example the "confirm you are not a bot" check).
 * Tries several YouTube clients with a fresh visitor ID and a BotGuard PO token, decodes the
 * protected links, and checks each one actually downloads before using it.
 */
object Fallback {
    const val VR_VERSION = "1.65.10"
    const val VR_UA = "com.google.android.apps.youtube.vr.oculus/$VR_VERSION (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    private data class Client(val name: String, val id: Int, val version: () -> String, val ua: String, val extra: Map<String, Any> = emptyMap(), val web: Boolean = false, val auth: Boolean = false)

    /** Used with the Google session: YouTube lets signed-in users through its bot check. */
    private val authClients = listOf(
        // Mobile web: still returns direct links (desktop web gets SABR-only streams).
        Client("MWEB", 2, { runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20261106.00.00") }, MWEB_UA, mapOf("userAgent" to MWEB_UA), web = true, auth = true),
        Client("TVHTML5", 7, { tvVersion() }, TV_UA, auth = true),
        // Safari web client: may return an HLS manifest.
        Client("WEB", 1, { runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20261106.00.00") }, SAFARI_UA, mapOf("userAgent" to SAFARI_UA), web = true, auth = true),
    )

    private val clients = listOf(
        Client("WEB", 1, { runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20261106.00.00") }, Http.BROWSER_UA, web = true),
        Client("MWEB", 2, { runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20261106.00.00") }, MWEB_UA, mapOf("userAgent" to MWEB_UA), web = true),
        Client("ANDROID_VR", 28, { VR_VERSION }, VR_UA, mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L")),
        Client("IOS", 5, { "20.45.4" }, "com.google.ios.youtube/20.45.4 (iPhone16,2; U; CPU iOS 18_6 like Mac OS X;)", mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.6.0.22G86")),
        Client("VISIONOS", 101, { "0.1" }, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15", mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice14,1", "osName" to "visionOS", "osVersion" to "1.3.21O771")),
    )

    private const val SAFARI_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
    const val MWEB_UA = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    private const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"

    @Volatile private var tvVer: String? = null

    /** Current TV client version, read from YouTube's TV page (old versions get refused). */
    private fun tvVersion(): String = tvVer ?: runCatching {
        val html = Http.client.newCall(Request.Builder().url("https://www.youtube.com/tv").header("User-Agent", TV_UA).build())
            .execute().use { it.body.string() }
        Regex("\"clientVersion\":\"(7\\.[0-9.]+)\"").find(html)?.groupValues?.get(1)
    }.getOrNull()?.also { tvVer = it } ?: "7.20261102.18.00"

    @Volatile private var diagTag = ""

    /** Index of the client that worked last time; tried first. */
    @Volatile private var preferred = 0

    fun freshVisitor(): String? = runCatching {
        val body = Http.client.newCall(Request.Builder().url("https://www.youtube.com/sw.js_data").header("User-Agent", Http.BROWSER_UA).build())
            .execute().use { it.body.string() }
        Regex("\"(Cg[st][A-Za-z0-9_%-]{20,})\"").find(body)?.groupValues?.get(1)
    }.getOrNull()

    suspend fun resolve(videoId: String): Resolved = withContext(Dispatchers.IO) {
        val sts = (if (JsSolver.available) JsSolver.signatureTimestamp() else null)
            ?: runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
        var lastError: Exception? = null
        val session = dev.kestrel.app.Graph.account.session.value
        if (session != null) {
            val ds = session.dataSyncId.substringBefore("||").ifBlank { null }
            val playerPot = dev.kestrel.app.player.PoTokens.mint(videoId)
            val captured = dev.kestrel.app.player.PotCapture.get(session)
            android.util.Log.w("Kestrel", "DIAG captured pot=${captured?.length}")
            val streamPot = captured ?: ds?.let { dev.kestrel.app.player.PoTokens.mint(it) }
            // TEMP: same request without our own player token, to see if that token is what caps links at 1 MB.
            authClients.firstOrNull { it.name == "MWEB" }?.let { m ->
                diagTag = "noPlayerPot"
                runCatching { tryClient(m, videoId, session.visitorData.ifBlank { freshVisitor() }, sts, null, streamPot, session) }
                    .onFailure { android.util.Log.w("Kestrel", "DIAG[noPlayerPot] ${it.message}") }
                diagTag = "withPlayerPot"
            }
            for (c in authClients) {
                try {
                    val r = tryClient(c, videoId, session.visitorData.ifBlank { freshVisitor() }, sts, playerPot, if (c.web) streamPot else null, session)
                    android.util.Log.i("Kestrel", "signed-in client ${c.name} works for $videoId")
                    return@withContext r
                } catch (e: Exception) {
                    android.util.Log.w("Kestrel", "signed-in client ${c.name} failed: ${e.message}")
                    lastError = e
                }
            }
        }
        // Round 1: BotGuard session (visitor + PO tokens). Round 2: brand-new session.
        for (round in 0..1) {
            if (round == 1) dev.kestrel.app.player.PoTokens.reset()
            val pot = runCatching { dev.kestrel.app.player.PoTokens.getWebClientPoToken(videoId) }.getOrNull()
            val visitor = pot?.visitorData ?: freshVisitor()
            val order = clients.indices.sortedBy { if (it == preferred) -1 else it }
            for (i in order) {
                val c = clients[i]
                try {
                    val r = tryClient(c, videoId, visitor, sts, pot?.playerRequestPoToken, pot?.streamingDataPoToken)
                    preferred = i
                    android.util.Log.i("Kestrel", "backup client ${c.name} works for $videoId (round $round)")
                    return@withContext r
                } catch (e: Exception) {
                    android.util.Log.w("Kestrel", "backup client ${c.name} failed (round $round): ${e.message}")
                    lastError = e
                }
            }
        }
        throw lastError ?: IllegalStateException("No backup client worked")
    }

    private suspend fun tryClient(c: Client, videoId: String, visitor: String?, sts: Int?, playerPot: String?, streamingPot: String?, session: dev.kestrel.app.data.account.Session? = null): Resolved {
        val version = c.version()
        val mobile = c.name == "MWEB"
        val sts = if (mobile && JsSolver.available) JsSolver.signatureTimestamp(true) ?: sts else sts
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", c.name)
                    put("clientVersion", version)
                    put("hl", "en")
                    put("gl", "US")
                    visitor?.let { put("visitorData", it) }
                    c.extra.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
                }
                val ds = session?.dataSyncId.orEmpty()
                if (ds.contains("||")) putJsonObject("user") { put("onBehalfOfUser", ds.substringBefore("||")) }
            }
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (sts != null) putJsonObject("playbackContext") { putJsonObject("contentPlaybackContext") { put("signatureTimestamp", sts) } }
            if (c.web && playerPot != null) putJsonObject("serviceIntegrityDimensions") { put("poToken", playerPot) }
        }
        val rb = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", c.ua)
            .header("X-Youtube-Client-Name", c.id.toString())
            .header("X-Youtube-Client-Version", version)
        visitor?.let { rb.header("X-Goog-Visitor-Id", it) }
        if (c.web || session != null) rb.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
        if (session != null) {
            rb.header("Cookie", session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            dev.kestrel.app.Graph.innerTube.authHeader(session.cookies)?.let { rb.header("Authorization", it) }
            rb.header("X-Goog-AuthUser", "0").header("X-Origin", "https://www.youtube.com")
        }
        val r = Http.client.newCall(rb.build()).execute().use { Json.parseToJsonElement(it.body.string()) as JsonObject }
        val status = r.at("playabilityStatus").str("status")
        if (status != "OK") error(r.at("playabilityStatus").str("reason") ?: "Video not playable ($status)")
        val d = r.obj("videoDetails")
        val raw = (r.at("streamingData.adaptiveFormats") as? JsonArray).orEmpty().map { it as JsonObject }

        fun cipherOf(f: JsonObject): Map<String, String>? = f.str("signatureCipher")
            ?.split("&")?.associate { it.substringBefore("=") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8") }

        // Decode every "n" and signature value in one go with the player script.
        val nVals = raw.mapNotNull { f -> (f.str("url") ?: cipherOf(f)?.get("url"))?.let(JsSolver::nOf) }
        val sVals = raw.mapNotNull { f -> cipherOf(f)?.get("s") }
        val solved = if (JsSolver.available && (nVals.isNotEmpty() || sVals.isNotEmpty())) {
            runCatching { JsSolver.solve(nVals, sVals, mobile) }.onFailure { android.util.Log.w("Kestrel", "solver failed: $it") }.getOrNull()
        } else null
        var newPipeBroken = false
        var sigLogged = 0

        fun urlOf(f: JsonObject): String? {
            var u = f.str("url")
            if (u == null) {
                val q = cipherOf(f) ?: return null
                val s = q["s"] ?: return null
                val sig = solved?.sig?.get(s)
                    ?: org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, s)
                u = q["url"] + "&" + (q["sp"] ?: "signature") + "=" + java.net.URLEncoder.encode(sig, "UTF-8")
                if (sigLogged++ == 0) android.util.Log.w("Kestrel", "DIAG sig ours=${sig.take(20)}.. np=${runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, s).take(20) }.getOrElse { it.toString().take(80) }} sp=${q["sp"]} len=${s.length}->${sig.length} sts=$sts")
            }
            val raw0 = u!!
            val n = JsSolver.nOf(raw0)
            val nn = n?.let { solved?.n?.get(it) }
            u = when {
                nn != null -> JsSolver.withN(raw0, nn)
                n == null || newPipeBroken -> raw0
                else -> runCatching { org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, raw0) }
                    .onFailure { newPipeBroken = true }.getOrDefault(raw0)
            }
            if (streamingPot != null && !u.contains("&pot=")) u += "&pot=$streamingPot"
            return u
        }

        fun opt(f: JsonObject): StreamOption? {
            val url = urlOf(f) ?: return null
            val mime = f.str("mimeType").orEmpty()
            val codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty()
            val ext = when {
                mime.startsWith("audio/mp4") -> "m4a"
                mime.contains("webm") -> "webm"
                else -> "mp4"
            }
            return StreamOption(
                url, ext, f.str("height")?.toIntOrNull() ?: 0, codec,
                f.str("bitrate")?.toIntOrNull() ?: 0, f.str("itag")?.toIntOrNull() ?: 0, f.str("fps")?.toIntOrNull() ?: 30,
            )
        }

        val audio = raw.filter { it.str("mimeType")?.startsWith("audio") == true }.mapNotNull { runCatching { opt(it) }.getOrNull() }
        val video = raw.filter { it.str("mimeType")?.startsWith("video") == true }.mapNotNull { runCatching { opt(it) }.getOrNull() }
        val live = d.str("isLive") == "true"
        val hls = r.at("streamingData").str("hlsManifestUrl")
        val hlsOnly = audio.isEmpty() && hls != null
        if (hlsOnly) {
            val code = Http.client.newCall(Request.Builder().url(hls!!).header("User-Agent", c.ua).build()).execute().use { it.code }
            if (code !in 200..299) error("${c.name}: HLS refused (HTTP $code)")
        } else if (!live) {
            // Make sure the links really download before trusting this client.
            val probe = audio.firstOrNull { it.ext == "m4a" } ?: audio.firstOrNull() ?: error("${c.name}: no audio (fmts=${raw.size} urls=${raw.count { it.str("url") != null }} cipher=${raw.count { it.str("signatureCipher") != null }} sabr=${r.at("streamingData").str("serverAbrStreamingUrl") != null})")
            val code = Http.client.newCall(dev.kestrel.app.player.YtDataSource.rangedRequest(probe.url, 0, 4095, 1)).execute().use { it.code }
            if (code !in 200..299) error("${c.name}: stream links refused (HTTP $code, ciphered=${raw.count { it.str("signatureCipher") != null }}/${raw.size})")
            run {
                val v = video.firstOrNull { it.height in 1..720 && it.ext == "mp4" }
                val vc = v?.let { Http.client.newCall(dev.kestrel.app.player.YtDataSource.rangedRequest(it.url, 0, 4095, 1)).execute().use { r2 -> r2.code } }
                val a2 = Http.client.newCall(dev.kestrel.app.player.YtDataSource.rangedRequest(probe.url, 0, 2097151, 2)).execute().use { r2 -> r2.code }
                val a3 = Http.client.newCall(dev.kestrel.app.player.YtDataSource.rangedRequest(probe.url, 2097152, 4194303, 3)).execute().use { r2 -> r2.code }
                android.util.Log.w("Kestrel", "DIAG ${c.name} audio=$code video(${v?.itag})=$vc audio2=$a2 audio3=$a3 ciphered=${raw.count { it.str("signatureCipher") != null }}")
                if (session != null) {
                    // TEMP: which form of the streaming token lets bytes past 1 MB through?
                    val base = probe.url.replace(Regex("&pot=[^&]*"), "")
                    val tok = Regex("&pot=([^&]*)").find(probe.url)?.groupValues?.get(1)
                    val variants = listOf(
                        "asIs" to probe.url,
                        "noPad" to (tok?.let { "$base&pot=${it.trimEnd('=')}" } ?: base),
                        "pctPad" to (tok?.let { "$base&pot=${it.replace("=", "%3D")}" } ?: base),
                        "none" to base,
                    )
                    val res = variants.mapIndexed { i, (label, u3) ->
                        "$label=" + Http.client.newCall(dev.kestrel.app.player.YtDataSource.rangedRequest(u3, 1048576, 1572863, i + 10)).execute().use { r2 -> r2.code }
                    }
                    android.util.Log.w("Kestrel", "DIAG[$diagTag] past1MB $res tokLen=${tok?.length}")
                }
            }
        } else if (hls == null) error("${c.name}: no live stream")
        val v = Video(
            id = videoId,
            title = d.str("title").orEmpty(),
            channelName = d.str("author").orEmpty(),
            channelId = d.str("channelId"),
            thumbnail = d?.obj("thumbnail").thumb() ?: dev.kestrel.app.data.model.thumbFor(videoId),
            durationSec = d.str("lengthSeconds")?.toLongOrNull() ?: -1,
            views = d.str("viewCount")?.toLongOrNull() ?: -1,
            isLive = live,
        )
        return Resolved(
            video = v,
            description = d.str("shortDescription").orEmpty(),
            channelAvatar = null, subscribers = -1, likes = -1, uploadDate = null,
            videoOnly = if (hlsOnly) emptyList() else video, audio = if (hlsOnly) emptyList() else audio, muxed = emptyList(),
            hls = hls, subtitles = emptyList(), related = emptyList(), viaFallback = true,
        )
    }

    private fun JsonObject?.obj(k: String) = this?.get(k) as? JsonObject
}
