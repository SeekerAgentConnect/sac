package io.github.brrenat.seekervault.connections

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Approval
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.approval
import java.time.Instant

/** A request, by both IDs: a request ID is unique only within its connection. */
data class RequestKey(val connectionId: String, val requestId: String)

/** The owner's answer to a request waiting on this phone. */
enum class Answer {
    /** An acknowledgement: the owner tapped OK on a queued `ack`. */
    Acknowledge,
    /** A refusal, of any kind of request. Nothing is signed. */
    Reject,
    /**
     * The owner approved a wallet action after reviewing it: the exact bytes of a `sign_message`
     * request, or the transaction this phone read for a `transfer` (SAW-020). Only then does the
     * app ask the wallet; what the wallet answers is kept in [LocalResult.signing].
     */
    Approve,
}

/**
 * The exact transaction the owner approved, stored before the wallet is ever opened
 * (docs/architecture.md#approval-binding). It is the record of the execution this phone is about to
 * attempt, and it is what binds the approval: the sidecar accepts it only while [version] is the
 * request's latest preparation and [contentHash] is that preparation's own.
 *
 * [transaction] is kept because the wallet must be handed the bytes the owner approved and no
 * others. Fetching them again would hand the wallet whatever the sidecar has now, which is the one
 * thing an approval must not do.
 */
data class ApprovedTransaction(
    /** The `PreparedTransaction.version` the owner reviewed. */
    val version: Int,
    /** SHA-256 of [transaction], which the phone computed itself. */
    val contentHash: ByteString,
    /** The unsigned transaction, byte for byte as it was reviewed. */
    val transaction: ByteString,
)

/**
 * What the wallet did with an approved message (docs/guides/message-signing.md) or an approved
 * transfer (docs/guides/transfers.md). A message is never broadcast, so no signature over one can
 * be in doubt. A transfer can be, which is what [Unresolved] means for one.
 */
sealed interface SigningOutcome {
    /** The wallet signed: 64 bytes, which the sidecar verifies against the request's wallet. */
    data class Signed(val signature: ByteString) : SigningOutcome

    /**
     * The wallet signed the approved transaction and sent it (SAW-021). [signature] is the
     * transaction's first signature, which is its ID on chain. Whether it succeeds there is a
     * separate question, and one this phone doesn't answer (SAW-022).
     */
    data class Sent(val signature: ByteString) : SigningOutcome

    /** The owner declined in the wallet. */
    data object Declined : SigningOutcome

    /** Nothing was signed, and the phone knows why. [detail] is display text, and goes as one. */
    data class Failed(val detail: String) : SigningOutcome

    /**
     * The phone never learned what the wallet did: the app closed, or the wallet never answered,
     * while the action was with it (SAW-017). The wallet is never asked a second time either way,
     * but what it means differs by what was with it. For a message, no signature reached this phone
     * and none exists anywhere, so the sidecar is told the request failed. For a transfer, the
     * wallet may have sent the transaction, so the sidecar is told the outcome is UNKNOWN: an
     * outcome nobody knows must never be reported as one somebody does. [detail] is display text,
     * and goes as one.
     */
    data class Unresolved(val detail: String) : SigningOutcome
}

/** Where an answer stands between this phone and its sidecar. */
enum class Delivery {
    /** Kept on the phone until the sidecar confirms it, and sent again on each refresh. */
    Waiting,
    /** The sidecar accepted it. */
    Accepted,
    /**
     * The request had moved on before the answer arrived: the agent cancelled it, or it expired.
     */
    Superseded,
    /** The sidecar no longer accepts this phone's connection, so the answer can't be sent. */
    Undeliverable,
}

/**
 * An answer given on this phone (docs/guides/pending-requests.md). It's stored before it's sent, so
 * a crash, a dead network, or a lost response can't lose it.
 */
