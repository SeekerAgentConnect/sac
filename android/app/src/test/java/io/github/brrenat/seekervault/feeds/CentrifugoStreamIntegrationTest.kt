package io.github.brrenat.seekervault.feeds

import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.gateway.v1.FeedEvent
import io.github.brrenat.seekervault.proposal.v1.Proposal
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The phone's own listener against the real broker (SEE-91).
 *
 * `UniStreamInteropTest` proves the client speaks the protocol; this proves the pair works: **two
 * broker nodes sharing Redis**, the configuration this repository ships, and the adapter the app
 * actually uses. What it answers are the ticket's acceptance questions that only a real broker can
 * answer — cross-node delivery, what recovery does and does not cover, and what happens to a
 * listener that stops reading.
 *
 * It is opt-in, because a broker and a Redis are services and CI has neither:
 * ```
 * android/gradlew -p android :app:testDebugUnitTest \
 *   --tests 'io.github.brrenat.seekervault.feeds.CentrifugoStreamIntegrationTest' \
 *   -Dseekervault.centrifugo=/path/to/centrifugo -Dseekervault.redis=/path/to/redis-server
 * ```
 *
 * Two things here stand in for the gateway, which is a Go service this test does not run: the
 * ticket is minted with the same claims (`feed-gateway/internal/stream/ticket_test.go` pins them), and
 * publications are made with the same request (`feed-gateway/internal/stream/broker_test.go` pins that
 * against a real broker). What is under test is everything between them and the phone.
 */
class CentrifugoStreamIntegrationTest {
    private val centrifugo = System.getProperty("seekervault.centrifugo")
    private val redis = System.getProperty("seekervault.redis")
    private val config =
        File(
            System.getProperty("seekervault.repoRoot") ?: ".",
            "feed-gateway/centrifugo.yaml",
        )

    private val processes = mutableListOf<Process>()
    private val scope = CoroutineScope(SupervisorJob())
    private val client = ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
    private val stream = CentrifugoFeedStream(client)

    private var redisPort = 0
    private val api = IntArray(2)
    private val grpc = IntArray(2)

    @Before
    fun start() {
        assumeTrue(
            "set -Dseekervault.centrifugo and -Dseekervault.redis to run this",
            centrifugo != null && redis != null,
        )
        redisPort = free()
        // No persistence: the history here is a recovery cache, which is all it ever is
        // (docs/wiki/feed-gateway.md#the-stream).
        processes +=
            ProcessBuilder(
                    redis!!,
                    "--port",
                    redisPort.toString(),
                    "--save",
                    "",
                    "--appendonly",
                    "no",
                )
                .redirectErrorStream(true)
                .redirectOutput(logFile("redis"))
                .start()
        for (node in 0..1) {
            api[node] = free()
            grpc[node] = free()
            processes += node(node)
        }
        for (node in 0..1) waitFor(node)
    }

    @After
    fun stop() {
        scope.cancel()
        processes.forEach { it.destroy() }
        processes.forEach { it.waitFor() }
    }

    private fun node(index: Int): Process =
        ProcessBuilder(centrifugo!!, "-c", config.absolutePath)
            .also {
                it.environment() +=
                    mapOf(
                        "CENTRIFUGO_ENGINE_TYPE" to "redis",
                        "CENTRIFUGO_ENGINE_REDIS_ADDRESS" to "redis://127.0.0.1:$redisPort",
                        "CENTRIFUGO_HTTP_SERVER_PORT" to api[index].toString(),
                        "CENTRIFUGO_UNI_GRPC_PORT" to grpc[index].toString(),
                        "CENTRIFUGO_HTTP_API_KEY" to API_KEY,
                        "CENTRIFUGO_CLIENT_TOKEN_HMAC_SECRET_KEY" to TOKEN_KEY,
                        "CENTRIFUGO_NODE_NAME" to "node-$index",
                        "CENTRIFUGO_LOG_LEVEL" to "error",
                    )
            }
            .redirectErrorStream(true)
            .redirectOutput(logFile("node-$index"))
            .start()

