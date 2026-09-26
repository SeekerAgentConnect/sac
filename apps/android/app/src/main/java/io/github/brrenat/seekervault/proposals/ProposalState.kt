package io.github.brrenat.seekervault.proposals

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.executable
import java.time.Instant

/**
 * Everything this device holds about one proposal (SEE-89, docs/wiki/shared-proposals.md).
 *
 * The two halves are kept apart on purpose, because they belong to different people. [proposal] is
 * the publisher's, identical on every phone that received it, and it is replaced whole when the
 * publisher publishes a new revision. Everything else is this device's: what the owner hid, what
 * they reviewed and chose, and what their wallet did. None of it is ever published, so dismissing
 * or executing a proposal here changes nothing about the same proposal on anyone else's phone —
 * there is no per-subscriber state on the publishing server for it to change.
 *
 * That is also why nothing here says "completed". A proposal is not a request one person answers on
 * everyone's behalf: the publisher's half says whether the proposal still stands
 * ([proposalAvailability]), and this device's half says what this device did about it.
 */
data class ProposalRecord(
    /**
     * The feed connection this proposal was read on. A proposal is keyed by its published identity
     * ([ProposalKey]) and held under the connection that reads that publisher's feed, so removing
     * the feed removes its proposals with it, and a record whose identity disagrees with the
     * connection's publisher is refused rather than read.
     */
    val connectionId: String,
    val proposal: Proposal,
    /** When this device hid it, and which revision it hid. Null until the owner does. */
    val dismissed: ProposalDismissal? = null,
    /** What the owner reviewed and chose here. Null until they have. */
    val review: ProposalReview? = null,
    /** What this device did about it. Null until it began, and there is never more than one. */
    val execution: ProposalExecution? = null,
    /**
     * A delivery this phone refused for this proposal, and which rule it broke. Null when the
     * publisher has said nothing the phone could not accept.
     *
     * It exists for one case, and it is the one that can't be reported by declining to write: a
     * publisher that changes the terms without changing the revision has contradicted itself
     * ([ProposalProblem.ChangedWithoutRevision]). The phone keeps the terms it validated — this
     * device's own record of what it did must not be thrown away over the publisher's mistake — and
     * executes nothing further from the proposal until the publisher says something new. A higher
     * revision clears it, because that is the publisher saying something new.
     */
    val refused: ProposalProblem? = null,
) {
    init {
        require(isConnectionId(connectionId)) { "not a connection ID: $connectionId" }
    }

    val key: ProposalKey
        get() = proposal.key
}

/**
 * That the owner hid a proposal on this device, and the revision they hid.
 *
 * A dismissal is final for the proposal's identity. A replayed delivery must not bring a dismissed
 * item back, and neither may a new revision: otherwise a publisher could put a dismissed proposal
 * back in front of the owner by changing a number. A publisher with something else to propose
 * publishes another proposal, which is another identity (docs/wiki/shared-proposals.md#dismissal).
 */
data class ProposalDismissal(val revision: Long, val at: Instant)

/**
 * What the owner reviewed here, and what they chose.
 *
 * [revision] is the exact revision they read, not the latest: it is the whole point of keeping it.
 * When the publisher moves the terms, the review no longer describes them, and the owner reviews
 * again rather than having their earlier answer applied to something they never saw
 * ([BindingProblem.ProposalChanged]).
 *
 * [choice] is theirs and stays here. It is not sent to the publisher, to the gateway, or to any
 * server this phone talks to: a proposal is common, and a decision about it is not.
 */
data class ProposalReview(val revision: Long, val choice: ParameterChoice, val at: Instant)

/**
 * The one operation this device carried out about one proposal.
 *
 * It is written before the wallet is opened, which is what makes a second tap harmless: the second
 * one finds this and stops ([BindingProblem.AlreadyExecuted]). It is the same mechanic an approved
 * transfer uses, for the same reason (SAW-021).
 *
 * There is never a second one, and that is not relaxed for an outcome that failed or was declined,
 * nor for a new revision. A proposal that has already put an operation to the owner's wallet must
 * not become spendable again because the publisher republished it; a publisher that wants a second
 * operation publishes a second proposal.
 */
data class ProposalExecution(
    /** Exactly what was bound before the wallet was opened. */
    val binding: ExecutionBinding,
    val startedAt: Instant,
    val outcome: ProposalOutcome = ProposalOutcome.Pending,
    /** When the outcome was recorded; null while it is still [ProposalOutcome.Pending]. */
    val settledAt: Instant? = null,
)

/**
 * What came of the one operation this device executed.
 *
 * It is not a [io.github.brrenat.seekervault.connections.SigningOutcome], and the difference is the
 * point: a signing outcome is half of an answer a sidecar is owed, and it is sent until that
 * sidecar settles it. A proposal owes nobody an answer. Nothing here is delivered anywhere, and the
 * record exists for the owner alone.
 *
 * The bytes a plugin prepares are a transaction the wallet signs and sends, so there is no "signed
 * but not sent" state here — and [Unresolved] is never reported as a failure, because a transaction
 * the wallet may have sent is not one that didn't happen.
 */
sealed interface ProposalOutcome {
    /** The operation was bound and the wallet is being asked. Nothing is known yet. */
    data object Pending : ProposalOutcome

    /**
     * The wallet signed the prepared transaction and sent it. [signature] is its first signature,
     * which is its ID on chain. Whether it succeeded there is a separate question, and one this
     * phone doesn't answer: nothing in this build follows a proposal's transaction to the chain,
     * and nothing pretends to.
     */
    data class Submitted(val signature: ByteString) : ProposalOutcome

