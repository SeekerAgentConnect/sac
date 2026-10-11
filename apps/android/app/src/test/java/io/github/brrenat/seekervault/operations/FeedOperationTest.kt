package io.github.brrenat.seekervault.operations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.config.ConfigAvailability
import io.github.brrenat.seekervault.config.ConfigProblem
import io.github.brrenat.seekervault.config.ZERO_CONFIG
import io.github.brrenat.seekervault.config.feeConfig
import io.github.brrenat.seekervault.connections.ExecutionOutcome
import io.github.brrenat.seekervault.plugins.ActionOperation
import io.github.brrenat.seekervault.plugins.ExecutionProvider
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PreparedOperation
import io.github.brrenat.seekervault.plugins.ServiceFeeTerms
import io.github.brrenat.seekervault.plugins.TestExecutionProvider
import io.github.brrenat.seekervault.plugins.actions.SwapParameterNames
import io.github.brrenat.seekervault.plugins.testAction
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.NetworkReason
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.solana.AccountSnapshot
import io.github.brrenat.seekervault.solana.NetworkAccounts
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * A feed operation after SEE-185: the network is the signal's, the wallet is the one chosen for
 * that network, the endpoint is the gateway's for that network, the fee terms are the gateway's for
 * that network, and preview or execution is chosen when the owner acts.
 *
 * Everything is the real code except the provider and the wallet app, exactly as in
 * [OperationViewModelTest]; the client configuration is set by hand ([Phone.config]).
 */
@RunWith(AndroidJUnit4::class)
class FeedOperationTest {
    @get:Rule val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun resetMain() = Dispatchers.resetMain()

    private val clock = Instant.parse("2026-09-17T10:00:00Z")

    private fun phone(alternates: List<ExecutionProvider> = emptyList()) =
        Phone(File(folder.root, "phone"), alternates) { clock }

    /** Connects one wallet on [network], the owner's only profile there. */
    private suspend fun Phone.connect(address: String, network: WalletNetwork): WalletProfile {
        wallet.load()
        adapter.answerConnected(address, chains = listOf(network.chain))
        return wallet.connectProfiles(network).profiles.single()
    }

    private fun Phone.opened(proposalId: String = PROPOSAL): OperationViewModel = runBlocking {
        val model = viewModel()
        model.refresh(CONNECTION)
        model.open(CONNECTION, proposalId)
        model
    }

    private fun OperationViewModel.amount(baseUnits: ULong) =
        choose(SwapParameterNames.INPUT_AMOUNT, ParameterValue.Amount(baseUnits))

    /** What the alternate provider's form asks for: an amount and a side. */
    private fun OperationViewModel.alternateChoice(baseUnits: ULong) {
        amount(baseUnits)
        choose(ParameterKey("outcome"), ParameterValue.Selected(ParameterKey("yes")))
    }

    private val nothingSent = emptyList<Triple<ByteString, SelectedWallet, String>>()

    // --- Two networks from one feed --------------------------------------------------------------

    @Test
    fun aMainnetAndADevnetSignalFromOneFeedEachUseTheirOwnNetworksWalletAndEndpoint() =
        runBlocking {
            val chains = RecordingChains()
            val provider = NetworkProvider(chains)
            val phone = phone(listOf(provider)).requiring(EXAMPLE_PLUGIN)
            phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
            phone.connect(DEVNET_OWNER, WalletNetwork.Devnet)
            phone.feed.answers =
                listOf(
                    swapProposal(proposalId = PROPOSAL).withPlugin(EXAMPLE_PLUGIN),
                    swapProposal(proposalId = DEVNET_PROPOSAL).withPlugin(EXAMPLE_PLUGIN),
                )
            phone.feed.networks = mapOf(DEVNET_PROPOSAL to Network.NETWORK_DEVNET)

            val model = phone.opened(PROPOSAL)
            val mainnet = checkNotNull(model.review.value)
            assertEquals(Network.NETWORK_MAINNET, mainnet.network)
            assertEquals(MAINNET_OWNER, mainnet.wallet?.address)
            model.alternateChoice(1_000_000UL)
            model.prepare()
            assertEquals(MAINNET_OWNER, checkNotNull(model.review.value).preparedFor?.address)

            model.open(CONNECTION, DEVNET_PROPOSAL)
            val devnet = checkNotNull(model.review.value)
            assertEquals(Network.NETWORK_DEVNET, devnet.network)
            assertEquals(DEVNET_OWNER, devnet.wallet?.address)
            model.alternateChoice(2_000_000UL)
            model.prepare()
            assertEquals(DEVNET_OWNER, checkNotNull(model.review.value).preparedFor?.address)

            // Each operation was handed its signal's network and the wallet chosen for it, and its
            // accounts were read through that network's endpoint — never the other one's.
            assertEquals(
                listOf(
                    Network.NETWORK_MAINNET to MAINNET_OWNER,
                    Network.NETWORK_DEVNET to DEVNET_OWNER,
                ),
                provider.prepared.map { it.network to it.wallet?.address },
            )
            assertEquals(
                listOf(
                    Network.NETWORK_MAINNET to MAINNET_OWNER,
                    Network.NETWORK_DEVNET to DEVNET_OWNER,
                ),
                chains.reads,
            )
        }

