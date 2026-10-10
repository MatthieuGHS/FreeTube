package fr.appartfinder.freetube.download

import fr.appartfinder.freetube.library.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadOutputTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun out(dir: File, meta: String) = "[download]  42.0% of 3.1MiB\n${TITLE_PREFIX}Song\n$META_PREFIX$meta\n"

    private fun audio(dir: File, name: String = "dQw4w9WgXcQ.m4a") = File(dir, name).apply { writeText("a") }

    private fun meta(file: File, title: String = "Song", channel: String? = "Artist", duration: String = "213.4", ext: String = "m4a") =
        """{"title":"$title","channel":${channel?.let { "\"$it\"" } ?: "null"},"uploader":"Up","duration":$duration,"ext":"$ext","filepath":"${file.path}"}"""

    @Test
    fun validOutput() {
        val dir = tmp.newFolder("audio")
        val file = audio(dir)
        assertEquals(DownloadedAudio(TrackMeta("Song", "Artist", 213, AudioFormat.M4A), file), parseDownloadOutput(out(dir, meta(file)), dir))
    }

    @Test
    fun metadataIsNormalisedToServerBounds() {
        val dir = tmp.newFolder("audio")
        val file = audio(dir)
        assertEquals("Up", parseDownloadOutput(out(dir, meta(file, channel = null)), dir)!!.meta.artist)
        assertEquals("Daft Punk", parseDownloadOutput(out(dir, meta(file, channel = "Daft Punk - Topic")), dir)!!.meta.artist)
        val blank = parseDownloadOutput(out(dir, meta(file, title = "   ", channel = " ").replace("\"Up\"", "\"\"")), dir)!!.meta
        assertEquals("Sans titre" to "Artiste inconnu", blank.title to blank.artist)
        assertEquals(300, parseDownloadOutput(out(dir, meta(file, title = "x".repeat(400))), dir)!!.meta.title.length)
        assertEquals(AudioFormat.WEBM, parseDownloadOutput(out(dir, meta(audio(dir, "dQw4w9WgXcQ.webm"), ext = "webm")), dir)!!.meta.format)
    }

    @Test
    fun unusableOutputs() {
        val dir = tmp.newFolder("audio")
        val file = audio(dir)
        assertNull(parseDownloadOutput(out(dir, meta(file, ext = "mp4")), dir))
        assertNull(parseDownloadOutput(out(dir, meta(file, duration = "null")), dir))
        assertNull(parseDownloadOutput(out(dir, meta(file, duration = "90000")), dir))
        assertNull(parseDownloadOutput(out(dir, meta(File(tmp.newFolder("elsewhere"), "x.m4a").apply { writeText("a") })), dir))
        assertNull(parseDownloadOutput(out(dir, meta(File(dir, "dQw4w9WgXcQ.m4a.part"))), dir))
        assertNull(parseDownloadOutput(out(dir, meta(File(dir, "missing.m4a"))), dir))
        assertNull(parseDownloadOutput("no meta line\n", dir))
        assertNull(parseDownloadOutput("${META_PREFIX}{not json", dir))
    }
}
