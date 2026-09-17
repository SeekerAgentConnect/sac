package io.github.brrenat.seekervault.proposals

import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposal.v1.ProposalStatus as WireStatus
import io.github.brrenat.seekervault.servers.SERVER_A
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.channelFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the phone makes of a document a publisher broadcast (SEE-89).
 *
 * A proposal comes from a server this phone has no relationship with, through a gateway shared with
 * everyone else, so every rule is about being able to refuse one without being confused by it. Each
 * case here is one rule, and the problem it reports is the one a person would want to be told.
 */
class ProposalValidationTest {
    @Test
    fun aPublishersOpenProposalReadsWhole() {
        val proposal =
            valid(
                wireProposal(
                    revision = 4,
                    note = "Ротация в USDC",
                    values = listOf("input_mint" to "So111", "published_price" to "139420000"),
                )
            )

        assertEquals(ProposalKey(SERVER_B, channelFor(SERVER_B), PROPOSAL_A), proposal.key)
        assertEquals(4L, proposal.revision)
        assertEquals(OperationId(SWAP), proposal.operation)
        assertEquals(PluginId(SWAP_PLUGIN), proposal.plugin)
        assertEquals(ProposalStatus.Open, proposal.status)
        assertEquals(PUBLISHED, proposal.createdAt)
        assertEquals(PUBLISHED.plusSeconds(3600), proposal.expiresAt)
        assertEquals("Ротация в USDC", proposal.note)
        assertEquals("139420000", proposal.value("published_price"))
        assertEquals(null, proposal.value("slippage_bps"))
    }

    @Test
    fun aWithdrawnProposalIsStillReadable() {
        // The publisher took it back. It is read, because a device that acted on it has a record
        // either way and the owner is owed the terms it was about; what it isn't is executable
        // (ProposalStandingTest).
        val proposal = valid(wireProposal(status = WireStatus.PROPOSAL_STATUS_CANCELLED))

        assertEquals(ProposalStatus.Cancelled, proposal.status)
    }

    @Test
    fun aProposalFromAnotherPublisherIsRefused() {
        // The gateway is shared. A document from one publisher must not land in another's feed,
        // whatever channel it names.
        assertEquals(
            ProposalProblem.OtherServer,
            refused(wireProposal(serverId = SERVER_A, channel = channelFor(SERVER_A))),
        )
    }

    @Test
    fun anIdentityThatIsNotAnIdentityIsRefused() {
        assertEquals(ProposalProblem.BadServerId, refused(wireProposal(serverId = "publisher")))
    }

    @Test
    fun aProposalClaimingAnotherPublishersChannelIsRefused() {
        // Its own identity is the one expected, and the channel it names belongs to someone else:
        // a publisher may only speak to its own audience.
        assertEquals(
            ProposalProblem.ForeignChannel,
            refused(wireProposal(channel = channelFor(SERVER_A))),
        )
    }

    @Test
    fun anIdNothingCouldBeKeyedByIsRefused() {
        assertEquals(ProposalProblem.BadProposalId, refused(wireProposal(proposalId = "../../etc")))
    }

    @Test
    fun aProposalWithNoRevisionCannotBeCompared() {
        assertEquals(ProposalProblem.NoRevision, refused(wireProposal(revision = 0)))
    }

    @Test
    fun aRevisionAboveWhatThisRuntimeCanOrderIsNoRevision() {
        // The protocol carries a uint64 and this runtime has no unsigned long, so the largest
        // revision a publisher could send arrives as a negative number. It is refused rather than
        // read as older or newer than anything.
        assertEquals(ProposalProblem.NoRevision, refused(wireProposal(revision = -1)))
    }

    @Test
    fun aRevisionBelowTheOneHeldIsRefusedAndOneThatRepeatsItIsNot() {
        // A replayed older document must not restore terms the publisher has moved past.
        assertEquals(
            ProposalProblem.StaleRevision,
            refused(wireProposal(revision = 2), heldRevision = 3),
        )
        assertEquals(3L, valid(wireProposal(revision = 3), heldRevision = 3).revision)
        assertEquals(4L, valid(wireProposal(revision = 4), heldRevision = 3).revision)
    }

    @Test
    fun aProposalWithNoStatusIsNotReadAsOpen() {
        assertEquals(
            ProposalProblem.NoStatus,
            refused(wireProposal(status = WireStatus.PROPOSAL_STATUS_UNSPECIFIED)),
        )
    }

    @Test
    fun anOperationOrPluginThatIsNotANameIsRefused() {
        assertEquals(ProposalProblem.BadOperation, refused(wireProposal(operation = "Swap!")))
        // A plugin ID is a name and nothing loadable: never a URL, never a package.
        assertEquals(
            ProposalProblem.BadPlugin,
            refused(wireProposal(plugin = "https://example.com/swap.js")),
        )
    }

    @Test
    fun aProposalWithNoExpiryIsRefusedRatherThanOpenForEver() {
        assertEquals(
            ProposalProblem.NoTimes,
            refused(wireProposal().toBuilder().clearExpiresAt().build()),
        )
    }

    @Test
    fun timesThatContradictEachOtherAreRefused() {
        assertEquals(
            ProposalProblem.BadTimes,
            refused(wireProposal(expiresAt = PUBLISHED)),
        )
        assertEquals(
            ProposalProblem.BadTimes,
            refused(wireProposal(updatedAt = PUBLISHED.minusSeconds(1))),
        )
    }

    @Test
    fun theTermsAreBounded() {
        assertEquals(
            ProposalProblem.TooManyValues,
            refused(
                wireProposal(values = (0..MAX_PROPOSAL_VALUES).map { "term_$it" to it.toString() })
            ),
        )
        assertEquals(
            ProposalProblem.DuplicateValue,
            refused(wireProposal(values = listOf("price" to "1", "price" to "2"))),
        )
        assertEquals(
            ProposalProblem.BadValue,
            refused(wireProposal(values = listOf("Price" to "1"))),
        )
        assertEquals(
            ProposalProblem.BadValue,
            refused(
                wireProposal(values = listOf("price" to "x".repeat(MAX_PROPOSAL_TEXT_BYTES + 1)))
            ),
        )
    }

    @Test
    fun aNoteIsProseAndIsBoundedLikeOne() {
        // Prose, so a line break is text. Everything else that isn't printable is not: a publisher
        // could otherwise make its own words read as something the app said.
        assertEquals("first\nsecond", valid(wireProposal(note = "first\nsecond")).note)
        assertEquals(
            ProposalProblem.BadNote,
            refused(wireProposal(note = "a".repeat(MAX_PROPOSAL_NOTE_BYTES + 1))),
        )
        assertEquals(ProposalProblem.BadNote, refused(wireProposal(note = "Approved")))
    }

    private fun valid(message: WireProposal, heldRevision: Long? = null): Proposal =
        (proposalFrom(message, expect(heldRevision)) as ProposalResult.Valid).proposal

    private fun refused(message: WireProposal, heldRevision: Long? = null): ProposalProblem =
        (proposalFrom(message, expect(heldRevision)) as ProposalResult.Invalid).problem

    private fun expect(heldRevision: Long?) =
        ProposalExpectation(serverId = SERVER_B, heldRevision = heldRevision)
}
