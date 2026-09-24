package io.github.brrenat.seekervault.proposals

import io.github.brrenat.seekervault.plugins.ExecutionProviderId
import io.github.brrenat.seekervault.plugins.JUPITER_PREDICTION
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.TestExecutionProvider
import io.github.brrenat.seekervault.plugins.UnsupportedReason
import io.github.brrenat.seekervault.plugins.actions.Instrument
import io.github.brrenat.seekervault.plugins.jupiterLike
import io.github.brrenat.seekervault.proposal.v1.ProposalStatus as WireStatus
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What has to hold before a proposal's operation reaches a wallet (SEE-89).
 *
 * A proposal is common to everyone who received it, so what an owner executes is never "the
 * proposal": it is the terms as they stood, the parameters this owner chose, the wallet they had
 * selected, the plugin this build carries, and one particular set of bytes. Each case here changes
 * exactly one of those under the binding and checks that it is caught — because any of them moving
 * means the owner would be signing something other than what they reviewed.
 *
 * None of these is a warning to be overruled: an operation that isn't executable has no wallet
 * interaction waiting behind a second tap.
 */
class ProposalBindingTest {
    private val proposal = proposal()
    private val chose = choice(1_000_000u)

    @Test
    fun aReviewedProposalWithAMatchingBindingIsAllowed() {
        assertNull(problem(reviewed(), binding(proposal, chose)))
    }

    @Test
    fun aProposalThisDeviceAlreadyExecutedIsNeverExecutedAgain() {
        // Not once more, and not after a failure: a proposal that has already put an operation to
        // this owner's wallet must not become spendable again.
        val executed =
            reviewed()
                .copy(
                    execution =
                        ProposalExecution(
                            binding = binding(proposal, chose),
                            startedAt = NOW,
                            outcome = ProposalOutcome.Failed("the wallet refused"),
                            settledAt = NOW,
                        )
                )

        assertEquals(
            BindingProblem.AlreadyExecuted,
            problem(executed, binding(proposal, chose)),
        )
    }

    @Test
    fun theStandingIsTheGate() {
        // Whatever the owner is shown is what the gate allows, because the gate asks for the
        // standing rather than deciding again.
        assertEquals(
            BindingProblem.Dismissed,
            problem(
                reviewed().copy(dismissed = ProposalDismissal(1, NOW)),
                binding(proposal, chose),
            ),
        )
        assertEquals(
            BindingProblem.ProposalRefused,
            problem(
                reviewed().copy(refused = ProposalProblem.ChangedWithoutRevision),
                binding(proposal, chose),
            ),
        )
        assertEquals(
            BindingProblem.ProposalCancelled,
            problem(
                reviewed(status = WireStatus.PROPOSAL_STATUS_CANCELLED),
                binding(proposal, chose),
            ),
        )
        assertEquals(
            BindingProblem.ProposalExpired,
            problem(reviewed(), binding(proposal, chose), at = proposal.expiresAt),
        )
        assertEquals(
            BindingProblem.ServerUnsupported,
            problem(
                reviewed(),
                binding(proposal, chose),
                support = ServerSupport.PluginMissing(listOf(PluginId(SWAP_PLUGIN))),
            ),
        )
    }

    @Test
    fun nothingIsExecutedWithoutAReviewOnThisDevice() {
        assertEquals(
            BindingProblem.NotReviewed,
            problem(record(), binding(proposal, chose)),
        )
    }

    @Test
    fun termsThatMovedNeedReviewingAgain() {
        // The publisher's revision is the one thing both halves have to agree about: a binding for
        // another revision, and a review of another revision, are both the owner's answer to terms
        // that are no longer the terms.
        assertEquals(
            BindingProblem.ProposalChanged,
            problem(reviewed(), binding(proposal, chose).copy(revision = 2)),
        )
        val reviewedOlder =
            record().copy(review = ProposalReview(revision = 0, choice = chose, at = NOW))
        assertEquals(
            BindingProblem.ProposalChanged,
            problem(reviewedOlder, binding(proposal, chose)),
        )
    }

    @Test
    fun aQuantityTheOwnerDidNotReviewIsNotSigned() {
        // The whole point of keeping the choice: an amount changed after the review — by a fresh
        // quote, or by anything else — is a different operation.
        assertEquals(
            BindingProblem.ChoiceChanged,
            problem(reviewed(), binding(proposal, choice(2_000_000u))),
        )
    }

    @Test
    fun bytesFromAnotherProviderActionSchemaOrContractAreRefused() {
        // SEE-145: who prepared it, what they prepared, and at which version of the action, are
        // each bound and each refused by name. A provider is a venue, not an implementation
        // detail, so bytes from another one are not a smaller kind of the same operation.
        assertEquals(
            BindingProblem.OtherProvider,
            problem(reviewed(), binding(proposal, chose, provider = ExecutionProviderId("other"))),
        )
        assertEquals(
            BindingProblem.OtherAction,
            problem(reviewed(), binding(proposal, chose, action = PREDICTION_BUY_ACTION)),
        )
        assertEquals(
            BindingProblem.OtherSchema,
            problem(reviewed(), binding(proposal, chose, schemaVersion = 2)),
        )
        assertEquals(
            BindingProblem.OtherContract,
            problem(reviewed(), binding(proposal, chose, contract = PROVIDER_CONTRACT + 1)),
        )
    }

