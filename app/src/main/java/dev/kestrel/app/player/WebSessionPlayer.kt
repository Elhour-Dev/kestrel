package dev.kestrel.app.player

import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Media session view of the web player ([WebHost]). With it the playback service shows the
 * media notification and stays in the foreground, so web playback keeps going in the background.
 */
@UnstableApi
class WebSessionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private val commands = Player.Commands.Builder().addAll(
        COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD,
        COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
    ).build()

    /** Call after the web player's state or the video details change. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val np = PlayerManager.now.value
        val id = WebHost.videoId
        val b = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(WebHost.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
        if (id == null || np == null || np.video.id != id) return b.setPlaybackState(STATE_IDLE).build()
        val meta = MediaMetadata.Builder()
            .setTitle(np.video.title)
            .setArtist(np.video.channelName)
            .setArtworkUri(np.video.thumbnail.takeIf { it.isNotBlank() }?.toUri())
            .build()
        val dur = WebHost.durationMs
        val item = MediaItemData.Builder(id)
            .setMediaItem(MediaItem.Builder().setMediaId(id).setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            .setDurationUs(if (dur > 0) dur * 1000 else C.TIME_UNSET)
            .setIsSeekable(dur > 0)
            .setIsDynamic(np.video.isLive)
            .build()
        return b.setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(STATE_READY)
            .setContentPositionMs(PositionSupplier.getExtrapolating(WebHost.positionMs, if (WebHost.playing) 1f else 0f))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) WebHost.resume() else WebHost.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (positionMs != C.TIME_UNSET) WebHost.seekTo(positionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        PlayerManager.stop()
        return Futures.immediateVoidFuture()
    }
}
