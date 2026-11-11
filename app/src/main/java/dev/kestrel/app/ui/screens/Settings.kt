package dev.kestrel.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import dev.kestrel.app.BuildConfig
import dev.kestrel.app.Graph
import dev.kestrel.app.data.Pref
import dev.kestrel.app.data.SponsorCategories
import dev.kestrel.app.data.account.AccountState
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.downloads.DlStatus
import dev.kestrel.app.downloads.DownloadItem
import dev.kestrel.app.player.PlayerManager
import dev.kestrel.app.system.Health
import dev.kestrel.app.system.Updater
import dev.kestrel.app.ui.Avatar
import dev.kestrel.app.ui.EmptyState
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.SectionTitle
import dev.kestrel.app.ui.Thumb
import dev.kestrel.app.ui.friendlyError
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
@Composable
fun DownloadsScreen() {
    val nav = LocalNav.current
    val items by Graph.downloads.items.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<DownloadItem?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Downloads")
        if (items.isEmpty()) {
            EmptyState("No downloads yet.\nUse Download on any video to save it for offline.", icon = Icons.Default.Download)
            return@Column
        }
        LazyColumn {
            items(items, key = { it.id }) { d ->
                Row(Modifier.fillMaxWidth().clickable(enabled = d.status == DlStatus.Done) {
                    PlayerManager.playLocal(d.video, d.location!!, d.audioOnly)
                    nav.go("player")
                }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(d.video, Modifier.size(width = 120.dp, height = 68.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(d.video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        val kind = if (d.audioOnly) "Audio" else if (d.quality > 0) "${d.quality}p" else "Best"
                        val status = when (d.status) {
                            DlStatus.Queued -> "Waiting"
                            DlStatus.Running -> "${(d.progress * 100).toInt()}% • ${mb(d.done)} / ${mb(d.total)}"
                            DlStatus.Paused -> "Paused • ${(d.progress * 100).toInt()}%"
                            DlStatus.Merging -> "Merging video and audio…"
                            DlStatus.Done -> "Saved • ${mb(d.total)} • tap to play offline"
                            DlStatus.Failed -> "Failed: ${d.error}"
                        }
                        Text("$kind • $status", style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            color = if (d.status == DlStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.status == DlStatus.Running || d.status == DlStatus.Paused) LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
                        if (d.status == DlStatus.Merging || d.status == DlStatus.Queued) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    when (d.status) {
                        DlStatus.Running, DlStatus.Queued -> IconButton(onClick = { Graph.downloads.pause(d.id) }) { Icon(Icons.Default.Pause, "Pause") }
                        DlStatus.Paused, DlStatus.Failed -> IconButton(onClick = { Graph.downloads.resume(d.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                        else -> {}
                    }
                    if (d.status == DlStatus.Done) IconButton(onClick = { confirm = d }) { Icon(Icons.Default.Delete, "Delete") }
                    else if (d.status != DlStatus.Merging) IconButton(onClick = { Graph.downloads.cancel(d.id) }) { Icon(Icons.Default.Close, "Cancel") }
                }
            }
        }
    }
    confirm?.let { d ->
        ConfirmDialog("Delete \"${d.video.title}\" from this phone?", { confirm = null }) { Graph.downloads.delete(d.id) }
    }
}

private fun mb(b: Long) = if (b <= 0) "?" else String.format(Locale.US, "%.1f MB", b / 1048576.0)

@Composable
private fun SwitchRow(title: String, sub: String? = null, pref: Pref<Boolean>) {
    val v by pref.flow.collectAsStateWithLifecycle()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Switch(v, { pref.value = it }) },
        modifier = Modifier.clickable { pref.value = !v },
    )
}

@Composable
private fun <T> ChoiceRow(title: String, pref: Pref<T>, options: List<Pair<T, String>>) {
    val v by pref.flow.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(options.firstOrNull { it.first == v }?.second ?: v.toString()) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { pref.value = value; open = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(v == value, { pref.value = value; open = false }); Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}

private val regions: List<Pair<String, String>> by lazy {
    Locale.getISOCountries().map { it to Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH) }.sortedBy { it.second }
}

@UnstableApi
@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = Graph.settings
    val session by Graph.account.session.collectAsStateWithLifecycle()
    val accountState by Graph.account.state.collectAsStateWithLifecycle()
    val sbCats by s.sponsorBlockCategories.flow.collectAsStateWithLifecycle()
    val sbOn by s.sponsorBlockEnabled.flow.collectAsStateWithLifecycle()
    val folder by s.downloadFolder.flow.collectAsStateWithLifecycle()
    val health by Health.results.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            s.downloadFolder.value = uri.toString()
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Graph.store.exportJson().toByteArray()) }
            nav.message("Library exported")
        }.onFailure { nav.message(friendlyError(it)) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            nav.message(Graph.store.import(text))
        }.onFailure { nav.message("Import failed: ${friendlyError(it)}") }
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Settings")
        LazyColumn {
            item { SectionTitle("Account") }
            item {
                val sess = session
                if (sess == null) {
                    ListItem(
                        headlineContent = { Text("Sign in with Google") },
                        supportingContent = { Text("Your feed, subscriptions, history, playlists, likes and comments") },
                        modifier = Modifier.clickable { nav.go("login") },
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(sess.name.ifBlank { "Google account" }) },
                        supportingContent = { Text(if (accountState == AccountState.Expired) "Session expired — tap to sign in again" else sess.handle.ifBlank { "Signed in" }) },
                        leadingContent = { Avatar(sess.avatar, 40) },
                        trailingContent = { TextButton(onClick = { confirmSignOut = true }) { Text("Sign out") } },
                        modifier = Modifier.clickable { if (accountState == AccountState.Expired) nav.go("login") },
                    )
                }
            }
            if (session != null) {
                item { SwitchRow("Send plays to watch history", "Videos you watch appear in your YouTube history", s.sendHistory) }
                item {
                    ListItem(headlineContent = { Text("Sync subscriptions now") }, modifier = Modifier.clickable {
                        scope.launchCatching(nav, "Subscriptions synced") { Graph.store.mergeSubscriptions(Graph.innerTube.subscribedChannels()) }
                    })
                }
            }

            item { SectionTitle("Appearance") }
            item { ChoiceRow("Theme", s.theme, listOf("system" to "System", "light" to "Light", "dark" to "Dark")) }

            item { SectionTitle("Playback") }
            item { ChoiceRow("Default quality", s.defaultQuality, listOf(0 to "Best available", 2160 to "2160p", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p", 240 to "240p", 144 to "144p")) }
            item { SwitchRow("Background play", "Keep playing when you leave the app or turn the screen off", s.backgroundPlay) }
            item { SwitchRow("Picture-in-picture", "Shrink the video to a small window when you leave the app", s.autoPip) }
            item { SwitchRow("Resume where you left off", null, s.resumePlayback) }

            item { SectionTitle("SponsorBlock") }
            item { SwitchRow("Skip segments automatically", "Community-submitted segments from sponsor.ajay.app", s.sponsorBlockEnabled) }
            item { SwitchRow("Show a message when skipping", null, s.sponsorBlockToast) }
            items(SponsorCategories.all.entries.toList()) { (key, label) ->
                ListItem(
                    headlineContent = { Text(label) },
                    trailingContent = {
                        Switch(key in sbCats, enabled = sbOn, onCheckedChange = { on ->
                            s.sponsorBlockCategories.value = if (on) sbCats + key else sbCats - key
                        })
                    },
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            item { SectionTitle("Downloads") }
            item {
                ListItem(
                    headlineContent = { Text("Download folder") },
                    supportingContent = { Text(if (folder.isBlank()) "Downloads/Kestrel" else Uri.parse(folder).lastPathSegment ?: folder) },
                    leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                    trailingContent = { if (folder.isNotBlank()) TextButton(onClick = { s.downloadFolder.value = "" }) { Text("Reset") } },
                    modifier = Modifier.clickable { pickFolder.launch(null) },
                )
            }
            item { ChoiceRow("Downloads at the same time", s.maxParallelDownloads, (1..5).map { it to it.toString() }) }

            item { SectionTitle("Content") }
            item {
                val region by s.region.flow.collectAsStateWithLifecycle()
                ChoiceRow("Content region", s.region, regions)
                androidx.compose.runtime.LaunchedEffect(region) {
                    NpRepo.setRegion(region, s.language.value)
                    Graph.innerTube.gl = region
                }
            }

            item { SectionTitle("Your data") }
            item { ListItem(headlineContent = { Text("Import") }, supportingContent = { Text("Kestrel backup, NewPipe subscriptions, or Google Takeout subscriptions.csv") }, modifier = Modifier.clickable { importFile.launch(arrayOf("*/*")) }) }
            item { ListItem(headlineContent = { Text("Export") }, supportingContent = { Text("Subscriptions, playlists, history (no login data)") }, modifier = Modifier.clickable { exportFile.launch("kestrel-backup.json") }) }
            item { ListItem(headlineContent = { Text("Clear watch history") }, modifier = Modifier.clickable { Graph.store.clearHistory(); nav.message("History cleared") }) }

            item { SectionTitle("System status") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (health.isEmpty()) Text("Not checked yet.", style = MaterialTheme.typography.bodySmall)
                    health.values.forEach {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Icon(if (it.ok) Icons.Default.CheckCircle else Icons.Default.Error, null,
                                tint = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text("  ${it.feature.label}: ${it.message}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(enabled = !checking, onClick = {
                        checking = true
                        scope.launch { runCatching { Health.runAll {} }; checking = false }
                    }) { Text(if (checking) "Checking…" else "Run check again") }
                }
            }

            item { SectionTitle("About") }
            item {
                ListItem(headlineContent = { Text("Kestrel v${BuildConfig.VERSION_NAME}") }, supportingContent = { Text("Check for updates") }, modifier = Modifier.clickable {
                    scope.launch {
                        try {
                            val r = Updater.check()
                            if (r == null) nav.message("You're on the latest version")
                            else {
                                nav.message("Downloading ${r.name}…")
                                Updater.install(ctx, Updater.download(ctx, r) {})
                            }
                        } catch (e: Exception) { nav.message(friendlyError(e)) }
                    }
                })
            }
            item { ListItem(headlineContent = { Text("Help") }, modifier = Modifier.clickable { nav.go("help") }) }
            item { ListItem(headlineContent = { Text("License") }, supportingContent = { Text("GNU GPL v3. Uses NewPipeExtractor (GPLv3), Media3, SponsorBlock API.") }) }
            item { HorizontalDivider() }
        }
    }
    if (confirmSignOut) ConfirmDialog("Sign out? Your local subscriptions and playlists stay on this phone.", { confirmSignOut = false }) {
        Graph.account.signOut()
        Health.clearAccountIssue()
        nav.message("Signed out")
    }
}

@Composable
fun HelpScreen() {
    val faq = listOf(
        "Why are there no ads?" to "Kestrel plays the video streams directly, so YouTube's ad system never runs.",
        "What does SponsorBlock skip?" to "Parts of videos the community marked as sponsors, self-promotion, intros, outros, \"like and subscribe\" reminders and previews. Pick the categories in Settings.",
        "How do I play in the background?" to "Just leave the app or turn off the screen. Use the notification to pause or skip. Turn this off in Settings > Background play.",
        "How do I use the popup player?" to "On a video, tap Popup. The first time, allow \"Display over other apps\". Drag the window to move it, pinch to resize, tap it for controls.",
        "Picture-in-picture" to "Press Home while a video plays, or tap PiP on the video page.",
        "Player gestures" to "Double-tap the left or right side to jump 10 seconds. Swipe up/down on the left for brightness, on the right for volume.",
        "Where are my downloads?" to "In Library > Downloads (they play offline) and in your phone's Downloads/Kestrel folder, unless you picked another folder.",
        "Is signing in safe?" to "You sign in on Google's own page. Kestrel keeps the session cookies encrypted on this phone only and never sends them anywhere except YouTube.",
        "\"Your session expired\"" to "Google ended the login. Tap \"Sign in again\". Your local data is kept.",
        "Something stopped working" to "YouTube changes often. Kestrel tries to fix itself at startup and checks for a fixed version. See Settings > System status. Updates arrive automatically.",
        "Import subscriptions" to "Settings > Import accepts a NewPipe export, a Google Takeout subscriptions.csv, or a Kestrel backup.",
    )
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Help")
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(faq) { (q, a) ->
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text(q, style = MaterialTheme.typography.titleSmall)
                    Text(a, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
