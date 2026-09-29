package io.github.brrenat.seekervault

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LegacyUpdateTransport
import io.github.brrenat.seekervault.discover.DiscoverTags
import io.github.brrenat.seekervault.discover.FeedCatalog
import io.github.brrenat.seekervault.feeds.ConnectFeedGateway
import io.github.brrenat.seekervault.gateway.v1.ListRecommendedFeedsResponse
import io.github.brrenat.seekervault.gateway.v1.getServerManifestResponse
import io.github.brrenat.seekervault.gateway.v1.listRecommendedFeedsResponse
import io.github.brrenat.seekervault.gateway.v1.recommendedFeed
import io.github.brrenat.seekervault.server.v1.FeedAccessPolicy
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import io.github.brrenat.seekervault.server.v1.feedAccess
import io.github.brrenat.seekervault.servers.SERVER_A
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.feedManifest
import io.github.brrenat.seekervault.wallet.WalletTags
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Discover tab in the real activity on a fresh install (SEE-176): a catalog read from the
 * configured gateway with no connection and no wallet, browsing that adds nothing, and Connect /
 * Request access going through the ordinary Add connection flow against a real manifest endpoint.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val gateway = MockWebServer()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val catalogAsked = mutableListOf<String>()

    private val origin: String
        get() = "http://127.0.0.1:${gateway.port}"

    @Before
    fun useFakes() {
        gateway.dispatcher = Manifests()
        gateway.start(InetAddress.getByName("127.0.0.1"), 0)
        app.connectionGateway = { FakeConnectionGateway() }
        app.updateTransport = { LegacyUpdateTransport() }
        app.connectionIo = Dispatchers.Unconfined
        app.feeds = {
            ConnectFeedGateway(ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build())
        }
        app.discoveryUrl = { origin }
        app.feedCatalog = { FeedCatalog { url, _, token -> catalog(url, token) } }
    }

    @After
    fun close() {
        scenario?.close()
        gateway.close()
    }

    private fun catalog(url: String, token: String): ListRecommendedFeedsResponse {
        catalogAsked += token
        assertEquals(origin, url)
        return listRecommendedFeedsResponse {
            feeds += listed(SERVER_A, "Alpha signals", restricted = false)
            feeds += listed(SERVER_B, "Members desk", restricted = true)
        }
    }

    private fun listed(serverId: String, name: String, restricted: Boolean) = recommendedFeed {
        this.serverId = serverId
        gatewayUrl = origin
        channel = channelFor(serverId)
        displayName = name
        description = "$name, as its operator describes it."
        access = feedAccess {
            if (restricted) {
                policy = FeedAccessPolicy.FEED_ACCESS_POLICY_RESTRICTED
                authOrigin = "https://auth.example.com"
            } else {
                policy = FeedAccessPolicy.FEED_ACCESS_POLICY_PUBLIC
            }
        }
        supportedNetworks += SolanaNetwork.SOLANA_NETWORK_MAINNET
    }

    /**
     * The gateway's manifest endpoint for both feeds, and a refusal for everything else — the
     * stream, topics and snapshots a newly added feed goes on to ask for are not what this test is
     * about.
     */
    private inner class Manifests : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (!request.target.endsWith("/GetServerManifest")) {
                return MockResponse.Builder()
                    .code(501)
                    .setHeader("content-type", "application/json")
                    .body("""{"code":"unimplemented","message":"not here"}""")
                    .build()
            }
            val asked =
                io.github.brrenat.seekervault.gateway.v1.GetServerManifestRequest.parseFrom(
                    request.body!!.toByteArray()
                )
            val restricted = asked.serverId == SERVER_B
            val manifest =
                feedManifest(
                    serverId = asked.serverId,
                    gateway = origin,
                    name = if (restricted) "Members desk" else "Alpha signals",
                    networks = listOf(SolanaNetwork.SOLANA_NETWORK_MAINNET),
                    access =
                        if (restricted)
                            feedAccess {
                                policy = FeedAccessPolicy.FEED_ACCESS_POLICY_RESTRICTED
                                authOrigin = "https://auth.example.com"
                            }
                        else null,
                )
            val answer = getServerManifestResponse {
                this.manifest = manifest
                settingsRevision = manifest.settingsRevision
            }
            return MockResponse.Builder()
                .setHeader("content-type", "application/proto")
                .body(Buffer().write(answer.toByteArray()))
                .build()
        }
    }

    private fun launch() = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun openDiscover() {
        compose.onNodeWithText(app.getString(R.string.nav_discover)).performClick()
        compose.waitForIdle()
    }

    private fun cardAction(serverId: String, label: Int) =
        compose.onNode(
            hasText(app.getString(label)) and
                hasAnyAncestor(hasTestTag(DiscoverTags.card(serverId)))
        )

    /** The manifest is read over real HTTP, off the main thread the rule waits for. */
    private fun awaitAdded() {
        compose.waitUntil(timeoutMillis = 15_000) {
            app.connectionRepository.connections.value.isNotEmpty() ||
                compose
                    .onAllNodes(hasTestTag(ConnectionsTags.FEED_FAILURE))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithTag(ConnectionsTags.FEED_FAILURE).assertDoesNotExist()
        compose.waitForIdle()
    }

    @Test
    fun aFreshInstallBrowsesTheCatalogAndConnectsAPublicFeedThroughAddConnection() {
        launch()
        assertTrue(catalogAsked.isEmpty()) // nothing is read before the tab is shown
        openDiscover()
        compose.onNodeWithTag(DiscoverTags.card(SERVER_A)).performScrollTo().assertExists()
        compose.onNodeWithTag(DiscoverTags.card(SERVER_B)).performScrollTo().assertExists()
        compose.onNodeWithText(app.getString(R.string.discover_access_restricted)).assertExists()
        assertEquals(listOf(""), catalogAsked)

        // Opening a card's details adds nothing and asks nothing.
        compose
            .onNodeWithTag(DiscoverTags.card(SERVER_A))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(app.getString(R.string.discover_detail_stale)).assertExists()
        compose.onNodeWithText(app.getString(R.string.discover_close)).performClick()
        compose.mainClock.advanceTimeBy(400)
        assertTrue(app.connectionRepository.connections.value.isEmpty())
        assertEquals(0, gateway.requestCount)

        // Connect is the ordinary confirmation, prefilled; nothing is stored until it is confirmed.
        cardAction(SERVER_A, R.string.discover_connect)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(ConnectionsTags.CONFIRM_FEED).assertExists()
        assertTrue(app.connectionRepository.connections.value.isEmpty())
        compose
            .onNodeWithTag(ConnectionsTags.ADD_FEED)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)

        // Added: the wallet is chosen next on the connection's own sheet, over Discover.
        awaitAdded()
        compose.onNodeWithTag(WalletTags.PICKER).assertExists()
        val added = app.connectionRepository.connections.value.single()
        assertEquals(SERVER_A, added.serverId)
        compose.onNodeWithTag(ConnectionsTags.DIALOG_DISMISS).performClick()
        compose.onNodeWithTag(ConnectionsTags.CLOSE).performClick()
        compose.mainClock.advanceTimeBy(400)

        // Back on Discover, the card says what the phone holds, and a second tap opens it rather
        // than adding it again.
        cardAction(SERVER_A, R.string.discover_open)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(ConnectionsTags.CLOSE).assertExists()
        assertEquals(1, app.connectionRepository.connections.value.size)
    }

    @Test
    fun aRestrictedFeedIsAddedFromDiscoverAndShownByItsAccessStateNeverAsConnected() {
        launch()
        openDiscover()
        cardAction(SERVER_B, R.string.discover_request_access)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(ConnectionsTags.CONFIRM_FEED).assertExists()
        compose.onNodeWithText(app.getString(R.string.feed_confirm_restricted)).assertExists()
        compose
            .onNodeWithTag(ConnectionsTags.ADD_FEED)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitAdded()
        compose.onNodeWithTag(WalletTags.PICKER).assertExists()
        // No wallet is chosen, so no request is signed or sent.
        compose.onNodeWithTag(ConnectionsTags.DIALOG_DISMISS).performClick()
        compose.onNodeWithTag(ConnectionsTags.CLOSE).performClick()
        compose.mainClock.advanceTimeBy(400)

        compose.onNodeWithText(app.getString(R.string.discover_status_not_requested)).assertExists()
        cardAction(SERVER_B, R.string.discover_continue).assertExists()
        assertEquals(1, app.connectionRepository.connections.value.size)
    }
}
