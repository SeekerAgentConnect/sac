package io.github.brrenat.seekervault.proposals

import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.proposal.v1.ProposalStatus as WireStatus
import io.github.brrenat.seekervault.servers.SWAP_PLUGIN
import io.github.brrenat.seekervault.servers.ServerSupport
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a proposal stands: the publisher's half, and this device's (SEE-89).
 *
 * Both are derived on every read, never stored, and this is the test that says why: the same record
 * and the same publisher answer differently as the clock moves, as this owner acts, and as the
 * build changes. A verdict written to disk would outlive all three.
 */
class ProposalStateTest {
    @Test
    fun anOpenProposalBeforeItsExpiryIsAvailableAndActionable() {
        val record = record()

        assertEquals(ProposalAvailability.Open, proposalAvailability(record.proposal, NOW))
        assertEquals(ProposalStanding.Open, standing(record))
        assertTrue(standing(record).executable)
    }

    @Test
    fun theSameProposalExpiresWithoutAnythingBeingWritten() {
        // One record, two clocks. Expiry is absolute, so a phone that was offline for a day reads
        // it the same way as one that was not.
        val record = record()

        assertEquals(ProposalStanding.Open, standing(record, at = NOW))
        assertEquals(
            ProposalStanding.Expired,
            standing(record, at = record.proposal.expiresAt),
        )
        assertFalse(standing(record, at = record.proposal.expiresAt).executable)
    }

    @Test
    fun aWithdrawnProposalIsCancelledBeforeItExpires() {
        val record = record(status = WireStatus.PROPOSAL_STATUS_CANCELLED)

        assertEquals(ProposalAvailability.Cancelled, proposalAvailability(record.proposal, NOW))
        assertEquals(ProposalStanding.Cancelled, standing(record))
    }

    @Test
    fun aWithdrawalIsReportedAheadOfTheClock() {
        // The publisher did something; the clock only happened. The owner is told the thing that
        // was done.
        val record = record(status = WireStatus.PROPOSAL_STATUS_CANCELLED)

        assertEquals(
            ProposalAvailability.Cancelled,
            proposalAvailability(record.proposal, record.proposal.expiresAt.plusSeconds(1)),
        )
    }

    @Test
    fun aProposalTheOwnerHidStaysHidden() {
        val record = record().copy(dismissed = ProposalDismissal(1, NOW))

        assertEquals(ProposalStanding.Dismissed(NOW), standing(record))
        assertFalse(standing(record).executable)
    }

    @Test
    fun whatThisDeviceDidOutranksWhatThePublisherDidAfterwards() {
        // A proposal the publisher later withdrew is still one this owner acted on, and the record
        // of that is the first thing to say about it.
        val record =
            record(status = WireStatus.PROPOSAL_STATUS_CANCELLED)
                .copy(
                    dismissed = ProposalDismissal(1, NOW),
                    execution =
                        ProposalExecution(
                            binding = binding(proposal(), choice(1_000_000u)),
                            startedAt = NOW,
                        ),
                )

        assertEquals(ProposalStanding.Executed(ProposalOutcome.Pending), standing(record))
        assertFalse(standing(record).executable)
    }

    @Test
    fun aPublisherThatContradictedItselfIsActedOnNoFurther() {
        val record = record().copy(refused = ProposalProblem.ChangedWithoutRevision)

        assertEquals(
            ProposalStanding.Refused(ProposalProblem.ChangedWithoutRevision),
            standing(record),
        )
        assertFalse(standing(record).executable)
    }

    @Test
    fun anOpenProposalFromAnUnsupportedServerIsReadableAndNotExecutable() {
        // The same document, and the difference is what this build carries (SEE-88): a proposal
        // whose plugin isn't here is shown, and nothing is prepared from it.
        val missing = ServerSupport.PluginMissing(listOf(PluginId(SWAP_PLUGIN)))
        val record = record()

        assertEquals(ProposalStanding.Unsupported(missing), standing(record, support = missing))
        assertFalse(standing(record, support = missing).executable)
        assertEquals(ProposalStanding.Open, standing(record))
    }

    @Test
    fun onlyAnOpenProposalIsExecutable() {
        assertTrue(ProposalStanding.Open.executable)
        assertFalse(ProposalStanding.Executed(ProposalOutcome.Declined).executable)
        assertFalse(ProposalStanding.Dismissed(NOW).executable)
        assertFalse(ProposalStanding.Refused(ProposalProblem.BadNote).executable)
        assertFalse(ProposalStanding.Cancelled.executable)
        assertFalse(ProposalStanding.Expired.executable)
        assertFalse(ProposalStanding.Unsupported(ServerSupport.Unknown).executable)
    }

    @Test
    fun onlyAPendingOutcomeIsUnsettled() {
        // What the wallet did is said once. Everything else is the last word, including the one
        // nobody knows: an unresolved operation is not retried, and is not called a failure.
        assertFalse(ProposalOutcome.Pending.settled)
        assertTrue(ProposalOutcome.Submitted(hash(2)).settled)
        assertTrue(ProposalOutcome.Declined.settled)
        assertTrue(ProposalOutcome.Failed("the wallet refused").settled)
        assertTrue(ProposalOutcome.Unresolved("nobody knows").settled)
    }

    @Test
    fun aRecordIsKeyedByThePublishedIdentity() {
        val record = record()

        assertEquals(record.proposal.key, record.key)
        assertEquals(PROPOSAL_A, record.key.proposalId)
    }

    private fun record(status: WireStatus = WireStatus.PROPOSAL_STATUS_OPEN): ProposalRecord =
        ProposalRecord(
            connectionId = CONNECTION,
            proposal = proposal(wireProposal(status = status)),
        )

    private fun standing(
        record: ProposalRecord,
        support: ServerSupport = ServerSupport.Supported,
        at: Instant = NOW,
    ) = proposalStanding(record, support, at)

    private companion object {
        const val CONNECTION = "11111111-2222-4333-8444-555555555555"
        val NOW: Instant = PUBLISHED.plusSeconds(60)
    }
}
