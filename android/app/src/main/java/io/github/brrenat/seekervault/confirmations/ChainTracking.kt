package io.github.brrenat.seekervault.confirmations

import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant

/**
 * Where a transaction the owner's wallet sent was found on chain, by this phone and nothing else
 * (SEE-165, docs/wiki/chain-confirmation.md).
 *
 * It is separate from every other fact about the same request: the owner's decision, what the
 * wallet did, and whether a server was told are kept where they always were. This one says what the
 * chain showed, and it only ever says something the phone verified itself.
 */
enum class ChainState(val code: String) {
    /**
     * The transaction was captured for the wallet and the wallet has not answered yet. Nothing is
     * looked up: there is no signature to look up.
     */
    Awaiting("awaiting"),

    /** There is a signature, and the chain has not settled it yet as far as this phone knows. */
    Checking("checking"),

    /** The approved transaction ran and succeeded, at [ChainCheck.level]. */
    Confirmed("confirmed"),

    /** The approved transaction ran and failed, with [ChainCheck.chainError]. */
    Failed("failed"),

    /**
     * Proven never to land: its blockhash is no longer valid on the finalized chain and a search of
     * the ledger has no record of the signature. Nothing was spent.
     */
    Expired("expired"),

    /**
     * No automatic check will settle it: the chain holds another transaction under the signature,
     * the record has no verification context, or automatic checks ran out. [ChainCheck.reason] says
     * which, and a manual check is still allowed where it can help.
     */
    Unresolved("unresolved");

    /** Whether the chain gave a final answer about the approved transaction. */
    val verified: Boolean
        get() = this == Confirmed || this == Failed || this == Expired

    companion object {
        fun of(code: String): ChainState? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The commitment a status was observed at. [Processed] is not a result: a node saw it, and no
 * supermajority has voted on it yet.
 */
enum class ChainLevel(val code: String) {
    Processed("processed"),
    Confirmed("confirmed"),
    Finalized("finalized");

    companion object {
        fun of(code: String?): ChainLevel? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Why a check settled nothing, or why a record is [ChainState.Unresolved]. Codes, so a later build
 * with better words can read an old record better.
 */
enum class ChainReason(val code: String) {
    /** The endpoint has no status for the signature yet, and it can still land. */
    NotYetVisible("not_yet_visible"),
    /** A node processed it; nobody has voted on its block yet. */
    ProcessedOnly("processed_only"),
    /** Confirmed, and the transaction body wasn't served yet to be compared. */
    BodyNotServed("body_not_served"),
    /** Settled, and checks continue until the finalized level is observed. */
    AwaitingFinality("awaiting_finality"),
    /** No endpoint is configured for the record's cluster. */
    NoEndpoint("no_rpc_endpoint"),
    /** The configured endpoint serves another cluster. Nothing it says is about this record. */
    WrongCluster("rpc_wrong_cluster"),
    Unreachable("rpc_unreachable"),
    RateLimited("rpc_rate_limited"),
    Refused("rpc_refused"),
    Unusable("rpc_unusable"),
    /** The chain holds a different transaction under this signature. */
    Mismatch("mismatch"),
    /** The record predates tracking and has no approved message to compare. */
    MissingContext("missing_context"),
    /** Automatic checks stopped; a manual check is still possible. */
    GaveUp("gave_up");

    companion object {
        fun of(code: String?): ChainReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What the phone last learned about one transaction on chain. It is what History shows, and it is
 * stored beside the Activity record as well as in the tracking record, so a record that outlives
 * its tracking still says what was found.
 *
 * [host] is only ever a host: a configured endpoint can carry an API key in its path or query.
 */
data class ChainCheck(
    val state: ChainState,
    val level: ChainLevel? = null,
    val slot: Long? = null,
    val chainError: String? = null,
    val checkedAt: Instant? = null,
    val checks: Int = 0,
    val host: String? = null,
    val reason: ChainReason? = null,
    /** When the next automatic check is due; null when none will run. */
    val nextCheckAt: Instant? = null,
) {
    /** Whether the owner can usefully ask again. */
    val checkable: Boolean
        get() =
            state == ChainState.Checking ||
                (state == ChainState.Unresolved && reason != ChainReason.MissingContext) ||
                (state == ChainState.Confirmed && level != ChainLevel.Finalized) ||
                (state == ChainState.Failed && level != ChainLevel.Finalized)
}

/** Where a tracked transaction came from, which decides nothing but how it is described. */
enum class TrackingOrigin(val code: String) {
    /** A direct server's transfer or staking request. */
    Direct("direct"),
    /** An operation executed from a feed's shared proposal. */
    Operation("operation");

    companion object {
        fun of(code: String): TrackingOrigin? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One transaction the phone follows to the chain.
 *
 * Everything the check needs is pinned here when the transaction is captured — before the wallet is
 * opened for an operation, and from the stored approval for a direct request — and nothing is read
 * from the phone's current wallet or connection afterwards. A wallet or network switch cannot
 * redirect an old check, and removing the connection doesn't stop one.
 */
data class ChainTracking(
    val key: RequestKey,
    val origin: TrackingOrigin,
    /** The cluster the transaction was bound to. It is the only cluster whose word counts. */
    val network: Network,
    /** The wallet that signed, as a public address. */
    val wallet: String,
    /** The approved message bytes: what the wallet's signature covers, and what is compared. */
    val message: ByteArray,
    /** The message's recent blockhash, which is what bounds when it can land. */
    val blockhash: String?,
    val capturedAt: Instant,
    /** The transaction's ID on chain, in base58; null while [ChainState.Awaiting]. */
    val signature: String? = null,
    val submittedAt: Instant? = null,
    val check: ChainCheck = ChainCheck(ChainState.Awaiting),
    /** Consecutive automatic attempts that settled nothing, which is what the backoff counts. */
    val attempts: Int = 0,
) {
    /** Whether automatic checks still have something to find out. */
    val unfinished: Boolean
        get() =
            signature != null &&
                check.nextCheckAt != null &&
                (check.state == ChainState.Checking ||
                    ((check.state == ChainState.Confirmed || check.state == ChainState.Failed) &&
                        check.level != ChainLevel.Finalized))

    override fun equals(other: Any?): Boolean =
        other is ChainTracking &&
            key == other.key &&
            origin == other.origin &&
            network == other.network &&
            wallet == other.wallet &&
            message.contentEquals(other.message) &&
            blockhash == other.blockhash &&
            capturedAt == other.capturedAt &&
            signature == other.signature &&
            submittedAt == other.submittedAt &&
            check == other.check &&
            attempts == other.attempts

    override fun hashCode(): Int = key.hashCode() * 31 + message.contentHashCode()
}
