package dev.kestrel.app.data.newpipe

import dev.kestrel.app.data.model.Channel
import dev.kestrel.app.data.model.Comment
import dev.kestrel.app.data.model.Item
import dev.kestrel.app.data.model.Page
import dev.kestrel.app.data.model.Playlist
import dev.kestrel.app.data.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.Page as NpPage

/** Anonymous YouTube access through NewPipeExtractor. All calls run on IO. */
object NpRepo {
    private val yt get() = ServiceList.YouTube

    fun init(region: String, language: String) {
        NewPipe.init(NpDownloader, Localization(language), ContentCountry(region))
    }

    fun setRegion(region: String, language: String) {
        NewPipe.setupLocalization(Localization(language), ContentCountry(region))
    }

    // ---------- mapping ----------
    fun bestImage(images: List<Image>?): String? =
        images?.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 1 }?.url

    fun smallImage(images: List<Image>?): String? =
        images?.filter { it.height in 1..300 }?.maxByOrNull { it.height }?.url ?: bestImage(images)

    fun videoId(url: String): String? =
        Regex("(?:v=|/shorts/|youtu\\.be/|/live/|/embed/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    fun channelId(url: String?): String? =
        url?.let { Regex("/channel/(UC[A-Za-z0-9_-]{22})").find(it)?.groupValues?.get(1) }

    fun playlistId(url: String): String? =
        Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)

    fun map(item: InfoItem): Item? = when (item) {
        is StreamInfoItem -> videoId(item.url)?.let { id ->
            Video(
                id = id,
                title = item.name,
                channelName = item.uploaderName.orEmpty(),
                channelId = channelId(item.uploaderUrl),
                thumbnail = bestImage(item.thumbnails) ?: dev.kestrel.app.data.model.thumbFor(id),
                durationSec = item.duration,
                views = item.viewCount,
                uploaded = item.textualUploadDate,
                isLive = item.streamType == StreamType.LIVE_STREAM,
                isShort = item.isShortFormContent || item.url.contains("/shorts/"),
                uploadedAt = runCatching { item.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull() ?: 0,
            )
        }
        is ChannelInfoItem -> channelId(item.url)?.let {
            Channel(it, item.name, smallImage(item.thumbnails), item.subscriberCount)
        }
        is PlaylistInfoItem -> playlistId(item.url)?.let {
            Playlist(it, item.name, bestImage(item.thumbnails), item.streamCount, item.uploaderName.orEmpty())
        }
        else -> null
    }

    private fun mapAll(items: List<InfoItem>) = items.mapNotNull { map(it) }

    // ---------- search ----------
    suspend fun suggestions(query: String): List<String> = io {
        if (query.isBlank()) emptyList() else yt.suggestionExtractor.suggestionList(query)
    }

    data class SearchState(val handler: org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler, val page: NpPage?)

    suspend fun search(query: String, filter: String? = null): Page<Item> = io {
        val filters = if (filter == null) emptyList() else listOf(filter)
        val handler = yt.searchQHFactory.fromQuery(query, filters, "")
        val info = SearchInfo.getInfo(yt, handler)
        Page(mapAll(info.relatedItems), info.nextPage?.let { SearchState(handler, it) })
    }

    suspend fun searchMore(state: SearchState): Page<Item> = io {
        val more = SearchInfo.getMoreItems(yt, state.handler, state.page)
        Page(mapAll(more.items), more.nextPage?.let { SearchState(state.handler, it) })
    }

    // ---------- trending / kiosks ----------
    fun kioskIds(): List<String> = yt.kioskList.availableKiosks.toList()

    data class KioskState(val url: String, val page: NpPage?)

    suspend fun kiosk(id: String? = null): Page<Item> = io {
        val extractor = if (id == null) yt.kioskList.defaultKioskExtractor
        else yt.kioskList.getExtractorById(id, null)
        extractor.fetchPage()
        val info = KioskInfo.getInfo(extractor)
        Page(mapAll(info.relatedItems), info.nextPage?.let { KioskState(info.url, it) })
    }

    suspend fun kioskMore(state: KioskState): Page<Item> = io {
        val more = KioskInfo.getMoreItems(yt, state.url, state.page)
        Page(mapAll(more.items), more.nextPage?.let { KioskState(state.url, it) })
    }

    // ---------- streams ----------
    suspend fun stream(videoId: String): StreamInfo = io {
        StreamInfo.getInfo(yt, "https://www.youtube.com/watch?v=$videoId")
    }

    fun related(info: StreamInfo): List<Item> = mapAll(info.relatedItems)

    // ---------- channels ----------
    data class ChannelData(
        val channel: Channel,
        val banner: String?,
        val description: String,
        val tabs: Map<String, ListLinkHandler>,
    )

    suspend fun channel(id: String): ChannelData = io {
        val info = ChannelInfo.getInfo(yt, "https://www.youtube.com/channel/$id")
        val tabs = info.tabs.associateBy { it.contentFilters.firstOrNull() ?: "" }
        ChannelData(
            Channel(id, info.name, smallImage(info.avatars), info.subscriberCount),
            bestImage(info.banners),
            info.description.orEmpty(),
            tabs,
        )
    }

    data class TabState(val handler: ListLinkHandler, val page: NpPage?)

    suspend fun channelTab(handler: ListLinkHandler): Page<Item> = io {
        val info = ChannelTabInfo.getInfo(yt, handler)
        Page(mapAll(info.relatedItems), info.nextPage?.let { TabState(handler, it) })
    }

    suspend fun channelTabMore(state: TabState): Page<Item> = io {
        val more = ChannelTabInfo.getMoreItems(yt, state.handler, state.page!!)
        Page(mapAll(more.items), more.nextPage?.let { TabState(state.handler, it) })
    }

    /** Latest uploads of a channel, used for the local subscriptions feed. */
    suspend fun latestUploads(id: String): List<Video> = io {
        val data = channel(id)
        val tab = data.tabs[ChannelTabs.VIDEOS] ?: return@io emptyList()
        channelTab(tab).items.filterIsInstance<Video>().map {
            it.copy(channelName = it.channelName.ifBlank { data.channel.name }, channelId = it.channelId ?: id)
        }
    }

    // ---------- playlists ----------
    data class PlaylistState(val url: String, val page: NpPage?)

    suspend fun playlist(id: String): Pair<Playlist, Page<Item>> = io {
        val info = PlaylistInfo.getInfo(yt, "https://www.youtube.com/playlist?list=$id")
        val p = Playlist(id, info.name, bestImage(info.thumbnails), info.streamCount, info.uploaderName.orEmpty())
        p to Page(mapAll(info.relatedItems), info.nextPage?.let { PlaylistState(info.url, it) })
    }

    suspend fun playlistMore(state: PlaylistState): Page<Item> = io {
        val more = PlaylistInfo.getMoreItems(yt, state.url, state.page)
        Page(mapAll(more.items), more.nextPage?.let { PlaylistState(state.url, it) })
    }

    // ---------- comments ----------
    data class CommentState(val url: String, val page: NpPage?)

    private fun mapComment(c: CommentsInfoItem, url: String) = Comment(
        id = c.commentId ?: c.url ?: "",
        author = c.uploaderName.orEmpty(),
        authorAvatar = smallImage(c.uploaderAvatars),
        authorChannelId = channelId(c.uploaderUrl),
        text = c.commentText?.content.orEmpty(),
        likes = c.textualLikeCount.orEmpty(),
        published = c.textualUploadDate.orEmpty(),
        replyCount = c.replyCount.coerceAtLeast(0),
        repliesPage = c.replies?.let { CommentState(url, it) },
    )

    suspend fun comments(videoId: String): Page<Comment> = io {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val info = CommentsInfo.getInfo(yt, url)
        if (info.isCommentsDisabled) return@io Page(emptyList())
        Page(info.relatedItems.map { mapComment(it, url) }, info.nextPage?.let { CommentState(url, it) })
    }

    suspend fun commentsMore(state: CommentState): Page<Comment> = io {
        val more = CommentsInfo.getMoreItems(yt, state.url, state.page)
        Page(more.items.map { mapComment(it, state.url) }, more.nextPage?.let { CommentState(state.url, it) })
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