    /**
     * Waits for a node to be healthy, which is also how the shipped configuration is checked:
     * `centrifugo checkconfig` accepts settings the server then refuses to start with, so a node
     * that never becomes healthy fails this test rather than being worked around.
     */
    private fun waitFor(node: Int) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val healthy = runCatching {
                client.newCall(Request.Builder().url(health(node)).build()).execute().use {
                    it.isSuccessful
                }
            }
                .getOrDefault(false)
            if (healthy) return
            Thread.sleep(50)
        }
        error("node $node never became healthy")
    }

    // --------------------------------------------------------------- the tests

    /**
     * Acceptance: two streaming nodes deliver the same committed publication to clients connected
     * to either one. That is Redis's whole job here — the gateway publishes once, to whichever node
     * it reaches, and every listener hears it.
     */
    @Test
    fun aPublicationOnOneNodeReachesListenersOnBothOfThem() = runBlocking {
        val first = listener(node = 0)
        val second = listener(node = 1)
        first.opened()
        second.opened()

        publish(node = 0, event = event(revision = 4))

        for (listening in listOf(first, second)) {
            val published = listening.published()
            assertEquals(4L, published.event.proposal.revision)
            assertEquals(1L, published.offset)
        }
    }

    /**
     * Acceptance: a disconnect and reconnect converge without reading the feed again — when the
     * broker can prove it. The cursor goes up, the missed documents come back, and
     * [Continuity.Recovered] is the phone's own reading of that answer.
     */
    @Test
    fun whatWasPublishedWhileAwayComesBackOnTheNextConnection() = runBlocking {
        val before = listener(node = 0)
        val epoch = before.opened().subscriptions.getValue(CHANNEL).epoch
        publish(node = 0, event = event(revision = 1))
        val cursor = FeedCursor(epoch, before.published().offset)
        before.stop()

        // Two publications while nobody is listening.
        publish(node = 0, event = event(revision = 2))
        publish(node = 1, event = event(revision = 3))

        val after = listener(node = 0, resume = mapOf(CHANNEL to cursor))
        val opening = after.opened()
        val subscription = opening.subscriptions.getValue(CHANNEL)

        assertTrue("the broker replayed what was missed", subscription.recovered)
        assertEquals(Continuity.Recovered, continuity(cursor, subscription))
        assertEquals(
            listOf(2L, 3L),
            listOf(after.published(), after.published()).map {
                it.event.proposal.revision
            },
        )
    }

    /**
     * Acceptance: a cursor/history gap is admitted rather than papered over. The broker keeps a
     * bounded history, so a listener further behind than that is told it cannot be caught up — and
     * the phone reads the authoritative snapshot instead of believing a partial replay.
     */
    @Test
    fun aGapLongerThanTheBrokersHistoryIsAdmitted() = runBlocking {
        val first = listener(node = 0)
        val opening = first.opened()
        val epoch = opening.subscriptions.getValue(CHANNEL).epoch
        first.stop()

        // The shipped configuration keeps 256 publications on a feed channel.
        for (revision in 1..HISTORY + 20) publish(
            node = revision % 2,
            event = event(revision.toLong()),
        )

        val after = listener(node = 0, resume = mapOf(CHANNEL to FeedCursor(epoch, 1)))
        val subscription = after.opened().subscriptions.getValue(CHANNEL)

        assertFalse("the broker says it could not replay that far", subscription.recovered)
        assertTrue("and it was asked to", subscription.wasRecovering)
        assertEquals(
            Continuity.Snapshot(Continuity.Why.TooFarBehind),
            continuity(FeedCursor(epoch, 1), subscription),
        )
        // The position it reports is where the channel is now, which is where a listener carries on
        // from once it has read the snapshot.
        assertEquals((HISTORY + 20).toLong(), subscription.offset)
    }

    /**
     * Acceptance: a history that was replaced is not mistaken for a history that moved on. The
     * epoch is what says which stream an offset counts in, so losing Redis's keys must produce a
     * new one — and the phone must notice.
     */
    @Test
    fun aHistoryThatWasReplacedIsNoticedByItsEpoch() = runBlocking {
        val first = listener(node = 0)
        val epoch = first.opened().subscriptions.getValue(CHANNEL).epoch
        publish(node = 0, event = event(revision = 1))
        val cursor = FeedCursor(epoch, first.published().offset)
        first.stop()

        flushRedis()

        val after = listener(node = 0, resume = mapOf(CHANNEL to cursor))
        val subscription = after.opened().subscriptions.getValue(CHANNEL)

        assertFalse(subscription.recovered)
        assertTrue("a new history has a new epoch", subscription.epoch != epoch)
        assertEquals(
            Continuity.Snapshot(Continuity.Why.EpochChanged),
            continuity(cursor, subscription),
        )
    }

    /**
     * Acceptance: a duplicate publication is one event. The gateway's outbox is at-least-once, so
     * the same document at the same revision can be sent twice; the idempotency key makes the
     * broker keep one, and the phone's apply path makes a repeat free either way.
     */
    @Test
    fun theSameDocumentSentTwiceIsPublishedOnce() = runBlocking {
        val listening = listener(node = 0)
        listening.opened()

        publish(node = 0, event = event(revision = 7), idempotency = "proposal/7")
        publish(node = 1, event = event(revision = 7), idempotency = "proposal/7")

        assertEquals(7L, listening.published().event.proposal.revision)
        assertNull("no second publication", listening.next(300))
    }

    /**
     * Acceptance: a node shutting down ends its listeners' streams with a code they come back from,
     * and the other node carries on serving. Draining is what makes a deployment ordinary.
     */
    @Test
    fun aNodeShuttingDownEndsItsStreamsAndTheOtherNodeCarriesOn() = runBlocking {
        val leaving = listener(node = 1)
        val staying = listener(node = 0)
        leaving.opened()
        staying.opened()

        processes.last().destroy()
        processes.last().waitFor()

        val closed = leaving.closed()
        assertEquals(3001, closed.code)
        assertEquals(AfterClose.Reconnect(reticket = false), afterClose(closed.code))

        // And the surviving node still delivers.
        publish(node = 0, event = event(revision = 2))
        assertEquals(2L, staying.published().event.proposal.revision)
    }

    /**
     * Acceptance, the half of it that is about everyone else: a listener that cannot keep up does
     * not hold up the listeners beside it on the same node.
     *
     * The stalled listener here takes a second over every event while sixty are published. The
     * healthy one on the same node receives all sixty, in order, meanwhile.
     *
     * The other half — that the broker closes a client whose queue grows past
     * `client.queue_max_size` — is **not** asserted here, and the reason is a property of our own
     * client worth writing down: connect-kotlin 0.9.0 keeps reading the HTTP/2 stream regardless of
     * how slowly the application consumes it, so a slow *phone* never becomes a slow *socket* and
     * the broker's queue never grows. What bounds a phone that falls behind is therefore the
     * gateway's publish rate limit per publisher (SEE-90), not the broker's queue; the queue bound
     * protects the broker from a client that stops reading its socket, which was verified against
     * the real broker by hand (docs/testing/stage-7-1.md) rather than pretended about here.
     */
    @Test
    fun aListenerThatCannotKeepUpDoesNotHoldUpTheOthers() = runBlocking {
        val healthy = listener(node = 0)
        healthy.opened()
        val stalled = Channel<FeedStreamEvent>(Channel.UNLIMITED)
        val slow = scope.launch {
            stream.listen(url(0), ticket(), emptyMap()).collect {
                stalled.trySend(it)
                delay(1_000)
            }
        }
        withTimeout(20_000) { stalled.receive() } // its opening

        val payload = "x".repeat(16 * 1024)
        for (revision in 1..60) publish(node = 0, event = event(revision.toLong(), payload))

        val seen = mutableListOf<Long>()
        while (seen.size < 60) {
            val next = healthy.next(20_000) ?: break
            seen += next.event.proposal.revision
        }
        assertEquals((1L..60L).toList(), seen)

        slow.cancel()
        slow.join()
    }

    // -------------------------------------------------------------- the plumbing

    /** A listener on one node, collected in the background with its events on a channel. */
    private inner class Listening(node: Int, resume: Map<String, FeedCursor>) {
        private val events = Channel<FeedStreamEvent>(Channel.UNLIMITED)
        private val job: Job = scope.launch {
            try {
                stream.listen(url(node), ticket(), resume).collect { events.trySend(it) }
            } finally {
                events.close()
            }
        }

        suspend fun opened(): FeedStreamEvent.Opened =
            withTimeout(20_000) {
                var event = events.receive()
                while (event !is FeedStreamEvent.Opened) event = events.receive()
                event
            }

        suspend fun published(): FeedStreamEvent.Published =
            withTimeout(20_000) {
                var event = events.receive()
                while (event !is FeedStreamEvent.Published) event = events.receive()
                event
            }

        suspend fun closed(): FeedStreamEvent.Closed =
            withTimeout(20_000) {
                var event = events.receive()
                while (event !is FeedStreamEvent.Closed) event = events.receive()
                event
            }

        /** The next publication, or null if none arrives in [millis] — for asserting a silence. */
        suspend fun next(millis: Long): FeedStreamEvent.Published? =
            withTimeoutOrNull<FeedStreamEvent.Published?>(millis) {
                var event: FeedStreamEvent? = events.receiveCatching().getOrNull()
                while (event != null && event !is FeedStreamEvent.Published) {
                    event = events.receiveCatching().getOrNull()
                }
                event as? FeedStreamEvent.Published
            }

        suspend fun stop() {
            job.cancel()
            job.join()
        }
    }

    private fun listener(node: Int, resume: Map<String, FeedCursor> = emptyMap()) =
        Listening(node, resume)

    /**
     * A ticket with the claims the gateway mints: an empty subject, an expiry, and the channels.
     * Nothing about the listener, which is the property the gateway's own test pins.
     */
    private fun ticket(channels: List<String> = listOf(CHANNEL)): String {
        val expiry = System.currentTimeMillis() / 1000 + 3600
        val header = segment("""{"alg":"HS256","typ":"JWT"}""")
        val claims =
            segment(
                """{"sub":"","exp":$expiry,"channels":[${channels.joinToString(",") { "\"$it\"" }}]}"""
            )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(), "HmacSHA256"))
        val signature =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mac.doFinal("$header.$claims".toByteArray()))
        return "$header.$claims.$signature"
    }

    private fun segment(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    /** A publication, as the gateway's dispatcher makes it: base64 bytes and an idempotency key. */
    private fun publish(node: Int, event: FeedEvent, idempotency: String? = null) {
        val data = Base64.getEncoder().encodeToString(event.toByteArray())
        val body = buildString {
            append("""{"channel":"$CHANNEL","b64data":"$data"""")
            if (idempotency != null) append(""","idempotency_key":"$idempotency"""")
            append("}")
        }
        val response =
            client
                .newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:${api[node]}/api/publish")
                        .header("X-API-Key", API_KEY)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                )
                .execute()
        response.use {
            val answer = it.body?.string().orEmpty()
            check(it.isSuccessful && "\"error\"" !in answer) { "publishing failed: $answer" }
        }
    }

    /** Loses the broker's history the way a Redis restart would, so the epoch has to change. */
    private fun flushRedis() {
        Socket("127.0.0.1", redisPort).use { socket ->
            socket.getOutputStream().write("*1\r\n\$8\r\nFLUSHALL\r\n".toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().read(ByteArray(64))
        }
    }

    private fun event(revision: Long, note: String = ""): FeedEvent =
        FeedEvent.newBuilder()
            .setSequence(revision)
            .setProposal(
                Proposal.newBuilder()
                    .setServerId(SERVER)
                    .setChannel("server/$SERVER")
                    .setProposalId(PROPOSAL)
                    .setRevision(revision)
                    .setPublisherNote(note)
                    .build()
            )
            .build()

    private fun url(node: Int) = "http://127.0.0.1:${grpc[node]}"

    private fun health(node: Int) = "http://127.0.0.1:${api[node]}/health"

    private fun free(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Where a process's output goes. Kept rather than discarded, and named, because the first
     * question about a failing integration test is what the broker said about it.
     */
    private fun logFile(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "seekervault-$name-${hashCode()}.log")

    private companion object {
        const val API_KEY = "integration-api-key"
        const val TOKEN_KEY = "integration-token-key"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val PROPOSAL = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val CHANNEL = "feed:server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        /** What the shipped configuration keeps on a feed channel (feed-gateway/centrifugo.yaml). */
        const val HISTORY = 256
    }
}
