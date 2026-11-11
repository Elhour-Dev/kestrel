package dev.kestrel.app.data.innertube

import dev.kestrel.app.data.account.AccountStore
import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Comment
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Page
import dev.kestrel.app.data.model.Playlist
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

class SessionExpiredException : Exception("Your Google session expired. Please sign in again.")
class NotSignedInException : Exception("Sign in to use this feature.")

/**
 * YouTube's internal web API, used with the signed-in browser session.
 * Client version and visitor data refresh at runtime when YouTube changes them.
 */
class InnerTube(private val account: AccountStore) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile var clientVersion = DEFAULT_WEB_VERSION
        private set
    @Volatile var visitorData: String? = null
        private set
    var hl = "en"
    var gl = "US"

    companion object {
        const val DEFAULT_WEB_VERSION = "2.20261105.01.00"
        private const val BASE = "https://www.youtube.com"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    /** Re-reads client version and visitor data from youtube.com. Part of the self-heal. */
    suspend fun refreshConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/sw.js_data")
                .header("User-Agent", Http.BROWSER_UA)
                .build()
            val body = Http.client.newCall(req).execute().use { it.body.string() }
            Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*,\\s*\"([\\d.]+)\"").find(body)?.let { clientVersion = it.groupValues[1] }
                ?: Regex("\"(2\\.20\\d{6}\\.\\d{2}\\.\\d{2})\"").find(body)?.let { clientVersion = it.groupValues[1] }
            Regex("\"(Cg[st][A-Za-z0-9_%-]{20,})\"").find(body)?.let { visitorData = it.groupValues[1] }
            if (visitorData == null) {
                val home = Http.client.newCall(Request.Builder().url(BASE).header("User-Agent", Http.BROWSER_UA).build())
                    .execute().use { it.body.string() }
                Regex("\"INNERTUBE_CLIENT_VERSION\":\"([\\d.]+)\"").find(home)?.let { clientVersion = it.groupValues[1] }
                Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(home)?.let { visitorData = it.groupValues[1] }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun resetConfig() {
        clientVersion = DEFAULT_WEB_VERSION
        visitorData = null
    }

    private fun sapisidHash(cookies: Map<String, String>): String? {
        val sapisid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return null
        val ts = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1").digest("$ts $sapisid $BASE".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$hash"
    }

    private fun context(): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "WEB")
            put("clientVersion", clientVersion)
            put("hl", hl)
            put("gl", gl)
            (account.session.value?.visitorData?.takeIf { it.isNotBlank() } ?: visitorData)?.let { put("visitorData", it) }
        }
        val ds = account.session.value?.dataSyncId.orEmpty()
        if (ds.contains("||")) {
            putJsonObject("user") { put("onBehalfOfUser", ds.substringBefore("||")) }
        }
    }

    /** Raw InnerTube POST. [auth] = send the Google session. */
    suspend fun post(endpoint: String, body: JsonObject, auth: Boolean = true): JsonObject =
        withContext(Dispatchers.IO) {
            val session = account.session.value
            if (auth && session == null) throw NotSignedInException()
            val full = JsonObject(body + ("context" to context()))
            val rb = Request.Builder()
                .url("$BASE/youtubei/v1/$endpoint?prettyPrint=false")
                .post(full.toString().toRequestBody(JSON_TYPE))
                .header("User-Agent", Http.BROWSER_UA)
                .header("Origin", BASE)
                .header("X-Origin", BASE)
                .header("Referer", "$BASE/")
                .header("X-Youtube-Client-Name", "1")
                .header("X-Youtube-Client-Version", clientVersion)
            (session?.visitorData?.takeIf { it.isNotBlank() } ?: visitorData)?.let { rb.header("X-Goog-Visitor-Id", it) }
            if (auth && session != null) {
                rb.header("Cookie", session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                sapisidHash(session.cookies)?.let { rb.header("Authorization", it) }
                rb.header("X-Goog-AuthUser", "0")
            }
            Http.client.newCall(rb.build()).execute().use { resp ->
                if (auth) account.mergeCookies(resp.headers("Set-Cookie"))
                val text = resp.body.string()
                if (auth && (resp.code == 401 || resp.code == 403)) {
                    account.markExpired(); throw SessionExpiredException()
                }
                if (!resp.isSuccessful) error("YouTube returned HTTP ${resp.code} for $endpoint")
                val obj = json.parseToJsonElement(text) as JsonObject
                if (auth && obj.at("responseContext.mainAppWebResponseContext.loggedOut")?.let { (it as? JsonPrimitive)?.content } == "true") {
                    account.markExpired()
                    throw SessionExpiredException()
                }
                obj
            }
        }

    // ---------------- feeds ----------------

    suspend fun browse(browseId: String, params: String? = null): Page<Item> {
        val r = post("browse", buildJsonObject {
            put("browseId", browseId)
            params?.let { put("params", it) }
        })
        return Page(Parser.items(r), Parser.continuation(r))
    }

    suspend fun browseMore(token: String): Page<Item> {
        val r = post("browse", buildJsonObject { put("continuation", token) })
        return Page(Parser.items(r), Parser.continuation(r))
    }

    suspend fun home() = browse("FEwhat_to_watch")
    suspend fun subscriptionsFeed() = browse("FEsubscriptions")
    suspend fun history() = browse("FEhistory")
    suspend fun watchLater() = browse("VLWL")
    suspend fun liked() = browse("VLLL")
    suspend fun playlistItems(id: String) = browse("VL$id")

    /** All channels the account subscribes to. */
    suspend fun subscribedChannels(): List<Channel> {
        val out = LinkedHashMap<String, Channel>()
        var page = browse("FEchannels")
        var guard = 0
        while (true) {
            page.items.filterIsInstance<Channel>().forEach { out[it.id] = it }
            val next = page.next as? String ?: break
            if (++guard > 30) break
            page = browseMore(next)
        }
        return out.values.toList()
    }

    suspend fun libraryPlaylists(): List<Playlist> {
        val a = runCatching { browse("FEplaylist_aggregation").items }.getOrDefault(emptyList())
        val b = if (a.isEmpty()) runCatching { browse("FElibrary").items }.getOrDefault(emptyList()) else emptyList()
        return (a + b).filterIsInstance<Playlist>().distinctBy { it.id }
            .filterNot { it.id == "WL" || it.id == "LL" }
    }

    data class AccountInfo(val name: String, val handle: String, val avatar: String)

    suspend fun accountInfo(): AccountInfo {
        val r = post("account/account_menu", buildJsonObject {})
        val header = r.findAll("activeAccountHeaderRenderer").firstOrNull()
        return AccountInfo(
            header.obj("accountName").text().orEmpty(),
            header.obj("channelHandle").text().orEmpty(),
            header.obj("accountPhoto").thumb().orEmpty(),
        )
    }

    // ---------------- video state & actions ----------------

    data class VideoState(val like: String?, val subscribed: Boolean?, val channelId: String?)

    suspend fun videoState(videoId: String): VideoState {
        val r = post("next", buildJsonObject { put("videoId", videoId) })
        val like = r.findFirstStr("likeStatus")
            ?: r.findAll("likeButtonRenderer").firstOrNull()?.str("likeStatus")
        val sub = r.findAll("subscribeButtonRenderer").firstOrNull()?.bool("subscribed")
            ?: r.findAll("subscribeButtonViewModel").firstOrNull()?.let { vm ->
                val key = vm.str("stateEntityStoreKey")
                r.findWhere { it.str("key") == key && it.containsKey("subscribed") }.firstOrNull()?.bool("subscribed")
            }
        val channel = r.findAll("videoOwnerRenderer").firstOrNull()?.findFirst("browseId")?.let { (it as? JsonPrimitive)?.content }
        return VideoState(like, sub, channel)
    }

    /** status: "like", "dislike" or "removelike". */
    suspend fun rate(videoId: String, status: String) {
        post("like/$status", buildJsonObject { putJsonObject("target") { put("videoId", videoId) } })
    }

    suspend fun subscribe(channelId: String, on: Boolean) {
        post(if (on) "subscription/subscribe" else "subscription/unsubscribe", buildJsonObject {
            putJsonArray("channelIds") { add(JsonPrimitive(channelId)) }
        })
    }

    // ---------------- playlists ----------------

    suspend fun createPlaylist(title: String, firstVideo: String? = null): String? {
        val r = post("playlist/create", buildJsonObject {
            put("title", title)
            put("privacyStatus", "PRIVATE")
            firstVideo?.let { putJsonArray("videoIds") { add(JsonPrimitive(it)) } }
        })
        return r.str("playlistId")
    }

    private suspend fun editPlaylist(id: String, action: JsonObject) {
        post("browse/edit_playlist", buildJsonObject {
            put("playlistId", id)
            putJsonArray("actions") { add(action) }
        })
    }

    suspend fun addToPlaylist(id: String, videoId: String) = editPlaylist(id, buildJsonObject {
        put("action", "ACTION_ADD_VIDEO"); put("addedVideoId", videoId)
    })

    suspend fun removeFromPlaylist(id: String, videoId: String) = editPlaylist(id, buildJsonObject {
        put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID"); put("removedVideoId", videoId)
    })

    suspend fun renamePlaylist(id: String, name: String) = editPlaylist(id, buildJsonObject {
        put("action", "ACTION_SET_PLAYLIST_NAME"); put("playlistName", name)
    })

    suspend fun deletePlaylist(id: String) {
        post("playlist/delete", buildJsonObject { put("playlistId", id) })
    }

    // ---------------- history ----------------

    /** Tells YouTube the video was watched, so it lands in the account's watch history. */
    suspend fun reportPlayback(videoId: String) {
        val r = post("player", buildJsonObject {
            put("videoId", videoId)
            put("contentCheckOk", true); put("racyCheckOk", true)
        })
        val base = r.at("playbackTracking.videostatsPlaybackUrl.baseUrl")?.let { (it as? JsonPrimitive)?.content } ?: return
        val cpn = (1..16).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".random() }.joinToString("")
        val url = base.replace("https://s.youtube.com", BASE) + "&ver=2&c=WEB&cver=$clientVersion&cpn=$cpn"
        withContext(Dispatchers.IO) {
            val session = account.session.value ?: return@withContext
            val req = Request.Builder().url(url)
                .header("User-Agent", Http.BROWSER_UA)
                .header("Cookie", session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .apply { sapisidHash(session.cookies)?.let { header("Authorization", it) } }
                .build()
            Http.client.newCall(req).execute().close()
        }
    }

    // ---------------- comments ----------------

    data class CommentsPage(val comments: List<Comment>, val next: String?, val createParams: String?)

    suspend fun comments(videoId: String): CommentsPage {
        val r = post("next", buildJsonObject { put("videoId", videoId) })
        val section = r.findWhere { it.str("sectionIdentifier") == "comment-item-section" }.firstOrNull()
            ?: r.findAll("itemSectionRenderer").firstOrNull { it.toString().contains("comments-section") }
        val token = section.findFirst("continuationCommand").str("token")
            ?: section?.let { Parser.continuation(it) }
            ?: return CommentsPage(emptyList(), null, null)
        return commentsMore(token)
    }

    suspend fun commentsMore(token: String): CommentsPage {
        val r = post("next", buildJsonObject { put("continuation", token) })
        // The page token is the last continuation outside of reply threads.
        val conts = r.findAll("continuationItemRenderer").filter { c ->
            c.findFirst("continuationCommand") != null
        }
        val next = conts.lastOrNull()?.findFirst("continuationCommand").str("token")
            ?.takeIf { t -> Parser.comments(r).none { it.repliesPage == t } }
        return CommentsPage(Parser.comments(r), next, r.findFirstStr("createCommentParams"))
    }

    suspend fun postComment(createParams: String, text: String) {
        post("comment/create_comment", buildJsonObject {
            put("createCommentParams", createParams); put("commentText", text)
        })
    }

    suspend fun reply(replyParams: String, text: String) {
        post("comment/create_comment_reply", buildJsonObject {
            put("createReplyParams", replyParams); put("commentText", text)
        })
    }

    suspend fun deleteComment(action: String) {
        post("comment/perform_comment_action", buildJsonObject {
            putJsonArray("actions") { add(JsonPrimitive(action)) }
        })
    }
}

