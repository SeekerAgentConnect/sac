package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.request.v1.ActionRequest
import java.time.Instant

/** A request, by both IDs: a request ID is unique only within its connection. */
data class RequestKey(val connectionId: String, val requestId: String)

/** The owner's answer to a queued acknowledgement. */
enum class Answer {
    Acknowledge,
    Reject,
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
) {
    val key: RequestKey
        get() = RequestKey(connectionId, requestId)
}
