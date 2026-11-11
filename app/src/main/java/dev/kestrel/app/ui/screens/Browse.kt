package dev.kestrel.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kestrel.app.Graph
import dev.kestrel.app.data.account.AccountState
import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Page
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.system.Health
import dev.kestrel.app.ui.Avatar
import dev.kestrel.app.ui.EmptyState
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.PagedItemList
import dev.kestrel.app.ui.PagedVm
import dev.kestrel.app.ui.SectionTitle
import dev.kestrel.app.ui.Thumb
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun itemsVm(first: suspend () -> Page<Item>, more: suspend (Any) -> Page<Item>) = PagedVm(first, more)

/** Next-page loader that understands both NewPipe and InnerTube tokens. */
suspend fun moreAny(token: Any): Page<Item> = when (token) {
    is String -> Graph.innerTube.browseMore(token)
    is NpRepo.SearchState -> NpRepo.searchMore(token)
    is NpRepo.KioskState -> NpRepo.kioskMore(token)
    is NpRepo.TabState -> NpRepo.channelTabMore(token)
    is NpRepo.PlaylistState -> NpRepo.playlistMore(token)
    else -> Page(emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(title: String) {
    val nav = LocalNav.current
    val session by Graph.account.session.collectAsStateWithLifecycle()
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(28)
                Spacer(Modifier.width(10.dp))
                Text(title)
            }
        },
        actions = {
            IconButton(onClick = { nav.go("search") }) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = { nav.go("settings") }) {
                val avatar = session?.avatar
                if (!avatar.isNullOrBlank()) Avatar(avatar, 28) else Icon(Icons.Default.Settings, "Settings")
            }
        },
    )
}

