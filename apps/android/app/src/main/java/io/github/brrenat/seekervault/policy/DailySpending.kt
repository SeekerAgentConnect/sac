package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedSpending
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.StakingOperation
import io.github.brrenat.seekervault.skr.SKR_MINT
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
sealed interface DailySpendScope {
    val wallet: String
    val asset: PolicyAsset
}

/** One connection's daily threshold scope. */
data class SpendScope(
    val connectionId: String,
    override val wallet: String,
    override val asset: PolicyAsset,
) : DailySpendScope

/** A global daily threshold scope: the same wallet, asset, and chain across every connection. */
data class GlobalSpendScope(
    override val wallet: String,
    override val asset: PolicyAsset,
) : DailySpendScope

/** A request ID is unique only inside its connection. */
data class SpendRequest(val connectionId: String, val requestId: String)

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

/**
 * One movement the app handled, as the day's counters see it.
 *
 * What a record establishes about *where* the money left from decides which counters it can reach.
 * A transfer, a stake and an operation recorded since SEE-181 name the wallet, the asset and the
 * chain. An operation recorded before then names a wallet and a chain but not what it spent, so it
 * may belong to any asset's counter there: [asset] is null, and so is [amount].
 */
data class Spend(
    /** The connection-qualified request whose Activity record supplied this movement. */
    val request: SpendRequest,
    /** The wallet it left; null when the record doesn't say. */
    val wallet: String?,
    /** The chain; null when the record doesn't say. */
    val network: Network?,
    /** What left, on [network]; null when the record doesn't establish it. */
    val asset: PolicyAsset?,
    /**
     * What makes this the same movement and not another: the transaction's signature when there is
     * one, and otherwise the request it belongs to. A request prepared three times, answered,
     * re-sent, and status-checked ten times is one movement; two records carrying one signature are
     * one movement too.
     */
    val identity: String,
    /** When the owner answered, which is the moment the day's count puts it in. */
    val at: Instant,
    /** Base units, or null when the record's amount didn't read back or was never recorded. */
    val amount: ULong?,
    val status: SpendStatus,
) {
    init {
        require(asset == null || asset.network == network) { "an asset is on its own chain" }
        require(asset != null || amount == null) { "an amount is an amount of something" }
    }

    /**
     * Whether this movement may be in [scope]'s count. Known dimensions must match; an unknown one
     * matches anything, because "this record does not say which asset" is not "not this asset".
     */
    fun mayBelongTo(scope: DailySpendScope): Boolean =
        (scope !is SpendScope || request.connectionId == scope.connectionId) &&
            (wallet == null || wallet == scope.wallet) &&
            (network == null || network == scope.asset.network) &&
            (asset == null || asset == scope.asset)
}

/**
 * One scope's spending on one local day: what is known to have moved, and what may have.
 *
 * The two numbers are never added together for the owner and called spending. [projected] adds them
 * for one purpose only — warning about a threshold — and the screen shows both halves beside it.
 */
