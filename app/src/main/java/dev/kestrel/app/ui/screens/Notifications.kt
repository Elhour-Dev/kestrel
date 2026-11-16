package dev.kestrel.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.kestrel.app.Graph
import dev.kestrel.app.data.innertube.InnerTube
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.system.YtNotifications
import dev.kestrel.app.ui.Avatar
import dev.kestrel.app.ui.EmptyState
import dev.kestrel.app.ui.ErrorState
import dev.kestrel.app.ui.LoadMoreEffect
import dev.kestrel.app.ui.LoadingState
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.launch

/** YouTube's notification inbox (the bell), for signed-in users. */
@Composable
fun NotificationsScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<InnerTube.Notice>?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { Graph.innerTube.notifications() }
            .onSuccess { items = it.items.distinctBy { n -> n.id }; next = it.next; YtNotifications.markRead(it.items) }
            .onFailure { error = friendlyError(it) }
    }
    val state = rememberLazyListState()
    LoadMoreEffect(state) {
        val t = next ?: return@LoadMoreEffect
        if (loadingMore) return@LoadMoreEffect
        loadingMore = true
        scope.launch {
            runCatching { Graph.innerTube.notificationsMore(t) }.onSuccess { p -> val have = items.orEmpty().map { it.id }.toHashSet(); items = items.orEmpty() + p.items.filter { it.id !in have }.distinctBy { it.id }; next = p.next }
            loadingMore = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Notifications")
        val list = items
        when {
            !Graph.account.isSignedIn -> EmptyState("Sign in with Google to see your YouTube notifications.", icon = Icons.Outlined.Notifications,
                action = "Sign in" to { nav.go("login") })
            error != null && list == null -> ErrorState(error!!) { attempt++ }
            list == null -> LoadingState(skeleton = false)
            list.isEmpty() -> EmptyState("No notifications yet.", icon = Icons.Outlined.Notifications)
            else -> LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(list, key = { it.id }) { n -> NoticeRow(n) { n.videoId?.let { nav.openVideo(Video(it, ""), emptyList()) } } }
            }
        }
    }
}

@Composable
private fun NoticeRow(n: InnerTube.Notice, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(10.dp).padding(top = 18.dp), contentAlignment = Alignment.Center) {
            if (!n.read) Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Avatar(n.avatar, 44)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(n.text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(n.time, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        n.thumbnail?.let {
            AsyncImage(it, null, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop)
        }
    }
}
