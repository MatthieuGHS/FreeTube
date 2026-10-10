package fr.appartfinder.freetube.api

import fr.appartfinder.freetube.library.AudioFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
}
