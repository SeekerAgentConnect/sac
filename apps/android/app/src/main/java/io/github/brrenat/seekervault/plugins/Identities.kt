package io.github.brrenat.seekervault.plugins

/**
 * The three names this boundary uses, and the one rule they share (SEE-145).
 *
 * All of them are lowercase dot-separated segments, at most a screenful, and none of them is ever
 * anything loadable: not a URL, not a package, not a class. A name here selects nothing — it is
 * matched against code that is already compiled into this build, and a name this build has nothing
 * for is reported rather than fetched (docs/wiki/execution-providers.md).
 */

/** Lowercase dot-separated segments, each starting with a letter: `jupiter`, `prediction.buy`. */
private val NAME_PATTERN = Regex("""[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*""")

/** The shared spelling rule: what a provider, an action, a term key and a parameter key all are. */
fun isDottedName(value: String): Boolean = value.length in 1..64 && NAME_PATTERN.matches(value)

/**
 * A bundled plugin's stable identity, as a server manifest written before SEE-145 spells it:
 * `jupiter.swap`. It names a provider *and* an action at once, which is exactly what SEE-145
 * separated, so it survives only as a compatibility claim ([LEGACY_CAPABILITIES]).
 */
@JvmInline
value class PluginId(val value: String) {
    init {
        require(isPluginId(value)) { "not a plugin ID: $value" }
    }

    override fun toString(): String = value
}

/** A legacy plugin name: the shared rule, a little longer, and it must carry a dot. */
fun isPluginId(value: String): Boolean =
    value.length in 1..128 && NAME_PATTERN.matches(value) && value.contains('.')

/**
 * Who prepares an operation: `jupiter`, and one day something else compatible.
 *
 * It is a single segment by convention rather than by rule, and it is never inferred. A request
 * names it, a build carries it, and a build that doesn't answers "no such provider" rather than
 * handing the operation to whichever provider it happens to have for the action — because a
 * prediction market at one venue is not the same instrument as a similarly named market at another,
 * and silently substituting one for the other is the failure this type exists to make impossible.
 */
@JvmInline
value class ExecutionProviderId(val value: String) {
    init {
        require(isDottedName(value)) { "not an execution provider: $value" }
    }

    override fun toString(): String = value
}

/**
 * What a request asks for, named at the protocol's own level (docs/protocol.md#actions): `swap` and
 * `prediction.buy`, never the provider that serves either.
 *
 * That is deliberate. Core says which action a request is for, and a provider claims the ones it
 * can do, so nothing in core has to know that Jupiter is what makes `swap` work.
 */
@JvmInline
value class ActionId(val value: String) {
    init {
        require(isDottedName(value)) { "not an action: $value" }
    }

    override fun toString(): String = value
}

/** Exchanging one spot asset for another. */
val SWAP_ACTION: ActionId = ActionId("swap")

/** The version of the swap payload this release reads. */
const val SWAP_SCHEMA_VERSION: Int = 1

/**
 * Buying one side of a prediction market.
 *
 * It is `prediction.buy` rather than `prediction` because buying a side is one thing that can be
 * done to a market and not the only one: selling out of a position ([PREDICTION_SELL_ACTION]) and
 * claiming a settled payout are others. Naming the action for what it does leaves room for them to
 * be separate actions with separate rules rather than a mode flag inside this one. Documents
 * written before SEE-145 say `prediction`, and that maps here ([LEGACY_CAPABILITIES]).
 */
val PREDICTION_BUY_ACTION: ActionId = ActionId("prediction.buy")

/** The version of the prediction-buy payload this release reads. */
const val PREDICTION_BUY_SCHEMA_VERSION: Int = 1

/**
 * Selling the whole of a prediction position the owner holds (SEE-172).
 *
 * Its own action with its own review, never a mode of [PREDICTION_BUY_ACTION], and never something
 * a publisher proposes: it has no payload a signal could carry, and is reached only from the
 * owner's own record of a purchase ([PositionManagement]). It spends nothing of the owner's, so no
 * spending rule counts it, and the purchase it closes is not counted again.
 */
val PREDICTION_SELL_ACTION: ActionId = ActionId("prediction.sell")

/** The version of the sale this release builds and reads. */
const val PREDICTION_SELL_SCHEMA_VERSION: Int = 1
