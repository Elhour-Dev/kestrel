package dev.kestrel.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Whatshot
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

/** Top bar of the Home tabs: logo, then search (and the bell once notifications are on). */
@Composable
fun MainTopBar() {
    val nav = LocalNav.current
    dev.kestrel.app.ui.BrandBar {
        dev.kestrel.app.ui.BarIcon(Icons.Default.Search, "Search") { nav.go("search") }
    }
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
fun HomeScreen(start: String = "home") {
    val accountState by Graph.account.state.collectAsStateWithLifecycle()
    val signedIn = accountState == AccountState.SignedIn
    val kiosks = remember {
        runCatching { NpRepo.kioskIds() }.getOrDefault(emptyList())
            .filter { it !in BROKEN_KIOSKS }
            .sortedBy { KIOSK_ORDER.indexOf(it).let { i -> if (i < 0) KIOSK_ORDER.size else i } }
    }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(start) }
    val tabs = buildList {
        add("home" to "Home")
        add("subs" to "Subs")
        if ("trending_music" in kiosks) add("trending_music" to "Music")
        if ("live" in kiosks) add("live" to "Live")
        add("more" to "More")
    }
    Column {
        MainTopBar()
        HealthBanner()
        dev.kestrel.app.ui.TopTabs(tabs, tab) { tab = it }
        when (tab) {
            "subs" -> SubsFeed()
            "more" -> MoreList(kiosks.filter { it != "trending_music" && it != "live" })
            "home" -> {
                // Signed in: the account's own feed. Signed out: a mix of the category pages.
                val vm = viewModel(key = "home-$signedIn") {
                    itemsVm(
                        first = { if (signedIn) Graph.innerTube.home() else Page(mixedKiosks(kiosks)) },
                        more = ::moreAny,
                    )
                }
                PagedItemList(vm, "Nothing here right now. Try again later.")
            }
            else -> {
                val vm = viewModel(key = "home-$tab") { itemsVm({ NpRepo.kiosk(tab) }, ::moreAny) }
                PagedItemList(vm, "Nothing here right now. Try again later.")
            }
        }
    }
}

/** First page of each category, interleaved, for the signed-out Home tab. */
private suspend fun mixedKiosks(kiosks: List<String>): List<Item> = coroutineScope {
    val pages = kiosks.filter { it != "live" }.map { k -> async { runCatching { NpRepo.kiosk(k).items }.getOrDefault(emptyList()) } }.awaitAll()
    val out = mutableListOf<Item>()
    val seen = HashSet<String>()
    val longest = pages.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) pages.forEach { p ->
        val item = p.getOrNull(i) ?: return@forEach
        val id = (item as? Video)?.id
        if (id == null || seen.add(id)) out += item
    }
    if (out.isEmpty()) throw java.io.IOException("Couldn't load videos")
    out
}

/** "More" tab: the other category pages. */
@Composable
private fun MoreList(kiosks: List<String>) {
    val nav = LocalNav.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(kiosks) { k ->
            ListItem(
                headlineContent = { Text(kioskLabel(k), style = MaterialTheme.typography.titleMedium) },
                leadingContent = { Icon(kioskIcon(k), null, Modifier.size(26.dp)) },
                modifier = Modifier.clickable { nav.go("kiosk/$k") },
            )
        }
    }
}

private fun kioskIcon(id: String): ImageVector = when (id) {
    "trending_gaming" -> Icons.Default.SportsEsports
    "trending_movies_and_shows" -> Icons.Default.Movie
    "trending_music" -> Icons.Default.MusicNote
    "live" -> Icons.Default.LiveTv
    else -> Icons.Default.Whatshot
}

/** One category page opened from "More". */
@Composable
fun KioskScreen(id: String) {
    Column {
        BackTopBar(kioskLabel(id))
        val vm = viewModel(key = "kiosk-$id") { itemsVm({ NpRepo.kiosk(id) }, ::moreAny) }
        PagedItemList(vm, "Nothing here right now. Try again later.")
    }
}

