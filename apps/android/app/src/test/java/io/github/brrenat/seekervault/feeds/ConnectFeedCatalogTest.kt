package io.github.brrenat.seekervault.feeds

import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.gateway.v1.ListRecommendedFeedsRequest
import io.github.brrenat.seekervault.gateway.v1.listRecommendedFeedsResponse
import io.github.brrenat.seekervault.gateway.v1.recommendedFeed
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Discover catalog read on the wire (SEE-176): the page size and token go out, and nothing
 * about this phone does — no session, no cookie, no header of its own.
 */
class ConnectFeedCatalogTest {
    private val server = MockWebServer()
    private val gateway =
        ConnectFeedGateway(ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build())

    @Before fun start() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After fun stop() = server.close()

    private val url: String
        get() = "http://127.0.0.1:${server.port}"

    @Test
    fun aPageIsAskedForWithItsSizeAndTokenAndNothingElse() {
        val answer = listRecommendedFeedsResponse {
            feeds += recommendedFeed { serverId = "one" }
            nextPageToken = "after-one"
        }
        server.enqueue(
            MockResponse.Builder()
                .setHeader("content-type", "application/proto")
                .body(Buffer().write(answer.toByteArray()))
                .build()
        )
        val read = runBlocking { withTimeout(30_000) { gateway.recommended(url, 20, "token") } }
        assertEquals(answer, read)

        val request = server.takeRequest()
        assertEquals(
            "/seekervault.gateway.v1.FeedService/ListRecommendedFeeds",
            request.url.encodedPath,
        )
        val asked = ListRecommendedFeedsRequest.parseFrom(request.body!!.toByteArray())
        assertEquals(20, asked.pageSize)
        assertEquals("token", asked.pageToken)
        assertNull(request.headers["authorization"])
        assertNull(request.headers["cookie"])
    }

    @Test
    fun aGatewayWithoutACatalogIsUnimplementedAndAnUnreachableOneIsUnreachable() {
        server.enqueue(
            MockResponse.Builder()
                .code(501)
                .setHeader("content-type", "application/json")
                .body("""{"code":"unimplemented","message":"no"}""")
                .build()
        )
        val refused = runCatching { runBlocking { gateway.recommended(url, 20, "") } }
        assertEquals(
            GatewayException.Kind.Unimplemented,
            (refused.exceptionOrNull() as GatewayException).kind,
        )
        server.close()
        val unreachable = runCatching { runBlocking { gateway.recommended(url, 20, "") } }
        assertTrue(unreachable.exceptionOrNull() is GatewayException)
    }
}
