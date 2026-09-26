package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.ProposalRepository
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.feeds.ConnectFeedGateway
import io.github.brrenat.seekervault.gateway.v1.ListRequestsRequest
import io.github.brrenat.seekervault.gateway.v1.listRequestsRequest
import io.github.brrenat.seekervault.gateway.v1.listRequestsResponse
import io.github.brrenat.seekervault.jupiter.HttpJupiterPrediction
import io.github.brrenat.seekervault.jupiter.HttpJupiterProvider
import io.github.brrenat.seekervault.jupiter.JupiterExecutionProvider
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.requests.commonEnvelope
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletRepository
import io.github.brrenat.seekervault.wallet.encodeBase58
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The traffic, captured, with nothing stood in for (SEE-93).
 *
 * This is the acceptance that cannot be argued from the code: two real HTTP servers — one standing
 * in for the shared gateway, speaking real Connect bodies, and one for the provider, speaking real
 * Jupiter JSON — and the whole path run through them, from reading a feed to a signature. Then
 * every byte the phone sent to each is read back and searched.
 *
 * What must be true, and is:
 *
 * - **The gateway is told a channel and a sequence.** Not the owner's wallet, not the amount they
 *   chose, not the slippage, not the signature, not that a review was even opened.
 * - **The provider is told what it needs to build a transaction and nothing else**: two mints, an
 *   amount, and the owner's public address. Not which publisher proposed it, not the proposal's ID,
 *   and not the signature afterwards.
 * - **Nothing goes out after the wallet.** The last request of the run is the provider's build;
 *   there is no result to deliver, because a broadcast owes nobody an answer (SEE-89).
 */
