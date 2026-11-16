package dev.kestrel.app.player

import dev.kestrel.app.data.streams.isUnplayable

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.kestrel.app.Graph
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.data.SponsorBlock
import dev.kestrel.app.data.SponsorCategories
import dev.kestrel.app.data.model.SponsorSegment
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.streams.Fallback
import dev.kestrel.app.data.streams.Resolved
import dev.kestrel.app.data.streams.Streams
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NowPlaying(
    val video: Video,
    val resolved: Resolved? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val quality: Int = 0,
    val audioOnly: Boolean = false,
    val localPath: String? = null,
    val segments: List<SponsorSegment> = emptyList(),
    /** Our links are blocked: play in YouTube's own web player ([WebHost]) instead. */
    val web: Boolean = false,
    /** Watch page details (likes, description, collaborators, bell), loaded next to the stream links. */
    val info: dev.kestrel.app.data.innertube.InnerTube.WatchInfo? = null,
)

/** Owns the single ExoPlayer used by the screen, background service, PiP and the popup. */
@UnstableApi
object PlayerManager {
    lateinit var player: ExoPlayer
        private set

    /** Player view of the web player: drives the notification and the on-screen controls. */
    val webPlayer by lazy { WebSessionPlayer() }
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> get() = _now

    private val _queue = MutableStateFlow<List<Video>>(emptyList())
    val queue: StateFlow<List<Video>> get() = _queue
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> get() = _index

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> get() = _messages

    private var loadJob: Job? = null
    private var infoJob: Job? = null
    private var retried = false
    private var webRetried = false
    private var lastWebPos = 0L
    private val skipped = HashSet<String>()
    private var tick = 0

    private lateinit var dataSourceFactory: DefaultDataSource.Factory

