package io.github.brrenat.seekervault.inbox

import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.proposals.wireProposal
import io.github.brrenat.seekervault.request.v2.PresentationCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingItemTest {
    @Test
    fun privateAndFeedRequestsShareOneOrderedCollectionWithoutIdentityCollisions() {
        val private =
            FakeConnectionGateway.request(CONNECTION, REQUEST, "private")
                .toBuilder()
                .setCreatedAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(10))
                .build()
        val signal =
            ProposalRecord(
                connectionId = CONNECTION,
                proposal = proposal(wireProposal(proposalId = REQUEST)),
            )

        val items =
            pendingItems(
                Inbox(pending = mapOf(CONNECTION to listOf(private))),
                listOf(signal),
                { ProposalStanding.Open },
            )

        assertEquals(2, items.size)
        assertEquals(setOf("private", "feed"), items.map { it.namespace }.toSet())
        assertEquals(2, items.map { "${it.namespace}/${it.requestId}" }.toSet().size)
        assertEquals(
            setOf(
                PresentationCategory.PRESENTATION_CATEGORY_REQUEST,
                PresentationCategory.PRESENTATION_CATEGORY_SIGNAL,
            ),
            items.map { it.envelope.presentation.category }.toSet(),
        )
    }

    private companion object {
        const val CONNECTION = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"
        const val REQUEST = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    }
}
