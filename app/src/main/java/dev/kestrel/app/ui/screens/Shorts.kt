package dev.kestrel.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
        !vm.loaded -> { LoadingState(); return }
        shorts.isEmpty() && vm.error != null -> { ErrorState(vm.error!!) { vm.refresh() }; return }
        shorts.isEmpty() -> { EmptyState("No Shorts found right now.", icon = Icons.Default.Whatshot); return }
    }
    val pager = rememberPagerState { shorts.size }
    var current by remember { mutableStateOf(-1) }
    DisposableEffect(Unit) {
        PlayerManager.player.repeatMode = Player.REPEAT_MODE_ONE
        onDispose {
            PlayerManager.player.repeatMode = Player.REPEAT_MODE_OFF
            PlayerManager.player.pause()
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
    VerticalPager(pager, Modifier.fillMaxSize().background(Color.Black), key = { shorts[it].id }) { page ->
        val v = shorts[page]
        Box(Modifier.fillMaxSize()) {
            if (page == current) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            player = PlayerManager.player
                            keepScreenOn = true
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            setOnClickListener { val p = PlayerManager.player; if (p.isPlaying) p.pause() else p.play() }
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(v.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 72.dp, bottom = 24.dp)) {
                Text(v.channelName, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clickable { v.channelId?.let { nav.openChannel(it) } })
                Text(v.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 24.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { nav.videoMenu(v) }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Save", tint = Color.White) }
                IconButton(onClick = { Graph.downloads.enqueue(v, audioOnly = false, quality = 1080); nav.message("Download added") }) { Icon(Icons.Default.Download, "Download", tint = Color.White) }
                IconButton(onClick = { nav.videoMenu(v) }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
            }
        }
    }
}
