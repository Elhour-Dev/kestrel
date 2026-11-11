package dev.kestrel.app.data.innertube

import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Comment
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Playlist
import dev.kestrel.app.data.model.Video
import dev.kestrel.app.data.model.thumbFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Pulls videos, channels and playlists out of any InnerTube response.
 * It searches by renderer name instead of fixed paths, so small layout changes don't break it.
 */
object Parser {
    private val videoKeys = setOf(
        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer",
        "playlistPanelVideoRenderer", "videoCardRenderer",
    )

    fun items(root: JsonElement?): List<Item> {
        val out = LinkedHashMap<String, Item>()
        walk(root, out)
        return out.values.toList()
    }

    private fun walk(e: JsonElement?, out: MutableMap<String, Item>) {
        when (e) {
            is JsonObject -> for ((k, v) in e) {
                val item = when (k) {
                    in videoKeys -> video(v)
                    "reelItemRenderer" -> reel(v)
                    "shortsLockupViewModel" -> shortsLockup(v)
                    "lockupViewModel" -> lockup(v)
                    "channelRenderer", "gridChannelRenderer" -> channel(v)
                    "playlistRenderer", "gridPlaylistRenderer", "compactPlaylistRenderer" -> playlist(v)
                    else -> null
                }
                if (item != null) out.putIfAbsent(item.key, item)
                // Don't descend into items we already parsed (avoids nested duplicates).
                else walk(v, out)
            }
            is JsonArray -> e.forEach { walk(it, out) }
            else -> {}
        }
    }

    private fun channelIdIn(e: JsonElement?): String? =
        e.findWhere({ it.str("browseId")?.startsWith("UC") == true }).firstOrNull()?.str("browseId")

    private fun parseDuration(s: String?): Long {
        if (s.isNullOrBlank()) return -1
        val parts = s.trim().split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return -1
        return parts.fold(0L) { acc, p -> acc * 60 + p }
    }

    private fun parseViews(s: String?): Long {
        if (s == null) return -1
        val m = Regex("([\\d.,]+)\\s*([KMB]?)", RegexOption.IGNORE_CASE).find(s) ?: return -1
        val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1
        val mul = when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
        return (num * mul).toLong()
    }

    fun video(v: JsonElement): Video? {
        val id = v.str("videoId") ?: return null
        val owner = v.obj("ownerText") ?: v.obj("shortBylineText") ?: v.obj("longBylineText")
        val duration = v.obj("lengthText").text()
            ?: v.findAll("thumbnailOverlayTimeStatusRenderer").firstOrNull()?.obj("text").text()
        val live = v.findAll("badges").isNotEmpty() && v.toString().contains("BADGE_STYLE_TYPE_LIVE_NOW")
        return Video(
            id = id,
            title = v.obj("title").text() ?: v.obj("headline").text() ?: "",
            channelName = owner.text().orEmpty(),
            channelId = channelIdIn(owner),
            thumbnail = v.obj("thumbnail").thumb() ?: thumbFor(id),
            durationSec = parseDuration(duration),
            views = parseViews(v.obj("viewCountText").text() ?: v.obj("shortViewCountText").text()),
            uploaded = v.obj("publishedTimeText").text(),
            isLive = live,
        )
    }

    private fun reel(v: JsonElement): Video? {
        val id = v.str("videoId") ?: return null
        return Video(id, v.obj("headline").text().orEmpty(), thumbnail = v.obj("thumbnail").thumb() ?: thumbFor(id), isShort = true)
    }

    private fun shortsLockup(v: JsonElement): Video? {
        val id = v.findFirstStr("videoId") ?: return null
        return Video(
            id = id,
            title = v.at("overlayMetadata.primaryText").text() ?: v.str("accessibilityText").orEmpty(),
            thumbnail = v.at("thumbnail.sources").thumb() ?: thumbFor(id),
            views = parseViews(v.at("overlayMetadata.secondaryText").text()),
            isShort = true,
        )
    }

    private fun lockup(v: JsonElement): Item? {
        val type = v.str("contentType") ?: return null
        val id = v.str("contentId") ?: return null
        val meta = v.at("metadata.lockupMetadataViewModel")
        val title = meta.obj("title").text().orEmpty()
        val rows = meta.at("metadata.contentMetadataViewModel.metadataRows") as? JsonArray
        val parts = rows?.flatMap { r -> (r.arr("metadataParts") ?: JsonArray(emptyList())).mapNotNull { it.obj("text").text() } }.orEmpty()
        val image = v.at("contentImage.thumbnailViewModel.image") ?: v.at("contentImage.collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel.image")
        val badges = v.findAll("thumbnailBadgeViewModel").mapNotNull { it.str("text") }
        return when (type) {
            "LOCKUP_CONTENT_TYPE_VIDEO" -> Video(
                id = id,
                title = title,
                channelName = parts.firstOrNull().orEmpty(),
                channelId = channelIdIn(meta),
                thumbnail = image.thumb() ?: thumbFor(id),
                durationSec = parseDuration(badges.firstOrNull { it.contains(':') }),
                views = parseViews(parts.firstOrNull { it.contains("view", true) }),
                uploaded = parts.firstOrNull { it.contains("ago", true) },
                isLive = badges.any { it.equals("LIVE", true) },
            )
            "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST", "LOCKUP_CONTENT_TYPE_ALBUM" -> Playlist(
                id = id,
                title = title,
                thumbnail = image.thumb(),
                count = badges.firstNotNullOfOrNull { Regex("(\\d+)").find(it.replace(",", ""))?.value?.toLongOrNull() } ?: -1,
                owner = parts.firstOrNull().orEmpty(),
            )
            else -> null
        }
    }

