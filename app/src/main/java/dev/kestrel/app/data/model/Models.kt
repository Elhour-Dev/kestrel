package dev.kestrel.app.data.model

import kotlinx.serialization.Serializable

/** Anything that can appear in a list. */
sealed interface Item {
    val key: String
}

@Serializable
data class Video(
    val id: String,
    val title: String,
    val channelName: String = "",
    val channelId: String? = null,
    val thumbnail: String = thumbFor(id),
    val durationSec: Long = -1,
    val views: Long = -1,
    val uploaded: String? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
) : Item {
    override val key get() = "v:$id"
    val url get() = "https://www.youtube.com/watch?v=$id"
}

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val subscribers: Long = -1,
) : Item {
    override val key get() = "c:$id"
    val url get() = "https://www.youtube.com/channel/$id"
}

@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val thumbnail: String? = null,
    val count: Long = -1,
    val owner: String = "",
) : Item {
    override val key get() = "p:$id"
    val url get() = "https://www.youtube.com/playlist?list=$id"
}

/** A page of items with an opaque token for the next page. */
data class Page<T>(val items: List<T>, val next: Any? = null)

data class Comment(
    val id: String,
    val author: String,
    val authorAvatar: String?,
    val authorChannelId: String?,
    val text: String,
    val likes: String,
    val published: String,
    val replyCount: Int,
    val isMine: Boolean = false,
    val repliesPage: Any? = null,
    val replyParams: String? = null,
    val deleteAction: String? = null,
)

data class SponsorSegment(val startMs: Long, val endMs: Long, val category: String, val uuid: String)

fun thumbFor(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

fun formatDuration(sec: Long): String {
    if (sec < 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatCount(n: Long): String = when {
    n < 0 -> ""
    n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> n.toString()
}.replace(".0", "")