    fun init(context: Context) {
        appContext = context.applicationContext
        dataSourceFactory = DefaultDataSource.Factory(appContext, YtDataSource.Factory())
        player = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_500, 3_000).build())
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    _now.value?.let { Graph.store.savePosition(it.video.id, 0) }
                    if (_index.value < _queue.value.lastIndex) next()
                }
            }
        })
        WebHost.onChange = { webPlayer.refresh() }
        WebHost.onProgress = { pos, _ -> onWebProgress(pos) }
        WebHost.onEnded = { onWebEnded() }
        WebHost.onError = {
            // YouTube's page failed mid-video: reload once at the same spot, then show a clear message.
            val np = _now.value
            if (np?.web == true) {
                if (!webRetried) {
                    webRetried = true
                    startWeb(np.video, lastWebPos / 1000)
                } else {
                    WebHost.release()
                    _now.update { it?.copy(web = false, loading = false, error = "YouTube's player stopped with an error. Try again or switch VPN server.") }
                }
            }
        }
        WebHost.onBotCheck = {
            if (_now.value?.web == true) {
                WebHost.release()
                val hint = if (Graph.account.isSignedIn) "Try another VPN server." else "Sign in with Google (Library > Sign in) or try another VPN server."
                _now.update { it?.copy(web = false, loading = false, error = "YouTube is blocking video on this network (bot check). $hint") }
            }
        }
        WebHost.onCrash = {
            _now.update { if (it?.web == true) it.copy(web = false, loading = false, error = "The video player stopped. Try again.") else it }
        }
        handler.post(ticker)
    }

    // ------------- controls that work for both players -------------

    private val web get() = _now.value?.web == true
    fun isPlaying() = if (web) WebHost.playing else player.isPlaying
    fun isPlayingOrWill() = if (web) WebHost.playing else player.isPlaying || player.playWhenReady
    fun positionMs() = if (web) WebHost.positionMs else player.currentPosition
    fun durationMs() = if (web) WebHost.durationMs else player.duration
    fun pause() = if (web) WebHost.pause() else player.pause()
    fun toggle() {
        when {
            isPlaying() -> pause()
            web -> WebHost.resume()
            else -> player.play()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 250)
        }
    }

    private fun onTick() {
        val np = _now.value ?: return
        if (np.web || !player.isPlaying) return
        onPosition(np, player.currentPosition) { player.seekTo(it) }
    }

    private fun onWebProgress(pos: Long) {
        val np = _now.value ?: return
        if (pos > 0) lastWebPos = pos
        if (np.web && WebHost.videoId == np.video.id && WebHost.playing) onPosition(np, pos) { WebHost.seekTo(it) }
    }

    private fun onWebEnded() {
        val np = _now.value ?: return
        if (!np.web) return
        Graph.store.savePosition(np.video.id, 0)
        when {
            player.repeatMode == Player.REPEAT_MODE_ONE -> { WebHost.seekTo(0); WebHost.resume() }
            hasNext() -> next()
            else -> WebHost.pause()
        }
    }

    /** SponsorBlock skips and saving the position, for either player. */
    private fun onPosition(np: NowPlaying, pos: Long, seek: (Long) -> Unit) {
        if (Graph.settings.sponsorBlockEnabled.value && np.segments.isNotEmpty()) {
            val cats = Graph.settings.sponsorBlockCategories.value
            val seg = np.segments.firstOrNull {
                it.category in cats && it.uuid !in skipped && pos >= it.startMs && pos < it.endMs - 500
            }
            if (seg != null) {
                skipped += seg.uuid
                seek(seg.endMs)
                if (Graph.settings.sponsorBlockToast.value) {
                    _messages.tryEmit("Skipped ${SponsorCategories.all[seg.category] ?: seg.category}")
                }
            }
        }
        if (++tick % 20 == 0 && !np.video.isLive) {
            Graph.store.savePosition(np.video.id, pos)
        }
    }

    private fun startService() {
        runCatching {
            // Plain start: Media3 promotes the service to foreground itself once playback begins.
            appContext.startService(Intent(appContext, PlaybackService::class.java))
        }
    }

    // ------------- public controls -------------

    fun play(video: Video, queue: List<Video> = listOf(video), startIndex: Int = 0) {
        _queue.value = queue
        _index.value = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        load(video)
    }

    fun playLocal(video: Video, path: String, audioOnly: Boolean) {
        saveCurrentPosition()
        loadJob?.cancel()
        WebHost.release()
        _queue.value = listOf(video)
        _index.value = 0
        _now.value = NowPlaying(video, loading = false, audioOnly = audioOnly, localPath = path)
        val uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(java.io.File(path))
        val item = mediaItem(video, uri)
        player.setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item))
        player.prepare()
        resume(video.id, video.durationSec, video.isLive)
        player.playWhenReady = true
        startService()
        if (video.title.isBlank()) scope.launch {
            basicInfo(video.id)?.let { (title, channel) ->
                _now.update { if (it?.video?.id == video.id) it.copy(video = it.video.copy(title = title, channelName = channel)) else it }
            }
        }
    }

    fun next() {
        val q = _queue.value
        if (_index.value < q.lastIndex) {
            _index.value += 1
            load(q[_index.value])
        }
    }

    fun previous() {
        if (positionMs() > 5000 || _index.value == 0) {
            if (web) WebHost.seekTo(0) else player.seekTo(0)
            return
        }
        _index.value -= 1
        load(_queue.value[_index.value])
    }

    fun hasNext() = _index.value < _queue.value.lastIndex
    fun hasPrevious() = _index.value > 0

    fun addToQueue(v: Video) {
        if (_now.value == null) play(v) else _queue.update { it + v }
        _messages.tryEmit("Added to queue")
    }

    fun setQuality(height: Int) {
        val np = _now.value ?: return
        if (np.web) {
            // YouTube's web player picks the stream itself; tell it which level we want.
            WebHost.wantHeight = height
            _now.value = np.copy(quality = height)
            return
        }
        val r = np.resolved ?: return
        _now.value = np.copy(quality = height)
        rebuild(r, keepPosition = true)
    }

    fun setAudioOnly(on: Boolean) {
        val np = _now.value ?: return
        if (np.web) {
            _messages.tryEmit("Audio only isn't available in the web player")
            return
        }
        _now.value = np.copy(audioOnly = on)
        if (np.localPath != null) return
        np.resolved?.let { rebuild(it, keepPosition = true) }
    }

    fun stop() {
        saveCurrentPosition()
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        WebHost.release()
        _now.value = null
        _queue.value = emptyList()
    }

    fun saveCurrentPosition() {
        val np = _now.value ?: return
        val pos = positionMs()
        if (!np.video.isLive && pos > 0) Graph.store.savePosition(np.video.id, pos)
    }

    fun retry() {
        _now.value?.let { load(it.video) }
    }

    // ------------- loading -------------

    private fun load(video: Video) {
        if (linkStart?.first != video.id) linkStart = null
        saveCurrentPosition()
        loadJob?.cancel()
        retried = false
        webRetried = false
        lastWebPos = 0L
        skipped.clear()
        val audioOnly = _now.value?.audioOnly ?: false
        _now.value = NowPlaying(video, loading = true, quality = Graph.settings.defaultQuality.value, audioOnly = audioOnly)
        player.stop()
        player.clearMediaItems()
        loadInfo(video)
        if (dev.kestrel.app.BuildConfig.FORCE_WEB || Streams.linksBlocked()) {
            // This network blocks our links: go straight to the web player, fetch details on the side.
            startWeb(video, resumeSec(video))
            loadJob = scope.launch { webDetails(video) }
            return
        }
        WebHost.release()
        loadJob = scope.launch {
            try {
                // If YouTube can't be reached at all, requests hang: give up with a clear message.
                val r = try { withNetTimeout { Streams.resolve(video.id) } } catch (e: java.net.SocketTimeoutException) {
                    _now.update { it?.copy(loading = false, error = "The connection timed out. Check your network or VPN and try again.") }
                    return@launch
                }
                val v = r.video.copy(
                    title = r.video.title.ifBlank { video.title },
                    channelName = r.video.channelName.ifBlank { video.channelName },
                    thumbnail = video.thumbnail.ifBlank { r.video.thumbnail },
                )
                val rv = r.copy(video = v)
                _now.update { it?.copy(video = v, resolved = rv, loading = false) }
                rebuild(rv, keepPosition = false)
                Graph.store.addHistory(v)
                reportHistory(v.id)
                loadSegments(v.id)
                if (r.viaFallback) fillDetails(v.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Kestrel", "resolve failed for ${video.id}", e)
                if (unplayable(e)) {
                    _now.update { it?.copy(loading = false, error = dev.kestrel.app.ui.friendlyError(e)) }
                    if (video.title.isBlank()) basicInfo(video.id)?.let { (title, channel) ->
                        _now.update { if (it?.video?.id == video.id) it.copy(video = it.video.copy(title = title, channelName = channel)) else it }
                    }
                    return@launch
                }
                useWebPlayer()
                webDetails(video)
            }
        }
    }

    /** Details come from YouTube's watch page right away, so they show while the links still load. */
    private fun loadInfo(video: Video) {
        infoJob?.cancel()
        infoJob = scope.launch {
            val info = runCatching { Graph.innerTube.watchInfo(video.id) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) android.util.Log.w("Kestrel", "watch info failed: ${it.message}") }
                .getOrNull() ?: return@launch
            android.util.Log.i("Kestrel", "watch info: likes=${info.likes.isNotBlank()} desc=${info.description.isNotBlank()} collabs=${info.collaborators.size} subscribed=${info.subscribed}")
            _now.update { np ->
                if (np?.video?.id != video.id) return@update np
                val v = np.video
                np.copy(info = info, video = v.copy(
                    title = v.title.ifBlank { info.title },
                    channelName = v.channelName.ifBlank { info.channelName },
                    channelId = v.channelId ?: info.channelId,
                ))
            }
        }
    }

    private fun reportHistory(id: String) {
        if (!Graph.account.isSignedIn || !Graph.settings.sendHistory.value) return
        scope.launch { runCatching { Graph.innerTube.reportPlayback(id) } }
    }

    private fun loadSegments(id: String) {
        if (!Graph.settings.sponsorBlockEnabled.value) return
        scope.launch {
            val segs = runCatching { SponsorBlock.segments(id, SponsorCategories.all.keys) }.getOrDefault(emptyList())
            _now.update { if (it?.video?.id == id) it.copy(segments = segs) else it }
        }
    }

    private fun mediaItem(video: Video, uri: Uri, mime: String? = null): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(video.id)
            .apply { mime?.let { setMimeType(it) } }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.title)
                    .setArtist(video.channelName)
                    .setArtworkUri(Uri.parse(video.thumbnail))
                    .build()
            )
            .build()

    private fun buildSource(r: Resolved, quality: Int, audioOnly: Boolean): MediaSource {
        val video = r.video
        val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
        if (r.isLive && r.hls != null) {
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem(video, Uri.parse(r.hls), MimeTypes.APPLICATION_M3U8))
        }
        val audio = r.pickAudio()
        if (audioOnly && audio != null) {
            return progressive.createMediaSource(mediaItem(video, Uri.parse(audio.url)))
        }
        val vid = r.pickVideo(quality)
        if (vid == null) {
            if (r.hls != null) return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem(video, Uri.parse(r.hls), MimeTypes.APPLICATION_M3U8))
            if (audio != null) return progressive.createMediaSource(mediaItem(video, Uri.parse(audio.url)))
            error("No playable stream found")
        }
        val subs = r.subtitles.filter { !it.auto }.ifEmpty { r.subtitles.take(1) }.take(12).map {
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                .setMimeType(it.mime)
                .setLanguage(it.lang)
                .setLabel(it.label)
                .build()
        }
        // DefaultMediaSourceFactory attaches and parses the side-loaded captions.
        val withSubs = DefaultMediaSourceFactory(dataSourceFactory)
        if (r.videoOnly.isEmpty() || audio == null) {
            val item = mediaItem(video, Uri.parse(vid.url)).buildUpon().setSubtitleConfigurations(subs).build()
            return withSubs.createMediaSource(item)
        }
        val videoSrc = progressive.createMediaSource(mediaItem(video, Uri.parse(vid.url)))
        val audioSrc = withSubs.createMediaSource(
            MediaItem.Builder().setUri(Uri.parse(audio.url)).setSubtitleConfigurations(subs).build()
        )
        return MergingMediaSource(true, videoSrc, audioSrc)
    }

    private fun rebuild(r: Resolved, keepPosition: Boolean) {
        val np = _now.value ?: return
        val pos = player.currentPosition
        val playing = player.playWhenReady || !keepPosition
        try {
            player.setMediaSource(buildSource(r, np.quality, np.audioOnly))
        } catch (e: Exception) {
            _now.update { it?.copy(error = friendlyError(e)) }
            return
        }
        player.prepare()
        if (keepPosition) player.seekTo(pos) else resume(r.video.id, r.video.durationSec, r.video.isLive)
        player.playWhenReady = playing
        startService()
    }

    /** Start time from a shared link's "t=" (video id to ms); used once, before the saved position. */
    @Volatile var linkStart: Pair<String, Long>? = null

    private fun takeLinkStart(id: String): Long? = linkStart?.takeIf { it.first == id }?.second?.also { linkStart = null }

    private fun resume(id: String, durationSec: Long, isLive: Boolean) {
        takeLinkStart(id)?.let { if (!isLive) player.seekTo(it); return }
        if (!Graph.settings.resumePlayback.value) return
        val saved = Graph.store.position(id)
        val durMs = if (durationSec > 0) durationSec * 1000 else Long.MAX_VALUE
        if (saved > 5_000 && saved < durMs - 15_000) player.seekTo(saved)
    }

    private fun onError(error: PlaybackException) {
        val np = _now.value ?: return
        if (np.localPath != null) {
            _now.update { it?.copy(error = "Can't play this file: ${error.errorCodeName}") }
            return
        }
        val code = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code == 403) {
            // Links refused mid-play: YouTube's own player still works on these networks.
            useWebPlayer()
            return
        }
        if (!retried) {
            // Stream URLs expire or get blocked: fetch fresh ones (fallback client on 2nd try) and continue.
            retried = true
            val pos = player.currentPosition
            loadJob = scope.launch {
                try {
                    val r = if (np.resolved?.viaFallback == true) Streams.resolve(np.video.id) else Fallback.resolve(np.video.id)
                    _now.update { it?.copy(resolved = r, error = null) }
                    rebuild(r, keepPosition = false)
                    player.seekTo(pos)
                    player.playWhenReady = true
                } catch (e: Exception) {
                    useWebPlayer()
                }
            }
        } else {
            useWebPlayer()
        }
    }

    /** Switch the current video to the web player (keeps the position). */
    private fun useWebPlayer() {
        val np = _now.value ?: return
        val pos = player.currentPosition
        player.stop()
        android.util.Log.i("Kestrel", "switching to web player for ${np.video.id}")
        startWeb(np.video, if (pos > 5_000) pos / 1000 else resumeSec(np.video))
    }

    private fun startWeb(video: Video, startSec: Long) {
        _now.update { it?.copy(loading = false, error = null, web = true) }
        WebHost.wantHeight = _now.value?.quality ?: Graph.settings.defaultQuality.value
        WebHost.play(appContext, video.id, startSec)
        startService()
    }

    /** Videos YouTube's own player can't play either: show the reason instead of trying the web player. */
    private fun unplayable(e: Throwable): Boolean = e.isUnplayable(Graph.account.isSignedIn)

    /** Gives up after 40 s even if the network call is stuck in blocking code (a plain timeout can't interrupt that). */
    private suspend fun <T> withNetTimeout(block: suspend () -> T): T {
        val job = scope.async { block() }
        return kotlinx.coroutines.withTimeoutOrNull(40_000) { job.await() } ?: run { job.cancel(); throw java.net.SocketTimeoutException("timed out") }
    }

    /** History, SponsorBlock and video details (title, description, related) while the web player plays. */
    private suspend fun webDetails(video: Video) {
        Graph.store.addHistory(video)
        reportHistory(video.id)
        loadSegments(video.id)
        val res = runCatching { withNetTimeout { Streams.fromNewPipe(NpRepo.stream(video.id)) } }
        res.exceptionOrNull()?.let { e ->
            // Unplayable, or YouTube can't be reached at all (the web page would just stay blank): show the error.
            // If the page already plays the video, a slow details lookup isn't a reason to stop it.
            val offline = generateSequence(e) { it.cause }.any { it is java.io.IOException } &&
                !(WebHost.videoId == video.id && WebHost.durationMs > 0)
            if ((unplayable(e) || offline) && _now.value?.video?.id == video.id) {
                WebHost.release()
                _now.update { it?.copy(web = false, loading = false, error = dev.kestrel.app.ui.friendlyError(e)) }
                return
            }
        }
        val r = res.getOrNull()
        if (r == null) {
            // Details lookup failed on this network: at least get title and channel.
            if (video.title.isBlank()) basicInfo(video.id)?.let { (title, channel) ->
                _now.update { if (it?.video?.id == video.id) it.copy(video = it.video.copy(title = title, channelName = channel)) else it }
            }
            return
        }
        val v = r.video.copy(thumbnail = video.thumbnail.ifBlank { r.video.thumbnail })
        val details = r.copy(video = v, videoOnly = emptyList(), audio = emptyList(), muxed = emptyList(), hls = null)
        _now.update { if (it?.video?.id == video.id) it.copy(video = v, resolved = details) else it }
    }

    /** Backup clients give streams but few details: add title, channel, description and related videos. */
    private suspend fun fillDetails(id: String) {
        val d = runCatching { withNetTimeout { Streams.fromNewPipe(NpRepo.stream(id)) } }.getOrNull()
        if (d == null) {
            if (_now.value?.video?.title.isNullOrBlank()) basicInfo(id)?.let { (title, channel) ->
                _now.update { if (it?.video?.id == id) it.copy(video = it.video.copy(title = title, channelName = channel)) else it }
            }
            return
        }
        _now.update { np ->
            val cur = np?.resolved
            if (np == null || np.video.id != id || cur == null) return@update np
            val v = d.video.copy(thumbnail = np.video.thumbnail.ifBlank { d.video.thumbnail }, isLive = np.video.isLive)
            np.copy(
                video = v,
                resolved = cur.copy(
                    video = v, description = d.description.ifBlank { cur.description }, channelAvatar = d.channelAvatar,
                    subscribers = d.subscribers, likes = d.likes, uploadDate = d.uploadDate,
                    subtitles = cur.subtitles.ifEmpty { d.subtitles }, related = d.related,
                ),
            )
        }
    }

    /** Title and channel name from YouTube's public oEmbed endpoint. */
    private suspend fun basicInfo(id: String): Pair<String, String>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Fallback.oEmbed(id) }


    /** Saved position to resume from, in seconds (0 = start). */
    private fun resumeSec(video: Video): Long {
        takeLinkStart(video.id)?.let { return if (video.isLive) 0 else it / 1000 }
        if (!Graph.settings.resumePlayback.value || video.isLive) return 0
        val saved = Graph.store.position(video.id)
        val durMs = if (video.durationSec > 0) video.durationSec * 1000 else Long.MAX_VALUE
        return if (saved > 5_000 && saved < durMs - 15_000) saved / 1000 else 0
    }
}
