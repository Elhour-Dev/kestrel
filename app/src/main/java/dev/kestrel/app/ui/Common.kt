package dev.kestrel.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Page
import dev.kestrel.app.data.model.Playlist
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.model.formatCount
import dev.kestrel.app.data.model.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Navigation actions available everywhere. */
interface AppNav {
    fun openVideo(v: Video, queue: List<Video> = listOf(v))
    fun openChannel(id: String)
    fun openPlaylist(id: String, source: String = "yt")
    fun go(route: String)
    fun back()
    fun message(text: String)
    fun videoMenu(v: Video, extra: List<Pair<String, () -> Unit>> = emptyList())
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("no nav") }

// ---------------- paged list state ----------------

class PagedVm<T>(
    private val first: suspend () -> Page<T>,
    private val more: suspend (Any) -> Page<T>,
) : ViewModel() {
    var items by mutableStateOf<List<T>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var loaded by mutableStateOf(false); private set
    private var next: Any? = null
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        job?.cancel()
        loading = true
        error = null
        job = viewModelScope.launch {
            try {
                val p = first()
                items = p.items
                next = p.next
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            }
            loading = false
            loaded = true
        }
    }

    fun loadMore() {
        val n = next ?: return
        if (loading) return
        loading = true
        job = viewModelScope.launch {
            try {
                val p = more(n)
                val keys = items.map { (it as? Item)?.key ?: it }.toHashSet()
                items = items + p.items.filter { ((it as? Item)?.key ?: it) !in keys }
                next = p.next
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                next = null
                error = friendlyError(e)
            }
            loading = false
        }
    }

    val hasMore get() = next != null

    fun removeWhere(pred: (T) -> Boolean) {
        items = items.filterNot(pred)
    }
}

@Composable
fun LoadMoreEffect(state: LazyListState, onMore: () -> Unit) {
    val nearEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { nearEnd }.distinctUntilChanged().filter { it }.collect { onMore() }
    }
}

/** Standard list for a paged feed: loading, error, empty and items states. */
@Composable
fun PagedItemList(
    vm: PagedVm<Item>,
    emptyText: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    header: (LazyListScope.() -> Unit)? = null,
    menuExtra: (Video) -> List<Pair<String, () -> Unit>> = { emptyList() },
) {
    val state = rememberLazyListState()
    LoadMoreEffect(state) { vm.loadMore() }
    val nav = LocalNav.current
    when {
        !vm.loaded && vm.loading && header == null -> LoadingState(modifier)
        vm.loaded && vm.items.isEmpty() && vm.error != null && header == null -> ErrorState(vm.error!!, modifier) { vm.refresh() }
        else -> LazyColumn(modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
            header?.invoke(this)
            if (header != null && !vm.loaded && vm.loading) item { LoadingState(Modifier.height(200.dp)) }
            if (header != null && vm.items.isEmpty() && vm.error != null) item { ErrorState(vm.error!!, Modifier.height(260.dp)) { vm.refresh() } }
            if (vm.loaded && vm.items.isEmpty() && vm.error == null) item { EmptyState(emptyText, Modifier.height(300.dp)) }
            val videos = vm.items.filterIsInstance<Video>()
            items(vm.items, key = { it.key }) { item ->
                ItemView(item, compact = compact, onVideo = { nav.openVideo(it, videos.dropWhile { v -> v.id != it.id }) }, menuExtra = menuExtra)
            }
            if (vm.loaded && vm.loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
            if (vm.loaded && vm.items.isNotEmpty() && vm.error != null) item {
                Text("Couldn't load more: ${vm.error}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------- states ----------------

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.AutoMirrored.Filled.PlaylistPlay, action: Pair<String, () -> Unit>? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(12.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, f) ->
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = f) { Text(label) }
        }
    }
}

@Composable
fun ErrorState(text: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    val offline = text.contains("internet", true)
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (offline) Icons.Default.CloudOff else Icons.Default.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

// ---------------- item views ----------------

@Composable
fun ItemView(item: Item, compact: Boolean = false, onVideo: (Video) -> Unit, menuExtra: (Video) -> List<Pair<String, () -> Unit>> = { emptyList() }) {
    val nav = LocalNav.current
    when (item) {
        is Video -> if (compact) VideoRow(item, { onVideo(item) }, { nav.videoMenu(item, menuExtra(item)) })
        else VideoCard(item, { onVideo(item) }, { nav.videoMenu(item, menuExtra(item)) })
        is Channel -> ChannelRow(item) { nav.openChannel(item.id) }
        is Playlist -> PlaylistRow(item) { nav.openPlaylist(item.id) }
    }
}

fun videoMeta(v: Video): String = listOfNotNull(
    v.channelName.takeIf { it.isNotBlank() },
    if (v.isLive) "LIVE" else v.views.takeIf { it >= 0 }?.let { "${formatCount(it)} views" },
    v.uploaded?.takeIf { it.isNotBlank() },
).joinToString(" • ")

@Composable
fun Thumb(v: Video, modifier: Modifier = Modifier, progress: Float = 0f) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        AsyncImage(v.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val label = if (v.isLive) "LIVE" else formatDuration(v.durationSec)
        if (label.isNotBlank()) {
            Text(
                label,
                Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(if (v.isLive) Color(0xFFCC0000) else Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White, style = MaterialTheme.typography.labelMedium,
            )
        }
        if (progress > 0.02f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun watchProgress(v: Video): Float {
    val pos = dev.kestrel.app.Graph.store.position(v.id)
    return if (pos > 0 && v.durationSec > 0) pos / (v.durationSec * 1000f) else 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(v: Video, onClick: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Thumb(v, Modifier.fillMaxWidth(), watchProgress(v))
        Row(Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(videoMeta(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMenu, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "More") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(v: Video, onClick: () -> Unit, onMenu: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMenu).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Thumb(v, Modifier.width(160.dp), watchProgress(v))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(videoMeta(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else IconButton(onClick = onMenu, Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "More") }
    }
}

@Composable
fun Avatar(url: String?, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun ChannelRow(c: Channel, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickableCompat(onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.avatar, 48)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.subscribers >= 0) Text("${formatCount(c.subscribers)} subscribers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun PlaylistRow(p: Playlist, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickableCompat(onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (p.thumbnail != null) AsyncImage(p.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Surface(Modifier.align(Alignment.BottomEnd).padding(5.dp), color = Color(0xCC000000), shape = RoundedCornerShape(4.dp)) {
                Row(Modifier.padding(horizontal = 5.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(14.dp), tint = Color.White)
                    if (p.count >= 0) Text(" ${p.count}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle ?: p.owner.ifBlank { "Playlist" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)
