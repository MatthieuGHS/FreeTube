package fr.appartfinder.freetube.download

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PutPresignedTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    @Test
    fun sendsExactlyTheSignedContentTypeAndNoAuthorization() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        val file = tmp.newFile("a.m4a").apply { writeText("audio") }
        putPresigned(OkHttpClient(), server.url("/audio/x.m4a?X-Amz-Signature=s").toString(), file, "audio/mp4")
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("audio/mp4", request.headers["Content-Type"])
        assertNull(request.headers["Authorization"])
        assertEquals("audio", request.body?.utf8())
    }

    @Test
    fun s3RefusalIsAStorageError() {
        server.enqueue(MockResponse.Builder().code(403).build())
        val file = tmp.newFile("t.jpg")
        val error = assertThrows(PipelineException::class.java) {
            runBlocking { putPresigned(OkHttpClient(), server.url("/thumbs/x.jpg").toString(), file, "image/jpeg") }
        }
        assertEquals(PipelineError.STORAGE, error.error)
    }
}