data class DailyTotal(
    val scope: DailySpendScope,
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
    /**
     * Movements today that may belong to this scope and whose records never said what they spent:
     * operations recorded before SEE-181 (docs/policy.md#records-written-before-see-181). They make
     * the total unknown for the same reason an unreadable amount does.
     */
    val uncounted: Int = 0,
) {
    /**
     * What the day could already come to. It is the number a threshold is warned about, and it is
     * not a claim that this much was spent.
     */
    val projected: ULong
        get() = confirmed saturatingPlus unresolved

    /** Whether every movement in the day read back, so the total means what it says. */
    val known: Boolean
        get() = unreadable == 0 && uncounted == 0

    companion object {
        /** Nothing counted: no movement in this scope on this day. */
        fun none(scope: DailySpendScope, day: LocalDate): DailyTotal =
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
 * The movements in [records], as the counters see them (docs/policy.md#what-counts-as-spending).
 *
 * Every kind of record that can take something out of the owner's balance is read here, from its
 * typed terms and never from display text: a transfer's reviewed terms, a stake's, and what an
 * operation's inspected bytes were pinned to spend. Acknowledging text, signing a message,
 * unstaking, cancelling an unstake and withdrawing move nothing out, and are left out entirely.
 */
fun spendsOf(records: List<ActivityRecord>): List<Spend> = records.mapNotNull { record ->
    val outflow = outflowOf(record) ?: return@mapNotNull null
    Spend(
        request = SpendRequest(record.connectionId, record.requestId),
        wallet = outflow.wallet,
        network = outflow.network,
        asset = outflow.asset,
        identity = record.signature ?: "${record.connectionId}/${record.requestId}",
        at = record.answeredAt,
        amount = outflow.amount,
        status = statusOf(record),
    )
}

/** Where one record's money left from, and how much, as far as its terms say. */
private data class Outflow(
    val wallet: String?,
    val network: Network?,
    val asset: PolicyAsset?,
    val amount: ULong?,
)

/** A record that says something may have moved, and not what. */
private val UNSAID = Outflow(null, null, null, null)

/**
 * What [record] takes out of the owner's spendable balance, or null when it takes nothing.
 *
 * Staking is accounted per action, by what the action moves rather than by its principal, so the
 * same SKR is never counted twice (docs/policy.md#staking): a stake sends SKR from the wallet into
 * the vault and counts; an unstake and a cancellation only change a position; a withdrawal brings
 * SKR back, and that does not replenish a threshold about SKR going out.
 */
private fun outflowOf(record: ActivityRecord): Outflow? =
    when (record.kind) {
        ActivityKind.Transfer -> {
            val transfer = record.transfer
            if (transfer == null) UNSAID
            else
                Outflow(
                    wallet = transfer.wallet,
                    network = transfer.network,
                    asset = PolicyAsset(transfer.network, transfer.mint),
                    amount = transfer.amount.toULongOrNull(),
                )
        }
        ActivityKind.Staking -> {
            val staking = record.staking
            when (staking?.operation) {
                null -> UNSAID
                StakingOperation.STAKING_OPERATION_STAKE.name ->
                    Outflow(
                        wallet = staking.wallet,
                        network = staking.network,
                        asset = PolicyAsset(staking.network, SKR_MINT),
                        amount = staking.amount.toULongOrNull(),
                    )
                StakingOperation.STAKING_OPERATION_UNSTAKE.name,
                StakingOperation.STAKING_OPERATION_CANCEL_UNSTAKE.name,
                StakingOperation.STAKING_OPERATION_WITHDRAW.name -> null
                // An action this build has no name for may have moved something.
                else -> Outflow(staking.wallet, staking.network, null, null)
            }
        }
        ActivityKind.Operation -> {
            val operation = record.operation
            when (val spending = operation?.spending) {
                is ReviewedSpending.Outgoing ->
                    Outflow(
                        wallet = spending.wallet,
                        network = spending.network,
                        asset = PolicyAsset(spending.network, spending.mint),
                        amount = spending.amount,
                    )
                ReviewedSpending.None -> null
                // Recorded before SEE-181, or unreadable: it may have spent any asset this wallet
                // holds on this chain, and how much is not known. Never zero.
                null -> Outflow(operation?.wallet, operation?.network, null, null)
            }
        }
        // Acknowledging text and signing a message move nothing. A record of a kind this build has
        // no name for is an answer to a request no wallet was opened for here.
        ActivityKind.Acknowledgement,
        ActivityKind.MessageSignature,
        ActivityKind.Other -> null
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
        // A rehearsal in a sandbox connection: nothing was signed, so nothing moved and there is
        // no exposure to carry either (SEE-97). It must not count against a threshold the owner
        // set on real money, for the same reason devnet play money never does
        // (docs/policy.md#counters).
        ActivityOutcome.Simulated -> SpendStatus.NotSpent
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
    scope: DailySpendScope,
    day: LocalDate,
    zone: ZoneId,
    /** The request in review is projected below, so an existing record for it is left out here. */
    excluding: SpendRequest? = null,
    /** Files the Activity store found but could not read; their scope cannot safely be guessed. */
    unreadableHistory: Int = 0,
): DailyTotal {
    val excludedIdentities =
        if (excluding == null) emptySet()
        else spends.filter { it.request == excluding }.map { it.identity }.toSet()
    val counted =
        spends
            .filter {
                it.request != excluding &&
                    it.identity !in excludedIdentities &&
                    it.mayBelongTo(scope)
            }
            // One movement, counted once. Where two records carry one identity, the one that knows
            // the most wins: either chain outcome settles what the phone's guess couldn't. For a
            // global scope this also deduplicates one signature recorded under two connections.
            .groupBy { it.identity }
            .values
            .mapNotNull { duplicates ->
                duplicates.minWithOrNull(
                    compareBy<Spend> { it.status.resolutionRank }
                        .thenBy { it.at }
                        .thenBy { it.request.connectionId }
                        .thenBy { it.request.requestId }
                )
            }
            // Deduplicate before choosing the day, so one signature cannot appear once on each side
            // of midnight merely because two connections recorded it at different instants.
            .filter { it.at.atZone(zone).toLocalDate() == day }
    var confirmed = 0UL
    var unresolved = 0UL
    var confirmedCount = 0
    var unresolvedCount = 0
    var unreadable = unreadableHistory
    var uncounted = 0
    for (spend in counted) {
        if (spend.status == SpendStatus.NotSpent) continue
        if (spend.asset == null) {
            uncounted++
            continue
        }
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
        uncounted = uncounted,
    )
}

/** A settled chain result outranks unresolved exposure when duplicate records disagree. */
private val SpendStatus.resolutionRank: Int
    get() =
        when (this) {
            SpendStatus.Confirmed -> 0
            SpendStatus.NotSpent -> 1
            SpendStatus.Unresolved -> 2
        }
