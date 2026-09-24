package io.github.brrenat.seekervault.feeds

import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.gateway.v1.FeedAvailability as WireAvailability
import io.github.brrenat.seekervault.gateway.v1.GetFeedStatusRequest
import io.github.brrenat.seekervault.gateway.v1.feedStatus
import io.github.brrenat.seekervault.gateway.v1.getFeedStatusResponse
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
 * What the phone asks the gateway about whether its feeds' publishers are running, and what it does
 * with the answer (SEE-150).
 *
 * A real HTTP endpoint answering real Connect bodies, like the topics test beside it, because what
 * is worth proving is on the wire: that the request carries the channels and nothing about this
 * phone, and above all that every answer this build cannot read lands on "unknown" rather than on
 * "online". The bug being fixed was a feed shown as running when nobody had established that it
 * was, and a mapping that guessed generously would put it straight back.
 */
class ConnectFeedStatusTest {
    private val server = MockWebServer()
    private val gateway =
        ConnectFeedGateway(ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build())

    @Before fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After fun stop() = server.close()

    private val url: String
        get() = "http://127.0.0.1:${server.port}"

    private fun answer(vararg statuses: Pair<String, WireAvailability>) {
        val message = getFeedStatusResponse {
            this.statuses += statuses.map { (named, state) ->
                feedStatus {
                    channel = named
                    availability = state
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

    private fun asked(): GetFeedStatusRequest =
        GetFeedStatusRequest.parseFrom(server.takeRequest().body!!.toByteArray())

    private fun statuses(vararg channels: String): Map<String, FeedAvailability> = runBlocking {
        withTimeout(30_000) { gateway.statuses(url, channels.toList()) }
    }

    @Test
    fun theRequestCarriesTheChannelsAndEachAnswerIsMapped() {
        answer(
            CHANNEL_A to WireAvailability.FEED_AVAILABILITY_ONLINE,
            CHANNEL_B to WireAvailability.FEED_AVAILABILITY_OFFLINE,
        )

        val answered = statuses(CHANNEL_A, CHANNEL_B)

        assertEquals(
            mapOf(
                CHANNEL_A to FeedAvailability.Online,
                CHANNEL_B to FeedAvailability.Offline,
            ),
            answered,
        )
        // One call for every feed on the gateway, carrying the channels and nothing else.
        assertEquals(listOf(CHANNEL_A, CHANNEL_B), asked().channelsList)
        assertEquals(1, server.requestCount)
    }

    /** Asking twice about one feed asks once: this is a read, not a registration. */
    @Test
    fun duplicatesAreAskedAboutOnce() {
        answer(CHANNEL_A to WireAvailability.FEED_AVAILABILITY_ONLINE)

        statuses(CHANNEL_A, CHANNEL_A)

        assertEquals(listOf(CHANNEL_A), asked().channelsList)
    }

    /**
     * A channel this gateway does not host is absent from the answer, and that is not an error and
     * not a verdict: the phone keeps what it was told about its other feeds, and reads the missing
     * one as unknown.
     */
    @Test
    fun aChannelTheGatewayDoesNotHostIsAbsentRatherThanOffline() {
        answer(CHANNEL_A to WireAvailability.FEED_AVAILABILITY_ONLINE)

        val answered = statuses(CHANNEL_A, CHANNEL_B)

        assertEquals(mapOf(CHANNEL_A to FeedAvailability.Online), answered)
        assertEquals(null, answered[CHANNEL_B])
    }

    /**
     * The one mapping that matters most: an availability this build has no name for — a value the
     * contract gains later, or the unspecified zero — is unknown. Reading it as online is the bug
     * this whole feature exists to fix, arriving by a different route.
     */
    @Test
    fun anAvailabilityThisBuildCannotReadIsUnknownAndNeverOnline() {
        answer(CHANNEL_A to WireAvailability.FEED_AVAILABILITY_UNSPECIFIED)

        assertEquals(mapOf(CHANNEL_A to FeedAvailability.Unknown), statuses(CHANNEL_A))
    }

    /**
     * A status for a channel nobody asked about is refused whole, as a topic list is: at that point
     * nothing in the answer describes this phone's feeds, and using part of it would mean showing
     * the owner a verdict about somebody else's publisher.
     */
    @Test
    fun aStatusForAChannelNobodyAskedAboutIsRefused() {
        answer(
            CHANNEL_A to WireAvailability.FEED_AVAILABILITY_ONLINE,
            CHANNEL_B to WireAvailability.FEED_AVAILABILITY_ONLINE,
        )

        val failure = runCatching { statuses(CHANNEL_A) }.exceptionOrNull()

        assertTrue(failure is GatewayException)
        assertEquals(GatewayException.Kind.BadResponse, (failure as GatewayException).kind)
    }

    /**
     * A gateway older than SEE-150 has no such method. That is a working deployment: its feeds are
     * still read and still streamed, and they stay unknown rather than being called offline.
     */
    @Test
    fun aGatewayThatDoesNotAnswerPresenceIsReadAsUnimplemented() {
        refuse(501, "unimplemented")

        val failure = runCatching { statuses(CHANNEL_A) }.exceptionOrNull()

        assertEquals(GatewayException.Kind.Unimplemented, (failure as GatewayException).kind)
    }

    /** And a gateway that is not there at all is unreachable, which is a different thing again. */
    @Test
    fun aGatewayThatIsNotThereIsUnreachable() {
        server.close()

        val failure = runCatching { statuses(CHANNEL_A) }.exceptionOrNull()

        assertEquals(GatewayException.Kind.Unreachable, (failure as GatewayException).kind)
    }

    private companion object {
        const val CHANNEL_A = "server/3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val CHANNEL_B = "server/7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"
    }
}
