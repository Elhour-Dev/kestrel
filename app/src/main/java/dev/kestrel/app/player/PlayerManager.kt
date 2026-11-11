package dev.kestrel.app.player

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
)

/** Owns the single ExoPlayer used by the screen, background service, PiP and the popup. */
@UnstableApi
object PlayerManager {
    lateinit var player: ExoPlayer
        private set
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
    private var retried = false
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
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 250)
        }
    }

    private fun onTick() {
        val np = _now.value ?: return
        if (!player.isPlaying) return
        val pos = player.currentPosition
        if (Graph.settings.sponsorBlockEnabled.value && np.segments.isNotEmpty()) {
            val cats = Graph.settings.sponsorBlockCategories.value
            val seg = np.segments.firstOrNull {
                it.category in cats && it.uuid !in skipped && pos >= it.startMs && pos < it.endMs - 500
            }
            if (seg != null) {
                skipped += seg.uuid
                player.seekTo(seg.endMs)
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
            ContextCompat.startForegroundService(appContext, Intent(appContext, PlaybackService::class.java))
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
        _queue.value = listOf(video)
        _index.value = 0
        _now.value = NowPlaying(video, loading = false, audioOnly = audioOnly, localPath = path)
        val uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(java.io.File(path))
        val item = mediaItem(video, uri)
        player.setMediaSource(ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item))
        player.prepare()
        resume(video.id, video.durationSec)
        player.playWhenReady = true
        startService()
    }

    fun next() {
        val q = _queue.value
        if (_index.value < q.lastIndex) {
            _index.value += 1
            load(q[_index.value])
        }
    }

    fun previous() {
        if (player.currentPosition > 5000 || _index.value == 0) {
            player.seekTo(0)
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
        val r = np.resolved ?: return
        _now.value = np.copy(quality = height)
        rebuild(r, keepPosition = true)
    }

    fun setAudioOnly(on: Boolean) {
        val np = _now.value ?: return
        _now.value = np.copy(audioOnly = on)
        if (np.localPath != null) return
        np.resolved?.let { rebuild(it, keepPosition = true) }
    }

    fun stop() {
        saveCurrentPosition()
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _now.value = null
        _queue.value = emptyList()
    }

    fun saveCurrentPosition() {
        val np = _now.value ?: return
        if (!np.video.isLive && player.currentPosition > 0) Graph.store.savePosition(np.video.id, player.currentPosition)
    }

    fun retry() {
        _now.value?.let { load(it.video) }
    }

    // ------------- loading -------------

    private fun load(video: Video) {
        saveCurrentPosition()
        loadJob?.cancel()
        retried = false
        skipped.clear()
        val audioOnly = _now.value?.audioOnly ?: false
        _now.value = NowPlaying(video, loading = true, quality = Graph.settings.defaultQuality.value, audioOnly = audioOnly)
        player.stop()
        player.clearMediaItems()
        startService()
        loadJob = scope.launch {
            try {
                val r = Streams.resolve(video.id)
                val v = r.video.copy(thumbnail = video.thumbnail.ifBlank { r.video.thumbnail })
                _now.update { it?.copy(video = v, resolved = r, loading = false) }
                rebuild(r, keepPosition = false)
                Graph.store.addHistory(v)
                reportHistory(v.id)
                loadSegments(v.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Kestrel", "resolve failed for ${video.id}", e)
                _now.update { it?.copy(loading = false, error = friendlyError(e)) }
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
        if (keepPosition) player.seekTo(pos) else resume(r.video.id, r.video.durationSec)
        player.playWhenReady = playing
    }

    private fun resume(id: String, durationSec: Long) {
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
                    _now.update { it?.copy(error = "Playback failed: ${error.errorCodeName}") }
                }
            }
        } else {
            _now.update { it?.copy(error = "Playback failed (${error.errorCodeName}). Tap retry.") }
        }
    }
}
