package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What this app has moved today, counted from its own records (docs/policy.md#counters).
 *
 * A counter here is a record of what went through **this app** and nothing else. It sees nothing
 * the owner did in their wallet directly, nothing another app did with the same wallet, and nothing
 * that happened before this app was installed. It is therefore a floor on the day's spending and
 * never a ceiling, and it is advisory: no number here caps anything on chain.
 */

/**
 * What one day's spending is counted for: one connection, one wallet, one asset on one chain.
 *
 * All four matter. Two connections using the same wallet keep separate counts, because a threshold
 * is a rule about one agent, not about the wallet. Two wallets spending the same mint keep separate
 * counts, because the money comes out of different places. And an asset is a mint *on a network*,
 * so devnet play money is never counted against a mainnet threshold.
 */
data class SpendScope(val connectionId: String, val wallet: String, val asset: PolicyAsset)

/** What the app knows about whether the money actually left. */
enum class SpendStatus {
    /** The chain confirmed the transaction. The money moved. */
    Confirmed,
    /**
     * The wallet was handed the transaction, and nobody has established what came of it: it may be
     * in flight, it may have landed, it may have been dropped. It counts towards the day's
     * projected exposure and it is never reported as spending.
     */
    Unresolved,
    /**
     * Nothing moved: the owner rejected it, the wallet declined or couldn't sign, or the chain ran
     * the transaction and it failed. A rejection is not a transfer.
     */
    NotSpent,
}

/** One movement the app handled, as the day's counters see it. */
data class Spend(
    val scope: SpendScope,
    /**
     * What makes this the same movement and not another: the transaction's signature when there is
     * one, and otherwise the request it belongs to. A request prepared three times, answered,
     * re-sent, and status-checked ten times is one movement; two records carrying one signature are
     * one movement too.
     */
    val identity: String,
    /** When the owner answered, which is the moment the day's count puts it in. */
    val at: Instant,
    /** Base units, or null when the record's amount didn't read back as a whole number. */
    val amount: ULong?,
    val status: SpendStatus,
)

/**
 * One scope's spending on one local day: what is known to have moved, and what may have.
 *
 * The two numbers are never added together for the owner and called spending. [projected] adds them
 * for one purpose only — warning about a threshold — and the screen shows both halves beside it.
 */
data class DailyTotal(
    val scope: SpendScope,
    val day: LocalDate,
    /** Base units the chain confirmed. */
    val confirmed: ULong,
    /** Base units handed to the wallet whose fate nobody has established. */
    val unresolved: ULong,
    val confirmedCount: Int,
    val unresolvedCount: Int,
    /**
     * Movements in this scope and day whose amount didn't read back. One is enough to make the
     * day's total unknown: a threshold checked against a total that is missing an amount would read
     * as more room than there is.
     */
    val unreadable: Int,
) {
    /**
     * What the day could already come to. It is the number a threshold is warned about, and it is
     * not a claim that this much was spent.
     */
    val projected: ULong
        get() = confirmed saturatingPlus unresolved

    /** Whether every movement in the day read back, so the total means what it says. */
    val known: Boolean
        get() = unreadable == 0

    companion object {
        /** Nothing counted: no movement in this scope on this day. */
        fun none(scope: SpendScope, day: LocalDate): DailyTotal =
            DailyTotal(scope, day, 0UL, 0UL, 0, 0, 0)
    }
}

/**
 * Addition that stops at the largest base-unit amount there is instead of wrapping round to a small
 * one. A total that saturated is still over every threshold, which is the answer that keeps the
 * owner informed; a wrapped one would be under all of them.
 */
internal infix fun ULong.saturatingPlus(other: ULong): ULong {
    val sum = this + other
    return if (sum < this) ULong.MAX_VALUE else sum
}

/**
 * The movements in [records], as the counters see them. Records that aren't transfers are left out
 * entirely: acknowledging text and signing a message move nothing.
 */
fun spendsOf(records: List<ActivityRecord>): List<Spend> = records.mapNotNull { record ->
    if (record.kind != ActivityKind.Transfer) return@mapNotNull null
    val transfer = record.transfer ?: return@mapNotNull null
    Spend(
        scope =
            SpendScope(
                connectionId = record.connectionId,
                wallet = transfer.wallet,
                asset = PolicyAsset(transfer.network, transfer.mint),
            ),
        identity = record.signature ?: "${record.connectionId}/${record.requestId}",
        at = record.answeredAt,
        amount = transfer.amount.toULongOrNull(),
        status = statusOf(record),
    )
}

/**
 * What one record says about whether the money left.
 *
 * The question the counters ask is narrower than the one the Activity screen answers: not how it
 * went, but whether anything moved. Anything the wallet was handed and never accounted for counts
 * as exposure, because the alternative is telling the owner they have room they may not have.
 */
private fun statusOf(record: ActivityRecord): SpendStatus =
    when (record.outcome) {
        ActivityOutcome.Confirmed -> SpendStatus.Confirmed
        // Sent is not paid, and unknown is not nothing.
        ActivityOutcome.Sent,
        ActivityOutcome.Unknown -> SpendStatus.Unresolved
        // The approval was taken by the server and the wallet has it; its answer isn't here yet.
        ActivityOutcome.Waiting -> SpendStatus.Unresolved
        // The chain ran it and it failed, so the transfer didn't happen. The fee did, and this
        // app counts what the owner set thresholds on, which is the amount that would have moved.
        ActivityOutcome.ChainFailed -> SpendStatus.NotSpent
        ActivityOutcome.Rejected,
        ActivityOutcome.DeclinedInWallet,
        ActivityOutcome.NotSigned,
        ActivityOutcome.Acknowledged,
        ActivityOutcome.MessageSigned -> SpendStatus.NotSpent
        // The answer never reached the server, or the request had moved on. Neither says anything
        // about the wallet, so a signature does: the wallet made one, so it sent something.
        ActivityOutcome.NotDelivered,
        ActivityOutcome.Superseded ->
            if (record.signature != null) SpendStatus.Unresolved else SpendStatus.NotSpent
    }

/**
 * What [scope] has moved on [day], in [zone].
 *
 * A day is a local day: it runs from midnight to midnight where the phone is, because that is the
 * day the owner means when they set a daily threshold. The day a movement falls in is worked out
 * here, from the instant the owner answered, so the records never have to be rewritten — and so a
 * phone carried into another time zone re-reads its own history in the zone it is in now
 * (docs/policy.md#the-day-a-counter-counts).
 */
fun dailyTotal(
    spends: List<Spend>,
    scope: SpendScope,
    day: LocalDate,
    zone: ZoneId,
): DailyTotal {
    val counted =
        spends
            .filter { it.scope == scope && it.at.atZone(zone).toLocalDate() == day }
            // One movement, counted once. Where two records carry one identity, the one that knows
            // the most wins: the chain's word settles what the phone's guess couldn't.
            .groupBy { it.identity }
            .values
            .mapNotNull { duplicates -> duplicates.minByOrNull { it.status.ordinal } }
    var confirmed = 0UL
    var unresolved = 0UL
    var confirmedCount = 0
    var unresolvedCount = 0
    var unreadable = 0
    for (spend in counted) {
        if (spend.status == SpendStatus.NotSpent) continue
        val amount = spend.amount
        if (amount == null) {
            unreadable++
            continue
        }
        if (spend.status == SpendStatus.Confirmed) {
            confirmed = confirmed saturatingPlus amount
            confirmedCount++
        } else {
            unresolved = unresolved saturatingPlus amount
            unresolvedCount++
        }
    }
    return DailyTotal(
        scope = scope,
        day = day,
        confirmed = confirmed,
        unresolved = unresolved,
        confirmedCount = confirmedCount,
        unresolvedCount = unresolvedCount,
        unreadable = unreadable,
    )
}
