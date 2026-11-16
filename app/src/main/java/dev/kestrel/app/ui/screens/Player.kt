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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
        if (np.web) {
            // YouTube's page without its own controls; ours go on top and take every touch.
            WebPlayer(Modifier.fillMaxSize())
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = PlayerManager.player
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { pv -> if (pv.player == null) pv.player = PlayerManager.player },
                onRelease = { pv -> pv.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (np.audioOnly && np.error == null && !np.loading) {
                AsyncImage(np.video.thumbnail, null, Modifier.fillMaxSize(), alpha = 0.55f)
            }
        }
        if (!inPip) PlayerControls(np, activity, MainActivity.Ui.fullscreen || LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE, Modifier.fillMaxSize())
        if (np.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        np.error?.let { err ->
            // Opaque, so nothing shows through the message.
            Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { PlayerManager.retry() }) { Icon(Icons.Default.Refresh, null); Text(" Retry") }
                dev.kestrel.app.ui.CopyDetailsButton(err, Color.White)
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
    val webQ by dev.kestrel.app.player.WebHost.quality.collectAsStateWithLifecycle()
    var showComments by remember { mutableStateOf(false) }
    var saveFor by remember { mutableStateOf<Video?>(null) }
    var downloadFor by remember { mutableStateOf<Video?>(null) }
    var expanded by remember(v.id) { mutableStateOf(false) }

    val info = np.info
    var showCollabs by remember(v.id) { mutableStateOf(false) }
    LaunchedEffect(info) {
        val st = info ?: return@LaunchedEffect
        if (!Graph.account.isSignedIn || np.localPath != null) return@LaunchedEffect
        like = st.like
        // Keep the local subscription list in step with the account.
        val cid = st.channelId ?: v.channelId
        if (cid != null && st.subscribed != null && st.collaborators.isEmpty()) {
            val local = Graph.store.isSubscribed(cid)
            if (st.subscribed && !local) Graph.store.subscribe(Channel(cid, st.channelName.ifBlank { v.channelName }, st.channelAvatar))
            if (!st.subscribed && local) Graph.store.unsubscribe(cid)
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

    var showDescription by remember(v.id) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var firstComment by remember(v.id) { mutableStateOf<dev.kestrel.app.data.model.Comment?>(null) }
    LaunchedEffect(v.id, np.localPath) {
        if (np.localPath != null) return@LaunchedEffect
        firstComment = runCatching { Graph.innerTube.comments(v.id).comments.firstOrNull() }.getOrNull()
    }
    val collabs = info?.collaborators.orEmpty()
    val avatar = r?.channelAvatar ?: info?.channelAvatar
    val description = r?.description?.ifBlank { null } ?: info?.description.orEmpty()
    val viewsText = v.views.takeIf { it >= 0 }?.let { "${formatCount(it)} views" } ?: np.info?.views?.ifBlank { null }
    val dateText = r?.uploadDate ?: v.uploaded ?: np.info?.date?.ifBlank { null }
    val likesText = r?.likes?.takeIf { it >= 0 }?.let { formatCount(it) } ?: info?.likes?.ifBlank { null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clickable { showDescription = true }.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
                Text(v.title.ifBlank { if (np.error != null) "Video unavailable" else "Loading…" }, style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(viewsText, dateText).joinToString("  ")
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(meta, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("  ...more", style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { if (collabs.isNotEmpty()) showCollabs = true else v.channelId?.let { nav.openChannel(it) } }
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(avatar, 36)
                Text(if (collabs.isNotEmpty()) collabs.joinToString(" and ") { it.name } else v.channelName, Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subs = if (collabs.isNotEmpty()) "${collabs.size} channels" else r?.subscribers?.takeIf { it >= 0 }?.let { formatCount(it) } ?: info?.subscribers?.ifBlank { null }
                subs?.let { Text("  $it", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    ?: Spacer(Modifier.weight(1f))
                if (collabs.isEmpty()) v.channelId?.let { cid ->
                    SubscribeButton(Channel(cid, v.channelName, avatar), info?.subscribeParams?.takeIf { info.channelId == cid })
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { LikePill(like, likesText, { rate("LIKE") }, { rate("DISLIKE") }) }
                item {
                    Pill(Icons.Default.Share, "Share") {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.url), "Share"))
                    }
                }
                item { Pill(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { saveFor = v } }
                item { Pill(Icons.Default.Download, "Download") { downloadFor = v } }
                if (np.web && webQ.heights.isNotEmpty()) item {
                    Pill(Icons.Default.HighQuality, webQ.current.takeIf { it > 0 }?.let { "${it}p" } ?: "Quality") { showQuality = true }
                } else if (r != null && !r.isLive && np.localPath == null && !np.web) item {
                    Pill(Icons.Default.HighQuality, r.pickVideo(np.quality)?.label ?: "Quality") { showQuality = true }
                }
                item { Pill(Icons.Default.PictureInPicture, "PiP") { activity.enterPip() } }
            }
        }
        if (np.localPath == null) item {
            Surface(Modifier.fillMaxWidth().padding(12.dp).clickable { showComments = true }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(12.dp)) {
                    Text("Comments", style = MaterialTheme.typography.titleSmall)
                    firstComment?.let { c ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.authorAvatar, 24)
                            Text(c.text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (queue.size > 1) {
            item {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Up next (${index + 1}/${queue.size})", Modifier.weight(1f))
                    TextButton(onClick = { showQueue = true }) { Text("Edit queue") }
                }
            }
            itemsIndexed(queue.drop(index + 1).take(25), key = { i, q -> "q$i${q.id}" }) { i, q ->
                VideoRow(q, { PlayerManager.play(q, queue, index + 1 + i) }, { nav.videoMenu(q) })
            }
        }
        val related = r?.related.orEmpty().ifEmpty { info?.related.orEmpty() }
        if (related.isNotEmpty()) {
            if (queue.size > 1) item { SectionTitle("Related") } else item { Spacer(Modifier.height(4.dp)) }
            items(related, key = { "r" + it.key }) { item ->
                ItemView(item, compact = false, onVideo = { nav.openVideo(it, listOf(it)) })
            }
        }
    }

    if (showQueue) QueueSheet { showQueue = false }
    if (showDescription) DescriptionSheet(
        title = v.title,
        stats = listOfNotNull(likesText?.let { it to "Likes" }, viewsText?.let { it.removeSuffix(" views") to "Views" }, dateText?.let { it to "Date" }),
        segments = np.segments.size,
        description = description,
    ) { showDescription = false }

    if (showQuality && (np.web || r != null)) {
        // Web player: "Auto" (0) lets YouTube adapt to the network.
        val heights = if (np.web) listOf(0) + webQ.heights else r!!.qualities()
        val selected = if (np.web) (if (np.quality <= 0) 0 else webQ.current) else r!!.pickVideo(np.quality)?.height
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("Quality") },
            text = {
                LazyColumn {
                    items(heights) { h ->
                        Row(Modifier.fillMaxWidth().clickable { PlayerManager.setQuality(h); showQuality = false }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == h, { PlayerManager.setQuality(h); showQuality = false })
                            Text(if (h <= 0) "Auto" + (webQ.current.takeIf { it > 0 }?.let { " (${it}p)" } ?: "") else "${h}p")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQuality = false }) { Text("Close") } },
        )
    }
    if (showComments) CommentsSheet(v.id) { showComments = false }
    if (showCollabs) CollaboratorsDialog(info?.collaborators.orEmpty()) { showCollabs = false }
    saveFor?.let { SaveToPlaylistDialog(it) { saveFor = null } }
    downloadFor?.let { DownloadDialog(it) { downloadFor = null } }
}

/** The play queue: tap to play, move up/down, remove. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val queue by PlayerManager.queue.collectAsStateWithLifecycle()
    val index by PlayerManager.index.collectAsStateWithLifecycle()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Text("Queue", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(queue, key = { i, q -> "${i}_${q.id}" }) { i, q ->
                val current = i == index
                Row(
                    Modifier.fillMaxWidth().background(if (current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                        .clickable { if (!current) PlayerManager.play(q, queue, i) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(q.thumbnail, null, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(q.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (current) "Now playing" else q.channelName, style = MaterialTheme.typography.bodySmall,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    androidx.compose.material3.IconButton(onClick = { PlayerManager.moveInQueue(i, i - 1) }, enabled = i > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, "Move up")
                    }
                    androidx.compose.material3.IconButton(onClick = { PlayerManager.moveInQueue(i, i + 1) }, enabled = i < queue.lastIndex) {
                        Icon(Icons.Default.KeyboardArrowDown, "Move down")
                    }
                    androidx.compose.material3.IconButton(onClick = { PlayerManager.removeFromQueue(i) }, enabled = !current) {
                        Icon(Icons.Default.Close, "Remove from queue")
                    }
                }
            }
        }
    }
}

/** Rounded grey pill used for the watch-page actions. */
@Composable
private fun Pill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Like | Dislike in one pill, like the YouTube app. */
@Composable
private fun LikePill(like: String?, likes: String?, onLike: () -> Unit, onDislike: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable(onClick = onLike).padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (like == "LIKE") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, if (like == "LIKE") "Remove like" else "Like", Modifier.size(20.dp))
                Text(likes ?: "Like", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outline))
            Box(Modifier.clickable(onClick = onDislike).padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)) {
                Icon(if (like == "DISLIKE") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, if (like == "DISLIKE") "Remove dislike" else "Dislike", Modifier.size(20.dp))
            }
        }
    }
}

/** Full description with the numbers on top, opened from "...more". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DescriptionSheet(title: String, stats: List<Pair<String, String>>, segments: Int, description: String, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { Text("Description", style = MaterialTheme.typography.titleLarge) }
            item { Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium) }
            if (stats.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    stats.forEach { (value, label) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (segments > 0) item {
                Text("$segments SponsorBlock segments", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(description.ifBlank { "No description." }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** The channels of a collaboration video: open or subscribe to each one. */
@Composable
private fun CollaboratorsDialog(channels: List<Channel>, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Collaborators") },
        text = {
            Column {
                channels.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { onDismiss(); nav.openChannel(c.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.avatar, 36)
                        Text(c.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SubscribeButton(c)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
