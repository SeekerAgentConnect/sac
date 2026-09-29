package io.github.brrenat.seekervault.operations

import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FeedSnapshot
import io.github.brrenat.seekervault.connections.ProposalFeed
import io.github.brrenat.seekervault.connections.ProposalRepository
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.jupiter.EVENT_ID
import io.github.brrenat.seekervault.jupiter.FakePrediction
import io.github.brrenat.seekervault.jupiter.JupiterExecutionProvider
import io.github.brrenat.seekervault.jupiter.JupiterProvider
import io.github.brrenat.seekervault.jupiter.JupiterQuote
import io.github.brrenat.seekervault.jupiter.JupiterSwap
import io.github.brrenat.seekervault.jupiter.MARKET_ID
import io.github.brrenat.seekervault.jupiter.SOL_MINT
import io.github.brrenat.seekervault.jupiter.USDC_MINT
import io.github.brrenat.seekervault.jupiter.quoteFor
import io.github.brrenat.seekervault.jupiter.swapTransaction
import io.github.brrenat.seekervault.jupiter.tableFor
import io.github.brrenat.seekervault.jupiter.usdcTerms
import io.github.brrenat.seekervault.plugins.ExecutionProvider
import io.github.brrenat.seekervault.plugins.JUPITER_PREDICTION
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.actions.PredictionTermNames
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.plugins.actions.SwapTermNames
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposals.PREDICTION
import io.github.brrenat.seekervault.proposals.SWAP
import io.github.brrenat.seekervault.proposals.wireProposal
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.wallet.FakeConnectionWallets
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import io.github.brrenat.seekervault.wallet.WalletRepository
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import java.time.Instant
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One phone, assembled the way the app assembles it, for the operation tests (SEE-93).
 *
 * The plugin is the real one and the provider is the only thing stood in for: everything a test
 * here is about — the review, the binding, the wallet order, the record — is the real code. What a
 * provider would answer is the one thing a test has to be able to choose, and the one thing that
 * would otherwise need the internet.
 */
class Phone(
    root: File,
    /**
     * Providers registered beside the bundled one, for the tests that show a second provider
     * driving the same core flow (SEE-145). Empty everywhere else, which is what the app ships.
     */
    alternates: List<ExecutionProvider> = emptyList(),
    private val clock: () -> Instant,
) {
    private val key: SecretKey = SecretKeySpec(ByteArray(32) { 3 }, "AES")

    val feed = FakeFeed()
    val provider = FakeProvider()
    val adapter = FakeWalletAdapter()
    val history = ActivityLog(ActivityStore(File(root, "activity")), clock)
    val policies = PolicyStore(File(root, "policies"))
    val evaluator = PolicyEvaluator(policies, records = { history.records.value })
    /** The prediction API, and the chain its orders are resolved through (SEE-94). */
    val markets = FakePrediction()
    val chain = OrderChain()

    /** The real bundled provider, with only the two APIs it reaches stood in for (SEE-145). */
    val jupiter = JupiterExecutionProvider(provider, markets, chain, now = clock)
    val plugins: ProviderRegistry =
        ProviderRegistry.of(*(listOf(jupiter) + alternates).toTypedArray())

    var connection =
        Connection(
            id = CONNECTION,
            label = "A trader",
            serverUrl = GATEWAY,
            serverId = SERVER_B,
            deviceName = "",
            pairedAt = Instant.EPOCH,
            hasCredential = false,
            mode = ConnectionMode.GatewayFeed,
            // The publisher's manifest requires this build's plugins at the contract they declare,
            // which is what makes the server supported and its proposals executable (SEE-88).
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER_B,
                        protocolVersion = SERVER_PROTOCOL,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_B)),
                        required =
                            listOf(
                                PluginRequirement(JUPITER_SWAP, 1..1),
                                PluginRequirement(JUPITER_PREDICTION, 1..1),
                            ),
                        environments = setOf(PluginEnvironment.Production),
                        // Every network, so a test chooses the wallet's network and nothing else
                        // decides it (SEE-174).
                        supportedNetworks = WalletNetwork.entries.toSet(),
                    )
                ),
        )

    val connections = MutableStateFlow(listOf(connection))
    val loaded = MutableStateFlow(true)

    /**
     * Puts this phone's feed in [environment], the way the app gets there (SEE-97): the publisher
     * serves it, and the owner keeps it.
     *
     * Both halves on purpose. A connection in an environment its server does not serve is
     * unsupported and executes nothing, which is a different thing from a sandbox — so a test that
     * wants a rehearsal has to say both, exactly as a real deployment does.
     */
    fun keeps(
        environment: PluginEnvironment,
        served: Set<PluginEnvironment> = setOf(environment),
    ) {
        val manifest = checkNotNull(connection.server.manifest)
        connection =
            connection.copy(
                environment = environment,
                server = ServerRecord.Known(manifest.copy(environments = served)),
                // The owner's wallet binding is theirs, and moving the environment keeps it.
                walletProfileId = connections.value.firstOrNull()?.walletProfileId,
            )
        connections.value = listOf(connection)
    }

    val proposals =
        ProposalRepository(
            store = ProposalStore(File(root, "proposals")),
            connections = { connections.value },
            plugins = plugins,
            feed = feed,
            history = history,
            now = clock,
            io = Dispatchers.Unconfined,
        )

    // The wallet's own repository, over a fake wallet app and this phone's one feed. A feed has no
    // `PublishWallet`, and nothing here publishes an address anywhere: the feed's binding is only
    // which profile it signs with (SEE-174).
    val bindings = FakeConnectionWallets(connections)

    val wallet =
        WalletRepository(
            WalletStore(File(root, "wallet"), File(root, "no_backup/wallet")) { key },
            adapter,
            bindings,
            now = clock,
            io = Dispatchers.Unconfined,
        )

    /**
     * Connects a wallet on [network] and binds every feed on this phone to it, the way the owner
     * does when they add each feed (SEE-174). Returns the profile the feeds now sign with.
     */
    suspend fun connectWallet(network: WalletNetwork = WalletNetwork.Mainnet): WalletProfile {
        wallet.load()
        val profile = wallet.connectProfiles(network).profiles.single()
        connections.value.forEach { wallet.bind(it.id, profile.id) }
        connection = connections.value.first { it.id == connection.id }
        return profile
    }

    fun viewModel() =
        OperationViewModel(
            proposals = proposals,
            connections = connections,
            connectionsLoaded = loaded,
            wallet = wallet,
            policies = evaluator,
            history = history,
            providers = plugins,
            now = clock,
            io = Dispatchers.Unconfined,
        )
}

