package fr.appartfinder.freetube.youtube

import java.net.URI
import java.net.URLDecoder

/** A YouTube video id, validated once: `[A-Za-z0-9_-]{11}` (brief §13). */
@JvmInline
value class VideoId private constructor(val value: String) {
    override fun toString() = value

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9_-]{11}")

        fun parse(raw: String?): VideoId? = raw?.takeIf(PATTERN::matches)?.let(::VideoId)
    }
}

/** A public YouTube playlist id. Private lists (LL, WL, LM) never parse: brief §3 excludes them. */
@JvmInline
value class PlaylistId private constructor(val value: String) {
    override fun toString() = value

    /** YouTube Mix ("RD…"): generated per user, not importable as a frozen copy. */
    val isMix: Boolean get() = value.startsWith("RD")

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9_-]{2,64}") // same bound as the server's YoutubePlaylistId
        private val PRIVATE = setOf("LL", "WL", "LM")

        fun parse(raw: String?): PlaylistId? =
            raw?.takeIf { PATTERN.matches(it) && it !in PRIVATE }?.let(::PlaylistId)
    }
}

/** What the download button offers for a URL (brief §8 table). `null` = button hidden. */
sealed interface YoutubeTarget {
    /** `watch?v=X`, `youtu.be/X`, or `watch?v=X&list=RD…` (Mix: track only). */
    data class Track(val videoId: VideoId) : YoutubeTarget

    /** `playlist?list=Y`. */
    data class Playlist(val listId: PlaylistId) : YoutubeTarget

    /** `watch?v=X&list=Y`, Y not a Mix. */
    data class TrackInPlaylist(val videoId: VideoId, val listId: PlaylistId) : YoutubeTarget
}

private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

/**
 * Pure (plain JVM, unit-tested). Used by the WebView button and, through [classifySharedText], by the
 * share intent. Shorts, lives, channels, search… hide the button.
 */
fun classifyYoutubeUrl(url: String): YoutubeTarget? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
    val host = uri.host?.lowercase() ?: return null // exact match: "youtube.com.evil.com" is rejected
    val segments = uri.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
    val params = queryParams(uri.rawQuery)
    val list = PlaylistId.parse(params["list"])
    val video = when {
        host == "youtu.be" -> VideoId.parse(segments.singleOrNull())
        host !in YOUTUBE_HOSTS -> return null
        segments == listOf("watch") -> VideoId.parse(params["v"])
        segments == listOf("playlist") -> return list?.takeUnless { it.isMix }?.let(YoutubeTarget::Playlist)
        else -> null
    } ?: return null
    return if (list == null || list.isMix) YoutubeTarget.Track(video) else YoutubeTarget.TrackInPlaylist(video, list)
}

/** Share text is free-form ("Regarde ça https://youtu.be/…"): the first token that classifies wins. */
fun classifySharedText(text: String): YoutubeTarget? =
    text.split(Regex("\\s+")).firstNotNullOfOrNull(::classifyYoutubeUrl)

/** First occurrence of each key wins, so "v=A&v=B" is deterministic. */
private fun queryParams(rawQuery: String?): Map<String, String> =
    rawQuery.orEmpty()
        .split('&')
        .filter(String::isNotEmpty)
        .map { it.split('=', limit = 2) }
        .mapNotNull { parts ->
            runCatching {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }.getOrNull()
        }
        .distinctBy { it.first }
        .toMap()
