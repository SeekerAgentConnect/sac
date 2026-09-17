package io.github.brrenat.seekervault.solana

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.jupiter.OWNER
import io.github.brrenat.seekervault.jupiter.TABLE_ONE
import io.github.brrenat.seekervault.jupiter.TABLE_TWO
import java.net.InetAddress
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one chain endpoint this app has, over a real HTTP endpoint (SEE-94).
 *
 * Two things are worth proving. What it asks for: one method, the accounts named, and nothing about
 * the owner beyond the addresses it was told to read — a lookup table is public, and asking about
 * one says nothing about who is asking. And what it refuses: a partial answer, a JSON-RPC error
 * arriving with a 200, data that is not base64, and an endpoint that is not configured at all.
 */
@RunWith(AndroidJUnit4::class)
class SolanaAccountsTest {
    private val server = MockWebServer()

    @Before fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After fun stop() = server.close()

    private fun reader(endpoint: String = "http://127.0.0.1:${server.port}") =
        HttpSolanaAccounts(OkHttpClient(), endpoint)

    private fun answer(body: String, status: Int = 200) {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .setHeader("content-type", "application/json")
                .body(body)
                .build()
        )
    }

    private fun read(vararg addresses: String): List<AccountSnapshot?> = runBlocking {
        withTimeout(30_000) { reader().accounts(addresses.toList()) }
    }

    private fun failure(block: suspend SolanaAccounts.() -> Unit): SolanaException = runBlocking {
        try {
            withTimeout(30_000) { reader().block() }
            throw AssertionError("it answered")
        } catch (e: SolanaException) {
            e
        }
    }

    private fun account(owner: String, data: ByteArray): JSONObject =
        JSONObject()
            .put("owner", owner)
            .put(
                "data",
                org.json.JSONArray().put(Base64.getEncoder().encodeToString(data)).put("base64"),
            )
            .put("executable", false)
            .put("lamports", 1)

    private fun result(vararg accounts: Any?): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put(
                "result",
                JSONObject()
                    .put("context", JSONObject().put("slot", 1))
                    .put(
                        "value",
                        org.json.JSONArray().apply {
                            accounts.forEach { put(it ?: JSONObject.NULL) }
                        },
                    ),
            )
            .toString()

    @Test
    fun itAsksForTheAccountsItWasToldToReadAndNothingElse() {
        answer(result(account(ADDRESS_LOOKUP_TABLE_PROGRAM, byteArrayOf(1, 2, 3))))

        val read = read(TABLE_ONE)

        val asked = server.takeRequest()
        assertEquals("POST", asked.method)
        val body = JSONObject(checkNotNull(asked.body).utf8())
        assertEquals("getMultipleAccounts", body.getString("method"))
        val params = body.getJSONArray("params")
        assertEquals(TABLE_ONE, params.getJSONArray(0).getString(0))
        assertEquals(1, params.getJSONArray(0).length())
        assertEquals("base64", params.getJSONObject(1).getString("encoding"))
        // Confirmed, because a table read at the processed commitment could be from a slot that
        // never sticks.
        assertEquals("confirmed", params.getJSONObject(1).getString("commitment"))
        // Nothing about the owner is in it. A lookup table is public and asking about one says
        // nothing about who asked.
        assertFalse(checkNotNull(asked.body).utf8().contains(OWNER))
        assertEquals(ADDRESS_LOOKUP_TABLE_PROGRAM, read.single()?.owner)
        assertArrayEqualsBytes(byteArrayOf(1, 2, 3), checkNotNull(read.single()).data)
    }

    @Test
    fun anAccountThatDoesNotExistComesBackAsNothingInItsOwnPlace() {
        answer(result(null, account(ADDRESS_LOOKUP_TABLE_PROGRAM, byteArrayOf(9))))

        val read = read(TABLE_ONE, TABLE_TWO)

        assertEquals(2, read.size)
        assertNull(read[0])
        assertEquals(ADDRESS_LOOKUP_TABLE_PROGRAM, read[1]?.owner)
    }

    @Test
    fun aBuildWithNoEndpointAsksNobodyAnything() {
        // Every checkout is one of these: nothing here reaches a cluster unless somebody
        // configured one, and the plugin that needs it says so rather than guessing an endpoint.
        val failure = runBlocking {
            try {
                HttpSolanaAccounts(OkHttpClient(), "").accounts(listOf(TABLE_ONE))
                throw AssertionError("it answered")
            } catch (e: SolanaException) {
                e
            }
        }

        assertEquals(SolanaProblem.NoEndpoint, failure.problem)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun everyWayTheAnswerCanBeUnusableIsRefused() {
        answer("not json")
        assertEquals(SolanaProblem.Unusable, failure { accounts(listOf(TABLE_ONE)) }.problem)

        // A JSON-RPC error arrives with a 200, which is exactly the case a status check misses.
        answer(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("error", JSONObject().put("code", -32602).put("message", "bad"))
                .toString()
        )
        val error = failure { accounts(listOf(TABLE_ONE)) }
        assertEquals(SolanaProblem.Unusable, error.problem)
        assertEquals("error -32602", error.detail)

        // Three asked about, two answered: "two of the three tables" is not a review.
        answer(result(account(ADDRESS_LOOKUP_TABLE_PROGRAM, byteArrayOf(1))))
        assertEquals(
            SolanaProblem.Unusable,
            failure { accounts(listOf(TABLE_ONE, TABLE_TWO)) }.problem,
        )

        answer(
            JSONObject()
                .put(
                    "result",
                    JSONObject()
                        .put("value", org.json.JSONArray().put(JSONObject().put("owner", "x"))),
                )
                .toString()
        )
        assertEquals(SolanaProblem.Unusable, failure { accounts(listOf(TABLE_ONE)) }.problem)
    }

    @Test
    fun eachWayTheEndpointCanSayNoIsReportedAsItself() {
        answer("slow down", status = 429)
        assertEquals(SolanaProblem.RateLimited, failure { accounts(listOf(TABLE_ONE)) }.problem)

        answer("nope", status = 503)
        val refused = failure { accounts(listOf(TABLE_ONE)) }
        assertEquals(SolanaProblem.Refused, refused.problem)
        // A status, and never the body.
        assertEquals("HTTP 503", refused.detail)
        assertFalse(refused.detail.orEmpty().contains("nope"))
    }

    @Test
    fun anEndpointThatIsNotThereIsUnreachableRatherThanSilent() {
        val closed = MockWebServer()
        closed.start(InetAddress.getByName("127.0.0.1"), 0)
        val endpoint = "http://127.0.0.1:${closed.port}"
        closed.close()

        val failure = runBlocking {
            try {
                HttpSolanaAccounts(OkHttpClient(), endpoint).accounts(listOf(TABLE_ONE))
                throw AssertionError("it answered")
            } catch (e: SolanaException) {
                e
            }
        }

        assertEquals(SolanaProblem.Unreachable, failure.problem)
    }

    @Test
    fun itRefusesToAskAboutMoreAccountsThanAnyMessageCouldName() {
        val many = (1..40).map { TABLE_ONE }

        val failure = runBlocking {
            try {
                reader().accounts(many)
                throw AssertionError("it answered")
            } catch (e: SolanaException) {
                e
            }
        }

        assertEquals(SolanaProblem.Unusable, failure.problem)
        assertEquals(0, server.requestCount)
        // And asking about nothing reaches nobody.
        assertTrue(runBlocking { reader().accounts(emptyList()) }.isEmpty())
        assertEquals(0, server.requestCount)
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}
