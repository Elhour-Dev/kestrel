package dev.kestrel.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.kestrel.app.Graph
import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Page
import dev.kestrel.app.data.model.Playlist
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.model.formatCount
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.ui.AppNav
import dev.kestrel.app.ui.Avatar
import dev.kestrel.app.ui.ChannelRow
import dev.kestrel.app.ui.EmptyState
import dev.kestrel.app.ui.ErrorState
import dev.kestrel.app.ui.LoadingState
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.PagedItemList
import dev.kestrel.app.ui.PlaylistRow
import dev.kestrel.app.ui.SectionTitle
import dev.kestrel.app.ui.VideoRow
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

/** Subscribes locally, and on the Google account when signed in. */
fun toggleSubscribe(c: Channel, scope: CoroutineScope, nav: AppNav) {
    val on = !Graph.store.isSubscribed(c.id)
    if (on) Graph.store.subscribe(c) else Graph.store.unsubscribe(c.id)
    if (Graph.account.isSignedIn) {
        scope.launch {
            runCatching { Graph.innerTube.subscribe(c.id, on) }.onFailure {
                nav.message("Saved on this phone, but Google sync failed: ${friendlyError(it)}")
            }
        }
    }
    nav.message(if (on) "Subscribed to ${c.name}" else "Unsubscribed from ${c.name}")
}

@Composable
fun SubscribeButton(c: Channel) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val subscribed = lib.subscriptions.any { it.id == c.id }
    if (subscribed) OutlinedButton(onClick = { toggleSubscribe(c, scope, nav) }) { Text("Subscribed") }
    else Button(onClick = { toggleSubscribe(c, scope, nav) }) { Text("Subscribe") }
}

private val tabOrder = listOf(ChannelTabs.VIDEOS to "Videos", ChannelTabs.SHORTS to "Shorts", ChannelTabs.LIVESTREAMS to "Live", ChannelTabs.PLAYLISTS to "Playlists")

@Composable
fun ChannelScreen(id: String) {
    var data by remember { mutableStateOf<NpRepo.ChannelData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(id, attempt) {
        error = null
        try { data = NpRepo.channel(id) } catch (e: Exception) { error = friendlyError(e) }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(data?.channel?.name ?: "Channel")
        val d = data
        when {
            error != null -> ErrorState(error!!) { attempt++ }
            d == null -> LoadingState()
            else -> {
                val tabs = tabOrder.filter { it.first in d.tabs }
                var tab by rememberSaveable { mutableStateOf(0) }
                val header: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                    item {
                        Column {
                            d.banner?.let {
                                AsyncImage(it, null, Modifier.fillMaxWidth().aspectRatio(6.2f).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                            }
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(d.channel.avatar, 64)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(d.channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (d.channel.subscribers >= 0) Text("${formatCount(d.channel.subscribers)} subscribers", style = MaterialTheme.typography.bodySmall)
                                }
                                SubscribeButton(d.channel)
                            }
                            if (d.description.isNotBlank()) Text(d.description, Modifier.padding(horizontal = 16.dp), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            if (tabs.size > 1) PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                                tabs.forEachIndexed { i, (_, label) -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
                            }
                        }
                    }
                }
                if (tabs.isEmpty()) {
                    LazyColumn { header() ; item { EmptyState("This channel has no videos.", Modifier.height(300.dp)) } }
                } else {
                    val handler = d.tabs[tabs[tab.coerceIn(tabs.indices)].first]!!
                    val vm = viewModel(key = "ch-$id-$tab") { itemsVm({ NpRepo.channelTab(handler) }, ::moreAny) }
                    PagedItemList(vm, "Nothing here.", compact = true, header = header)
                }
            }
        }
    }
}

/** source: "yt" (public), "account" (Google account, editable), "local" (this phone, editable). */
@Composable
fun PlaylistScreen(id: String, source: String) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    if (source == "local") {
        LocalPlaylistScreen(id)
        return
    }
    var title by remember { mutableStateOf(if (id == "WL") "Watch later" else "Playlist") }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val vm = viewModel(key = "pl-$source-$id") {
        itemsVm(
            first = {
                if (source == "account") Graph.innerTube.playlistItems(id)
                else try {
                    val (p, page) = NpRepo.playlist(id)
                    title = p.title
                    page
                } catch (e: Exception) {
                    if (Graph.account.isSignedIn) Graph.innerTube.playlistItems(id) else throw e
                }
            },
            more = ::moreAny,
        )
    }
    val videos = vm.items.filterIsInstance<Video>()
    Column(Modifier.fillMaxSize()) {
        BackTopBar(title) {
            if (source == "account") {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
        PlayButtons(videos)
        PagedItemList(vm, "This playlist is empty.", compact = true, menuExtra = { v ->
            if (source != "account") emptyList() else listOf("Remove from playlist" to {
                vm.removeWhere { (it as? Video)?.id == v.id }
                scope.launchCatching(nav, "Removed") { Graph.innerTube.removeFromPlaylist(id, v.id) }
            })
        })
    }
    if (renaming) NameDialog("Rename playlist", title, { renaming = false }) { name ->
        title = name
        scope.launchCatching(nav, "Renamed") { Graph.innerTube.renamePlaylist(id, name) }
    }
    if (confirmDelete) ConfirmDialog("Delete \"$title\" from your Google account?", { confirmDelete = false }) {
        scope.launchCatching(nav, "Playlist deleted") { Graph.innerTube.deletePlaylist(id); nav.back() }
    }
}

@Composable
fun PlayButtons(videos: List<Video>) {
    val nav = LocalNav.current
    if (videos.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        FilledTonalButton(onClick = { nav.openVideo(videos.first(), videos) }, Modifier.weight(1f)) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.padding(4.dp)); Text("Play all")
        }
        Spacer(Modifier.padding(6.dp))
        FilledTonalButton(onClick = { val s = videos.shuffled(); nav.openVideo(s.first(), s) }, Modifier.weight(1f)) {
            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.padding(4.dp)); Text("Shuffle")
        }
    }
}