/** What a publisher broadcast, as a swap signal this plugin can read. */
fun swapProposal(
    proposalId: String = PROPOSAL,
    revision: Long = 1,
    at: Instant = Instant.parse("2026-09-17T09:00:00Z"),
    expiresAt: Instant = Instant.parse("2026-09-18T09:00:00Z"),
    note: String = "Rotating out of the stable leg.",
    inputMint: String = USDC_MINT,
    outputMint: String = SOL_MINT,
    maxSlippageBps: String = "100",
    extra: Map<String, String> = emptyMap(),
): WireProposal =
    wireProposal(
        serverId = SERVER_B,
        proposalId = proposalId,
        revision = revision,
        operation = SWAP,
        plugin = JUPITER_SWAP.value,
        createdAt = at,
        updatedAt = at,
        expiresAt = expiresAt,
        note = note,
        values =
            listOf(
                SwapTermNames.INPUT_MINT to inputMint,
                SwapTermNames.INPUT_DECIMALS to if (inputMint == SOL_MINT) "9" else "6",
                SwapTermNames.INPUT_SYMBOL to if (inputMint == SOL_MINT) "SOL" else "USDC",
                SwapTermNames.OUTPUT_MINT to outputMint,
                SwapTermNames.OUTPUT_DECIMALS to if (outputMint == SOL_MINT) "9" else "6",
                SwapTermNames.OUTPUT_SYMBOL to if (outputMint == SOL_MINT) "SOL" else "USDC",
                SwapTermNames.MAX_SLIPPAGE_BPS to maxSlippageBps,
            ) + extra.toList(),
    )

/** The gateway's answers, and every question this phone asked it. */
class FakeFeed : ProposalFeed {
    var answers: List<WireProposal> = emptyList()

    /**
     * What a particular channel answers, for the tests that hold two feeds at once.
     *
     * A proposal ID belongs to the publisher that minted it, so two feeds can hold the same one and
     * mean two different documents. A channel is what tells them apart here, exactly as it does on
     * the real gateway; anything not listed falls back to [answers].
     */
    var answersByChannel: Map<String, List<WireProposal>> = emptyMap()
    val asked = mutableListOf<Pair<FeedReference, Long>>()

