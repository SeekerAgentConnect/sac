package io.github.brrenat.seekervault.plugins.actions

import io.github.brrenat.seekervault.plugins.ActionId
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_SCHEMA_VERSION
import io.github.brrenat.seekervault.plugins.PluginFinding
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.SWAP_SCHEMA_VERSION

/**
 * What one action is about, read once and typed (SEE-145).
 *
 * Before this stage a plugin was handed the publisher's bag of strings and parsed it itself. That
 * worked while exactly one plugin existed per operation and stopped being defensible the moment a
 * second provider of the same action became possible: two providers parsing the same document their
 * own way is two chances to disagree about what the owner is looking at, and a publisher is a
 * stranger. So core reads the payload against the action's own schema, before any provider is
 * consulted, and hands the result over already validated.
 *
 * A provider still decides everything that is genuinely its own — which stake tokens it settles in,
 * what it will build, what the bytes turn out to say — and nothing here is believed about any of
 * that (docs/wiki/execution-providers.md#the-action-and-the-provider).
 */
sealed interface ActionPayload {
    /** Exactly what this is about: the market, or the pair. Never a category. */
    val instrument: Instrument

    /**
     * The asset the owner puts in — the mint spent by a swap, the mint staked by an order — which
     * is what a provider's accepted-asset list is about
     * ([io.github.brrenat.seekervault.plugins.ActionCapability.depositAssets]).
     */
    val depositAsset: String

    data class Swap(val payload: SwapPayload) : ActionPayload {
        override val instrument: Instrument
            // A pair is defined by the chain rather than by a venue, so it has no market provider;
            // and it is ordered, because swapping A for B is not swapping B for A.
            get() = Instrument("", "${payload.inputMint}/${payload.outputMint}")

        override val depositAsset: String
            get() = payload.inputMint
    }

    data class PredictionBuy(val payload: PredictionPayload) : ActionPayload {
        override val instrument: Instrument
            get() = Instrument(payload.marketProvider, payload.marketId)

        override val depositAsset: String
            get() = payload.depositMint
    }
}

/**
 * Exactly which market or asset an operation is about (SEE-145).
 *
 * The ticket's own words for why this is a type rather than a string: *a prediction market from one
 * provider is not interchangeable with a similarly named market from another*. Two venues can both
 * list "will it rain in Chicago", settle on different sources, close at different times and pay out
 * differently. So an instrument carries the venue that defines it beside the identifier it defines,
 * the pair is bound to the review ([io.github.brrenat.seekervault.proposals.ExecutionBinding]), and
 * a document whose market moved under an open review invalidates what was prepared instead of
 * quietly buying a different thing.
 *
 * [marketProvider] is empty where there is no venue to name — an asset pair is defined by the chain
 * — and that is a real answer rather than a missing one.
 */
data class Instrument(val marketProvider: String, val id: String)

sealed interface ActionPayloadResult {
    data class Valid(val payload: ActionPayload) : ActionPayloadResult

    /**
     * The document cannot be read as this action. [finding] is what the owner is shown: which rule
     * broke, and the term it broke on.
     */
    data class Invalid(val finding: PluginFinding) : ActionPayloadResult

    /** This build has no schema for that action at that version, so there is nothing to read. */
    data class Unknown(val action: ActionId, val schemaVersion: Int) : ActionPayloadResult
}

/**
 * Reads a publisher's terms as [action] at [schemaVersion].
 *
 * An action this build has no reader for, or a version of one it does not read, comes back
 * [ActionPayloadResult.Unknown] rather than being guessed at — the same answer the registry gives
 * for a provider that does not serve it, reached independently, so a document that is wrong about
 * both is refused twice rather than slipping through either.
 */
fun actionPayloadFrom(
    action: ActionId,
    schemaVersion: Int,
    terms: Map<String, String>,
): ActionPayloadResult =
    when {
        action == SWAP_ACTION && schemaVersion == SWAP_SCHEMA_VERSION ->
            when (val read = swapPayloadFrom(terms)) {
                is SwapPayloadResult.Valid ->
                    ActionPayloadResult.Valid(ActionPayload.Swap(read.payload))
                is SwapPayloadResult.Invalid -> ActionPayloadResult.Invalid(read.finding)
            }
        action == PREDICTION_BUY_ACTION && schemaVersion == PREDICTION_BUY_SCHEMA_VERSION ->
            when (val read = predictionPayloadFrom(terms)) {
                is PredictionPayloadResult.Valid ->
                    ActionPayloadResult.Valid(ActionPayload.PredictionBuy(read.payload))
                is PredictionPayloadResult.Invalid -> ActionPayloadResult.Invalid(read.finding)
            }
        else -> ActionPayloadResult.Unknown(action, schemaVersion)
    }