    @Test
    fun aBuildThatNoLongerCarriesTheProviderRefusesWhatItOnceBound() {
        // The provider is asked for at the moment of acting, not taken from the binding: an app
        // updated between the review and the wallet is an app that must not sign for a venue it
        // no longer carries (SEE-145).
        assertEquals(
            BindingProblem.OtherProvider,
            problem(reviewed(), binding(proposal, chose), serving = null),
        )
    }

    @Test
    fun bytesPreparedForAnotherInstrumentAreRefused() {
        // The pair, or the market, that was actually reviewed. A binding that names a different one
        // is an owner about to buy something they did not look at (SEE-145).
        assertEquals(
            BindingProblem.OtherInstrument,
            problem(
                reviewed(),
                binding(proposal, chose, instrument = Instrument("", "$TEST_SOL/$TEST_USDC")),
            ),
        )
    }

    @Test
    fun termsThatCanNoLongerBeReadAsTheirActionBindToNothing() {
        val unreadable =
            proposal.copy(values = listOf(ProposalValue("published_price", "139420000")))

        assertEquals(
            BindingProblem.UnreadableTerms,
            problem(reviewed().copy(proposal = unreadable), binding(proposal, chose)),
        )
    }

    @Test
    fun thereHasToBeAPreparationToBindTo() {
        assertEquals(
            BindingProblem.NothingPrepared,
            problem(reviewed(), binding(proposal, chose, preparedVersion = 0)),
        )
        assertEquals(
            BindingProblem.NothingPrepared,
            problem(
                reviewed(),
                binding(proposal, chose, contentHash = hash(1).substring(0, 16)),
            ),
        )
    }

    @Test
    fun theWalletSelectedNowHasToBeTheOneBound() {
        assertEquals(
            BindingProblem.NoWallet,
            problem(reviewed(), binding(proposal, chose), wallet = null),
        )
        assertEquals(
            BindingProblem.OtherWallet,
            problem(reviewed(), binding(proposal, chose, wallet = OTHER_WALLET)),
        )
        // The cluster is core's, from the owner's own selection, and never the publisher's claim.
        assertEquals(
            BindingProblem.OtherNetwork,
            problem(reviewed(), binding(proposal, chose, network = Network.NETWORK_DEVNET)),
        )
    }

    @Test
    fun thePromiseBoundHasToBeTheOneTheConnectionKeepsNow() {
        // SEE-97. The owner switched the connection while this was in hand, so what they reviewed
        // was a rehearsal and what they would be doing is real, or the other way round. Either way
        // it is refused on the far side of the wait for the wallet, where the expiry and the wallet
        // selection are also checked.
        assertEquals(
            BindingProblem.OtherEnvironment,
            problem(
                reviewed(),
                binding(proposal, chose, environment = PluginEnvironment.Sandbox),
            ),
        )
        assertEquals(
            BindingProblem.OtherEnvironment,
            problem(
                reviewed(),
                binding(proposal, chose),
                environment = PluginEnvironment.Sandbox,
            ),
        )
        // And it is asked before anything about the terms: which of the two things the owner is
        // doing is not a question about what they chose.
        assertEquals(
            BindingProblem.OtherEnvironment,
            problem(
                record(),
                binding(proposal, chose, environment = PluginEnvironment.Sandbox),
            ),
        )
        assertNull(
            problem(
                reviewed(),
                binding(proposal, chose, environment = PluginEnvironment.Sandbox),
                environment = PluginEnvironment.Sandbox,
            )
        )
    }

    @Test
    fun aPreparationIsCheckedForFreshnessOnTheFarSideOfTheWait() {
        val deadline = NOW.plusSeconds(30)
        val bound = binding(proposal, chose, expiresAtEpochSeconds = deadline.epochSecond)

        assertNull(problem(reviewed(), bound))
        assertEquals(
            BindingProblem.PreparationExpired,
            problem(reviewed(), bound, at = deadline),
        )
    }

    @Test
    fun thisBuildUsesTheProviderTheDocumentNamesAndNoOther() {
        // A document cannot select code. The publisher names an execution provider — here through
        // the legacy plugin name it was written for — and that exact one is asked for (SEE-145).
        assertEquals(
            ProposalProvider.Serving(JUPITER_PROVIDER),
            proposalProvider(proposal, registry(jupiterLike()), MAINNET, PRODUCTION),
        )
        // A build carrying a different provider for the same action serves nothing here: there is
        // no routing and no substitution.
        assertEquals(
            ProposalProvider.Unserved(UnsupportedReason.NoProvider),
            proposalProvider(
                proposal,
                registry(TestExecutionProvider(id = "other")),
                MAINNET,
                PRODUCTION,
            ),
        )
    }

