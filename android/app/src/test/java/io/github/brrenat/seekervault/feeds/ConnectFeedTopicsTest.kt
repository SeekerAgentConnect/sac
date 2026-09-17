package io.github.brrenat.seekervault.feeds

import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.gateway.v1.GetFeedTopicsRequest
import io.github.brrenat.seekervault.gateway.v1.feedTopic
import io.github.brrenat.seekervault.gateway.v1.getFeedTopicsResponse
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the phone asks the gateway about where hints arrive, and what it does with the answer
 * (SEE-92).
 *
 * The server is a real HTTP endpoint answering with real Connect unary bodies — the generated
 * client, the generated messages and the adapter, with nothing stood in for — because the two
 * things worth proving here are both about the wire: that the request carries the channels and
 * nothing else, and that an answer naming a channel nobody asked about is refused rather than
 * subscribed to.
 */
class ConnectFeedTopicsTest {
    private val server = MockWebServer()
    private val gateway =
        ConnectFeedGateway(ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build())

    @Before fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After fun stop() = server.close()

    private val url: String
        get() = "http://127.0.0.1:${server.port}"

    private fun answer(vararg topics: Pair<String, String>) {
        val message = getFeedTopicsResponse {
            this.topics += topics.map { (named, name) ->
                feedTopic {
                    channel = named
                    topic = name
                }
            }
        }
        server.enqueue(
            MockResponse.Builder()
                .setHeader("content-type", "application/proto")
                .body(Buffer().write(message.toByteArray()))
                .build()
        )
    }

    private fun refuse(status: Int, code: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(status)
                .setHeader("content-type", "application/json")
                .body("""{"code":"$code","message":"$code"}""")
                .build()
        )
    }

    private fun asked(): GetFeedTopicsRequest =
        GetFeedTopicsRequest.parseFrom(server.takeRequest().body!!.toByteArray())

    private fun topics(vararg channels: String): List<FeedChannelTopic> = runBlocking {
        withTimeout(30_000) { gateway.topics(url, channels.toList()) }
    }

    @Test
    fun theRequestCarriesTheChannelsAndTheAnswerIsMappedInOrder() {
        answer(CHANNEL_A to TOPIC_A, CHANNEL_B to TOPIC_B)

        val named = topics(CHANNEL_A, CHANNEL_B)

        assertEquals(
            listOf(
                FeedChannelTopic(CHANNEL_A, TOPIC_A),
                FeedChannelTopic(CHANNEL_B, TOPIC_B),
            ),
            named,
        )
        // The channels, in the order they were asked about, and one call for all of them.
        assertEquals(listOf(CHANNEL_A, CHANNEL_B), asked().channelsList)
        assertEquals(0, server.requestCount - 1)
    }

    /** Asking twice about one feed asks once: a topic is a name, not a subscription. */
    @Test
    fun duplicatesAreAskedAboutOnce() {
        answer(CHANNEL_A to TOPIC_A)

        assertEquals(listOf(FeedChannelTopic(CHANNEL_A, TOPIC_A)), topics(CHANNEL_A, CHANNEL_A))

        assertEquals(listOf(CHANNEL_A), asked().channelsList)
    }

    /**
     * A channel this gateway does not name is simply absent, and that is not an error: the phone
     * keeps the hints for its other feeds and reads that one when the owner looks.
     */
    @Test
    fun aChannelTheGatewayDoesNotNameIsLeftOutOfTheAnswer() {
        answer(CHANNEL_A to TOPIC_A)

        assertEquals(listOf(FeedChannelTopic(CHANNEL_A, TOPIC_A)), topics(CHANNEL_A, CHANNEL_B))
    }

    /**
     * A topic for a channel nobody asked about is refused whole. Subscribing to it would mean this
     * phone listening for a publisher the owner never added, and at that point nothing about the
     * answer can be relied on — so none of it is used.
     */
    @Test
    fun aTopicForAChannelNobodyAskedAboutIsRefused() {
        answer(CHANNEL_A to TOPIC_A, CHANNEL_B to TOPIC_B)

        val failure = runCatching { topics(CHANNEL_A) }.exceptionOrNull()

        assertTrue(failure is GatewayException)
        assertEquals(GatewayException.Kind.BadResponse, (failure as GatewayException).kind)
    }

    /** An empty name is the same refusal: there is nothing to subscribe to in it. */
    @Test
    fun anEmptyTopicIsRefused() {
        answer(CHANNEL_A to "")

        val failure = runCatching { topics(CHANNEL_A) }.exceptionOrNull()

        assertEquals(
            GatewayException.Kind.BadResponse,
            (failure as GatewayException).kind,
        )
    }

    /**
     * A gateway that relays nothing says so with the code the phone already reads as "this one does
     * not do that". It is not a failure and not retried: the feeds on it are still read, and still
     * streamed if it streams.
     */
    @Test
    fun aGatewayThatRelaysNothingIsReadAsUnimplemented() {
        refuse(501, "unimplemented")

        val failure = runCatching { topics(CHANNEL_A) }.exceptionOrNull()

        assertEquals(
            GatewayException.Kind.Unimplemented,
            (failure as GatewayException).kind,
        )
    }

    /** And a gateway that is not there at all is unreachable, which is a different thing. */
    @Test
    fun aGatewayThatIsNotThereIsUnreachable() {
        server.close()

        val failure = runCatching { topics(CHANNEL_A) }.exceptionOrNull()

        assertEquals(
            GatewayException.Kind.Unreachable,
            (failure as GatewayException).kind,
        )
    }

    private companion object {
        const val CHANNEL_A = "server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val CHANNEL_B = "server/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"
        const val TOPIC_A = "feed.production.3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val TOPIC_B = "feed.production.7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"
    }
}
