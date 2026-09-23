package io.github.brrenat.seekervault.plugins

import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.policyAction
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Network

/**
 * Who would carry out one action, and what the owner's rules are therefore applied to (SEE-86,
 * SEE-145).
 *
 * This is the whole of core's knowledge about execution providers. It names actions at the
 * protocol's own level and asks a registry whether anything serves them; it never names a venue, so
 * the Jupiter adapter is registered in `SeekerVaultApplication` and nothing in `connections/`,
 * `sync/`, `live/` or `push/` changes for it (docs/wiki/execution-providers.md).
 */

/** Who would carry out an action. */
sealed interface ActionOwner {
    /**
     * The app itself, as it always has: an acknowledgement, a message signature, or a transfer. No
     * provider is consulted for these, and nothing about them changed in this stage.
     */
    data object Core : ActionOwner

    /** An action an execution provider would serve, if this build carries one for it. */
    data class Provider(val action: ActionId) : ActionOwner

    /**
     * An action kind this build has no name for. There is nothing to ask a provider for and nothing
     * established about it, which is not the same as nothing being wrong with it.
     */
    data object Unnamed : ActionOwner
}

/** Who would carry out [request]'s action. */
fun actionOwner(request: ActionRequest): ActionOwner =
    when (request.action.kindCase) {
        Action.KindCase.ACK,
        Action.KindCase.SIGN_MESSAGE,
        Action.KindCase.TRANSFER -> ActionOwner.Core
        Action.KindCase.SWAP -> ActionOwner.Provider(SWAP_ACTION)
        else -> ActionOwner.Unnamed
    }

/**
 * The rule vocabulary an action is written in, stated rather than inferred from its name.
 *
 * The two vocabularies used to coincide by spelling — an action called `swap` met a rule called
 * `swap` — and SEE-145 stopped relying on that: `prediction.buy` is the action, `prediction` is the
 * rule the owner wrote, and they mean the same thing because this function says so. An action no
 * rule vocabulary covers establishes no action, and an action nothing was written about passes
 * nothing (docs/policy.md#what-is-evaluated).
 */
fun policyActionFor(action: ActionId): PolicyAction? =
    when (action) {
        SWAP_ACTION -> PolicyAction.Swap
        PREDICTION_BUY_ACTION -> PolicyAction.Prediction
        else -> null
    }

/**
 * What the phone established about a request a provider would carry out, which is all a policy is
 * ever applied to.
 *
 * [network] is the chain the owner's wallet is selected for, and it is core's: an asset with no
 * chain names nothing to spend, and a provider's own idea of which network its bytes are on is not
 * consulted (docs/policy.md#what-is-evaluated).
 *
 * Nothing is established unless a provider read it. An action no provider serves, one whose bytes
 * haven't been prepared, and one whose bytes couldn't be read all come back the same way: value
 * moves, nothing is verified, and the verdict can never be ALLOWED ([RequestFacts.unread]). A
 * missing provider is a gap in the review and never a byte that turned out to be fine.
 */
fun actionFacts(
    connectionId: String,
    request: ActionRequest,
    network: Network,
    resolution: ProviderResolution,
    inspection: ActionInspection? = null,
): RequestFacts =
    actionFacts(
        connectionId = connectionId,
        action = policyAction(request),
        requestId = request.ref.requestId,
        network = network,
        resolution = resolution,
        inspection = inspection,
    )

/**
 * The same, for an operation that came from a publisher's broadcast rather than from a request
 * addressed to this phone (SEE-93).
 *
 * There is no `ActionRequest` to read the kind of action out of, so the action's own identity is
 * translated into the rule vocabulary ([policyActionFor]). So a rule the owner wrote about swaps
 * applies to a swap whether an agent asked for it privately or a publisher broadcast it.
 *
 * [proposalId] takes the place of a request ID for the one thing that identity is used for:
 * counting a day's spending once rather than twice.
 */
fun actionFacts(
    connectionId: String,
    proposalId: String,
    action: ActionId,
    network: Network,
    resolution: ProviderResolution,
    inspection: ActionInspection? = null,
): RequestFacts =
    actionFacts(
        connectionId = connectionId,
        action = policyActionFor(action),
        requestId = proposalId,
        network = network,
        resolution = resolution,
        inspection = inspection,
    )

private fun actionFacts(
    connectionId: String,
    action: PolicyAction?,
    requestId: String,
    network: Network,
    resolution: ProviderResolution,
    inspection: ActionInspection?,
): RequestFacts {
    if (resolution !is ProviderResolution.Supported) {
        return RequestFacts.unread(connectionId, action, requestId)
    }
    val facts = inspection?.facts ?: return RequestFacts.unread(connectionId, action, requestId)
    return RequestFacts(
        connectionId = connectionId,
        requestId = requestId,
        wallet = facts.wallet?.takeIf(String::isNotEmpty),
        action = action,
        movesValue = facts.movesValue,
        // The chain is the owner's, so a wallet that isn't connected leaves the asset
        // unestablished rather than guessing one — exactly as a transfer does.
        asset =
            if (!facts.movesValue || network == Network.NETWORK_UNSPECIFIED) null
            else PolicyAsset(network, facts.mint),
        recipient = facts.recipient,
        programs = facts.programs,
        amount = facts.amount,
        decimals = facts.decimals,
        fullyRead = facts.fullyRead,
        preparedVersion = inspection.version,
    )
}
