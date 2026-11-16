package dev.kestrel.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kestrel.app.Graph
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Page
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.newpipe.NpRepo
import dev.kestrel.app.system.Health
import dev.kestrel.app.ui.LocalNav
import dev.kestrel.app.ui.PagedItemList
import kotlinx.coroutines.delay

private val filters = listOf(null to "All", "videos" to "Videos", "channels" to "Channels", "playlists" to "Playlists")

/** YouTube's own search filter codes (protobuf, base64): upload date, length and sort order. */
private val extraFilters = listOf(
    "EgIIAg%3D%3D" to "Today", "EgIIAw%3D%3D" to "This week", "EgIIBA%3D%3D" to "This month", "EgIIBQ%3D%3D" to "This year",
    "EgIYAQ%3D%3D" to "Under 4 minutes", "EgIYAw%3D%3D" to "4-20 minutes", "EgIYAg%3D%3D" to "Over 20 minutes",
    "CAI%3D" to "Newest first", "CAM%3D" to "Most viewed", "EgJAAQ%3D%3D" to "Live",
).map { (code, label) -> java.net.URLDecoder.decode(code, "UTF-8") to label }

suspend fun searchAny(query: String, filter: String?, extra: String? = null): Page<Item> {
    if (extra != null) return Graph.innerTube.search(query, extra)
    if (!Health.searchFallback) {
        try {
            return NpRepo.search(query, filter)
        } catch (e: Exception) {
            // Fall through to the backup search.
            runCatching { return Graph.innerTube.search(query) }
            throw e
        }
    }
    return Graph.innerTube.search(query)
}

suspend fun searchMoreAny(token: Any): Page<Item> =
    if (token is String) Graph.innerTube.searchMore(token) else moreAny(token)

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    val keyboard = LocalSoftwareKeyboardController.current
    var text by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var extra by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search")
    }
    val canVoice = remember { voiceIntent.resolveActivity(ctx.packageManager) != null }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val lib by Graph.store.library.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    fun submit(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        keyboard?.hide()
        // A pasted link opens directly.
        if (query.startsWith("http")) {
            NpRepo.videoId(query)?.let { nav.openVideo(Video(it, "")); return }
            NpRepo.playlistId(query)?.let { nav.openPlaylist(it); return }
            NpRepo.channelId(query)?.let { nav.openChannel(it); return }
        }
        text = query
        Graph.store.addSearch(query)
        submitted = query
    }

    LaunchedEffect(text, submitted) {
        if (submitted == text || text.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        suggestions = runCatching { NpRepo.suggestions(text) }.getOrDefault(emptyList()).take(10)
    }
    LaunchedEffect(Unit) { if (submitted == null) focus.requestFocus() }
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { submit(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            TextField(
                value = text,
                onValueChange = { text = it; if (submitted != null && it != submitted) submitted = null },
                placeholder = { Text("Search videos, channels, playlists") },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                trailingIcon = {
                    if (text.isNotEmpty()) IconButton(onClick = { text = ""; submitted = null; focus.requestFocus() }) { Icon(Icons.Default.Close, "Clear") }
                    else if (canVoice) IconButton(onClick = { runCatching { voice.launch(voiceIntent) } }) { Icon(Icons.Default.Mic, "Voice search") }
                },
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                shape = MaterialTheme.shapes.extraLarge,
            )
        }
        val q = submitted
        if (q == null) {
            val history = if (text.isBlank()) lib.searches else lib.searches.filter { it.contains(text, true) }.take(4)
            LazyColumn {
                items(history, key = { "h$it" }) { s ->
                    ListItem(
                        headlineContent = { Text(s) },
                        leadingContent = { Icon(Icons.Default.History, null) },
                        trailingContent = { IconButton(onClick = { Graph.store.removeSearch(s) }) { Icon(Icons.Default.Close, "Remove") } },
                        modifier = Modifier.clickable { submit(s) },
                    )
                }
                items(suggestions.filter { it !in history }, key = { "s$it" }) { s ->
                    ListItem(
                        headlineContent = { Text(s) },
                        leadingContent = { Icon(Icons.Default.Search, null) },
                        trailingContent = { IconButton(onClick = { text = "$s " }) { Icon(Icons.Default.NorthWest, "Use") } },
                        modifier = Modifier.clickable { submit(s) },
                    )
                }
            }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { (f, label) -> FilterChip(filter == f && extra == null, { filter = f; extra = null }, { Text(label) }) }
                items(extraFilters) { (code, label) -> FilterChip(extra == code, { extra = if (extra == code) null else code }, { Text(label) }) }
            }
            val vm = viewModel(key = "search-$q-$filter-$extra") { itemsVm({ searchAny(q, filter, extra) }, ::searchMoreAny) }
            PagedItemList(vm, "No results for \"$q\".", compact = false)
        }
    }
}
