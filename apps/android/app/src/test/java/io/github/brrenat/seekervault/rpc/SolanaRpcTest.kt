package io.github.brrenat.seekervault.rpc

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.config.ClientConfigRepository
import io.github.brrenat.seekervault.config.storage.ClientConfigStore
import io.github.brrenat.seekervault.config.wireConfig
import io.github.brrenat.seekervault.confirmations.GENESIS_HASHES
import io.github.brrenat.seekervault.confirmations.HttpChainReader
import io.github.brrenat.seekervault.jupiter.EVENT_ID
import io.github.brrenat.seekervault.jupiter.FEE_ACCOUNT_SOL
import io.github.brrenat.seekervault.jupiter.FEE_OWNER
import io.github.brrenat.seekervault.jupiter.FakeChain
import io.github.brrenat.seekervault.jupiter.FakePrediction
import io.github.brrenat.seekervault.jupiter.JupiterExecutionProvider
import io.github.brrenat.seekervault.jupiter.JupiterProvider
import io.github.brrenat.seekervault.jupiter.JupiterQuote
import io.github.brrenat.seekervault.jupiter.MARKET_ID
import io.github.brrenat.seekervault.jupiter.OWNER
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.SwapFee
import io.github.brrenat.seekervault.jupiter.SwapFeePolicy
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.jupiter.decideSwapFee
import io.github.brrenat.seekervault.jupiter.orderTransaction
import io.github.brrenat.seekervault.jupiter.predictionOrder
import io.github.brrenat.seekervault.jupiter.tokenAccount
import io.github.brrenat.seekervault.jupiter.wallet
import io.github.brrenat.seekervault.plugins.ActionOperation
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionOutcomes
import io.github.brrenat.seekervault.plugins.actions.PredictionParameterNames
import io.github.brrenat.seekervault.plugins.actions.PredictionPayloadResult
import io.github.brrenat.seekervault.plugins.actions.PredictionTermNames
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.predictionPayloadFrom
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.HttpSolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import java.io.File
import java.net.InetAddress
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The app's one Solana resolver against real JSON-RPC endpoints whose every answer the test
 * controls (SEE-184), fed by the trusted gateway's client configuration (SEE-185).
 *
 * Two local servers stand in for two clusters, each identified only the way a real one is — by its
 * genesis hash — and every read goes through the same HTTP clients the app uses. What is proven: a
 * mainnet read and a devnet read in flight together reach their own endpoints; an endpoint serving
 * another cluster is refused for account reads and for confirmation reads alike; a configuration
 * applied at runtime is used for the very next read and drops every proof about the old one, while
 * the same configuration again changes nothing; and only a debug build's devnet or testnet endpoint
 * may be a local validator.
 */
@RunWith(AndroidJUnit4::class)
class SolanaRpcTest {
    @get:Rule val folder = TemporaryFolder()

    private val mainnet = Cluster(Network.NETWORK_MAINNET)
    private val devnet = Cluster(Network.NETWORK_DEVNET)
    private val otherDevnet = Cluster(Network.NETWORK_DEVNET)
    private val http = OkHttpClient()

    @Before
    fun start() {
        listOf(mainnet, devnet, otherDevnet).forEach { it.start() }
    }

    @After
    fun stop() {
        listOf(mainnet, devnet, otherDevnet).forEach { it.server.close() }
    }

    private fun rpc(
        vararg endpoints: Pair<Network, Cluster>,
        allowLocalValidator: Boolean = false,
        changed: MutableList<Network>? = null,
    ): SolanaRpc =
        SolanaRpc(
                endpoints.associate { (network, cluster) -> network to cluster.url },
                allowLocalValidator,
                reader = { url -> HttpChainReader(http, url) },
                accounts = { url -> HttpSolanaAccounts(http, url) },
            )
            .also { rpc -> changed?.let { list -> rpc.onChange { list += it } } }

    private fun failure(block: suspend () -> Unit): SolanaException = runBlocking {
        try {
            withTimeout(30_000) { block() }
            throw AssertionError("it answered")
        } catch (e: SolanaException) {
            e
        }
    }

