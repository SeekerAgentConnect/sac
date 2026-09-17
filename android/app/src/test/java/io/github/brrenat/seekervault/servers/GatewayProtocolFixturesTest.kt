package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.gateway.v1.FeedEvent
import io.github.brrenat.seekervault.gateway.v1.GetProposalResponse
import io.github.brrenat.seekervault.gateway.v1.GetServerManifestResponse
import io.github.brrenat.seekervault.gateway.v1.ListProposalsResponse
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.proposal.v1.Proposal as WireProposal
import io.github.brrenat.seekervault.proposals.Proposal
import io.github.brrenat.seekervault.proposals.ProposalExpectation
import io.github.brrenat.seekervault.proposals.ProposalResult
import io.github.brrenat.seekervault.proposals.ProposalStatus
import io.github.brrenat.seekervault.proposals.proposalFrom
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the broadcast gateway answers, read by the phone that will be asking (SEE-90,
 * proto/fixtures/seekervault/gateway/v1).
 *
 * The fixtures are not written for this test: the gateway's own Go tests run a scenario through the
 * real service and require each committed fixture to be exactly what it answered
 * (`broadcast/internal/gateway/fixtures_test.go`). This side then requires the phone's own
 * validators to accept what is in them. Neither runtime has to reach the other for that to mean
 * something — the transport is SEE-91 — and between them they say the thing that matters: the
 * documents the gateway serves are documents this build can use.
 *
 * The publisher, the gateway origin and the documents are the ones the manifest and proposal
 * fixtures already hold, so what is new here is the envelope: the caching answers, the page, the
 * snapshot boundary a walk reports, and — since SEE-91 — what the same documents look like when
 * they arrive on the stream instead of being asked for.
 */
