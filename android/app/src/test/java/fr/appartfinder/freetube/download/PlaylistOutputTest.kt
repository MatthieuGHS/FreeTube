package fr.appartfinder.freetube.download

import fr.appartfinder.freetube.youtube.VideoId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistOutputTest {
    private fun dump(title: String, vararg ids: String?) =
        """{"_type":"playlist","id":"PLx","title":"$title","entries":[${ids.joinToString(",") { id ->
            if (id == null) """{"title":"no id"}""" else """{"_type":"url","id":"$id","title":"[Private video]"}"""
        }}]}"""

    @Test
    fun idsInOrderDeduplicatedInvalidDropped() {
        val out = "WARNING: something\n" + dump("Mix été", "aaaaaaaaaaa", "bad", "bbbbbbbbbbb", null, "aaaaaaaaaaa", "ccccccccccc") + "\n"
        val parsed = parsePlaylistOutput(out)!!
        assertEquals("Mix été", parsed.title)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").map { VideoId.parse(it)!! }, parsed.videoIds)
    }

    @Test
    fun blankTitleGetsADefault() {
        assertEquals("Playlist YouTube", parsePlaylistOutput(dump("  "))!!.title)
        assertEquals(emptyList<VideoId>(), parsePlaylistOutput(dump("t"))!!.videoIds)
    }

    @Test
    fun notAPlaylistDump() {
        assertNull(parsePlaylistOutput(""))
        assertNull(parsePlaylistOutput("{not json"))
        assertNull(parsePlaylistOutput("""{"id":"aaaaaaaaaaa","title":"a video"}"""))
    }
}
