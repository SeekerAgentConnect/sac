package io.github.brrenat.seekervault.confirmations

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How far back an endpoint's own ledger reaches, read from a node that keeps pruning it: the slot
 * it names as its lowest can be gone a moment later, which must not stop every proof of absence.
 */
@RunWith(AndroidJUnit4::class)
class HttpChainReaderTest {
    private val server = MockWebServer()
    private val asked = CopyOnWriteArrayList<Long>()

    /** Block times by slot; a slot missing here answers the "cleaned up" error. */
    @Volatile private var times: Map<Long, Long> = emptyMap()

    private val reader by lazy {
        HttpChainReader(OkHttpClient(), "http://127.0.0.1:${server.port}")
    }

    @Before
    fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val call = JSONObject(checkNotNull(request.body).utf8())
                    val answer = JSONObject().put("jsonrpc", "2.0").put("id", 1)
                    when (call.getString("method")) {
                        "minimumLedgerSlot" -> answer.put("result", LOWEST)
                        "getBlockTime" -> {
                            val slot = call.getJSONArray("params").getLong(0)
                            asked += slot
                            val time = times[slot]
                            if (time != null) answer.put("result", time)
                            else answer.put("error", JSONObject().put("code", -32001))
                        }
                        else -> answer.put("error", JSONObject().put("code", -32601))
                    }
                    return MockResponse.Builder()
                        .code(200)
                        .setHeader("content-type", "application/json")
                        .body(answer.toString())
                        .build()
                }
            }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun theLowestSlotIsAskedFirst() = runBlocking {
        times = mapOf(LOWEST to 1_000L)
        assertEquals(Instant.ofEpochSecond(1_000), reader.retainedSince())
        assertEquals(listOf(LOWEST), asked)
    }

    @Test
    fun aLowestSlotPrunedSinceIsReplacedByALaterOne() = runBlocking {
        // Devnet answers "Block … cleaned up" for the slot it just named as its lowest.
        times = mapOf(LOWEST + 100 to 1_040L)
        assertEquals(Instant.ofEpochSecond(1_040), reader.retainedSince())
        assertEquals(listOf(LOWEST, LOWEST + 100), asked)
    }

    @Test
    fun aLedgerWithNoReadableTimeIsAnUnusableAnswerNotACoverageClaim() {
        times = emptyMap()
        val failure =
            assertThrows(SolanaException::class.java) { runBlocking { reader.retainedSince() } }
        assertEquals(SolanaProblem.Unusable, failure.problem)
        assertEquals(4, asked.size)
    }

    private companion object {
        const val LOWEST = 508_966_705L
    }
}