@Composable
fun HealthBanner() {
    val results by Health.results.collectAsStateWithLifecycle()
    val broken = results.values.filter { !it.ok }
    if (broken.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Column(Modifier.padding(start = 12.dp)) {
                broken.forEach {
                    Text("${it.feature.label}: ${it.message}", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    val accountState by Graph.account.state.collectAsStateWithLifecycle()
    val signedIn = accountState == AccountState.SignedIn
    val kiosks = remember { runCatching { NpRepo.kioskIds() }.getOrDefault(emptyList()).sortedBy { if (it.equals("Trending", true)) 0 else 1 } }
    var source by remember(signedIn) { mutableStateOf(if (signedIn) "foryou" else kiosks.firstOrNull() ?: "") }
    Column {
        MainTopBar("Kestrel")
        HealthBanner()
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (signedIn) item { FilterChip(source == "foryou", { source = "foryou" }, { Text("For you") }) }
            items(kiosks) { k -> FilterChip(source == k, { source = k }, { Text(kioskLabel(k)) }) }
        }
        val vm = viewModel(key = "home-$source") {
            itemsVm(
                first = {
                    if (source == "foryou") Graph.innerTube.home()
                    else NpRepo.kiosk(source.ifBlank { null })
                },
                more = ::moreAny,
            )
        }
        PagedItemList(vm, "Nothing here right now. Pull a different tab or try again later.")
    }
}

private fun kioskLabel(id: String) = id.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
fun SubscriptionsScreen() {
    val nav = LocalNav.current
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val accountState by Graph.account.state.collectAsStateWithLifecycle()
    val signedIn = accountState == AccountState.SignedIn
    val subs = lib.subscriptions
    Column {
        MainTopBar("Subscriptions")
        if (subs.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(subs.take(30), key = { it.id }) { c ->
                        Column(Modifier.width(64.dp).clickable { nav.openChannel(c.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(c.avatar, 52)
                            Text(c.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton(onClick = { nav.go("subchannels") }) { Text("All") }
            }
        }
        if (!signedIn && subs.isEmpty()) {
            EmptyState(
                "No subscriptions yet.\nSubscribe to channels, sign in, or import a list in Settings.",
                icon = Icons.Default.Subscriptions,
                action = "Import or sign in" to { nav.go("settings") },
            )
            return@Column
        }
        val vm = viewModel(key = "subs-$signedIn-${subs.size}") {
            itemsVm(
                first = { if (signedIn) Graph.innerTube.subscriptionsFeed() else Page(localFeed(subs)) },
                more = ::moreAny,
            )
        }
        PagedItemList(vm, "No new videos from your subscriptions.")
    }
}

/** Builds a feed from the latest uploads of each subscribed channel (no account needed). */
private suspend fun localFeed(subs: List<Channel>): List<Item> = coroutineScope {
    val gate = Semaphore(6)
    subs.take(80).map { c ->
        async { gate.withPermit { runCatching { NpRepo.latestUploads(c.id).take(15) }.getOrDefault(emptyList()) } }
    }.awaitAll().flatten().filterNot { it.isShort }.sortedByDescending { it.uploadedAt }.take(300)
}

@Composable
fun LibraryScreen() {
    val nav = LocalNav.current
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val accountState by Graph.account.state.collectAsStateWithLifecycle()
    val signedIn = accountState == AccountState.SignedIn
    val downloads by Graph.downloads.items.collectAsStateWithLifecycle()
    Column {
        MainTopBar("Library")
        LazyColumn(Modifier.fillMaxSize()) {
            if (lib.history.isNotEmpty()) {
                item { SectionTitle("Recently watched") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(lib.history.take(20), key = { it.video.id }) { h ->
                            Column(Modifier.width(160.dp).clickable { nav.openVideo(h.video) }) {
                                Thumb(h.video, Modifier.fillMaxWidth(), progress = Graph.store.position(h.video.id).let { p -> if (h.video.durationSec > 0) p / (h.video.durationSec * 1000f) else 0f })
                                Text(h.video.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { LibRow(Icons.Default.History, "History", "${lib.history.size} on this phone" + if (signedIn) " • account history" else "") { nav.go("feed/history") } }
            if (signedIn) {
                item { LibRow(Icons.Default.WatchLater, "Watch later", "From your Google account") { nav.go("feed/watchlater") } }
                item { LibRow(Icons.Default.ThumbUp, "Liked videos", "From your Google account") { nav.go("feed/liked") } }
            }
            item { LibRow(Icons.AutoMirrored.Filled.PlaylistPlay, "Playlists", "${lib.playlists.size} on this phone" + if (signedIn) " • account playlists" else "") { nav.go("playlists") } }
            item { LibRow(Icons.Default.Download, "Downloads", "${downloads.count { it.status.name == "Done" }} saved for offline") { nav.go("downloads") } }
            item { LibRow(Icons.Default.Person, "Channels", "${lib.subscriptions.size} subscriptions") { nav.go("subchannels") } }
        }
    }
}

@Composable
private fun LibRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        leadingContent = { Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    val nav = LocalNav.current
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
    )
}

/** History, Watch later and Liked videos. */
@Composable
fun FeedScreen(kind: String) {
    val nav = LocalNav.current
    val signedIn = Graph.account.isSignedIn
    when (kind) {
        "history" -> {
            var account by remember { mutableStateOf(false) }
            val lib by Graph.store.library.collectAsStateWithLifecycle()
            Column {
                BackTopBar("History") {
                    if (!account && lib.history.isNotEmpty()) TextButton(onClick = { Graph.store.clearHistory(); nav.message("History cleared") }) { Text("Clear") }
                }
                if (signedIn) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!account, { account = false }, { Text("This phone") })
                    FilterChip(account, { account = true }, { Text("Google account") })
                }
                if (account) {
                    val vm = viewModel(key = "acct-history") { itemsVm({ Graph.innerTube.history() }, ::moreAny) }
                    PagedItemList(vm, "Your account history is empty.", compact = true)
                } else if (lib.history.isEmpty()) {
                    EmptyState("Videos you watch show up here.", icon = Icons.Default.History)
                } else {
                    val videos = lib.history.map { it.video }
                    LazyColumn {
                        items(lib.history, key = { it.video.id }) { h ->
                            dev.kestrel.app.ui.VideoRow(h.video, { nav.openVideo(h.video, videos.dropWhile { it.id != h.video.id }) }, {
                                nav.videoMenu(h.video, listOf("Remove from history" to { Graph.store.removeHistory(h.video.id) }))
                            })
                        }
                    }
                }
            }
        }
        "watchlater", "liked" -> {
            val wl = kind == "watchlater"
            Column {
                BackTopBar(if (wl) "Watch later" else "Liked videos")
                val vm = viewModel(key = "feed-$kind") { itemsVm({ if (wl) Graph.innerTube.watchLater() else Graph.innerTube.liked() }, ::moreAny) }
                PagedItemList(vm, if (wl) "Nothing saved to Watch later." else "No liked videos.", compact = true, menuExtra = { v ->
                    if (!wl) emptyList() else listOf("Remove from Watch later" to {
                        vm.removeWhere { (it as? Video)?.id == v.id }
                        kotlinx.coroutines.MainScope().launchCatching(nav) { Graph.innerTube.removeFromPlaylist("WL", v.id) }
                    })
                })
            }
        }
        else -> EmptyState("Unknown page")
    }
}

fun kotlinx.coroutines.CoroutineScope.launchCatching(nav: dev.kestrel.app.ui.AppNav, ok: String? = null, block: suspend () -> Unit) {
    launch {
        try {
            block()
            ok?.let { nav.message(it) }
        } catch (e: Exception) {
            nav.message(dev.kestrel.app.ui.friendlyError(e))
        }
    }
}