class GatewayProtocolFixturesTest {
    @Test
    fun theManifestAGatewayServesIsOneThePhoneAccepts() {
        val response =
            GetServerManifestResponse.parseFrom(bytes("GetServerManifestResponse/manifest"))

        assertFalse(response.unchanged)
        assertEquals(12L, response.settingsRevision)
        // Validated against the reference the feed would have been added from, which is the whole
        // of what the phone trusts about a gateway's answer (SEE-88).
        val manifest =
            (manifestFrom(
                    response.manifest,
                    ManifestExpectation(
                        serverId = PUBLISHER,
                        mode = ConnectionMode.GatewayFeed,
                        origin = GATEWAY,
                    ),
                )
                    as ManifestResult.Valid)
                .manifest

        assertEquals(PUBLISHER, manifest.serverId)
        assertEquals(SERVER_PROTOCOL, manifest.protocolVersion)
        assertEquals(12L, manifest.settingsRevision)
        assertEquals(ServerReference.Feed(GATEWAY, channelFor(PUBLISHER)), manifest.reference)
        assertEquals(
            listOf(PluginId("jupiter.swap"), PluginId("jupiter.prediction")),
            manifest.required.map { it.id },
        )
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            manifest.environments,
        )
        assertEquals("Копи-трейдинг 📈", manifest.name)
    }

    @Test
    fun aManifestTheGatewaySaysHasNotChanged() {
        val response =
            GetServerManifestResponse.parseFrom(bytes("GetServerManifestResponse/unchanged"))

        // The cache rule from both ends: the phone asked with the revision it holds, and the answer
        // is that it may keep it. There is no document in it at all, and the revision is still
        // stated so a phone that asked with the wrong one learns which is current.
        assertTrue(response.unchanged)
        assertFalse(response.hasManifest())
        assertEquals(12L, response.settingsRevision)
    }

    @Test
    fun aPageOfAFeedIsProposalsThePhoneAccepts() {
        val response = ListProposalsResponse.parseFrom(bytes("ListProposalsResponse/page"))

        assertFalse(response.unchanged)
        // The walk's boundary, which is what lets a stream's events be buffered during a snapshot
        // read and applied after it (SEE-91).
        assertEquals(5L, response.snapshotSequence)
        assertTrue(response.nextPageToken.isNotEmpty())

        val proposals = response.proposalsList.map { valid(it) }
        assertEquals(listOf(SWAP_ID, PREDICTION_ID), proposals.map { it.key.proposalId })
        // Ordered by identity rather than by when: the walk stays in one place while the feed moves
        // underneath it.
        assertEquals(listOf(4L, 9L), proposals.map { it.revision })
        assertEquals(
            listOf(ProposalStatus.Open, ProposalStatus.Cancelled),
            proposals.map { it.status },
        )
        val swap = proposals.first()
        assertEquals("139420000", swap.value("published_price"))
        assertEquals("Ротация в USDC 📉", swap.note)
        assertEquals(Instant.parse("2026-09-17T10:00:00Z"), swap.expiresAt)
    }

    @Test
    fun aFeedTheGatewaySaysHasNotChanged() {
        val response = ListProposalsResponse.parseFrom(bytes("ListProposalsResponse/unchanged"))

        assertTrue(response.unchanged)
        assertEquals(0, response.proposalsCount)
        assertEquals(5L, response.snapshotSequence)
        assertTrue(response.nextPageToken.isEmpty())
    }

    @Test
    fun oneProposalsDetailIsTheDocumentItselfAndNothingAboutTheReader() {
        val response = GetProposalResponse.parseFrom(bytes("GetProposalResponse/cancelled"))
        val proposal = valid(response.proposal)

        assertEquals(PREDICTION_ID, proposal.key.proposalId)
        assertEquals(ProposalStatus.Cancelled, proposal.status)
        // The one field the gateway writes: the moment the withdrawal happened. Everything else is
        // the publisher's own, and the phone orders by the revision either way.
        assertEquals(Instant.parse("2026-09-17T08:00:00Z"), proposal.createdAt)
        assertEquals(Instant.parse("2026-09-17T08:45:00Z"), proposal.updatedAt)
        assertEquals(9L, proposal.revision)
        assertEquals("SOL above 200 on 2026-10-01", proposal.value("market"))
    }

    /**
     * What arrives on the stream is the same document, in an envelope this build can read (SEE-91).
     *
     * These three fixtures are taken from the gateway's own outbox, so they are what a subscriber
     * actually receives — and the point of reading them here is that the phone runs them through
     * the same validators it runs a read through. A document is not trusted more for having arrived
     * quickly.
     */
    @Test
    fun anEventCarriesADocumentThePhoneValidatesLikeAnyOther() {
        val settings = FeedEvent.parseFrom(bytes("FeedEvent/settings"))
        assertEquals(1L, settings.sequence)
        val manifest =
            (manifestFrom(
                    settings.manifest,
                    ManifestExpectation(
                        serverId = PUBLISHER,
                        mode = ConnectionMode.GatewayFeed,
                        origin = GATEWAY,
                        // The revision the phone already holds: a settings event is how it learns
                        // there is a newer one.
                        heldRevision = 11,
                    ),
                )
                    as ManifestResult.Valid)
                .manifest
        assertEquals(12L, manifest.settingsRevision)
        assertEquals(ServerReference.Feed(GATEWAY, channelFor(PUBLISHER)), manifest.reference)

        val published = FeedEvent.parseFrom(bytes("FeedEvent/proposal"))
        assertEquals(2L, published.sequence)
        val swap = valid(published.proposal)
        assertEquals(SWAP_ID, swap.key.proposalId)
        assertEquals(4L, swap.revision)
        assertEquals(ProposalStatus.Open, swap.status)

        // A withdrawal is a document with its status closed, not an absence: a phone that acted on
        // a proposal has to be told what happened to it (SEE-89).
        val withdrawn = FeedEvent.parseFrom(bytes("FeedEvent/withdrawn"))
        assertEquals(5L, withdrawn.sequence)
        val prediction = valid(withdrawn.proposal)
        assertEquals(PREDICTION_ID, prediction.key.proposalId)
        assertEquals(ProposalStatus.Cancelled, prediction.status)
        assertEquals(9L, prediction.revision)
        assertEquals(Instant.parse("2026-09-17T08:45:00Z"), prediction.updatedAt)
        // And the sequence is the gateway's count, never the broker's cursor: a later event on the
        // same channel has a higher one, and neither number is ever compared with the other.
        assertTrue(withdrawn.sequence > published.sequence)
    }

    @Test
    fun coversEveryFixture() {
        val dir = File(checkNotNull(javaClass.getResource("/$PACKAGE")) { "no fixtures" }.toURI())
        val names =
            dir.walk()
                .filter { it.extension == "json" }
                .map { "${it.parentFile?.name}/${it.nameWithoutExtension}" }
                .sorted()
                .toList()
        assertEquals(
            listOf(
                "FeedEvent/proposal",
                "FeedEvent/settings",
                "FeedEvent/withdrawn",
                "GetProposalResponse/cancelled",
                "GetServerManifestResponse/manifest",
                "GetServerManifestResponse/unchanged",
                "ListProposalsResponse/page",
                "ListProposalsResponse/unchanged",
            ),
            names,
        )
    }

    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$PACKAGE/$name.binpb")) {
                "Missing fixture $name.binpb; run pnpm generate"
            }
            .use { it.readBytes() }

    private fun valid(message: WireProposal): Proposal =
        (proposalFrom(message, ProposalExpectation(PUBLISHER)) as ProposalResult.Valid).proposal

    private companion object {
        const val PACKAGE = "seekervault/gateway/v1"
        const val PUBLISHER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val GATEWAY = "https://gateway.example.com"
        const val SWAP_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val PREDICTION_ID = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
    }
}
