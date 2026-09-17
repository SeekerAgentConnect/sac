package io.github.brrenat.seekervault.proposals

import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.servers.channelFor
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Kotlin half of the proposal fixtures in proto/fixtures/seekervault/proposal/v1 (SEE-89).
 *
 * The bytes matter more here than for most messages, because the publisher that writes them is a Go
 * program (SEE-95, SEE-96) and the phone that reads them is this one: `buf convert` writes each
 * `.binpb` with Go's own protobuf, the sidecar's proposals.test.ts checks the same bytes with
 * protobuf-es, and this side checks what the phone *makes* of each one. A document is a statement
 * by a publisher, and the same bytes have to be accepted or refused the same way whichever build
 * reads them.
 */
class ProposalFixturesTest {
    @Test
    fun aPublishersOpenProposal() {
        val proposal = valid("open")

        assertEquals(ProposalKey(PUBLISHER, channelFor(PUBLISHER), OPEN_ID), proposal.key)
        assertEquals(4L, proposal.revision)
        assertEquals(OperationId("swap"), proposal.operation)
        assertEquals(PluginId("jupiter.swap"), proposal.plugin)
        assertEquals(ProposalStatus.Open, proposal.status)
        assertEquals(Instant.parse("2026-09-17T09:00:00Z"), proposal.createdAt)
        assertEquals(Instant.parse("2026-09-17T09:30:00Z"), proposal.updatedAt)
        assertEquals(Instant.parse("2026-09-17T10:00:00Z"), proposal.expiresAt)
        // The publisher's own words, in another script with an emoji: shown as they are, and never
        // as this app's account of what the publisher is proposing.
        assertEquals("Ротация в USDC 📉", proposal.note)
        assertEquals("139420000", proposal.value("published_price"))
        assertEquals("50", proposal.value("slippage_bps"))
        assertEquals(4, proposal.values.size)
    }

    @Test
    fun aWithdrawnProposalReadsAsWithdrawn() {
        val proposal = valid("cancelled")

        assertEquals(ProposalStatus.Cancelled, proposal.status)
        assertEquals(OperationId("prediction"), proposal.operation)
        assertEquals(PluginId("jupiter.prediction"), proposal.plugin)
        assertEquals("SOL above 200 on 2026-10-01", proposal.value("market"))
    }

    @Test
    fun aProposalClaimingAnotherPublishersChannel() {
        // The identity is this publisher's and the channel is the sidecar's from the manifest
        // fixtures: a publisher may only speak to its own audience.
        assertEquals(ProposalProblem.ForeignChannel, refused("foreign_channel"))
    }

    @Test
    fun aRevisionAtTheUnsignedMaximum() {
        // The protocol carries a uint64 and this runtime has no unsigned long, so the largest
        // revision a publisher could send arrives here as a negative number. It is refused rather
        // than read as older or newer than anything, and the sidecar's own test pins the same bytes
        // as exactly 2^64 - 1.
        assertEquals(ProposalProblem.NoRevision, refused("max_revision"))
    }

    @Test
    fun coversEveryFixture() {
        val dir = File(checkNotNull(javaClass.getResource("/$PACKAGE")) { "no fixtures" }.toURI())
        val names =
            dir.walk()
                .filter { it.extension == "json" }
                .map { it.nameWithoutExtension }
                .sorted()
                .toList()
        assertEquals(listOf("cancelled", "foreign_channel", "max_revision", "open"), names)
    }

    private fun message(name: String): WireProposal =
        WireProposal.parseFrom(
            checkNotNull(javaClass.getResourceAsStream("/$PACKAGE/$name.binpb")) {
                    "Missing fixture $name.binpb; run pnpm generate"
                }
                .use { it.readBytes() }
        )

    private fun valid(name: String): Proposal =
        (proposalFrom(message(name), ProposalExpectation(PUBLISHER)) as ProposalResult.Valid)
            .proposal

    private fun refused(name: String): ProposalProblem =
        (proposalFrom(message(name), ProposalExpectation(PUBLISHER)) as ProposalResult.Invalid)
            .problem

    private companion object {
        const val PACKAGE = "seekervault/proposal/v1/Proposal"
        const val PUBLISHER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val OPEN_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    }
}
