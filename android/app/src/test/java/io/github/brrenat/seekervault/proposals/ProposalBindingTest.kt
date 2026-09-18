package io.github.brrenat.seekervault.proposals

import io.github.brrenat.seekervault.plugins.PLUGIN_CONTRACT
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.plugins.TestPlugin
import io.github.brrenat.seekervault.plugins.UnsupportedReason
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
    fun bytesFromAnotherPluginOrAnotherContractAreRefused() {
        assertEquals(
            BindingProblem.OtherPlugin,
            problem(reviewed(), binding(proposal, chose, plugin = "other.swap")),
        )
        assertEquals(
            BindingProblem.OtherContract,
            problem(reviewed(), binding(proposal, chose, contract = PLUGIN_CONTRACT + 1)),
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
    fun thisBuildUsesThePluginItResolvesAndChecksThePublishersName() {
        // A document cannot select code. The publisher names the plugin it wrote for, and that name
        // is checked against what this build actually resolves for the operation.
        assertEquals(
            ProposalPlugin.Serving(PluginId(SWAP_PLUGIN)),
            proposalPlugin(proposal, registry(TestPlugin(id = SWAP_PLUGIN)), PRODUCTION),
        )
        assertEquals(
            ProposalPlugin.OtherPlugin(PluginId("other.swap")),
            proposalPlugin(proposal, registry(TestPlugin(id = "other.swap")), PRODUCTION),
        )
    }

    @Test
    fun anOperationNothingInThisBuildServesIsReportedAsItself() {
        assertEquals(
            ProposalPlugin.Unserved(UnsupportedReason.NoPlugin),
            proposalPlugin(proposal, registry(), PRODUCTION),
        )
        assertEquals(
            ProposalPlugin.Unserved(UnsupportedReason.EnvironmentUnsupported),
            proposalPlugin(
                proposal,
                registry(
                    TestPlugin(id = SWAP_PLUGIN, environments = setOf(PluginEnvironment.Sandbox))
                ),
                PRODUCTION,
            ),
        )
    }

    private fun registry(vararg plugins: TestPlugin) = PluginRegistry.of(*plugins)

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
        at: Instant = NOW,
    ) = bindingProblem(record, binding, wallet, support, environment, at)

    private val selected =
        SelectedWallet(address = WALLET, network = WalletNetwork.Mainnet, selectedAt = PUBLISHED)

    private companion object {
        const val CONNECTION = "11111111-2222-4333-8444-555555555555"
        const val OTHER_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        val NOW: Instant = PUBLISHED.plusSeconds(60)
        val PRODUCTION = PluginEnvironment.Production
    }
}