    private fun channel(v: JsonElement): Channel? {
        val id = v.str("channelId") ?: return null
        val subs = v.obj("subscriberCountText").text() ?: v.obj("videoCountText").text()
        return Channel(id, v.obj("title").text().orEmpty(), v.obj("thumbnail").thumb(), parseViews(subs))
    }

    private fun playlist(v: JsonElement): Playlist? {
        val id = v.str("playlistId") ?: return null
        val thumb = v.obj("thumbnail").thumb() ?: v.findAll("thumbnail").firstOrNull().thumb()
        val count = (v.obj("videoCountText").text() ?: v.str("videoCount"))
            ?.let { Regex("(\\d+)").find(it.replace(",", ""))?.value?.toLongOrNull() } ?: -1
        return Playlist(id, v.obj("title").text().orEmpty(), thumb, count, v.obj("shortBylineText").text().orEmpty())
    }

    /** Token of the next page, if any. */
    fun continuation(root: JsonElement?): String? {
        val items = root.findAll("continuationItemRenderer")
        for (c in items.asReversed()) {
            c.findFirstStr("token")?.let { return it }
        }
        return root.findFirst("nextContinuationData").str("continuation")
    }

    // ---------------- comments ----------------

    /** Comments in either the classic renderer format or the newer entity format. */
    fun comments(root: JsonElement?): List<Comment> {
        val entities = HashMap<String, JsonObject>()
        root.at("frameworkUpdates.entityBatchUpdate.mutations")?.let { muts ->
            (muts as? JsonArray)?.forEach { m ->
                val key = m.str("entityKey") ?: return@forEach
                m.obj("payload")?.let { entities[key] = it }
            }
        }
        val out = mutableListOf<Comment>()
        // Each thread, or each standalone reply view model.
        val holders = root.findWhere { it.containsKey("commentThreadRenderer") || it.containsKey("commentViewModel") || it.containsKey("commentRenderer") }
        val seen = HashSet<String>()
        for (h in holders) {
            val thread = h.obj("commentThreadRenderer")
            val vm = (thread ?: h).obj("commentViewModel")?.let { it.obj("commentViewModel") ?: it }
            val classic = (thread?.obj("comment") ?: h).obj("commentRenderer")
            val c = when {
                vm != null -> fromViewModel(vm, thread, entities)
                classic != null -> fromClassic(classic, thread)
                else -> null
            } ?: continue
            if (seen.add(c.id)) out.add(c)
        }
        return out
    }

    private fun repliesToken(thread: JsonObject?): String? =
        thread?.obj("replies")?.findFirst("continuationCommand").str("token")
            ?: thread?.obj("replies")?.findFirstStr("token")

    private fun deleteActionIn(vararg e: JsonElement?): String? {
        for (x in e) {
            val del = x.findWhere { it.obj("icon").str("iconType") == "DELETE" || it.str("iconName") == "DELETE" }
            for (d in del) d.findFirst("performCommentActionEndpoint").str("action")?.let { return it }
        }
        return null
    }

    private fun fromViewModel(vm: JsonObject, thread: JsonObject?, entities: Map<String, JsonObject>): Comment? {
        val related = vm.values.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(entities::get) }
        val entity = related.firstNotNullOfOrNull { it.obj("commentEntityPayload") } ?: return null
        val props = entity.obj("properties")
        val author = entity.obj("author")
        val toolbar = entity.obj("toolbar")
        val id = props.str("commentId") ?: vm.str("commentId") ?: return null
        val delete = deleteActionIn(*related.toTypedArray(), thread)
        return Comment(
            id = id,
            author = author.str("displayName").orEmpty(),
            authorAvatar = author.str("avatarThumbnailUrl"),
            authorChannelId = author.str("channelId"),
            text = props.obj("content").text().orEmpty(),
            likes = toolbar.str("likeCountNotliked").orEmpty().trim(),
            published = props.str("publishedTime").orEmpty(),
            replyCount = toolbar.str("replyCount")?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
            isMine = author.bool("isCurrentUser") == true || delete != null,
            repliesPage = repliesToken(thread),
            replyParams = related.firstNotNullOfOrNull { it.findFirstStr("createReplyParams") } ?: thread.findFirstStr("createReplyParams"),
            deleteAction = delete,
        )
    }

    private fun fromClassic(c: JsonObject, thread: JsonObject?): Comment? {
        val id = c.str("commentId") ?: return null
        val delete = deleteActionIn(c.obj("actionMenu"))
        return Comment(
            id = id,
            author = c.obj("authorText").text().orEmpty(),
            authorAvatar = c.obj("authorThumbnail").thumb(),
            authorChannelId = c.at("authorEndpoint.browseEndpoint.browseId")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            text = c.obj("contentText").text().orEmpty(),
            likes = c.obj("voteCount").text().orEmpty(),
            published = c.obj("publishedTimeText").text().orEmpty(),
            replyCount = c.str("replyCount")?.toIntOrNull() ?: 0,
            isMine = delete != null,
            repliesPage = repliesToken(thread),
            replyParams = c.findFirstStr("createReplyParams"),
            deleteAction = delete,
        )
    }
}