/** YouTube removed its Trending page (2025); these kiosks no longer load (checked Nov 2026). */
private val BROKEN_KIOSKS = setOf("Trending", "trending_podcasts_episodes")
private val KIOSK_ORDER = listOf("trending_music", "trending_gaming", "trending_movies_and_shows", "live")

private fun kioskLabel(id: String) = when (id) {
    "trending_music" -> "Music"
    "trending_gaming" -> "Gaming"
    "trending_movies_and_shows" -> "Movies & shows"
    "live" -> "Live"
    else -> id.removePrefix("trending_").replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun SubsFeed() {
    val nav = LocalNav.current
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val accountState by Graph.account.state.collectAsStateWithLifecycle()
    val signedIn = accountState == AccountState.SignedIn
    val subs = lib.subscriptions
    Column {
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
    val session by Graph.account.session.collectAsStateWithLifecycle()
    val signedIn = accountState == AccountState.SignedIn
    val downloads by Graph.downloads.items.collectAsStateWithLifecycle()
    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            dev.kestrel.app.ui.BarIcon(Icons.Default.Search, "Search") { nav.go("search") }
            dev.kestrel.app.ui.BarIcon(Icons.Default.Settings, "Settings") { nav.go("settings") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                val s = session
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(s?.avatar?.ifBlank { null }, 72)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        if (s != null && signedIn) {
                            Text(s.name.ifBlank { "Your account" }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.handle.isNotBlank()) Text(s.handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("You", style = MaterialTheme.typography.headlineSmall)
                            Text("Sign in to see your subscriptions, playlists and likes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!signedIn) androidx.compose.material3.Button(onClick = { nav.go("login") }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Text("Sign in with Google") }
            }
            if (lib.history.isNotEmpty()) {
                item { SectionLink("History") { nav.go("feed/history") } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(lib.history.take(20), key = { it.video.id }) { h ->
                            Column(Modifier.width(168.dp).clickable { nav.openVideo(h.video) }) {
                                Thumb(h.video, Modifier.fillMaxWidth(), progress = Graph.store.position(h.video.id).let { p -> if (h.video.durationSec > 0) p / (h.video.durationSec * 1000f) else 0f })
                                Text(h.video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(h.video.channelName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item { SectionLink("Library", null) }
            item { LibRow(Icons.Outlined.History, "History", "${lib.history.size} on this phone" + if (signedIn) " · account history" else "") { nav.go("feed/history") } }
            if (signedIn) {
                item { LibRow(Icons.Outlined.WatchLater, "Watch later", null) { nav.go("feed/watchlater") } }
                item { LibRow(Icons.Outlined.ThumbUp, "Liked videos", null) { nav.go("feed/liked") } }
            }
            item { LibRow(Icons.AutoMirrored.Outlined.PlaylistPlay, "Playlists", "${lib.playlists.size} on this phone" + if (signedIn) " · account playlists" else "") { nav.go("playlists") } }
            item { LibRow(Icons.Outlined.Download, "Downloads", "${downloads.count { it.status.name == "Done" }} saved for offline") { nav.go("downloads") } }
            item { LibRow(Icons.Outlined.Subscriptions, "Your channels", "${lib.subscriptions.size} subscriptions") { nav.go("subchannels") } }
            item { androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            item { LibRow(Icons.Outlined.Settings, "Settings", null) { nav.go("settings") } }
            item { LibRow(Icons.AutoMirrored.Outlined.HelpOutline, "Help", null) { nav.go("help") } }
        }
    }
}

/** Section title, with an arrow when it opens a page. */
@Composable
private fun SectionLink(title: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun LibRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = sub?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        leadingContent = { Icon(icon, null, Modifier.size(26.dp)) },
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
        // The screen's Scaffold already leaves room for the status bar.
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
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