@Composable
private fun LocalPlaylistScreen(id: String) {
    val nav = LocalNav.current
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val p = lib.playlists.firstOrNull { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar(p?.name ?: "Playlist") {
            if (p != null) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
        if (p == null) { EmptyState("This playlist was deleted."); return@Column }
        PlayButtons(p.videos)
        if (p.videos.isEmpty()) EmptyState("No videos yet. Use \"Save to playlist\" on any video.")
        LazyColumn {
            items(p.videos, key = { it.id }) { v ->
                VideoRow(v, { nav.openVideo(v, p.videos.dropWhile { it.id != v.id }) }, {
                    nav.videoMenu(v, listOf("Remove from playlist" to { Graph.store.removeFromPlaylist(id, v.id) }))
                })
            }
        }
    }
    if (renaming && p != null) NameDialog("Rename playlist", p.name, { renaming = false }) { Graph.store.renamePlaylist(id, it) }
    if (confirmDelete && p != null) ConfirmDialog("Delete \"${p.name}\"?", { confirmDelete = false }) {
        Graph.store.deletePlaylist(id); nav.back()
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onOk(text.trim()); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(text: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PlaylistsScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val signedIn = Graph.account.isSignedIn
    var remote by remember { mutableStateOf<List<Playlist>?>(null) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        if (signedIn) try { remote = Graph.innerTube.libraryPlaylists() } catch (e: Exception) { remoteError = friendlyError(e) }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Playlists") { TextButton(onClick = { creating = true }) { Text("New") } }
        LazyColumn {
            item { SectionTitle("On this phone") }
            if (lib.playlists.isEmpty()) item { Text("No local playlists yet.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            items(lib.playlists, key = { it.id }) { p ->
                PlaylistRow(Playlist(p.id, p.name, p.videos.firstOrNull()?.thumbnail, p.videos.size.toLong()), subtitle = "${p.videos.size} videos") {
                    nav.openPlaylist(p.id, "local")
                }
            }
            if (signedIn) {
                item { SectionTitle("Google account") }
                item {
                    PlaylistRow(Playlist("WL", "Watch later"), subtitle = "Your account") { nav.go("feed/watchlater") }
                }
                when {
                    remoteError != null -> item { Text(remoteError!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                    remote == null -> item { LoadingState(Modifier.height(80.dp)) }
                    else -> items(remote!!, key = { "r" + it.id }) { p -> PlaylistRow(p, subtitle = "Your account") { nav.openPlaylist(p.id, "account") } }
                }
            }
        }
    }
    if (creating) {
        var onAccount by remember { mutableStateOf(signedIn) }
        NameDialog(if (onAccount) "New playlist (Google account)" else "New playlist", "", { creating = false }) { name ->
            if (onAccount) scope.launchCatching(nav, "Created \"$name\"") { Graph.innerTube.createPlaylist(name); reload++ }
            else Graph.store.createPlaylist(name)
        }
    }
}

@Composable
fun SubscribedChannelsScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Channels") {
            if (Graph.account.isSignedIn) IconButton(onClick = {
                scope.launchCatching(nav, "Subscriptions synced") { Graph.store.mergeSubscriptions(Graph.innerTube.subscribedChannels()) }
            }) { Icon(Icons.Default.Sync, "Sync") }
        }
        if (lib.subscriptions.isEmpty()) EmptyState("No subscriptions yet.", icon = Icons.AutoMirrored.Filled.PlaylistPlay)
        LazyColumn {
            items(lib.subscriptions, key = { it.id }) { c ->
                ChannelRow(c, trailing = {
                    TextButton(onClick = { toggleSubscribe(c, scope, nav) }) { Text("Unsubscribe") }
                }) { nav.openChannel(c.id) }
            }
        }
    }
}
