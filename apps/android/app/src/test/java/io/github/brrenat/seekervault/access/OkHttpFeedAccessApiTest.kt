package io.github.brrenat.seekervault.access

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The authentication endpoint's client reads each answer to its end on OkHttp's thread (SEE-156).
 * OkHttp hands a response over as soon as its headers arrive; a body read after that would run on
 * the caller's thread, which for a screen's button is the main one.
 */
@RunWith(AndroidJUnit4::class)
class OkHttpFeedAccessApiTest {
    private val server = MockWebServer()
    private val api = OkHttpFeedAccessApi(OkHttpClient())
    private val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun stop() {
        server.close()
        caller.close()
    }

    private val origin
        get() = server.url("/").toString()

    @Test
    fun aBodyThatArrivesAfterItsHeadersIsNotReadOnTheCallersThread() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(STATUS)
                .bodyDelay(BODY_DELAY_MS, TimeUnit.MILLISECONDS)
                .build()
        )
        withTimeout(10_000) {
            withContext(caller) {
                val started = System.nanoTime()
                // Something else the caller's thread has to do while the body is on its way: a
                // blocking read on that thread would hold it up until the body arrived.
                val other = async {
                    delay(OTHER_WORK_MS)
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                }
                val answer = api.status(origin, REQUEST, 0L, ByteArray(64))
                assertEquals("approved", answer.state)
                val ranAfter = other.await()
                assertTrue(
                    "the caller's thread was blocked for ${ranAfter}ms reading the body",
                    ranAfter < BODY_DELAY_MS - 300,
                )
            }
        }
    }

    @Test
    fun aBodyThatBreaksOffIsUnreachableRatherThanAnEscapedFailure() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(STATUS + " ".repeat(64 * 1024))
                .onResponseBody(SocketEffect.ShutdownConnection)
                .build()
        )
        try {
            api.status(origin, REQUEST, 0L, ByteArray(64))
            fail("a truncated answer was read")
        } catch (e: FeedAccessException) {
            assertEquals(FeedAccessException.Kind.Unreachable, e.kind)
        }
    }

    @Test
    fun anAnswerLongerThanAnyTheEndpointGivesIsNotThisApi() = runBlocking {
        server.enqueue(MockResponse.Builder().body(" ".repeat(64 * 1024 + 1) + STATUS).build())
        try {
            api.status(origin, REQUEST, 0L, ByteArray(64))
            fail("an oversized answer was read")
        } catch (e: FeedAccessException) {
            assertEquals(FeedAccessException.Kind.BadResponse, e.kind)
        }
    }

    private companion object {
        const val REQUEST = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
        const val STATUS = """{"request_id":"$REQUEST","state":"approved","connected":false}"""
        const val BODY_DELAY_MS = 1_500L
        const val OTHER_WORK_MS = 200L
    }
}