@RunWith(AndroidJUnit4::class)
class OperationPrivacyTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = MockWebServer()
    private val provider = MockWebServer()
    private val scheduler = TestCoroutineScheduler()
    private val clock = Instant.parse("2026-09-17T10:00:00Z")
    private val key: SecretKey = SecretKeySpec(ByteArray(32) { 5 }, "AES")
    private val owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val amount = 12_345_678UL
    private val slippage = 37
    private val signature = ByteString.copyFrom(ByteArray(64) { 11 })

    @Before
    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        gateway.start(InetAddress.getByName("127.0.0.1"), 0)
        provider.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        Dispatchers.resetMain()
        gateway.close()
        provider.close()
    }

    @Test
    fun theGatewayLearnsAChannelAndTheProviderLearnsOnlyWhatABuildNeeds() = runBlocking {
        val gatewayUrl = "http://127.0.0.1:${gateway.port}"
        val providerUrl = "http://127.0.0.1:${provider.port}"
        val connection =
            Connection(
                id = CONNECTION,
                label = "A trader",
                serverUrl = gatewayUrl,
                serverId = SERVER_B,
                deviceName = "",
                pairedAt = clock,
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server =
                    ServerRecord.Known(
                        ServerManifest(
                            serverId = SERVER_B,
                            protocolVersion = SERVER_PROTOCOL,
                            settingsRevision = 1,
                            mode = ConnectionMode.GatewayFeed,
                            reference = ServerReference.Feed(gatewayUrl, channelFor(SERVER_B)),
                            required = listOf(PluginRequirement(JUPITER_SWAP, 1..1)),
                            environments = setOf(PluginEnvironment.Production),
                        )
                    ),
            )
        // The gateway's real answer to a feed read: one proposal, on this publisher's channel.
        gateway.enqueue(
            MockResponse.Builder()
                .setHeader("content-type", "application/proto")
                .body(
                    Buffer()
                        .write(
                            listRequestsResponse {
                                snapshotSequence = 1
                                requests += proposal(swapProposal()).commonEnvelope()
                            }
                                .toByteArray()
                        )
                )
                .build()
        )
        // And the provider's: a quote, then a transaction built for this owner.
        val quoted = 987_654_321UL
        val minimumOut = quoted - quoted * slippage.toULong() / 10_000UL
        provider.enqueue(json(quoteBody(quoted, minimumOut)))
        provider.enqueue(json(swapBody(builtFor(owner))))

        val history = ActivityLog(ActivityStore(File(folder.root, "activity")), { clock })
        val http = ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
        val feeds = ConnectFeedGateway(http)
        val adapter = FakeWalletAdapter()
        val proposals =
            ProposalRepository(
                store = ProposalStore(File(folder.root, "proposals")),
                connections = { listOf(connection) },
                plugins = jupiter(providerUrl, clock),
                feed = feeds,
                history = history,
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        val wallet =
            WalletRepository(
                WalletStore(File(folder.root, "wallet"), File(folder.root, "nb")) { key },
                adapter,
                ConnectionRepository(
                    store = ConnectionStore(File(folder.root, "connections")),
                    vault = CredentialVault(File(folder.root, "credentials")) { key },
                    results = ResultStore(File(folder.root, "results")),
                    gateway = FakeConnectionGateway(),
                    history = history,
                    deviceName = "Seeker",
                    io = Dispatchers.Unconfined,
                ),
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        adapter.answerConnected(owner)
        adapter.sendWith(signature)
        wallet.load()
        wallet.connect(WalletNetwork.Mainnet)
        val policies = PolicyStore(File(folder.root, "policies"))
        val model =
            OperationViewModel(
                proposals = proposals,
                connections = MutableStateFlow(listOf(connection)),
                connectionsLoaded = MutableStateFlow(true),
                wallet = wallet,
                policies = PolicyEvaluator(policies, records = { history.records.value }),
                history = history,
                providers = jupiter(providerUrl, clock),
                now = { clock },
                io = Dispatchers.Unconfined,
            )

        // Every step is waited for rather than assumed, because here the calls are real: an HTTP
        // request to a real socket suspends, and a test that carried on regardless would be
        // asserting about traffic that had not happened yet.
        model.refresh(CONNECTION)
        await { model.state.value.records.isNotEmpty() }
        model.open(CONNECTION, PROPOSAL)
        model.choose(SwapParameterNames.INPUT_AMOUNT, ParameterValue.Amount(amount))
        model.choose(SwapParameterNames.SLIPPAGE_BPS, ParameterValue.Count(slippage.toUInt()))
        model.prepare()
        await { model.review.value?.prepared != null || model.review.value?.failure != null }
        assertEquals(
            "the review refused the provider's transaction: " +
                model.review.value?.inspection?.findings?.map { it.code } +
                " " +
                model.review.value?.failure?.code,
            true,
            model.review.value?.inspection?.approvable,
        )
        model.approve(wallet.wallet.value)
        await { adapter.sendings.isNotEmpty() }

        // It worked: the wallet signed exactly what was reviewed, and the record is local.
        assertEquals(1, adapter.sendings.size)
        val gatewayRequest = gateway.takeRequest().body
        assertEquals(
            ProposalOutcome.Submitted(signature),
            proposals.proposal(CONNECTION, PROPOSAL)?.execution?.outcome,
        )

        // Now the traffic. One request to the gateway, for the feed, and it holds exactly three
        // fields: the channel this phone subscribes to, where its last walk ended, and how big a
        // page it will take. Asserted as the decoded message rather than only as a text search,
        // because a number this app never wrote could still turn up in a protobuf body by
        // coincidence — and because this way a field *added* later has to be looked at here.
        val asked = ListRequestsRequest.parseFrom(checkNotNull(gatewayRequest).toByteArray())
        assertEquals(
            listRequestsRequest {
                this.channel = channelFor(SERVER_B)
                pageSize = asked.pageSize
            },
            asked,
        )
        val toGateway =
            listOf(
                "POST /seekervault.gateway.v1.FeedService/ListRequests? " +
                    (gatewayRequest?.utf8() ?: "")
            )
        val secrets =
            listOf(
                owner,
                amount.toString(),
                slippage.toString(),
                encodeBase58(signature.toByteArray()),
                Base64.getEncoder().encodeToString(signature.toByteArray()),
                quoted.toString(),
                minimumOut.toString(),
            )
        for (said in toGateway) {
            for (secret in secrets) {
                assertFalse("the gateway was told $secret: $said", said.contains(secret))
            }
        }
        // What it *was* told: the channel this phone subscribes to, and where the last walk ended.
        assertTrue(toGateway.single().contains(channelFor(SERVER_B)))

        // The provider was asked twice, and told the owner's address once — for the build, which
        // has to be built for the account that signs it.
        val toProvider = drain(provider)
        assertEquals(2, toProvider.size)
        assertFalse(toProvider[0].contains(owner))
        assertTrue(toProvider[0].contains(amount.toString()))
        assertTrue(toProvider[1].contains(owner))
        // And it was never told whose feed this came from, or which proposal it was.
        for (said in toProvider) {
            assertFalse(said.contains(SERVER_B))
            assertFalse(said.contains(PROPOSAL))
            assertFalse(said.contains(encodeBase58(signature.toByteArray())))
        }
        // Nothing went anywhere after the wallet: no result, no upload, no acknowledgement.
        assertEquals(0, gateway.requestCount - 1)
        assertEquals(0, provider.requestCount - 2)
    }

    /** Waits for something the app does on its own scope, or fails saying it never happened. */
    private suspend fun await(what: () -> Boolean) {
        withTimeout(20_000) {
            while (!what()) delay(10)
        }
    }

    /**
     * Every request a server received, as the text of its path, its query and its body.
     *
     * The host and port are deliberately left out: a test server's port is a random number, and a
     * search for a short one — a slippage in basis points, say — would find it there and call it a
     * leak. What is searched is what this app actually wrote.
     */
    private fun drain(server: MockWebServer): List<String> =
        (1..server.requestCount).map {
            val request = server.takeRequest()
            val url = checkNotNull(request.url)
            val body = request.body?.utf8().orEmpty()
            "${request.method} ${url.encodedPath}?${url.encodedQuery.orEmpty()} $body"
        }

    private fun json(body: String) =
        MockResponse.Builder().setHeader("content-type", "application/json").body(body).build()

    private fun quoteBody(outAmount: ULong, minimumOut: ULong) =
        """
        {"inputMint":"$USDC_MINT","outputMint":"$SOL_MINT","inAmount":"$amount",
         "outAmount":"$outAmount","otherAmountThreshold":"$minimumOut","swapMode":"ExactIn",
         "slippageBps":$slippage,"platformFee":null,"routePlan":[{"percent":100}]}
        """
            .trimIndent()

    private fun swapBody(transaction: String) =
        """
        {"swapTransaction":"$transaction","lastValidBlockHeight":1,"simulationError":null,
         "addressesByLookupTableAddress":null}
        """
            .trimIndent()

    /** A real swap transaction for this owner, of the shape the provider builds. */
    private fun builtFor(address: String): String {
        val terms =
            io.github.brrenat.seekervault.jupiter.usdcTerms(
                USDC_MINT,
                SOL_MINT,
                maxSlippageBps = 100,
            )
        val quote =
            io.github.brrenat.seekervault.jupiter.quoteFor(
                terms,
                amount,
                outAmount = 987_654_321UL,
                slippageBps = slippage,
            )
        return Base64.getEncoder()
            .encodeToString(
                io.github.brrenat.seekervault.jupiter
                    .swapTransaction(terms, amount, quote, owner = address)
                    .toByteArray()
            )
    }

    /**
     * The real bundled provider, pointed at the one local socket this test runs (SEE-145).
     *
     * It is the shipped `JupiterExecutionProvider`, not a stand-in: the point of this test is what
     * actually leaves the phone, so the code under test has to be the code that would.
     */
    private fun jupiter(providerUrl: String, clock: java.time.Instant) =
        ProviderRegistry.of(
            JupiterExecutionProvider(
                HttpJupiterProvider(OkHttpClient(), providerUrl),
                HttpJupiterPrediction(OkHttpClient(), providerUrl),
                OrderChain(),
            ) {
                clock
            }
        )
}
