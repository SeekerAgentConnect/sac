package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.TransferInspection

/**
 * What the phone itself established about one request, which is all a policy is ever applied to
 * (docs/policy.md#what-is-evaluated).
 *
 * Every field here comes from something the phone read: the transaction's own bytes for what moves,
 * where, and how much (SAW-020), the structured request for the kind of action, and the owner's own
 * wallet selection for the chain. Nothing an agent wrote is an input — not the description, not the
 * memo, not the sidecar's account of what it built. A fact the phone couldn't establish is null,
 * and null never passes a check.
 */
data class RequestFacts(
    /** The connection the request came from. A policy is only ever applied to its own. */
    val connectionId: String,
    /**
     * The connection-scoped request identity, used only to avoid projecting an existing attempt
     * twice.
     */
    val requestId: String? = null,
    /** The wallet that would pay, read from the transaction; null when the bytes don't say. */
    val wallet: String?,
    /** The kind of action, or null when this build has no name for it. */
    val action: PolicyAction?,
    /**
     * Whether this request moves value at all. An acknowledgement and a message signature don't:
     * they satisfy every rule about assets, recipients, programs, and amounts by doing nothing with
     * any of them, and the review says as much rather than pretending a check was run.
     */
    val movesValue: Boolean,
    /** The asset that moves, on the chain the owner's wallet is selected for. */
    val asset: PolicyAsset?,
    /** The wallet the funds provably reach. Null when the bytes don't establish one. */
    val recipient: String?,
    /** Every program the transaction calls. Null when the phone couldn't read the bytes at all. */
    val programs: List<String>?,
    /** Base units, exactly as the instruction carries them. */
    val amount: ULong?,
    /** The decimals the amount is shown with; display only, and never used to compare. */
    val decimals: Int,
    /**
     * Whether the phone accounted for every instruction in the transaction. When it didn't, the
     * assessment is withheld however well the part it did read matched the rules: an unread
     * instruction is a gap in the review, and no rule was written about what is in the gap.
     */
    val fullyRead: Boolean,
    /**
     * The preparation this was read from, which a stored assessment names; 0 when there is none.
     */
    val preparedVersion: Int = 0,
) {
    /** The counter this request would count against, or null when it can't be established. */
    val scope: SpendScope?
        get() {
            val wallet = wallet ?: return null
            val asset = asset ?: return null
            return SpendScope(connectionId, wallet, asset)
        }

    companion object {
        /**
         * A request that moves nothing: an acknowledgement, or a message signature. There are no
         * bytes to leave unread, so nothing about it is uncovered.
         */
        fun movesNothing(
            connectionId: String,
            action: PolicyAction?,
            requestId: String? = null,
        ): RequestFacts =
            RequestFacts(
                connectionId = connectionId,
                requestId = requestId,
                wallet = null,
                action = action,
                movesValue = false,
                asset = null,
                recipient = null,
                programs = null,
                amount = null,
                decimals = LAMPORT_DECIMALS,
                fullyRead = true,
            )

        /**
         * A request that moves value and whose transaction the phone has not read: a swap, which
         * Stage 6 will read, or a preparation that hasn't arrived. Nothing about it is established,
         * so nothing about it can be allowed.
         */
        fun unread(
            connectionId: String,
            action: PolicyAction?,
            requestId: String? = null,
        ): RequestFacts =
            RequestFacts(
                connectionId = connectionId,
                requestId = requestId,
                wallet = null,
                action = action,
                movesValue = true,
                asset = null,
                recipient = null,
                programs = null,
                amount = null,
                decimals = LAMPORT_DECIMALS,
                fullyRead = false,
            )
    }
}

/** The kind of action, as a policy names it, or null for one this build has no name for. */
fun policyAction(request: ActionRequest): PolicyAction? =
    when (request.action.kindCase) {
        Action.KindCase.ACK -> PolicyAction.Acknowledgement
        Action.KindCase.SIGN_MESSAGE -> PolicyAction.MessageSignature
        Action.KindCase.TRANSFER -> PolicyAction.Transfer
        Action.KindCase.SWAP -> PolicyAction.Swap
        else -> null
    }

/**
 * What the phone established about [request], with [inspection] when its transaction has been read
 * and [network] the chain the owner's wallet is selected for.
 *
 * [network] comes from the wallet the owner connected on this phone, not from the request: the
 * chain a rule is about is the one the owner is on, and the inspection has already refused a
 * preparation whose request names another (`Finding.NetworkMismatch`).
 */
fun policyFacts(
    connectionId: String,
    request: ActionRequest,
    network: Network,
    inspection: TransferInspection? = null,
): RequestFacts {
    val action = policyAction(request)
    return when (action) {
        PolicyAction.Acknowledgement,
        PolicyAction.MessageSignature ->
            RequestFacts.movesNothing(connectionId, action, request.ref.requestId)
        // A swap and a prediction order both move value, and core prepares neither: an
        // `ActionRequest` for one establishes nothing here, whatever a bundled plugin can do with
        // the same operation when a publisher broadcasts it (SEE-93, SEE-94). An unchecked
        // operation is not an allowed one, and this is where that stays true for the private path.
        PolicyAction.Swap,
        PolicyAction.Prediction -> RequestFacts.unread(connectionId, action, request.ref.requestId)
        PolicyAction.Transfer ->
            transferFacts(connectionId, request.ref.requestId, action, network, inspection)
        // An action this build has no name for moves value as far as it knows.
        null -> RequestFacts.unread(connectionId, null, request.ref.requestId)
    }
}

private fun transferFacts(
    connectionId: String,
    requestId: String,
    action: PolicyAction,
    network: Network,
    inspection: TransferInspection?,
): RequestFacts {
    val facts = inspection?.facts ?: return RequestFacts.unread(connectionId, action, requestId)
    return RequestFacts(
        connectionId = connectionId,
        requestId = requestId,
        wallet = facts.payer.takeIf(String::isNotEmpty),
        action = action,
        movesValue = true,
        // An asset with no chain names nothing to spend, so a wallet that isn't connected leaves
        // the asset unestablished rather than guessing one.
        asset =
            if (network == Network.NETWORK_UNSPECIFIED) null else PolicyAsset(network, facts.mint),
        recipient = facts.recipient,
        programs = facts.programs,
        amount = facts.amount,
        decimals = facts.decimals,
        fullyRead = facts.recognizedInstructions == facts.instructionCount,
        preparedVersion = inspection.version,
    )
}
