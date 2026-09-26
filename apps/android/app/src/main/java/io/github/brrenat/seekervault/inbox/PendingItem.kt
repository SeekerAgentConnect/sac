package io.github.brrenat.seekervault.inbox

import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v2.Request
import io.github.brrenat.seekervault.requests.commonEnvelope
import java.time.Instant

/**
 * One chronological review collection, namespaced so private and feed identities cannot collide.
 */
sealed interface PendingItem {
    val envelope: Request
    val namespace: String
    val connectionId: String
    val requestId: String
    val at: Instant

    data class Private(val request: ActionRequest) : PendingItem {
        override val envelope = request.commonEnvelope()
        override val namespace = "private"
        override val connectionId = envelope.identity.sourceId
        override val requestId = envelope.identity.requestId
        override val at: Instant = envelope.lifecycle.createdAt.instant()
    }

    data class Signal(val record: ProposalRecord) : PendingItem {
        override val envelope = record.proposal.commonEnvelope()
        override val namespace = "feed"
        override val connectionId = record.connectionId
        override val requestId = envelope.identity.requestId
        override val at: Instant = envelope.lifecycle.createdAt.instant()
    }
}

fun pendingItems(
    inbox: Inbox,
    signals: List<ProposalRecord>,
    standing: (ProposalRecord) -> ProposalStanding,
    connectionId: String? = null,
): List<PendingItem> {
    val private = inboxItems(inbox, connectionId).pending.map(PendingItem::Private)
    val shared =
        signals
            .filter { connectionId == null || it.connectionId == connectionId }
            .filter { standing(it) is ProposalStanding.Open }
            .map<ProposalRecord, PendingItem>(PendingItem::Signal)
    return (private + shared).sortedWith(
        compareBy<PendingItem> { it.at }
            .thenBy { it.namespace }
            .thenBy { it.connectionId }
            .thenBy { it.requestId }
    )
}