    @Test
    fun aSignalThatDeclaresNoNetworkIsReadableAndCannotBePrepared() = runBlocking {
        val phone = phone()
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.feed.answers = listOf(swapProposal())
        // The legacy document, as a contract-1 publisher sent it: no network at all.
        phone.feed.network = null

        val model = phone.opened()
        val review = checkNotNull(model.review.value)

        assertEquals(ProposalStanding.NetworkUnusable(NetworkReason.Missing), review.standing)
        assertNull(review.network)
        // No wallet is chosen for a network nobody published, even though one is connected.
        assertNull(review.wallet)
        model.amount(1_000_000UL)
        model.prepare()
        assertNull(checkNotNull(model.review.value).prepared)
        assertEquals(emptyList<String>(), phone.provider.asked)
        assertEquals(0, phone.config.asked)
    }

    // --- Preview, then execute -------------------------------------------------------------------

    @Test
    fun aPreviewRecordsItselfKeepsTheSignalOpenAndExecutingNeedsAFreshPreparation() = runBlocking {
        val phone = phone()
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.feed.answers = listOf(swapProposal())
        phone.adapter.sendWith(SIGNATURE)
        val model = phone.opened()
        assertTrue(checkNotNull(model.review.value).previewable)
        model.amount(1_000_000UL)
        model.prepare()
        assertNotNull(checkNotNull(model.review.value).prepared)

        model.preview(phone.operationWallet())

        // Recorded beside the proposal, never as its execution: nothing signed, nothing sent,
        // and the signal is still the owner's to execute.
        val previewed = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL))
        assertNull(previewed.execution)
        assertEquals(PluginEnvironment.Sandbox, previewed.preview?.binding?.environment)
        assertEquals(MAINNET_OWNER, previewed.preview?.binding?.wallet)
        assertEquals(ProposalStanding.Open, phone.proposals.standing(previewed))
        assertEquals(nothingSent, phone.adapter.sendings)
        assertEquals(nothingSent, phone.adapter.signings)
        // What was prepared is gone: the preview used it up.
        val after = checkNotNull(model.review.value)
        assertTrue(after.previewed)
        assertNull(after.prepared)
        assertNull(after.inspection)
        assertNull(after.preparedFee)

        // Approving now has nothing to approve.
        model.approve(phone.operationWallet())
        assertEquals(nothingSent, phone.adapter.sendings)

        // A fresh preparation, and then the real thing, once.
        model.prepare()
        assertFalse(checkNotNull(model.review.value).previewed)
        model.approve(phone.operationWallet())
        assertEquals(1, phone.adapter.sendings.size)
        val executed = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL))
        assertEquals(PluginEnvironment.Production, executed.execution?.binding?.environment)
        assertEquals(
            phone.operationWallet()?.profileId,
            executed.execution?.binding?.walletProfileId,
        )
        assertTrue(executed.execution?.outcome is ProposalOutcome.Submitted)
        assertTrue(phone.proposals.standing(executed) is ProposalStanding.Executed)
        // The preview stays in the record, as what happened before.
        assertNotNull(executed.preview)

        // A second approval of the same bytes is refused at the gate and reaches no wallet.
        model.approve(phone.operationWallet())
        assertEquals(
            OperationProblem.Binding(BindingProblem.AlreadyExecuted),
            checkNotNull(model.review.value).problem,
        )
        assertEquals(1, phone.adapter.sendings.size)
        // And so is one racing in from anywhere else with the binding that was executed, or a
        // preview of it after the fact.
        val binding = checkNotNull(executed.execution).binding
        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.AlreadyExecuted),
            phone.proposals.beginExecution(
                CONNECTION,
                PROPOSAL,
                binding,
                phone.operationWallet(),
            ),
        )
        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.AlreadyExecuted),
            phone.proposals.recordPreview(
                CONNECTION,
                PROPOSAL,
                binding.copy(environment = PluginEnvironment.Sandbox),
                phone.operationWallet(),
            ),
        )
    }

    @Test
    fun aPreviewIsNeverABeginningOfTheRealThing() = runBlocking {
        // The gate itself refuses a preview binding as an execution, whoever asks.
        val phone = phone()
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.feed.answers = listOf(swapProposal())
        val model = phone.opened()
        model.amount(1_000_000UL)
        model.prepare()
        model.preview(phone.operationWallet())
        val preview = checkNotNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.preview)

        assertEquals(
            ExecutionOutcome.Refused(BindingProblem.NothingPrepared),
            phone.proposals.beginExecution(
                CONNECTION,
                PROPOSAL,
                preview.binding,
                phone.operationWallet(),
            ),
        )
        assertNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution)
    }

    // --- The wallet chosen for the network -------------------------------------------------------

    @Test
    fun aWalletChosenBetweenPreparingAndApprovingSignsNothing() = runBlocking {
        val phone = phone()
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.feed.answers = listOf(swapProposal())
        phone.adapter.sendWith(SIGNATURE)
        val model = phone.opened()
        model.amount(1_000_000UL)
        model.prepare()
        val reviewed = checkNotNull(model.review.value).wallet
        assertNotNull(checkNotNull(model.review.value).prepared)

        val other = phone.connect(OTHER_OWNER, WalletNetwork.Mainnet)
        assertTrue(phone.wallet.chooseFeedWallet(CONNECTION, Network.NETWORK_MAINNET, other.id))

        val after = checkNotNull(model.review.value)
        assertEquals(OperationProblem.WalletChanged, after.problem)
        assertNull(after.prepared)
        assertEquals(OTHER_OWNER, after.wallet?.address)
        model.approve(reviewed)
        model.approve(after.wallet)
        assertEquals(nothingSent, phone.adapter.sendings)
        assertNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution)
    }

    // --- The gateway's configuration -------------------------------------------------------------

    @Test
    fun withoutAUsableConfigurationNothingIsPreparedAndTheFailureSaysWhy() = runBlocking {
        val phone = phone()
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.feed.answers = listOf(swapProposal())
        phone.config.set(ConfigAvailability.Unavailable(ConfigProblem.Unreachable("offline")))
        val model = phone.opened()
        model.amount(1_000_000UL)

        model.prepare()

        val review = checkNotNull(model.review.value)
        assertEquals("client_config_unavailable", review.failure?.code)
        assertNull(review.prepared)
        assertNull(review.preparedFee)
        // Asked once, and nothing went further: no quote, no build, no review written.
        assertEquals(1, phone.config.asked)
        assertEquals(emptyList<String>(), phone.provider.asked)
        assertNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.review)
    }

    @Test
    fun feeTermsThatMoveAfterPreparingDropThePreparationAndSignNothing() = runBlocking {
        val phone = phone()
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.feed.answers = listOf(swapProposal())
        phone.adapter.sendWith(SIGNATURE)
        val model = phone.opened()
        model.amount(1_000_000UL)
        model.prepare()
        assertEquals(ServiceFeeTerms.None, checkNotNull(model.review.value).preparedFee)
        val wallet = phone.operationWallet()

        // The operator configures a fee while the owner is reading.
        phone.config.withFee(feeConfig(bps = 20))

        val after = checkNotNull(model.review.value)
        assertEquals(OperationProblem.FeeTermsChanged, after.problem)
        assertNull(after.prepared)
        model.approve(wallet)
        assertEquals(nothingSent, phone.adapter.sendings)

        // Prepared again under the new terms, it goes through, carrying them.
        model.prepare()
        assertEquals(
            feeConfig(bps = 20).termsFor(Network.NETWORK_MAINNET),
            checkNotNull(model.review.value).preparedFee,
        )
        model.approve(wallet)
        assertEquals(1, phone.adapter.sendings.size)
    }

    @Test
    fun feeTermsThatMoveWhileTheApprovalIsUnderWayAreCaughtAtTheGate() = runBlocking {
        // A change the screen has not heard about yet: only what an approval re-reads moved.
        val phone = phone()
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.feed.answers = listOf(swapProposal())
        phone.adapter.sendWith(SIGNATURE)
        val model = phone.opened()
        model.amount(1_000_000UL)
        model.prepare()
        assertNotNull(checkNotNull(model.review.value).prepared)

        phone.config.holding =
            ConfigAvailability.Usable(
                ZERO_CONFIG.copy(swapFee = feeConfig(bps = 30)),
                cached = true,
            )
        model.approve(phone.operationWallet())
        assertEquals(OperationProblem.FeeTermsChanged, checkNotNull(model.review.value).problem)

        // And a configuration that is no longer usable at all stops it too, saying why.
        phone.config.holding = ConfigAvailability.Unavailable(ConfigProblem.Unreachable())
        model.approve(phone.operationWallet())
        assertEquals(
            OperationProblem.ConfigUnavailable(ConfigProblem.Unreachable()),
            checkNotNull(model.review.value).problem,
        )
        assertEquals(nothingSent, phone.adapter.sendings)
        assertNull(phone.proposals.proposal(CONNECTION, PROPOSAL)?.execution)
    }

    @Test
    fun aFeedSwapCarriesTheGatewaysFeeOnItsNetworkAndNoneOnAnother() = runBlocking {
        val chains = RecordingChains()
        val provider = NetworkProvider(chains)
        val phone = phone(listOf(provider)).requiring(EXAMPLE_PLUGIN)
        phone.connect(MAINNET_OWNER, WalletNetwork.Mainnet)
        phone.connect(DEVNET_OWNER, WalletNetwork.Devnet)
        phone.feed.answers =
            listOf(
                swapProposal(proposalId = PROPOSAL).withPlugin(EXAMPLE_PLUGIN),
                swapProposal(proposalId = DEVNET_PROPOSAL).withPlugin(EXAMPLE_PLUGIN),
            )
        phone.feed.networks = mapOf(DEVNET_PROPOSAL to Network.NETWORK_DEVNET)
        phone.config.withFee(feeConfig(bps = 20, network = Network.NETWORK_MAINNET))

        val model = phone.opened(PROPOSAL)
        model.alternateChoice(1_000_000UL)
        model.prepare()
        model.open(CONNECTION, DEVNET_PROPOSAL)
        model.alternateChoice(1_000_000UL)
        model.prepare()

        val (onMainnet, onDevnet) = provider.prepared
        assertEquals(Network.NETWORK_MAINNET, onMainnet.network)
        assertTrue(onMainnet.serviceFee.charges)
        assertEquals(20, onMainnet.serviceFee.bps)
        assertEquals(
            feeConfig(bps = 20).termsFor(Network.NETWORK_MAINNET),
            onMainnet.serviceFee,
        )
        // The same configuration, a devnet signal: no fee at all.
        assertEquals(Network.NETWORK_DEVNET, onDevnet.network)
        assertEquals(ServiceFeeTerms.None, onDevnet.serviceFee)
        assertEquals(ServiceFeeTerms.None, checkNotNull(model.review.value).preparedFee)
    }

    // --- Fixtures --------------------------------------------------------------------------------

    /** The feed requires [plugin], the name the alternate provider answers to. */
    private fun Phone.requiring(plugin: PluginId): Phone {
        connection =
            connection.copy(
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
                                    PluginRequirement(plugin, PROVIDER_CONTRACT..PROVIDER_CONTRACT)
                                ),
                            supportedNetworks = WalletNetwork.entries.toSet(),
                        )
                    )
            )
        connections.value = listOf(connection)
        return this
    }

    private fun io.github.brrenat.seekervault.proposal.v1.Proposal.withPlugin(plugin: PluginId) =
        toBuilder().setPluginId(plugin.value).build()

    /** One chain per network, each recording who read what through it. */
    private class RecordingChains : NetworkAccounts {
        val reads = mutableListOf<Pair<Network, String>>()

        override fun on(network: Network): SolanaAccounts =
            object : SolanaAccounts {
                override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
                    addresses.forEach { reads += network to it }
                    return addresses.map { null }
                }
            }
    }

    /**
     * A provider serving `swap` on mainnet and devnet, which reads the signer's account through the
     * operation's own network before it prepares — as a real one reads lookup tables or a fee
     * account — and keeps every operation it was handed.
     */
    private class NetworkProvider(
        private val chains: NetworkAccounts,
        private val inner: TestExecutionProvider =
            TestExecutionProvider(
                id = "example",
                actions =
                    listOf(
                        testAction(
                            networks = setOf(Network.NETWORK_MAINNET, Network.NETWORK_DEVNET)
                        )
                    ),
                environments = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
                legacyPlugins = setOf(EXAMPLE_PLUGIN),
                form = TestExecutionProvider.form(),
            ),
    ) : ExecutionProvider by inner {
        val prepared = mutableListOf<ActionOperation>()

        override suspend fun prepare(
            operation: ActionOperation,
            choice: ParameterChoice,
        ): PreparedOperation {
            prepared += operation
            chains.on(operation.network).accounts(listOf(checkNotNull(operation.wallet).address))
            return inner.prepare(operation, choice)
        }
    }

    private companion object {
        val EXAMPLE_PLUGIN = PluginId("example.swap")
        const val DEVNET_PROPOSAL = "5d6e7f80-1a2b-4c3d-9e4f-a0b1c2d3e4f5"
        const val MAINNET_OWNER = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        const val DEVNET_OWNER = "7EqQdEULxWcraVx3mXKFjc84LhCkMGZCkRuDpvcMwJeK"
        const val OTHER_OWNER = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        val SIGNATURE: ByteString = ByteString.copyFrom(ByteArray(64) { 5 })
    }
}
