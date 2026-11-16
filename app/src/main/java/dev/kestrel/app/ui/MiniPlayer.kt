package dev.kestrel.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.kestrel.app.player.PlayerManager
import dev.kestrel.app.ui.screens.WebPlayer
import kotlin.math.abs

/**
 * Floating mini player: the playing video in a small card at the bottom right, like the current YouTube app.
 * Tap to open, play/pause and close buttons on top, swipe sideways to close.
 */
@UnstableApi
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val now by PlayerManager.now.collectAsStateWithLifecycle()
    val np = now ?: return
    var playing by remember { mutableStateOf(PlayerManager.isPlaying()) }
    var progress by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val shownX by animateFloatAsState(dragX, label = "mini-drag")
    LaunchedEffect(np.video.id) {
        while (true) {
            playing = PlayerManager.isPlayingOrWill()
            val d = PlayerManager.durationMs()
            progress = if (d > 0) PlayerManager.positionMs() / d.toFloat() else 0f
            kotlinx.coroutines.delay(500)
        }
    }
    Surface(
        modifier
            .width(200.dp)
            .aspectRatio(16f / 9f)
            .graphicsLayer { translationX = shownX; alpha = (1f - abs(shownX) / 600f).coerceIn(0.2f, 1f) }
            .pointerInput(np.video.id) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { c, dx -> c.consume(); dragX += dx },
                    onDragEnd = { if (abs(dragX) > size.width * 0.45f) PlayerManager.stop() else dragX = 0f },
                    onDragCancel = { dragX = 0f },
                )
            }
            .semantics { contentDescription = "Mini player: ${np.video.title}. Tap to open" }
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black,
        shadowElevation = 8.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                np.error != null || np.loading || np.audioOnly ->
                    AsyncImage(np.video.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                np.web -> WebPlayer(Modifier.fillMaxSize())
                else -> AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            player = PlayerManager.player
                        }
                    },
                    update = { if (it.player == null) it.player = PlayerManager.player },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Touch layer on top so the video views (YouTube's page in web mode) never take the taps.
            Box(Modifier.fillMaxSize().background(Color(0x33000000)).clickable(onClick = onOpen))
            np.error?.let {
                Text("Video unavailable", Modifier.align(Alignment.Center).padding(8.dp), color = Color.White,
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (np.error == null) Box(
                Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color(0x80000000))
                    .clickable { PlayerManager.toggle(); playing = !playing }
                    .semantics { contentDescription = if (playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp).clip(CircleShape).background(Color(0x80000000))
                    .clickable { PlayerManager.stop() }
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, null, Modifier.size(18.dp), tint = Color.White) }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}