    @Test
    fun aLegacyNameCrossedWithAnotherActionIsRefusedBeforeAnythingIsPrepared() {
        // The build this document was written for refused it: the operation resolved to
        // `jupiter.swap`, that was not the plugin claimed, and it was another plugin's proposal.
        // The refusal survives SEE-145. It is told apart from a missing provider on purpose —
        // nothing is missing here. Jupiter is carried, it does swaps, and it answers to both
        // names, exactly as the shipped provider does; what is wrong is the document (SEE-145).
        val jupiter = jupiterLike(legacyPlugins = setOf(JUPITER_SWAP, JUPITER_PREDICTION))
        val crossed = proposal(wireProposal(operation = SWAP, plugin = JUPITER_PREDICTION.value))

        assertEquals(
            ProposalProvider.Unserved(UnsupportedReason.NameMismatch),
            proposalProvider(crossed, registry(jupiter), MAINNET, PRODUCTION),
        )
        // Nothing downstream is handed a provider for it either, so a binding cannot be made.
        assertNull(namedProvider(crossed, registry(jupiter)))

        // The other direction, where the action is one Jupiter also serves: still the document's
        // contradiction and not the action's, so the same answer rather than an unsupported one.
        val alsoCrossed =
            proposal(wireProposal(operation = PREDICTION, plugin = JUPITER_SWAP.value))

        assertEquals(
            ProposalProvider.Unserved(UnsupportedReason.NameMismatch),
            proposalProvider(alsoCrossed, registry(jupiter), MAINNET, PRODUCTION),
        )

        // And the pair as it was published is served, which is what keeps this a rule about
        // crossed names rather than about prediction proposals.
        val straight =
            proposal(wireProposal(operation = PREDICTION, plugin = JUPITER_PREDICTION.value))

        assertEquals(
            ProposalProvider.Serving(JUPITER_PROVIDER),
            proposalProvider(straight, registry(jupiter), MAINNET, PRODUCTION),
        )
    }

    @Test
    fun anUnknownCapabilityVersionIsNotServedAndCannotBeBound() {
        val newer = proposal.copy(capabilityVersion = 2)

        assertEquals(
            ProposalProvider.Unserved(UnsupportedReason.SchemaUnsupported),
            proposalProvider(newer, registry(jupiterLike()), MAINNET, PRODUCTION),
        )
        assertEquals(
            BindingProblem.OtherSchema,
            problem(reviewed().copy(proposal = newer), binding(newer, chose, schemaVersion = 1)),
        )
    }

    @Test
    fun aWalletOnAnotherClusterIsRefusedBeforeAnythingIsPrepared() {
        // A provider serving mainnet only, and a wallet selected for devnet. It is its own reason,
        // apart from the environment: a provider with no devnet is not a provider with no sandbox
        // (SEE-145).
        assertEquals(
            ProposalProvider.Unserved(UnsupportedReason.NetworkUnsupported),
            proposalProvider(proposal, registry(jupiterLike()), Network.NETWORK_DEVNET, PRODUCTION),
        )
    }

    @Test
    fun anOperationNothingInThisBuildServesIsReportedAsItself() {
        assertEquals(
            ProposalProvider.Unserved(UnsupportedReason.NoProvider),
            proposalProvider(proposal, registry(), MAINNET, PRODUCTION),
        )
        assertEquals(
            ProposalProvider.Unserved(UnsupportedReason.EnvironmentUnsupported),
            proposalProvider(
                proposal,
                registry(jupiterLike(environments = setOf(PluginEnvironment.Sandbox))),
                MAINNET,
                PRODUCTION,
            ),
        )
    }

    private fun registry(vararg providers: TestExecutionProvider) = ProviderRegistry.of(*providers)

    private fun record(status: WireStatus = WireStatus.PROPOSAL_STATUS_OPEN) =
        ProposalRecord(
            connectionId = CONNECTION,
            proposal =
                if (status == WireStatus.PROPOSAL_STATUS_OPEN) proposal
                else proposal(wireProposal(status = status)),
        )

    private fun reviewed(status: WireStatus = WireStatus.PROPOSAL_STATUS_OPEN) =
        record(status).let { it.copy(review = ProposalReview(it.proposal.revision, chose, NOW)) }

    private fun problem(
        record: ProposalRecord,
        binding: ExecutionBinding,
        wallet: SelectedWallet? = selected,
        support: ServerSupport = ServerSupport.Supported,
        environment: PluginEnvironment = PluginEnvironment.Production,
        serving: ExecutionProviderId? = JUPITER_PROVIDER,
        at: Instant = NOW,
    ) = bindingProblem(record, binding, wallet, support, environment, serving, at)

    private val selected =
        SelectedWallet(address = WALLET, network = WalletNetwork.Mainnet, selectedAt = PUBLISHED)

    private companion object {
        const val CONNECTION = "11111111-2222-4333-8444-555555555555"
        const val OTHER_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val NOW: Instant = PUBLISHED.plusSeconds(60)
        val PRODUCTION = PluginEnvironment.Production
        val MAINNET: Network = Network.NETWORK_MAINNET
    }
}
