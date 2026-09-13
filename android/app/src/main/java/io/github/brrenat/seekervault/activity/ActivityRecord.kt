package io.github.brrenat.seekervault.activity

import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant

/**
 * What kind of thing one record is about. It decides what a signature in the record means, which is
 * the difference that matters most here: a transfer's signature is a transaction's ID on chain, and
 * a message's signature is not a transaction at all (docs/guides/transfers.md).
 */
enum class ActivityKind {
    /** Display-only text the owner acknowledged. Nothing was signed. */
    Acknowledgement,
    /** A message the owner's wallet signed. It moves nothing and reaches no network. */
    MessageSignature,
    /** A payment from the owner's wallet. */
    Transfer,
    /** Something this version of the app doesn't have a name for. */
    Other,
}

/** Where a record stands. One per record, and it is what the owner reads first. */
enum class ActivityOutcome {
    /** The owner answered, and the answer hasn't reached the server yet. */
    Waiting,
    Acknowledged,
    Rejected,
    /** The owner declined in the wallet, after approving on this phone. */
    DeclinedInWallet,
    /** The wallet signed a message. There is a signature, and no transaction. */
    MessageSigned,
    /** The wallet sent the transaction. Whether it went through is a separate question. */
    Sent,
    /** The network confirmed the approved transaction. */
    Confirmed,
    /** The transaction ran on the network and failed. */
    ChainFailed,
    /** The wallet couldn't sign or send, and said so. Nothing happened. */
    NotSigned,
    /** Nobody knows: the wallet's answer never reached this phone, or the chain settled nothing. */
    Unknown,
    /** The answer never reached the server, and can't. */
    NotDelivered,
    /** The request had moved on — cancelled or expired — before the answer arrived. */
    Superseded,
}

/**
 * The transfer the owner reviewed, in the terms they reviewed it in. The amount is in the asset's
 * base units, which is the number the transaction carries: a decimal count read from a mint once
 * and kept for months is not worth the chance of showing the wrong amount later.
 *
 * The phone refuses to approve a transfer whose transaction doesn't match these terms byte for byte
 * (SAW-020), so recording the terms records what was approved.
 */
data class ReviewedTransfer(
    /** The wallet that pays. */
    val wallet: String,
    /** The cluster it was bound to. A signature belongs to this cluster and to no other. */
    val network: Network,
    val recipient: String,
    /** Base units, as a decimal string: lamports for SOL, the mint's own units for a token. */
    val amount: String,
    /** The SPL token's mint, or null for native SOL. */
    val mint: String? = null,
    /** The preparation the approval was bound to, which is what the sidecar checked it against. */
    val preparedVersion: Int = 0,
)

/**
 * What the owner was shown about their own rules when they answered, kept with the record (SAW-028,
 * docs/policy.md#the-stored-snapshot).
 *
 * It is codes and nothing else: the verdict's code, the reasons behind it, and the checks nothing
 * covered. The rules themselves are never written here — not a threshold, not an address, not a
 * list — because the record is about what the owner read, and the rules are already stored once, in
 * the one place they belong. Nothing in here ever reaches the sidecar.
 *
 * It records no decision either. An assessment approved nothing when it was shown, and reading it
 * back months later approves nothing now; [approvedAnyway] is the only thing here about what the
 * owner did, and it says they went ahead with a warning in front of them.
 */
data class ReviewedPolicy(
    /** The verdict's own code: `allowed` or `under_restrictions`. */
    val assessment: String,
    /** Every reason behind the verdict, by code, in the order they were shown. */
    val reasons: List<String> = emptyList(),
    /** The checks no rule covered, by code. ALLOWED never claims anything about these. */
    val notChecked: List<String> = emptyList(),
    /** When the assessment the owner read was made. */
    val assessedAt: Instant,
    /** Whether the owner answered with a warning on screen, having said so on purpose. */
    val approvedAnyway: Boolean = false,
)

/**
 * One thing this phone did about one request, kept as the record of it
 * (docs/security.md#local-storage-and-recovery). It is not the answer the sidecar is owed — that is
 * a `LocalResult`, and it is dropped a week after it settles and when its connection is removed.
 * This outlives both, because what was spent is worth keeping.
 *
 * It holds no credential and no key: a connection ID, public addresses, amounts, and what came of
 * them.
 */
data class ActivityRecord(
    val connectionId: String,
    val requestId: String,
    /** The connection's name when the record was written, so a renamed connection still reads. */
    val source: String,
    /**
     * The host the request came from. Never the URL: that is the connection's, not the record's.
     */
    val serverHost: String,
    val kind: ActivityKind,
    /** When the owner answered. */
    val answeredAt: Instant,
    /** When this record was last written. */
    val recordedAt: Instant,
    val outcome: ActivityOutcome,
    /** The terms of a transfer; null for everything else. */
    val transfer: ReviewedTransfer? = null,
    /**
     * What the rules made of the request when the owner answered; null when nothing assessed it.
     */
    val policy: ReviewedPolicy? = null,
    /** The wallet's signature in base58, once there is one. */
    val signature: String? = null,
    /** What the server or the wallet said about how it ended. */
    val detail: String? = null,
    /** The host whose word a confirmed or failed transfer rests on; null when nothing checked. */
    val checkedWith: String? = null,
) {
    val key: RequestKey
        get() = RequestKey(connectionId, requestId)

    /**
     * Whether [signature] is a transaction's ID on chain. A message signature is a signature over
     * bytes and nothing more: no explorer has it, and calling it a payment would be a lie.
     */
    val signatureIsTransaction: Boolean
        get() = kind == ActivityKind.Transfer && signature != null

    /**
     * What makes this the same record and not another: the connection it came from, the wallet that
     * paid, the cluster it was on, and the asset that moved. A later write that disagrees on any of
     * them is about something else, whatever request ID it carries.
     */
    val identity: ActivityIdentity
        get() =
            ActivityIdentity(
                connectionId = connectionId,
                wallet = transfer?.wallet,
                network = transfer?.network,
                asset = transfer?.mint,
            )
}

/** See [ActivityRecord.identity]. */
data class ActivityIdentity(
    val connectionId: String,
    val wallet: String?,
    val network: Network?,
    /** The mint, or null for native SOL and for anything that isn't a transfer. */
    val asset: String?,
)
