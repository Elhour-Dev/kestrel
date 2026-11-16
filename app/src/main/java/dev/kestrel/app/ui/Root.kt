package dev.kestrel.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import dev.kestrel.app.Graph
import dev.kestrel.app.MainActivity
import dev.kestrel.app.data.account.AccountState
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.player.PlayerManager
import dev.kestrel.app.ui.screens.*
import kotlinx.coroutines.launch


@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KestrelRoot(activity: MainActivity) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Pair<Video, List<Pair<String, () -> Unit>>>?>(null) }
    var saveFor by remember { mutableStateOf<Video?>(null) }
    var downloadFor by remember { mutableStateOf<Video?>(null) }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    MainActivity.Ui.onPlayerScreen = route == "player"

    val appNav = remember {
        object : AppNav {
            override fun openVideo(v: Video, queue: List<Video>) {
                val q = if (queue.any { it.id == v.id }) queue else listOf(v)
                PlayerManager.play(v, q, q.indexOfFirst { it.id == v.id }.coerceAtLeast(0))
                nav.navigate("player") { launchSingleTop = true }
            }
            override fun openChannel(id: String) = nav.navigate("channel/$id")
            override fun openPlaylist(id: String, source: String) = nav.navigate("playlist/$source/$id")
            override fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
            override fun back() { nav.popBackStack() }
            override fun message(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
            override fun videoMenu(v: Video, extra: List<Pair<String, () -> Unit>>) { menuFor = v to extra }
        }
    }

    LaunchedEffect(Unit) { PlayerManager.messages.collect { appNav.message(it) } }

    // Deep links: notification taps and shared YouTube links.
    val pending = MainActivity.Ui.pendingIntent
    LaunchedEffect(pending, route) {
        val i = pending ?: return@LaunchedEffect
        if (route.isBlank() || route == "startup" || route == "onboarding") return@LaunchedEffect
        MainActivity.Ui.pendingIntent = null
        handleIntent(i, appNav)
    }

    val fullscreenPlayer = route == "player" && (MainActivity.Ui.fullscreen || MainActivity.Ui.inPip)
    val showChrome = route in chromeRoutes
    val showMini = route !in setOf("player", "startup", "onboarding", "login", "shortsplayer") && !MainActivity.Ui.inPip

    CompositionLocalProvider(LocalNav provides appNav) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!fullscreenPlayer) Column {
                    if (showChrome) BottomBar(route) { r ->
                        nav.navigate(r) {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            },
            contentWindowInsets = if (fullscreenPlayer) androidx.compose.foundation.layout.WindowInsets(0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(if (fullscreenPlayer) androidx.compose.foundation.layout.PaddingValues(0.dp) else pad)) {
                if (!fullscreenPlayer && route != "startup" && route != "login") SessionBanner(appNav)
                Box(Modifier.weight(1f)) {
                NavHost(nav, startDestination = "startup", modifier = Modifier.fillMaxSize()) {
                    composable("startup") { StartupScreen { next -> nav.navigate(next) { popUpTo("startup") { inclusive = true } } } }
                    composable("onboarding") { OnboardingScreen { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } } }
                    composable("login") { LoginScreen() }
                    composable("home") { HomeScreen("home") }
                    composable("shorts") { ShortsScreen() }
                    composable("subs") { HomeScreen("subs") }
                    composable("kiosk/{id}") { KioskScreen(it.arguments?.getString("id").orEmpty()) }
                    composable("library") { LibraryScreen() }
                    composable("search") { SearchScreen() }
                    composable("channel/{id}") { ChannelScreen(it.arguments?.getString("id").orEmpty()) }
                    composable("playlist/{source}/{id}") {
                        PlaylistScreen(it.arguments?.getString("id").orEmpty(), it.arguments?.getString("source").orEmpty())
                    }
                    composable("feed/{kind}") { FeedScreen(it.arguments?.getString("kind").orEmpty()) }
                    composable("player") { PlayerScreen(activity) }
                    composable("downloads") { DownloadsScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("help") { HelpScreen() }
                    composable("subchannels") { SubscribedChannelsScreen() }
                    composable("playlists") { PlaylistsScreen() }
                }
                if (showMini && !fullscreenPlayer) MiniPlayer(Modifier.align(Alignment.BottomEnd).padding(12.dp)) { nav.navigate("player") { launchSingleTop = true } }
                }
            }
        }

        menuFor?.let { (v, extra) ->
            ModalBottomSheet(onDismissRequest = { menuFor = null }) {
                val close = { menuFor = null }
                Text(v.title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MenuRow(Icons.Default.PlayArrow, "Play") { close(); appNav.openVideo(v) }
                MenuRow(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") { close(); PlayerManager.addToQueue(v) }
                MenuRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Save to playlist") { close(); saveFor = v }
                if (Graph.account.isSignedIn) MenuRow(Icons.Default.WatchLater, "Save to Watch later") {
                    close()
                    scope.launch {
                        runCatching { Graph.innerTube.addToPlaylist("WL", v.id) }
                            .onSuccess { appNav.message("Saved to Watch later") }
                            .onFailure { appNav.message(friendlyError(it)) }
                    }
                }
                MenuRow(Icons.Default.Download, "Download") { close(); downloadFor = v }
                v.channelId?.let { cid -> MenuRow(Icons.Default.Person, "Go to channel") { close(); appNav.openChannel(cid) } }
                MenuRow(Icons.Default.Share, "Share") {
                    close()
                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, v.url), "Share"))
                }
                extra.forEach { (label, f) -> MenuRow(Icons.Default.Close, label) { close(); f() } }
                androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
            }
        }
        saveFor?.let { SaveToPlaylistDialog(it) { saveFor = null } }
        downloadFor?.let { DownloadDialog(it) { downloadFor = null } }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "t=" or "start=" from a YouTube link: 95, 95s, 1m35s, 1h2m3s. */
