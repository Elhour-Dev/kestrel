package dev.kestrel.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.kestrel.app.player.PlayerManager
import dev.kestrel.app.player.PopupService
import dev.kestrel.app.ui.KestrelRoot
import dev.kestrel.app.ui.theme.KestrelTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_PLAYER = "dev.kestrel.app.OPEN_PLAYER"
        const val ACTION_OPEN_DOWNLOADS = "dev.kestrel.app.OPEN_DOWNLOADS"
    }

    /** Shared with Compose: PiP state, fullscreen, pending deep link. */
    object Ui {
        var inPip by mutableStateOf(false)
        var fullscreen by mutableStateOf(false)
        var onPlayerScreen = false
        var pendingIntent by mutableStateOf<Intent?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ui.pendingIntent = intent
        setContent {
            val theme by Graph.settings.theme.flow.collectAsStateWithLifecycle()
            KestrelTheme(theme) {
                KestrelRoot(this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Ui.pendingIntent = intent
    }

    fun setFullscreen(on: Boolean) {
        Ui.fullscreen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applySystemBars()
    }

    fun applySystemBars() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hide = Ui.fullscreen || (landscape && Ui.onPlayerScreen)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
    }

    private fun shouldPip(): Boolean {
        val now = PlayerManager.now.value ?: return false
        return Graph.settings.autoPip.value && Ui.onPlayerScreen && PlayerManager.isPlaying() && !now.audioOnly && !PopupService.isShowing
    }

    fun enterPip(): Boolean = runCatching {
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }.getOrDefault(false)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldPip()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Ui.inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        PlayerManager.saveCurrentPosition()
        Graph.store.saveNow()
        val bgAllowed = Graph.settings.backgroundPlay.value
        if (!bgAllowed && !Ui.inPip && !PopupService.isShowing && !isChangingConfigurations) {
            PlayerManager.pause()
        }
    }
}
