package dev.kestrel.app.data

import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

@Serializable
data class HistoryEntry(val video: Video, val watchedAt: Long)

@Serializable
data class LocalPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val videos: List<Video> = emptyList(),
    val created: Long = System.currentTimeMillis(),
)

@Serializable
data class Library(
    val version: Int = 1,
    val history: List<HistoryEntry> = emptyList(),
    val subscriptions: List<Channel> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val positions: Map<String, Long> = emptyMap(),
    val searches: List<String> = emptyList(),
)

/** Local library kept in one JSON file. Works without an account. */
class LocalStore(dir: File) {
    private val file = File(dir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val state = MutableStateFlow(load())
    val library: StateFlow<Library> get() = state

    private fun load(): Library = try {
        if (file.exists()) json.decodeFromString(Library.serializer(), file.readText()) else Library()
    } catch (_: Exception) {
        Library()
    }

    private fun change(block: (Library) -> Library) {
        state.update(block)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            saveNow()
        }
    }

    fun saveNow() {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(json.encodeToString(Library.serializer(), state.value))
        tmp.renameTo(file).let { ok -> if (!ok) { file.delete(); tmp.renameTo(file) } }
    }

    // History
    fun addHistory(v: Video) = change { lib ->
        val rest = lib.history.filterNot { it.video.id == v.id }
        lib.copy(history = (listOf(HistoryEntry(v, System.currentTimeMillis())) + rest).take(1500))
    }
    fun removeHistory(id: String) = change { it.copy(history = it.history.filterNot { h -> h.video.id == id }) }
    fun clearHistory() = change { it.copy(history = emptyList(), positions = emptyMap()) }

    // Positions
    fun position(id: String): Long = state.value.positions[id] ?: 0L
    fun savePosition(id: String, ms: Long) = change { lib ->
        val map = LinkedHashMap(lib.positions)
        map.remove(id)
        if (ms > 0) map[id] = ms
        val trimmed = if (map.size > 3000) map.entries.drop(map.size - 3000).associate { it.key to it.value } else map
        lib.copy(positions = trimmed)
    }

    // Subscriptions
    fun isSubscribed(id: String) = state.value.subscriptions.any { it.id == id }
    fun subscribe(c: Channel) = change { lib ->
        if (lib.subscriptions.any { it.id == c.id }) lib else lib.copy(subscriptions = lib.subscriptions + c)
    }
    fun unsubscribe(id: String) = change { it.copy(subscriptions = it.subscriptions.filterNot { c -> c.id == id }) }
    fun mergeSubscriptions(list: List<Channel>) = change { lib ->
        val known = lib.subscriptions.associateBy { it.id }.toMutableMap()
        list.forEach { known[it.id] = it }
        lib.copy(subscriptions = known.values.sortedBy { it.name.lowercase() })
    }

    // Playlists
    fun createPlaylist(name: String, first: Video? = null): LocalPlaylist {
        val p = LocalPlaylist(name = name, videos = listOfNotNull(first))
        change { it.copy(playlists = it.playlists + p) }
        return p
    }
    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }
    fun deletePlaylist(id: String) = change { it.copy(playlists = it.playlists.filterNot { p -> p.id == id }) }
    fun addToPlaylist(id: String, v: Video) = editPlaylist(id) { p ->
        if (p.videos.any { it.id == v.id }) p else p.copy(videos = p.videos + v)
    }
    fun removeFromPlaylist(id: String, videoId: String) =
        editPlaylist(id) { p -> p.copy(videos = p.videos.filterNot { it.id == videoId }) }
    private fun editPlaylist(id: String, f: (LocalPlaylist) -> LocalPlaylist) = change { lib ->
        lib.copy(playlists = lib.playlists.map { if (it.id == id) f(it) else it })
    }

    // Search history
    fun addSearch(q: String) = change { lib ->
        lib.copy(searches = (listOf(q) + lib.searches.filterNot { it.equals(q, true) }).take(50))
    }
    fun removeSearch(q: String) = change { it.copy(searches = it.searches - q) }

    // Import / export
    fun exportJson(): String = json.encodeToString(Library.serializer(), state.value)

    /** Accepts Kestrel backups, NewPipe subscription exports, or Google Takeout subscriptions.csv. */
    fun import(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("{")) {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            if ("subscriptions" in obj && obj["subscriptions"]!!.jsonArray.firstOrNull()?.jsonObject?.containsKey("service_id") == true) {
                val subs = obj["subscriptions"]!!.jsonArray.mapNotNull { e ->
                    val o = e.jsonObject
                    val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val id = Regex("(UC[A-Za-z0-9_-]{22})").find(url)?.value ?: return@mapNotNull null
                    Channel(id, o["name"]?.jsonPrimitive?.content ?: id)
                }
                mergeSubscriptions(subs)
                return "Imported ${subs.size} subscriptions from NewPipe"
            }
            val lib = json.decodeFromString(Library.serializer(), trimmed)
            change { cur ->
                cur.copy(
                    history = (lib.history + cur.history).distinctBy { it.video.id }.take(1500),
                    subscriptions = (cur.subscriptions + lib.subscriptions).distinctBy { it.id },
                    playlists = cur.playlists + lib.playlists.filter { p -> cur.playlists.none { it.id == p.id } },
                    positions = cur.positions + lib.positions,
                    searches = (cur.searches + lib.searches).distinct().take(50),
                )
            }
            return "Imported ${lib.subscriptions.size} subscriptions, ${lib.playlists.size} playlists, ${lib.history.size} history entries"
        }
        // Takeout CSV: Channel Id,Channel Url,Channel Title
        val subs = trimmed.lines().drop(1).mapNotNull { line ->
            val parts = line.split(",")
            val id = parts.firstOrNull()?.trim()?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
            Channel(id, parts.getOrNull(2)?.trim().orEmpty().ifBlank { id })
        }
        if (subs.isEmpty()) error("File format not recognized")
        mergeSubscriptions(subs)
        return "Imported ${subs.size} subscriptions from Takeout"
    }
}