data class LocalResult(
    val connectionId: String,
    val requestId: String,
    val answer: Answer,
    val answeredAt: Instant,
    /** The request as the owner answered it, and afterwards as the sidecar last reported it. */
    val request: ActionRequest,
    val delivery: Delivery = Delivery.Waiting,
    /** Why the last attempt to send it failed, while it's waiting. */
    val lastFailure: CheckOutcome? = null,
    /**
     * When the sidecar settled it, or the phone found it undeliverable; null while it's waiting. A
     * settled answer is kept for a week from then.
     */
    val settledAt: Instant? = null,
    /**
     * True once the sidecar has accepted the owner's approval, which is the point where the request
     * becomes PROCESSING and the wallet may be asked. Only for [Answer.Approve].
     */
    val approved: Boolean = false,
    /**
     * True when the approval was sent and this phone never learned whether the sidecar took it: the
     * connection dropped, or the response was lost. The sidecar may well have moved the request to
     * PROCESSING, so the approval is kept, and the next delivery asks the sidecar what became of it
     * rather than guessing (SAW-021). No wallet was opened either way.
     */
    val approvalUncertain: Boolean = false,
    /** The wallet's answer to an approved message or transfer; null until it has given one. */
    val signing: SigningOutcome? = null,
    /**
     * For an approved transfer, the transaction the owner approved and what binds the approval to
     * it. Null for every other answer. It is written before the wallet is opened, and the bytes the
     * wallet is handed come from here.
     */
    val approvedTransaction: ApprovedTransaction? = null,
) {
    val key: RequestKey
        get() = RequestKey(connectionId, requestId)

    /**
     * An approved transfer the sidecar demonstrably hasn't accepted. The wallet is opened only once
     * it has, so this one was never asked anything: nothing is signed, nothing is sent, and nothing
     * is owed to the agent. It is removed rather than reported (SAW-021).
     *
     * An approval whose fate this phone never learned ([approvalUncertain]) is not one of these:
     * dropping it would leave the sidecar holding a PROCESSING request that nothing can ever
     * settle.
     */
    val uncommittedTransfer: Boolean
        get() =
            answer == Answer.Approve &&
                approvedTransaction != null &&
                !approved &&
                !approvalUncertain

    /**
     * Whether asking the sidecar again could still change what this says (SAW-022). A transfer the
     * sidecar accepted stops at SUBMITTED, or at UNKNOWN when the wallet's answer was lost, and
     * only the chain settles it. Checking opens no wallet and sends nothing: it asks the sidecar
     * what it has learned, and nothing else.
     */
    val awaitingChain: Boolean
        get() =
            delivery == Delivery.Accepted &&
                request.hasAction() &&
                request.action.hasTransfer() &&
                request.state !in SETTLED
}

/** The states a request can no longer leave (docs/protocol.md#lifecycle). */
private val SETTLED =
    setOf(
        RequestState.REQUEST_STATE_CONFIRMED,
        RequestState.REQUEST_STATE_COMPLETED,
        RequestState.REQUEST_STATE_REJECTED,
        RequestState.REQUEST_STATE_CANCELLED,
        RequestState.REQUEST_STATE_EXPIRED,
        RequestState.REQUEST_STATE_FAILED,
    )

/** What came of sending the owner's approval of a transfer to the sidecar. */
sealed interface ApprovalOutcome {
    /**
     * The sidecar accepted it: the request is PROCESSING there, and the wallet may now be asked
     * with [LocalResult.approvedTransaction]'s bytes and no others.
     */
    data class Accepted(val result: LocalResult) : ApprovalOutcome

    /**
     * The approval doesn't name the request's latest preparation, or that preparation can no longer
     * land. Nothing was approved. The phone prepares the request again, and the owner reviews the
     * new version before anything else happens.
     */
    data object Stale : ApprovalOutcome

    /** The request had already moved on: the agent cancelled it, or it expired. */
    data class Superseded(val request: ActionRequest) : ApprovalOutcome

    /** Nothing was approved, and nothing reached the wallet. [outcome] says why. */
    data class Refused(val outcome: CheckOutcome, val detail: String? = null) : ApprovalOutcome
}

/**
 * The approval that binds the owner's decision to exactly this preparation, and to nothing else.
 * The sidecar refuses it unless [ApprovedTransaction.version] is the request's latest prepared
 * version and the hash is that version's own, so an approval can never carry over to a transaction
 * the owner didn't see.
 */
fun ApprovedTransaction.toApproval(): Approval {
    val approved = this
    return approval {
        preparedVersion = approved.version
        contentHash = approved.contentHash
    }
}
