package io.github.brrenat.seekervault.history

import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.positions.HoldingRecord
import io.github.brrenat.seekervault.positions.PositionPurchase
import io.github.brrenat.seekervault.positions.PositionsState
import io.github.brrenat.seekervault.proposals.ProposalRecord

/**
 * A purchase whose position this phone still follows after its signal's feed record is gone
 * (SEE-172). Position management must not depend on the feed staying connected: removing a
 * connection removes the proposals it read, but not the holding linked to the purchase nor the
 * owner's own Activity record of it, and those are what reopen the item.
 */
data class RetainedPurchase(
    val key: RequestKey,
    val holding: HoldingRecord,
    val purchase: PositionPurchase,
    /** The owner's own record of the purchase; null when it could not be read. */
    val activity: ActivityRecord?,
)

/**
 * Every tracked purchase with no record in [feedRecords], newest first. Only an answer once the
 * feed records have been read: before that, every purchase would look retained.
 */
fun retainedPurchases(
    positions: PositionsState,
    feedRecords: List<ProposalRecord>,
    activity: List<ActivityRecord>,
): List<RetainedPurchase> {
    val present = feedRecords.mapTo(HashSet()) { RequestKey(it.connectionId, it.key.proposalId) }
    val records = activity.associateBy { it.key }
    return positions.holdings.values
        .flatMap { holding ->
            holding.purchases.mapNotNull { purchase ->
                val key = RequestKey(purchase.connectionId, purchase.proposalId)
                if (key in present) null else RetainedPurchase(key, holding, purchase, records[key])
            }
        }
        .sortedByDescending { it.purchase.boughtAt }
}
