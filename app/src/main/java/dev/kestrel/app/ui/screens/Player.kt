package dev.kestrel.app.ui.screens

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.kestrel.app.Graph
import dev.kestrel.app.MainActivity
import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.model.formatCount
import dev.kestrel.app.player.GestureFrame
import dev.kestrel.app.player.NowPlaying
import dev.kestrel.app.player.PlayerManager
import dev.kestrel.app.player.PopupService
import dev.kestrel.app.ui.Avatar
import dev.kestrel.app.ui.EmptyState
import dev.kestrel.app.ui.ItemView
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.SectionTitle
import dev.kestrel.app.ui.VideoRow
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun PlayerScreen(activity: MainActivity) {
    val now by PlayerManager.now.collectAsStateWithLifecycle()
    val np = now
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val full = MainActivity.Ui.fullscreen || landscape || MainActivity.Ui.inPip
    BackHandler(enabled = MainActivity.Ui.fullscreen) { activity.setFullscreen(false) }
    LaunchedEffect(landscape) { activity.applySystemBars() }
    DisposableEffect(Unit) {
        onDispose {
            if (MainActivity.Ui.fullscreen) activity.setFullscreen(false)
            MainActivity.Ui.onPlayerScreen = false
            activity.applySystemBars()
        }
    }
    if (np == null) {
        EmptyState("Nothing is playing.")
        return
    }
    if (full) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { VideoSurface(np, activity, Modifier.fillMaxSize()) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) { VideoSurface(np, activity, Modifier.fillMaxSize()) }
        Details(np, activity)
    }
}

@UnstableApi
@Composable
private fun VideoSurface(np: NowPlaying, activity: MainActivity, modifier: Modifier) {
    val inPip = MainActivity.Ui.inPip
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                val frame = GestureFrame(ctx, activity)
                val pv = PlayerView(ctx).apply {
                    player = PlayerManager.player
                    setShowSubtitleButton(true)
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    controllerShowTimeoutMs = 3000
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setFullscreenButtonClickListener { activity.setFullscreen(it) }
                }
                frame.onSeek = { fwd ->
                    val p = PlayerManager.player
                    p.seekTo((p.currentPosition + if (fwd) 10_000 else -10_000).coerceAtLeast(0))
                }
                frame.addView(pv, android.widget.FrameLayout.LayoutParams(-1, -1))
                frame.tag = pv
                frame
            },
            update = { frame ->
                val pv = frame.tag as PlayerView
                if (pv.player == null) pv.player = PlayerManager.player
                pv.useController = !inPip
                frame.enabledGestures = !inPip
                pv.setFullscreenButtonState(MainActivity.Ui.fullscreen)
            },
            onRelease = { frame -> (frame.tag as PlayerView).player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (np.audioOnly && np.error == null && !np.loading) {
            AsyncImage(np.video.thumbnail, null, Modifier.fillMaxSize().padding(bottom = 48.dp), alpha = 0.55f)
        }
        if (np.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        np.error?.let { err ->
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { PlayerManager.retry() }) { Icon(Icons.Default.Refresh, null); Text(" Retry") }
            }
        }
    }
}