    override suspend fun snapshot(reference: FeedReference, knownSequence: Long): FeedSnapshot {
        asked += reference to knownSequence
        return FeedSnapshot.Read(1L, answersByChannel[reference.channel] ?: answers)
    }
}

/** A provider that quotes honestly and builds the transaction its own quote describes. */
class FakeProvider : JupiterProvider {
    /** Everything it was asked, in order, so a test can assert what left the phone. */
    val asked = mutableListOf<String>()

    // Named apart from the two methods on purpose: a property called `quote` and a method called
    // `quote` are not the same thing, and calling one where the other was meant is a recursion
    // that looks like a hang.
    var answersQuote: (SwapPayload, ULong, Int) -> JupiterQuote = { terms, amount, slippage ->
        quoteFor(terms, amount, outAmount = amount * 10UL, slippageBps = slippage)
    }
    var answersBuild: (JupiterQuote, String) -> JupiterSwap = { quote, owner ->
        JupiterSwap(
            swapTransaction(
                terms = usdcTerms(quote.inputMint, quote.outputMint),
                amount = quote.inAmount,
                quote = quote,
                owner = owner,
            )
        )
    }

    override suspend fun quote(
        terms: SwapPayload,
        amount: ULong,
        slippageBps: Int,
        platformFeeBps: Int,
    ): JupiterQuote {
        asked +=
            "quote ${terms.inputMint}->${terms.outputMint} $amount @$slippageBps" +
                (if (platformFeeBps > 0) " fee $platformFeeBps" else "")
        return answersQuote(terms, amount, slippageBps)
    }

    override suspend fun build(
        quote: JupiterQuote,
        wallet: String,
        feeAccount: String?,
    ): JupiterSwap {
        asked += "build $wallet ${quote.inAmount}" + (feeAccount?.let { " fee $it" } ?: "")
        return answersBuild(quote, wallet)
    }
}

/** A market proposal, as a prediction publisher broadcasts one (SEE-94). */
fun predictionProposal(
    proposalId: String = PREDICTION_PROPOSAL,
    revision: Long = 1,
    at: Instant = Instant.parse("2026-09-17T09:00:00Z"),
    expiresAt: Instant = Instant.parse("2026-09-18T09:00:00Z"),
    note: String = "The market closes at the end of the season.",
    marketId: String = MARKET_ID,
    extra: Map<String, String> = emptyMap(),
): WireProposal =
    wireProposal(
        serverId = SERVER_B,
        proposalId = proposalId,
        revision = revision,
        operation = PREDICTION,
        plugin = JUPITER_PREDICTION.value,
        createdAt = at,
        updatedAt = at,
        expiresAt = expiresAt,
        note = note,
        values =
            listOf(
                PredictionTermNames.MARKET_ID to marketId,
                PredictionTermNames.EVENT_ID to EVENT_ID,
                PredictionTermNames.PROVIDER to "polymarket",
                PredictionTermNames.DEPOSIT_MINT to USDC_MINT,
                PredictionTermNames.DEPOSIT_DECIMALS to "6",
                PredictionTermNames.DEPOSIT_SYMBOL to "USDC",
            ) + extra.toList(),
    )

/**
 * A chain that resolves whatever the last order named, as a real one would.
 *
 * The tables come from the order the provider just built, which is what makes this a stand-in for
 * the chain rather than for the resolution: the addresses are the ones the message actually refers
 * to, and the resolver does the real work on them.
 */
class OrderChain : io.github.brrenat.seekervault.solana.SolanaAccounts {
    var tables: Map<String, List<String>> = emptyMap()
    var fails: io.github.brrenat.seekervault.solana.SolanaProblem? = null
    val asked = mutableListOf<List<String>>()

    override suspend fun accounts(
        addresses: List<String>
    ): List<io.github.brrenat.seekervault.solana.AccountSnapshot?> {
        asked += addresses
        fails?.let { throw io.github.brrenat.seekervault.solana.SolanaException(it) }
        return addresses.map { tables[it]?.let { held -> tableFor(held) } }
    }
}

const val CONNECTION = "b3f4b0f2-2a4e-4f45-9f3e-6b1c9a2d4e70"

const val PREDICTION_PROPOSAL = "2c4d6e80-9a1b-4c3d-8e5f-70819203a4b5"

const val PROPOSAL = "7a2c8b16-3f40-4b1e-9c2d-5e6f708192a3"
