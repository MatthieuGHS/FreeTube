package fr.appartfinder.freetube.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeUrlTest {
    private val v = VideoId.parse("dQw4w9WgXcQ")!!
    private val p = PlaylistId.parse("PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG")!!
    private val album = PlaylistId.parse("OLAK5uy_kV1aBcDeFgHiJkLmNoPqRsTuVwXyZ01")!!

    private fun check(cases: List<Pair<String, YoutubeTarget?>>) =
        cases.forEach { (url, expected) -> assertEquals(url, expected, classifyYoutubeUrl(url)) }

    @Test
    fun tracks() = check(
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123",
            "https://youtu.be/dQw4w9WgXcQ?t=42",
            "https://www.youtube.com/watch?t=42&v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&si=xyz&pp=ygU",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=30",
            "https://WWW.YouTube.com/watch?v=dQw4w9WgXcQ",
            "  https://youtu.be/dQw4w9WgXcQ  ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=aaaaaaaaaaa",
        ).map { it to YoutubeTarget.Track(v) },
    )

    @Test
    fun mixesAndPrivateListsGiveTheTrackOnly() = check(
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ" to YoutubeTarget.Track(v),
            "https://m.youtube.com/watch?list=RDMMdQw4w9WgXcQ&v=dQw4w9WgXcQ&start_radio=1" to YoutubeTarget.Track(v),
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ" to YoutubeTarget.Track(v),
            "https://www.youtube.com/playlist?list=RDdQw4w9WgXcQ" to null,
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=LL" to YoutubeTarget.Track(v),
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=WL&index=3" to YoutubeTarget.Track(v),
            "https://www.youtube.com/playlist?list=WL" to null,
            "https://music.youtube.com/playlist?list=LM" to null,
        ),
    )

    @Test
    fun trackInPlaylistAndPlaylists() = check(
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG" to YoutubeTarget.TrackInPlaylist(v, p),
            "https://m.youtube.com/watch?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&index=4&v=dQw4w9WgXcQ&pp=x" to YoutubeTarget.TrackInPlaylist(v, p),
            "https://youtu.be/dQw4w9WgXcQ?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&si=q" to YoutubeTarget.TrackInPlaylist(v, p),
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=OLAK5uy_kV1aBcDeFgHiJkLmNoPqRsTuVwXyZ01" to YoutubeTarget.TrackInPlaylist(v, album),
            "https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG" to YoutubeTarget.Playlist(p),
            "https://m.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG&si=abc" to YoutubeTarget.Playlist(p),
            "https://music.youtube.com/playlist?list=OLAK5uy_kV1aBcDeFgHiJkLmNoPqRsTuVwXyZ01" to YoutubeTarget.Playlist(album),
            "https://www.youtube.com/playlist?si=abc&list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG" to YoutubeTarget.Playlist(p),
        ),
    )

    @Test
    fun hiddenPages() = check(
        listOf(
            "https://m.youtube.com/",
            "https://m.youtube.com/results?search_query=daft+punk",
            "https://www.youtube.com/@DaftPunk",
            "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ", // shorts are not supported (decided 2026-10-10)
            "https://m.youtube.com/shorts/dQw4w9WgXcQ?feature=share",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=",
            "https://www.youtube.com/watch?v=dQw4w9WgXc",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQQ",
            "https://www.youtube.com/watch?v=dQw4w9WgX%21Q",
            "https://youtu.be/",
            "https://youtu.be/dQw4w9WgXcQ/extra",
            "https://www.youtube.com/playlist",
            "https://www.youtube.com/playlist?list=",
            "https://www.youtube.com/playlist?list=PL<script>",
        ).map { it to null },
    )

    @Test
    fun hostileOrForeignInputs() = check(
        listOf(
            "https://youtube.com.evil.com/watch?v=dQw4w9WgXcQ",
            "https://evil.com/watch?v=dQw4w9WgXcQ&ref=youtube.com",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com@evil.com/watch?v=dQw4w9WgXcQ",
            "javascript:alert(1)",
            "intent://www.youtube.com/watch?v=dQw4w9WgXcQ#Intent;scheme=https;end",
            "ftp://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "www.youtube.com/watch?v=dQw4w9WgXcQ",
            "",
            "not a url at all",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        ).map { it to null },
    )

    @Test
    fun sharedText() {
        val cases = listOf(
            "https://youtu.be/dQw4w9WgXcQ?si=abc" to YoutubeTarget.Track(v),
            "Regarde ça https://youtu.be/dQw4w9WgXcQ !" to YoutubeTarget.Track(v),
            "Rick Astley - Never Gonna Give You Up\nhttps://www.youtube.com/watch?v=dQw4w9WgXcQ" to YoutubeTarget.Track(v),
            "https://example.com/x https://youtu.be/dQw4w9WgXcQ" to YoutubeTarget.Track(v),
            "https://www.youtube.com/playlist?list=PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG" to YoutubeTarget.Playlist(p),
            "Bonjour" to null,
            "" to null,
        )
        cases.forEach { (text, expected) -> assertEquals(text, expected, classifySharedText(text)) }
    }

    @Test
    fun idParsing() {
        listOf("aaaaaaaaaa-", "___________", "dQw4w9WgXcQ").forEach { assertEquals(it, VideoId.parse(it)?.value) }
        listOf(null, "aaaaaaaaaa", "aaaaaaaaaaaa", "aaaaa aaaaa", "aaaaa+aaaaa", "aaaaa/aaaaa").forEach { assertNull(VideoId.parse(it)) }
        listOf("LL", "WL", "LM", "a", "x".repeat(65)).forEach { assertNull(it, PlaylistId.parse(it)) }
        assertTrue(PlaylistId.parse("RDdQw4w9WgXcQ")!!.isMix)
        assertFalse(p.isMix)
        assertFalse(album.isMix)
    }
}
