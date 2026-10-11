package fr.appartfinder.freetube.api

import fr.appartfinder.freetube.settings.DeviceToken
import fr.appartfinder.freetube.settings.ServerConfig
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthInterceptorTest {
    private val api = MockWebServer().apply { start() }
    private val s3 = MockWebServer().apply { start() }
    private val token = DeviceToken.parse("a".repeat(43))!!

    @After
    fun stop() {
        api.close()
        s3.close()
    }

    private fun client(server: ServerConfig?) =
        OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(authorize(chain.request(), server)) }.build()

    @Test
    fun tokenOnlyForTheServerHost() {
        val config = ServerConfig(api.url("/"), token)
        assertEquals("Bearer ${token.value}", authorize(Request.Builder().url(api.url("/api/x")).build(), config).header("Authorization"))
        assertNull(authorize(Request.Builder().url("https://i.ytimg.com/vi/x/hqdefault.jpg").build(), config).header("Authorization"))
        assertNull(authorize(Request.Builder().url(api.url("/api/x")).build(), null).header("Authorization"))
    }

    /** /stream answers 302 to a pre-signed S3 URL on another host: the token must not follow (brief §8). */
    @Test
    fun streamRedirectToS3DoesNotCarryTheToken() {
        // Same machine, different host name: "localhost" for the API, "127.0.0.1" for "S3".
        val s3Url = s3.url("/audio/x.m4a?X-Amz-Signature=sig").newBuilder().host("127.0.0.1").build()
        api.enqueue(MockResponse.Builder().code(302).addHeader("Location", s3Url.toString()).build())
        s3.enqueue(MockResponse.Builder().code(200).body("audio").build())
        val apiUrl = api.url("/api/tracks/x/stream").newBuilder().host("localhost").build()
        val config = ServerConfig(apiUrl.newBuilder().encodedPath("/").query(null).build(), token)

        client(config).newCall(Request.Builder().url(apiUrl).build()).execute().use { assertEquals("audio", it.body.string()) }

        assertEquals("Bearer ${token.value}", api.takeRequest().headers["Authorization"])
        assertNull(s3.takeRequest().headers["Authorization"])
    }
}
