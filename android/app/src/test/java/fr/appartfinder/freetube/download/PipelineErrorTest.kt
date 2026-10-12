package fr.appartfinder.freetube.download

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineErrorTest {
    @Test
    fun ytDlpMessages() = listOf(
        "ERROR: [youtube] xxxxxxxxxxx: Video unavailable. This video has been removed by the uploader" to PipelineError.VIDEO_UNAVAILABLE,
        "ERROR: [youtube] xxxxxxxxxxx: Private video. Sign in if you've been granted access" to PipelineError.VIDEO_UNAVAILABLE,
        "ERROR: [youtube] xxxxxxxxxxx: The uploader has not made this video available in your country" to PipelineError.GEO_BLOCKED,
        "ERROR: [youtube] xxxxxxxxxxx: Sign in to confirm your age. This video may be inappropriate" to PipelineError.LOGIN_REQUIRED,
        "ERROR: [youtube] xxxxxxxxxxx: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication." to PipelineError.BOT_CHECK,
        "ERROR: [youtube] xxxxxxxxxxx: Join this channel to get access to members-only content" to PipelineError.LOGIN_REQUIRED,
        "ERROR: unable to download video data: HTTP Error 403: Forbidden" to PipelineError.ENGINE_OUTDATED,
        "ERROR: [youtube] x: Unable to download API page: <urlopen error [Errno 7] No address associated with hostname>" to PipelineError.NETWORK,
        "ERROR: The read operation timed out" to PipelineError.NETWORK,
        "ERROR: unable to write data: [Errno 28] No space left on device" to PipelineError.NO_SPACE,
        "ERROR: [youtube] xxxxxxxxxxx: This live event will begin in 3 hours." to PipelineError.UNSUPPORTED,
        "ERROR: something new and weird" to PipelineError.UNKNOWN,
    ).forEach { (stderr, expected) -> assertEquals(stderr, expected, classifyYtDlpError(stderr)) }

    @Test
    fun httpStatuses() {
        assertEquals(PipelineError.UNAUTHORIZED, httpError(401))
        listOf(404, 409, 429, 500, 503).forEach { assertEquals("$it", PipelineError.SERVER, httpError(it)) }
        assertEquals(PipelineError.UNKNOWN, httpError(422))
    }
}
