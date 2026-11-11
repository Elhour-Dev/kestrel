package dev.kestrel.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.kestrel.app.MainActivity

/** Keeps playback alive in the background and shows the media notification. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val wrapped = object : ForwardingPlayer(PlayerManager.player) {
            override fun getAvailableCommands(): Player.Commands {
                val b = super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                return b.build()
            }
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
            override fun hasNextMediaItem() = PlayerManager.hasNext()
            override fun hasPreviousMediaItem() = true
            override fun seekToNext() = PlayerManager.next()
            override fun seekToNextMediaItem() = PlayerManager.next()
            override fun seekToPrevious() = PlayerManager.previous()
            override fun seekToPreviousMediaItem() = PlayerManager.previous()
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, wrapped).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlayerManager.player
        if (!p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