    @Test
    fun aMainnetAndADevnetOperationRunTogetherEachThroughItsOwnEndpoint() = runBlocking {
        mainnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER))
        devnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(USDC_MINT, FEE_OWNER))
        val rpc = rpc(MAINNET to mainnet, DEVNET to devnet)

        // Both in flight at once, as a mainnet feed and a devnet direct server would be.
        val (fromMainnet, fromDevnet, devnetStatus) =
            withTimeout(30_000) {
                listOf(
                        async { rpc.on(MAINNET).accounts(listOf(FEE_ACCOUNT_SOL)) },
                        async { rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL)) },
                        async { rpc.readerFor(DEVNET).statuses(listOf(SIGNATURE), false) },
                    )
                    .awaitAll()
            }

        assertEquals(tokenAccount(SOL_MINT, FEE_OWNER), (fromMainnet as List<*>).single())
        assertEquals(tokenAccount(USDC_MINT, FEE_OWNER), (fromDevnet as List<*>).single())
        assertEquals(1, (devnetStatus as List<*>).size)
        // Each endpoint was asked about its own network and nothing else.
        assertEquals(listOf("getMultipleAccounts"), mainnet.reads())
        assertEquals(
            setOf("getMultipleAccounts", "getSignatureStatuses"),
            devnet.reads().toSet(),
        )
        // And each proof is remembered: reads in flight together may each ask once, later ones
        // don't ask again.
        val proofs = mainnet.count("getGenesisHash") to devnet.count("getGenesisHash")
        rpc.on(MAINNET).accounts(listOf(FEE_ACCOUNT_SOL))
        rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL))
        assertEquals(proofs, mainnet.count("getGenesisHash") to devnet.count("getGenesisHash"))
    }

    @Test
    fun withOnlyAMainnetEndpointPredictionTablesResolveAndTheSwapFeeIsVerifiedThroughTheSameOne() {
        // Only mainnet is configured: a prediction resolves its tables through that endpoint.
        val rpc = rpc(MAINNET to mainnet)
        val prediction = FakePrediction()
        prediction.answersOrder = { terms, choice, owner ->
            val built =
                orderTransaction(
                    terms = terms,
                    yes = choice.yes,
                    deposit = choice.deposit,
                    owner = owner,
                )
            mainnet.source = FakeChain(built.tables)
            predictionOrder(built.transaction, yes = choice.yes, owner = owner)
        }
        val jupiter =
            JupiterExecutionProvider(NoSwaps, prediction, rpc) { Instant.ofEpochSecond(2_000) }

        val prepared = runBlocking {
            withTimeout(30_000) { jupiter.prepare(predictionSubject(), predictionChoice()) }
        }

        assertEquals(1, prepared.version)
        assertTrue(mainnet.count("getMultipleAccounts") >= 1)

        // The swap fee account is verified through the very same resolver and endpoint.
        mainnet.source = null
        mainnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER))
        val policy = SwapFeePolicy(20, FEE_OWNER, mapOf(SOL_MINT to FEE_ACCOUNT_SOL))
        val fee = runBlocking { decideSwapFee(policy, SOL_MINT, rpc.on(MAINNET)) }

        assertEquals(SwapFee.Charged(20, FEE_ACCOUNT_SOL, FEE_OWNER, SOL_MINT), fee)
        // Proven once for both: one endpoint, one network.
        assertEquals(1, mainnet.count("getGenesisHash"))
        // A network nobody configured has nothing to ask, and asks nobody.
        assertEquals(
            SolanaProblem.NoEndpoint,
            failure { rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL)) }.problem,
        )
        assertEquals(0, devnet.count())
    }

    @Test
    fun anEndpointServingAnotherClusterIsRefusedForAccountReadsAndForConfirmations() = runBlocking {
        // The configuration's devnet endpoint is a mainnet one.
        val rpc = rpc(DEVNET to mainnet)

        assertEquals(
            SolanaProblem.WrongNetwork,
            failure { rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL)) }.problem,
        )
        assertEquals(SolanaProblem.WrongNetwork, failure { rpc.readerFor(DEVNET) }.problem)
        // Nothing but the question of which chain it is was ever asked of it.
        assertEquals(emptyList<String>(), mainnet.reads())
        // And it is never used for the network it does serve, which nobody configured it for.
        assertEquals(SolanaProblem.NoEndpoint, failure { rpc.readerFor(MAINNET) }.problem)
    }

    @Test
    fun applyingChangedEndpointsDropsEveryProofAndTellsOnlyTheNetworksThatChanged() = runBlocking {
        val changed = mutableListOf<Network>()
        devnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER))
        otherDevnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(USDC_MINT, FEE_OWNER))
        val rpc = rpc(MAINNET to mainnet, DEVNET to devnet, changed = changed)
        rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL))
        rpc.readerFor(MAINNET)
        assertEquals(1, devnet.count("getGenesisHash"))
        assertEquals(1, mainnet.count("getGenesisHash"))

        // The same endpoints again: nothing changes, nobody is told, and every proof stands.
        rpc.apply(mapOf(MAINNET to mainnet.url, DEVNET to " ${devnet.url} "))
        assertEquals(emptyList<Network>(), changed)
        rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL))
        rpc.readerFor(MAINNET)
        assertEquals(1, devnet.count("getGenesisHash"))
        assertEquals(1, mainnet.count("getGenesisHash"))

        // The operator points devnet somewhere else. No restart, no rebuild.
        rpc.apply(mapOf(MAINNET to mainnet.url, DEVNET to otherDevnet.url))
        assertEquals(listOf(DEVNET), changed)
        assertEquals(otherDevnet.url, rpc.endpoint(DEVNET)?.url)
        assertEquals(mapOf(MAINNET to mainnet.url, DEVNET to otherDevnet.url), rpc.endpoints.value)
        assertEquals(
            tokenAccount(USDC_MINT, FEE_OWNER),
            rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL)).single(),
        )
        assertEquals(2, devnet.count("getMultipleAccounts"))
        assertEquals(1, otherDevnet.count("getMultipleAccounts"))
        // Every proof went, not only devnet's: mainnet is proven again before it is read again.
        rpc.readerFor(MAINNET)
        assertEquals(2, mainnet.count("getGenesisHash"))

        // A cluster whose endpoint now serves somebody else's chain is refused at once, even
        // though the same URL was proven a moment ago: a change drops every proof.
        otherDevnet.genesis = GENESIS_HASHES.getValue(MAINNET)
        rpc.apply(mapOf(MAINNET to mainnet.url, DEVNET to otherDevnet.url, TESTNET to devnet.url))
        assertEquals(listOf(DEVNET, TESTNET), changed)
        assertEquals(SolanaProblem.WrongNetwork, failure { rpc.readerFor(DEVNET) }.problem)

        // A network the configuration drops has no endpoint at all, and is told so.
        rpc.apply(mapOf(MAINNET to mainnet.url))
        assertEquals(listOf(DEVNET, TESTNET, DEVNET, TESTNET), changed)
        assertNull(rpc.endpoint(DEVNET))
        assertEquals(SolanaProblem.NoEndpoint, failure { rpc.readerFor(DEVNET) }.problem)
    }

    @Test
    fun theTrustedGatewaysConfigurationFeedsTheResolverAndAChangeAppliesWithoutARestart() =
        runBlocking {
            mainnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, FEE_OWNER))
            devnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(USDC_MINT, FEE_OWNER))
            otherDevnet.accounts = mapOf(FEE_ACCOUNT_SOL to tokenAccount(SOL_MINT, OWNER))
            var answer =
                wireConfig(
                    revision = 1,
                    rpc =
                        listOf(
                            SolanaNetwork.SOLANA_NETWORK_MAINNET to mainnet.url,
                            SolanaNetwork.SOLANA_NETWORK_DEVNET to devnet.url,
                        ),
                )
            val config =
                ClientConfigRepository(
                    gatewayUrl = "https://gateway.example.com",
                    store = ClientConfigStore(File(folder.root, "config")),
                    fetch = { answer },
                    allowCleartext = true,
                    io = Dispatchers.Unconfined,
                )
            config.refresh()
            // Wired the way the application wires it: built from what is held, then kept in step.
            val rpc =
                SolanaRpc(
                    initial = config.current?.rpc.orEmpty(),
                    allowLocalValidator = false,
                    reader = { url -> HttpChainReader(http, url) },
                    accounts = { url -> HttpSolanaAccounts(http, url) },
                )
            config.onChange { _, current -> rpc.apply(current.rpc) }

            // Two networks at once, each through its own endpoint.
            val (fromMainnet, fromDevnet) =
                withTimeout(30_000) {
                    listOf(
                            async { rpc.on(MAINNET).accounts(listOf(FEE_ACCOUNT_SOL)) },
                            async { rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL)) },
                        )
                        .awaitAll()
                }
            assertEquals(tokenAccount(SOL_MINT, FEE_OWNER), fromMainnet.single())
            assertEquals(tokenAccount(USDC_MINT, FEE_OWNER), fromDevnet.single())
            assertEquals(1, devnet.count("getGenesisHash"))

            // The operator moves devnet. The next refresh applies it in this process.
            answer =
                wireConfig(
                    revision = 2,
                    rpc =
                        listOf(
                            SolanaNetwork.SOLANA_NETWORK_MAINNET to mainnet.url,
                            SolanaNetwork.SOLANA_NETWORK_DEVNET to otherDevnet.url,
                        ),
                )
            config.refresh()
            assertEquals(otherDevnet.url, rpc.endpoint(DEVNET)?.url)
            assertEquals(
                tokenAccount(SOL_MINT, OWNER),
                rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL)).single(),
            )
            assertEquals(1, otherDevnet.count("getGenesisHash"))
            // The genesis cache went with the change: mainnet is proven again too.
            rpc.on(MAINNET).accounts(listOf(FEE_ACCOUNT_SOL))
            assertEquals(2, mainnet.count("getGenesisHash"))

            // An answer naming an endpoint that serves another cluster for devnet is applied —
            // it is a well-formed configuration — and refused by the proof, never used.
            answer =
                wireConfig(
                    revision = 3,
                    rpc =
                        listOf(
                            SolanaNetwork.SOLANA_NETWORK_MAINNET to mainnet.url,
                            SolanaNetwork.SOLANA_NETWORK_DEVNET to mainnet.url,
                        ),
                )
            config.refresh()
            assertEquals(
                SolanaProblem.WrongNetwork,
                failure { rpc.on(DEVNET).accounts(listOf(FEE_ACCOUNT_SOL)) }.problem,
            )
            assertEquals(2, mainnet.count("getMultipleAccounts"))
        }

    @Test
    fun aLocalValidatorIsAcceptedOnlyForADebugBuildsDevnetOrTestnet() = runBlocking {
        val validator = devnet.apply { genesis = "LocalValidatorGenesisHash1111111111111111111" }
        val debug = rpc(DEVNET to validator, MAINNET to validator, allowLocalValidator = true)
        debug.readerFor(DEVNET)
        assertEquals(true, debug.endpoint(DEVNET)?.allowUnknownGenesis)
        // Never mainnet's.
        assertEquals(false, debug.endpoint(MAINNET)?.allowUnknownGenesis)
        assertEquals(SolanaProblem.WrongNetwork, failure { debug.readerFor(MAINNET) }.problem)

        // And never in a release build.
        val release = rpc(DEVNET to validator)
        assertEquals(SolanaProblem.WrongNetwork, failure { release.readerFor(DEVNET) }.problem)
    }

    // ---

    /** A JSON-RPC endpoint that is one cluster, as far as anybody asking can tell. */
    private class Cluster(network: Network) : Dispatcher() {
        val server = MockWebServer()
        @Volatile var genesis: String = GENESIS_HASHES.getValue(network)
        @Volatile var accounts: Map<String, AccountSnapshot> = emptyMap()
        /** When set, accounts are answered by this fake instead of [accounts]. */
        @Volatile var source: SolanaAccounts? = null
        private val methods = CopyOnWriteArrayList<String>()

        val url: String
            get() = "http://127.0.0.1:${server.port}"

        val host = "127.0.0.1"

        fun start() {
            server.dispatcher = this
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        fun count(method: String? = null) = methods.count { method == null || it == method }

        /** Every call that was not the genesis question. */
        fun reads() = methods.filter { it != "getGenesisHash" }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val call = JSONObject(checkNotNull(request.body).utf8())
            val method = call.getString("method")
            methods += method
            val params = call.optJSONArray("params") ?: JSONArray()
            val result: Any =
                when (method) {
                    "getGenesisHash" -> genesis
                    "getMultipleAccounts" -> {
                        val asked = params.getJSONArray(0)
                        val addresses = (0 until asked.length()).map(asked::getString)
                        val held =
                            source?.let { runBlocking { it.accounts(addresses) } }
                                ?: addresses.map(accounts::get)
                        JSONObject()
                            .put("context", JSONObject().put("slot", 1))
                            .put(
                                "value",
                                JSONArray().apply {
                                    held.forEach { put(it?.json() ?: JSONObject.NULL) }
                                },
                            )
                    }
                    "getSignatureStatuses" -> {
                        val asked = params.getJSONArray(0)
                        JSONObject()
                            .put("context", JSONObject().put("slot", 1))
                            .put(
                                "value",
                                JSONArray().apply {
                                    repeat(asked.length()) {
                                        put(
                                            JSONObject()
                                                .put("slot", 5)
                                                .put("confirmations", JSONObject.NULL)
                                                .put("confirmationStatus", "finalized")
                                                .put("err", JSONObject.NULL)
                                        )
                                    }
                                },
                            )
                    }
                    else ->
                        return answer(
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", 1)
                                .put("error", JSONObject().put("code", -32601))
                        )
                }
            return answer(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", result))
        }

        private fun answer(body: JSONObject) =
            MockResponse.Builder()
                .code(200)
                .setHeader("content-type", "application/json")
                .body(body.toString())
                .build()

        private fun AccountSnapshot.json() =
            JSONObject()
                .put("owner", owner)
                .put(
                    "data",
                    JSONArray().put(Base64.getEncoder().encodeToString(data)).put("base64"),
                )
                .put("executable", executable)
                .put("lamports", 1)
    }

    /** A swap API that is never reached: a prediction order has nothing to swap. */
    private object NoSwaps : JupiterProvider {
        override suspend fun quote(
            terms: SwapPayload,
            amount: ULong,
            slippageBps: Int,
            platformFeeBps: Int,
        ) = throw AssertionError("a prediction asked for a swap quote")

        override suspend fun build(quote: JupiterQuote, wallet: String, feeAccount: String?) =
            throw AssertionError("a prediction asked for a swap build")
    }

    private fun predictionSubject() =
        ActionOperation(
            connectionId = "5b1f4b3a-6a5f-4f5c-9d6c-0f2f1e2d3c4b",
            action = PREDICTION_BUY_ACTION,
            schemaVersion = 1,
            provider = JUPITER_PROVIDER,
            environment = PluginEnvironment.Production,
            network = MAINNET,
            payload =
                ActionPayload.PredictionBuy(
                    (predictionPayloadFrom(
                            mapOf(
                                PredictionTermNames.MARKET_ID to MARKET_ID,
                                PredictionTermNames.EVENT_ID to EVENT_ID,
                                PredictionTermNames.PROVIDER to "polymarket",
                                PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
                                PredictionTermNames.DEPOSIT_DECIMALS to "6",
                                PredictionTermNames.DEPOSIT_SYMBOL to "USDC",
                            )
                        )
                            as PredictionPayloadResult.Valid)
                        .payload
                ),
            request = null,
            wallet = wallet(OWNER),
        )

    private fun predictionChoice() =
        ParameterChoice(
            mapOf(
                PredictionParameterNames.OUTCOME to ParameterValue.Selected(PredictionOutcomes.YES),
                PredictionParameterNames.DEPOSIT to ParameterValue.Amount(5_000_000UL),
            )
        )

    private companion object {
        val MAINNET = Network.NETWORK_MAINNET
        val DEVNET = Network.NETWORK_DEVNET
        val TESTNET = Network.NETWORK_TESTNET
        const val SIGNATURE =
            "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"
    }
}
