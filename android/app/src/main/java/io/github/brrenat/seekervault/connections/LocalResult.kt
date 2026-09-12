package io.github.brrenat.seekervault.connections

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.request.v1.ActionRequest
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
     * The owner approved a `sign_message` request, after reviewing the exact bytes. Only then does
     * the app ask the wallet; what the wallet answers is kept in [LocalResult.signing].
     */
    Approve,
}

/**
 * What the wallet did with an approved message (docs/guides/message-signing.md). Nothing is
 * broadcast either way, so there is no uncertain outcome to record.
 */
sealed interface SigningOutcome {
    /** The wallet signed: 64 bytes, which the sidecar verifies against the request's wallet. */
    data class Signed(val signature: ByteString) : SigningOutcome

    /** The owner declined in the wallet. */
    data object Declined : SigningOutcome

    /** Nothing was signed. [detail] is display text, and goes to the sidecar as one. */
    data class Failed(val detail: String) : SigningOutcome
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
    /** The wallet's answer to an approved message; null until it has given one. */
    val signing: SigningOutcome? = null,
) {
    val key: RequestKey
        get() = RequestKey(connectionId, requestId)
}