internal fun linkStartMs(url: String): Long? {
    val v = Regex("[?&#](?:t|start)=([0-9hms]+)").find(url)?.groupValues?.get(1) ?: return null
    v.toLongOrNull()?.let { return it.takeIf { it in 1..MAX_START_SEC }?.times(1000) }
    val m = Regex("^(?:([0-9]+)h)?(?:([0-9]+)m)?(?:([0-9]+)s)?$").find(v) ?: return null
    val (h, mi, se) = m.destructured
    val sec = (h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (se.toLongOrNull() ?: 0)
    return sec.takeIf { it in 1..MAX_START_SEC }?.times(1000)
}

private const val MAX_START_SEC = 7 * 24 * 3600L

private fun handleIntent(i: Intent, nav: AppNav) {
    when (i.action) {
        MainActivity.ACTION_OPEN_PLAYER -> if (PlayerManager.now.value != null) nav.go("player")
        MainActivity.ACTION_OPEN_DOWNLOADS -> nav.go("downloads")
        Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: i.dataString ?: return
            val url = Regex("https?://\\S+").find(text)?.value ?: text
            NpRepo.videoId(url)?.let { id ->
                PlayerManager.linkStart = linkStartMs(url)?.let { id to it }
                nav.openVideo(Video(id, "")); return
            }
            NpRepo.playlistId(url)?.let { nav.openPlaylist(it); return }
            NpRepo.channelId(url)?.let { nav.openChannel(it); return }
            nav.message("Can't open this link")
        }
    }
}

@Composable
private fun SessionBanner(nav: AppNav) {
    val state by Graph.account.state.collectAsStateWithLifecycle()
    AnimatedVisibility(state == AccountState.Expired) {
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your Google session expired.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { nav.go("login") }) { Text("Sign in again") }
            }
        }
    }
}

@Suppress("unused")
private fun NavHostController.safePop() = runCatching { popBackStack() }
