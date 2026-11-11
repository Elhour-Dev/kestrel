package dev.kestrel.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kestrel.app.Graph
import dev.kestrel.app.data.model.Playlist
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.streams.Streams
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.launch

@Composable
fun SaveToPlaylistDialog(video: Video, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val signedIn = Graph.account.isSignedIn
    var remote by remember { mutableStateOf<List<Playlist>?>(null) }
    var newName by remember { mutableStateOf("") }
    var newOnAccount by remember { mutableStateOf(signedIn) }
    LaunchedEffect(Unit) {
        if (signedIn) remote = runCatching { Graph.innerTube.libraryPlaylists() }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (lib.playlists.isNotEmpty()) item { Text("On this phone", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp)) }
                items(lib.playlists, key = { "l" + it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.videos.size} videos") },
                        leadingContent = { Icon(Icons.Default.PhoneAndroid, null) },
                        modifier = Modifier.clickable {
                            Graph.store.addToPlaylist(p.id, video)
                            nav.message("Saved to ${p.name}")
                            onDismiss()
                        },
                    )
                }
                if (signedIn) {
                    item { Text("Google account", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp)) }
                    if (remote == null) item { Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) { CircularProgressIndicator() } }
                    items(remote.orEmpty(), key = { "r" + it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title) },
                            leadingContent = { Icon(Icons.Default.Cloud, null) },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    runCatching { Graph.innerTube.addToPlaylist(p.id, video.id) }
                                        .onSuccess { nav.message("Saved to ${p.title}") }
                                        .onFailure { nav.message(friendlyError(it)) }
                                }
                                onDismiss()
                            },
                        )
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(newName, { newName = it }, label = { Text("New playlist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (signedIn) Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!newOnAccount, { newOnAccount = false }); Text("Phone")
                        RadioButton(newOnAccount, { newOnAccount = true }); Text("Google account")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                val name = newName.trim()
                if (newOnAccount && signedIn) {
                    scope.launch {
                        runCatching { Graph.innerTube.createPlaylist(name, video.id) }
                            .onSuccess { nav.message("Created \"$name\"") }
                            .onFailure { nav.message(friendlyError(it)) }
                    }
                } else {
                    Graph.store.createPlaylist(name, video)
                    nav.message("Created \"$name\"")
                }
                onDismiss()
            }) { Icon(Icons.Default.Add, null); Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun DownloadDialog(video: Video, onDismiss: () -> Unit) {
    val nav = LocalNav.current
    var qualities by remember { mutableStateOf<List<Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf(Graph.settings.defaultQuality.value) }
    var full by remember { mutableStateOf(video) }
    LaunchedEffect(video.id) {
        try {
            val r = Streams.resolve(video.id)
            full = r.video.copy(thumbnail = video.thumbnail.ifBlank { r.video.thumbnail })
            qualities = r.qualities()
            if (r.isLive) error = "Live streams can't be downloaded."
            val q = r.qualities()
            if (choice !in q && choice != -1) choice = q.firstOrNull { it <= choice.coerceAtLeast(1) } ?: q.lastOrNull() ?: -1
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            Column {
                Text(video.title.ifBlank { full.title }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    qualities == null -> Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) { CircularProgressIndicator() }
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(qualities!!) { h ->
                            Row(Modifier.fillMaxWidth().clickable { choice = h }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(choice == h, { choice = h }); Text("Video ${h}p" + if (h > 1080) " (WebM)" else " (MP4)")
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().clickable { choice = -1 }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(choice == -1, { choice = -1 }); Text("Audio only (M4A)")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = qualities != null && error == null, onClick = {
                Graph.downloads.enqueue(full, audioOnly = choice == -1, quality = choice.coerceAtLeast(0))
                nav.message("Download added")
                onDismiss()
            }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