    /** The owner declined in the wallet, after choosing to go ahead here. */
    data object Declined : ProposalOutcome

    /**
     * The connection is a sandbox, so the operation was rehearsed and not performed (SEE-97,
     * docs/wiki/environments.md).
     *
     * Everything up to the wallet happened, and happened for real: the plugin read the provider,
     * built the bytes and they were inspected, the owner's rules were applied, and the binding
     * below was written. Then nothing was signed and nothing was sent. There is deliberately no
     * signature here and none is invented — an explorer link needs one, so a rehearsal offers none
     * — and this is a settled outcome, because a rehearsal is over when it is over.
     *
     * Like every other outcome, it means this device has finished with the proposal. A rehearsal is
     * what the owner asked for under this connection's promise, and a phone that then let the same
     * proposal be executed for real would be treating what they asked for as not having counted
     * (`proposalStanding`).
     */
    data object Simulated : ProposalOutcome

    /** Nothing was signed, and the phone knows why. [detail] is display text. */
    data class Failed(val detail: String) : ProposalOutcome

    /**
     * The phone never learned what the wallet did: the app closed, or the wallet never answered,
     * while the operation was with it. The wallet is not asked again — and the outcome is not
     * called a failure, because the transaction may well have been sent.
     */
    data class Unresolved(val detail: String) : ProposalOutcome
}

/** Whether this outcome is the last thing that will be said about the execution. */
val ProposalOutcome.settled: Boolean
    get() = this != ProposalOutcome.Pending

/** Whether the publisher's proposal still stands. This is the shared half, and only that. */
enum class ProposalAvailability(val code: String) {
    Open("open"),
    /** The publisher withdrew it. */
    Cancelled("cancelled"),
    /** Its own absolute expiry has passed. */
    Expired("expired"),
}

/**
 * Whether [proposal] still stands, at [now].
 *
 * Derived and never stored. A stored "expired" would be a fact about when it was written rather
 * than about the proposal, and it would be wrong the moment the clock moved. The publisher's
 * withdrawal is reported ahead of the clock because it is something the publisher did, and the
 * clock is only something that happened.
 */
fun proposalAvailability(proposal: Proposal, now: Instant): ProposalAvailability =
    when {
        proposal.status == ProposalStatus.Cancelled -> ProposalAvailability.Cancelled
        !now.isBefore(proposal.expiresAt) -> ProposalAvailability.Expired
        else -> ProposalAvailability.Open
    }

/**
 * Where a proposal stands for this owner: the publisher's availability, and what this device has
 * already done about it.
 *
 * Also derived, for the same reason as [proposalAvailability] and as
 * [io.github.brrenat.seekervault.servers.serverSupport]: it depends on the clock, on what this
 * device did, and on the plugins this build carries, and a verdict written to disk would outlive
 * all three.
 */
sealed interface ProposalStanding {
    /** The publisher stands behind it, this device has done nothing about it, and it may act. */
    data object Open : ProposalStanding

    /**
     * This device already executed it. It is said first, ahead of anything the publisher did
     * afterwards, because it is the fact about this phone: a proposal the publisher later withdrew
     * is still one this owner acted on.
     */
    data class Executed(val outcome: ProposalOutcome) : ProposalStanding

    /** The owner hid it here. */
    data class Dismissed(val at: Instant) : ProposalStanding

    /**
     * The publisher contradicted itself about this proposal, and the phone won't act on either
     * version of the terms ([ProposalRecord.refused]).
     */
    data class Refused(val problem: ProposalProblem) : ProposalStanding

    /** The publisher withdrew it. */
    data object Cancelled : ProposalStanding

    /** Its expiry has passed. */
    data object Expired : ProposalStanding

    /**
     * The publisher's server needs something this build doesn't have (SEE-88). The proposal is
     * readable and says so; nothing is prepared and no wallet is opened.
     */
    data class Unsupported(val support: ServerSupport) : ProposalStanding
}

fun proposalStanding(
    record: ProposalRecord,
    support: ServerSupport,
    now: Instant,
): ProposalStanding {
    record.execution?.let {
        return ProposalStanding.Executed(it.outcome)
    }
    record.dismissed?.let {
        return ProposalStanding.Dismissed(it.at)
    }
    record.refused?.let {
        return ProposalStanding.Refused(it)
    }
    return when (proposalAvailability(record.proposal, now)) {
        ProposalAvailability.Cancelled -> ProposalStanding.Cancelled
        ProposalAvailability.Expired -> ProposalStanding.Expired
        ProposalAvailability.Open ->
            if (support.executable) ProposalStanding.Open else ProposalStanding.Unsupported(support)
    }
}

/**
 * Whether anything may still be executed from a proposal in this standing. Only one of them, and
 * the check is exhaustive so a standing added later has to be decided about rather than inheriting
 * an answer.
 *
 * A standing that isn't executable is not a smaller kind of approval: there is no wallet
 * interaction to be had, and nothing falls back to signing a message or a transaction this phone
 * couldn't account for (docs/wiki/server-manifests.md#viewing-without-executing).
 */
val ProposalStanding.executable: Boolean
    get() =
        when (this) {
            is ProposalStanding.Open -> true
            is ProposalStanding.Executed,
            is ProposalStanding.Dismissed,
            is ProposalStanding.Refused,
            is ProposalStanding.Cancelled,
            is ProposalStanding.Expired,
            is ProposalStanding.Unsupported -> false
        }
