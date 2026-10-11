package fr.appartfinder.freetube.api

import fr.appartfinder.freetube.library.AudioFormat
import fr.appartfinder.freetube.sync.OpType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hand-written DTOs vs the committed server contract (server/openapi.json): fails when they drift. */
class ContractTest {
    private val schemas = Json.parseToJsonElement(File("../../server/openapi.json").readText())
        .jsonObject.getValue("components").jsonObject.getValue("schemas").jsonObject

    private fun schema(name: String) = schemas.getValue(name).jsonObject
    private fun properties(name: String) = schema(name).getValue("properties").jsonObject.keys
    private fun required(name: String) = schema(name)["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private val json = Json { classDiscriminator = "status" }

    @Test
    fun uploadRequestMatchesUploadIn() {
        val sent = json.encodeToJsonElement(UploadRequest.serializer(), UploadRequest("t", "a", 1, "m4a", "audio/mp4")).jsonObject.keys
        assertEquals(properties("UploadIn"), sent)
        assertEquals(required("UploadIn"), sent)
    }

    @Test
    fun audioWhitelistMatchesServer() {
        val ext = schema("UploadIn").getValue("properties").jsonObject.getValue("audio_ext").jsonObject
        assertEquals(ext.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet(), AudioFormat.entries.map { it.ext }.toSet())
    }

    @Test
    fun uploadResponsesMatchServer() {
        assertEquals(setOf("status", "audio_put_url", "thumb_put_url"), properties("UploadPending"))
        assertEquals(setOf("status"), properties("UploadReady"))
        val pending = json.decodeFromString(UploadResponse.serializer(), """{"status":"pending_upload","audio_put_url":"a","thumb_put_url":"t"}""")
        assertEquals(UploadResponse.Pending("a", "t"), pending)
        assertEquals(UploadResponse.Ready, json.decodeFromString(UploadResponse.serializer(), """{"status":"ready"}"""))
        assertEquals(setOf("kind"), properties("Me"))
    }

    /** Sent bodies: exactly the schema's properties, and every required one present. */
    private fun <T> assertSent(schemaName: String, serializer: KSerializer<T>, value: T) {
        val sent = json.encodeToJsonElement(serializer, value).jsonObject.keys
        assertEquals(properties(schemaName), sent)
        assertTrue(sent.containsAll(required(schemaName)))
    }

    /** Received bodies: every field the DTO reads is a required server property (never silently absent). */
    private fun assertReads(schemaName: String, serializer: KSerializer<*>) {
        val read = serializer.descriptor.elementNames.toSet()
        assertTrue("$read not all required in $schemaName", required(schemaName).containsAll(read))
    }

    @Test
    fun opPayloadsMatchServer() {
        assertSent("UpsertPlaylistPayload", UpsertPlaylistPayload.serializer(), UpsertPlaylistPayload("i", "n", null, listOf("v")))
        assertSent("DeletePlaylistPayload", DeletePlaylistPayload.serializer(), DeletePlaylistPayload("i"))
        assertSent("UpdateTrackPayload", UpdateTrackPayload.serializer(), UpdateTrackPayload("v", "t", "a"))
        assertSent("DeleteTrackPayload", DeleteTrackPayload.serializer(), DeleteTrackPayload("v"))
    }

    @Test
    fun opsInMatchesServer() {
        val mapping = schema("OpsIn").getValue("properties").jsonObject.getValue("ops").jsonObject.getValue("items").jsonObject
            .getValue("discriminator").jsonObject.getValue("mapping").jsonObject.keys
        assertEquals(mapping, OpType.entries.map { it.wire }.toSet())
        assertSent("OpsIn", OpsIn.serializer(), OpsIn(emptyList(), null))
        for (name in listOf("UpsertPlaylistOp", "DeletePlaylistOp", "UpdateTrackOp", "DeleteTrackOp")) {
            assertSent(name, OpIn.serializer(), OpIn("o", "t", JsonNull))
        }
        assertEquals(setOf("acked"), properties("OpsOut"))
        assertEquals(OpsOut(2), json.decodeFromString(OpsOut.serializer(), """{"acked":2}"""))
    }

    @Test
    fun syncPullMatchesServer() {
        assertReads("SyncPull", SyncPull.serializer())
        assertReads("Track", TrackDto.serializer())
        assertReads("Playlist", PlaylistDto.serializer())
        val body = """{"cursor":7,"tracks":[{"video_id":"aaaaaaaaaaa","title":"t","artist":"a","duration_s":3,"audio_ext":"m4a",
            "audio_mime":"audio/mp4","status":"ready","rev":5,"deleted_at":null,"created_at":"2026-10-10T12:00:00Z"}],
            "playlists":[{"id":"i","name":"n","source_youtube_id":null,"items":["aaaaaaaaaaa"],"rev":7,
            "deleted_at":"2026-10-10T12:00:00Z","created_at":"2026-10-10T12:00:00Z"}]}"""
        val pull = Json { ignoreUnknownKeys = true; classDiscriminator = "status" }.decodeFromString(SyncPull.serializer(), body)
        assertEquals(TrackDto("aaaaaaaaaaa", "t", "a", 3, "m4a", null, "2026-10-10T12:00:00Z"), pull.tracks.single())
        assertEquals(PlaylistDto("i", "n", null, listOf("aaaaaaaaaaa"), "2026-10-10T12:00:00Z"), pull.playlists.single())
        assertEquals(7L, pull.cursor)
    }
}