@UnstableApi
@Composable
private fun Details(np: NowPlaying, activity: MainActivity) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = np.resolved
    val v = r?.video ?: np.video
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val index by PlayerManager.index.collectAsStateWithLifecycle()
    var like by remember(v.id) { mutableStateOf<String?>(null) }
    var showQuality by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var saveFor by remember { mutableStateOf<Video?>(null) }
    var downloadFor by remember { mutableStateOf<Video?>(null) }
    var expanded by remember(v.id) { mutableStateOf(false) }

    LaunchedEffect(v.id) {
        if (Graph.account.isSignedIn && np.localPath == null) {
            runCatching { Graph.innerTube.videoState(v.id) }.onSuccess { st ->
                like = st.like
                val cid = v.channelId ?: st.channelId
                if (cid != null && st.subscribed != null) {
                    val local = Graph.store.isSubscribed(cid)
                    if (st.subscribed && !local) Graph.store.subscribe(Channel(cid, v.channelName, r?.channelAvatar))
                    if (!st.subscribed && local) Graph.store.unsubscribe(cid)
                }
            }
        }
    }

    fun rate(target: String) {
        if (!Graph.account.isSignedIn) { nav.message("Sign in with Google to like videos"); return }
        val newStatus = if (like == target) "INDIFFERENT" else target
        val old = like
        like = newStatus
        scope.launch {
            runCatching {
                Graph.innerTube.rate(v.id, when (newStatus) { "LIKE" -> "like"; "DISLIKE" -> "dislike"; else -> "removelike" })
            }.onFailure { like = old; nav.message(friendlyError(it)) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).animateContentSize()) {
                Text(v.title.ifBlank { "Loading…" }, style = MaterialTheme.typography.titleMedium, maxLines = if (expanded) 10 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded })
                val meta = listOfNotNull(
                    v.views.takeIf { it >= 0 }?.let { "${formatCount(it)} views" },
                    r?.uploadDate ?: v.uploaded,
                    if (np.segments.isNotEmpty()) "${np.segments.size} SponsorBlock segments" else null,
                ).joinToString(" • ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Chip(if (like == "LIKE") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, r?.likes?.takeIf { it >= 0 }?.let { formatCount(it) } ?: "Like", like == "LIKE") { rate("LIKE") }
                }
                item { Chip(if (like == "DISLIKE") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Dislike", like == "DISLIKE") { rate("DISLIKE") } }
                item { Chip(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { saveFor = v } }
                item { Chip(Icons.Default.Download, "Download") { downloadFor = v } }
                item { Chip(Icons.Default.Headphones, "Audio only", np.audioOnly) { PlayerManager.setAudioOnly(!np.audioOnly) } }
                item {
                    Chip(Icons.Outlined.OpenInNew, "Popup") {
                        if (PopupService.canDraw(ctx)) {
                            PopupService.start(ctx)
                            nav.back()
                            activity.moveTaskToBack(true)
                        } else {
                            nav.message("Allow \"Display over other apps\" for the popup player")
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        }
                    }
                }
                item { Chip(Icons.Default.PictureInPicture, "PiP") { activity.enterPip() } }
                if (r != null && !r.isLive && np.localPath == null) item {
                    Chip(Icons.Default.HighQuality, r.pickVideo(np.quality)?.label ?: "Quality") { showQuality = true }
                }
                item {
                    Chip(Icons.Default.Share, "Share") {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.url), "Share"))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { v.channelId?.let { nav.openChannel(it) } }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(r?.channelAvatar, 40)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(v.channelName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    r?.subscribers?.takeIf { it >= 0 }?.let { Text("${formatCount(it)} subscribers", style = MaterialTheme.typography.bodySmall) }
                }
                v.channelId?.let { SubscribeButton(Channel(it, v.channelName, r?.channelAvatar)) }
            }
        }
        if (!r?.description.isNullOrBlank()) item {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { expanded = !expanded }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(r!!.description, Modifier.padding(12.dp).animateContentSize(), maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (np.localPath == null) item {
            Surface(Modifier.fillMaxWidth().padding(12.dp).clickable { showComments = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text("Comments", Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall)
            }
        }
        if (queue.size > 1) {
            item { SectionTitle("Up next (${index + 1}/${queue.size})") }
            itemsIndexed(queue.drop(index + 1).take(25), key = { i, q -> "q$i${q.id}" }) { i, q ->
                VideoRow(q, { PlayerManager.play(q, queue, index + 1 + i) }, { nav.videoMenu(q) })
            }
        }
        val related = r?.related.orEmpty()
        if (related.isNotEmpty()) {
            item { SectionTitle("Related") }
            items(related, key = { "r" + it.key }) { item ->
                ItemView(item, compact = true, onVideo = { nav.openVideo(it, listOf(it)) })
            }
        }
    }

    if (showQuality && r != null) {
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("Quality") },
            text = {
                LazyColumn {
                    items(r.qualities()) { h ->
                        Row(Modifier.fillMaxWidth().clickable { PlayerManager.setQuality(h); showQuality = false }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(r.pickVideo(np.quality)?.height == h, { PlayerManager.setQuality(h); showQuality = false })
                            Text("${h}p")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQuality = false }) { Text("Close") } },
        )
    }
    if (showComments) CommentsSheet(v.id) { showComments = false }
    saveFor?.let { SaveToPlaylistDialog(it) { saveFor = null } }
    downloadFor?.let { DownloadDialog(it) { downloadFor = null } }
}

@Composable
private fun Chip(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        shape = RoundedCornerShape(50),
        colors = if (selected) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else AssistChipDefaults.assistChipColors(),
    )
}
