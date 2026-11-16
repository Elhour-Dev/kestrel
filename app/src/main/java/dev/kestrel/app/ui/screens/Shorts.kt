package dev.kestrel.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.Comment
import dev.kestrel.app.data.model.formatCount
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.kestrel.app.Graph
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Page
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.player.PlayerManager
import dev.kestrel.app.ui.EmptyState
import dev.kestrel.app.ui.ErrorState
import dev.kestrel.app.ui.LoadingState
import dev.kestrel.app.ui.LocalNav
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

private fun isShortLike(v: Video) = v.isShort || v.durationSec in 1..75

/** Shorts from subscribed channels first, then popular Shorts from search. */
private suspend fun loadShorts(): Page<Item> = coroutineScope {
    val subs = Graph.store.library.value.subscriptions.shuffled().take(8)
    val fromSubs = subs.map { c ->
        async {
            runCatching {
                val d = NpRepo.channel(c.id)
                val tab = d.tabs[ChannelTabs.SHORTS] ?: return@runCatching emptyList()
                NpRepo.channelTab(tab).items.filterIsInstance<Video>().take(6).map { it.copy(channelName = d.channel.name, channelId = c.id, isShort = true) }
            }.getOrDefault(emptyList())
        }
    }
    val search = async { runCatching { searchAny("#shorts", "videos") }.getOrNull() }
    val subItems = fromSubs.awaitAll().flatten().shuffled()
    val s = search.await()
    val searchItems = s?.items?.filterIsInstance<Video>()?.filter(::isShortLike).orEmpty()
    Page((subItems + searchItems).distinctBy { it.id }, s?.next)
}

@UnstableApi
@Composable
fun ShortsScreen() {
    val nav = LocalNav.current
    val vm = viewModel(key = "shorts") { itemsVm(::loadShorts, { t -> searchMoreAny(t).let { p -> Page(p.items.filterIsInstance<Video>().filter(::isShortLike), p.next) } }) }
    val shorts = vm.items.filterIsInstance<Video>()
    when {
        !vm.loaded -> { LoadingState(skeleton = false); return }
        shorts.isEmpty() && vm.error != null -> { ErrorState(vm.error!!) { vm.refresh() }; return }
        shorts.isEmpty() -> { EmptyState("No Shorts found right now.", icon = Icons.Default.Whatshot); return }
    }
    val pager = rememberPagerState { shorts.size }
    var current by remember { mutableIntStateOf(-1) }
    DisposableEffect(Unit) {
        PlayerManager.player.repeatMode = Player.REPEAT_MODE_ONE
        onDispose {
            PlayerManager.player.repeatMode = if (PlayerManager.loop.value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            PlayerManager.pause()
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != current && page in shorts.indices) {
                current = page
                PlayerManager.play(shorts[page], listOf(shorts[page]))
            }
            if (page >= shorts.size - 3) vm.loadMore()
        }
    }
    val np = PlayerManager.now.collectAsStateWithLifecycle().value
    val web = np?.web == true
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var commentsFor by remember { mutableStateOf<String?>(null) }
    var saveFor by remember { mutableStateOf<Video?>(null) }
    VerticalPager(pager, Modifier.fillMaxSize().background(Color.Black), key = { shorts[it].id }) { page ->
        val v = shorts[page]
        Box(Modifier.fillMaxSize()) {
            if (page == current && web) {
                WebPlayer(Modifier.fillMaxSize())
            } else if (page == current) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = PlayerManager.player
                            keepScreenOn = true
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(v.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            // Tap anywhere on the video: pause / play (also in the web player, whose page must not get touches).
            var paused by remember(v.id) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) {
                if (page == current) { PlayerManager.toggle(); paused = !paused }
            })
            if (paused && page == current) Icon(Icons.Default.PlayArrow, "Paused", Modifier.align(Alignment.Center).size(72.dp), tint = Color.White.copy(alpha = 0.8f))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(260.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 80.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clickable { v.channelId?.let { nav.openChannel(it) } }, verticalAlignment = Alignment.CenterVertically) {
                        dev.kestrel.app.ui.Avatar(v.channelAvatar ?: np?.resolved?.channelAvatar?.takeIf { page == current }, 32)
                        Text(v.channelName.ifBlank { np?.video?.channelName?.takeIf { page == current }.orEmpty() }, Modifier.padding(start = 8.dp, end = 8.dp),
                            color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    v.channelId?.let { cid -> SubscribeButton(dev.kestrel.app.data.model.Channel(cid, v.channelName, v.channelAvatar)) }
                }
                Text(v.title, Modifier.padding(top = 8.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            var like by remember(v.id) { mutableStateOf(if (page == current) np?.info?.like else null) }
            LaunchedEffect(np?.info, page == current) { if (page == current && np?.video?.id == v.id) np.info?.like?.let { like = it } }
            fun rate(target: String) {
                if (!Graph.account.isSignedIn) { nav.message("Sign in with Google to like videos"); return }
                val newStatus = if (like == target) "INDIFFERENT" else target
                val old = like
                like = newStatus
                scope.launch {
                    runCatching { Graph.innerTube.rate(v.id, when (newStatus) { "LIKE" -> "like"; "DISLIKE" -> "dislike"; else -> "removelike" }) }
                        .onFailure { like = old; nav.message(dev.kestrel.app.ui.friendlyError(it)) }
                }
            }
            val likes = if (page == current) np?.resolved?.likes?.takeIf { it >= 0 }?.let { formatCount(it) } ?: np?.info?.likes?.ifBlank { null } else null
            Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                ShortAction(if (like == "LIKE") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, likes ?: "Like") { rate("LIKE") }
                ShortAction(if (like == "DISLIKE") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Dislike") { rate("DISLIKE") }
                ShortAction(Icons.AutoMirrored.Outlined.Comment, "Comments") { commentsFor = v.id }
                ShortAction(Icons.Outlined.Share, "Share") {
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "https://www.youtube.com/shorts/${v.id}"), "Share"))
                }
                ShortAction(Icons.AutoMirrored.Filled.PlaylistAdd, "Save") { saveFor = v }
                ShortAction(Icons.Default.Download, "Download") { Graph.downloads.enqueue(v, audioOnly = false, quality = 1080); nav.message("Download added") }
                ShortAction(Icons.Default.MoreVert, "More") { nav.videoMenu(v) }
            }
        }
    }
    commentsFor?.let { id -> CommentsSheet(id) { commentsFor = null } }
    saveFor?.let { SaveToPlaylistDialog(it) { saveFor = null } }
}

@Composable
private fun ShortAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).clickable(onClick = onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x40000000)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = Color.White)
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
