package io.github.brrenat.seekervault.plugins

import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.policyAction
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Network

/**
 * Who would carry out one action, and what the owner's rules are therefore applied to (SEE-86).
 *
 * This is the whole of core's knowledge about plugins. It names operations at the protocol's own
 * level and asks a registry whether anything serves them; it never names a provider, so adding
 * `jupiter.swap` (SEE-93) changes [PluginRegistry.bundled] and nothing in `connections/`, `sync/`,
 * `live/`, or `push/` (docs/wiki/client-plugins.md).
 */

/** The operation a swap request asks for. A plugin claims it; core doesn't know who serves it. */
val SWAP_OPERATION: OperationId = OperationId("swap")

/** Who would carry out an action. */
sealed interface ActionOwner {
    /**
     * The app itself, as it always has: an acknowledgement, a message signature, or a transfer. No
     * plugin is consulted for these, and nothing about them changed in this stage.
     */
    data object Core : ActionOwner

    /** An operation a plugin would serve, if this build carries one for it. */
    data class Plugin(val operation: OperationId) : ActionOwner

    /**
     * An action kind this build has no name for. There is nothing to ask a plugin for and nothing
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
        Action.KindCase.SWAP -> ActionOwner.Plugin(SWAP_OPERATION)
        else -> ActionOwner.Unnamed
    }

/**
 * What the phone established about a request a plugin would carry out, which is all a policy is
 * ever applied to.
 *
 * [network] is the chain the owner's wallet is selected for, and it is core's: an asset with no
 * chain names nothing to spend, and a plugin's own idea of which network its bytes are on is not
 * consulted (docs/policy.md#what-is-evaluated).
 *
 * Nothing is established unless a plugin read it. An operation no plugin serves, one whose bytes
 * haven't been prepared, and one whose bytes couldn't be read all come back the same way: value
 * moves, nothing is verified, and the verdict can never be ALLOWED ([RequestFacts.unread]). A
 * missing plugin is a gap in the review and never a byte that turned out to be fine.
 */
fun pluginFacts(
    connectionId: String,
    request: ActionRequest,
    network: Network,
    resolution: PluginResolution,
    inspection: ActionInspection? = null,
): RequestFacts {
    val action = policyAction(request)
    val requestId = request.ref.requestId
    if (resolution !is PluginResolution.Supported) {
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
