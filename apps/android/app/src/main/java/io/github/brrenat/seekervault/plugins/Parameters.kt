package io.github.brrenat.seekervault.plugins

import androidx.annotation.StringRes

/**
 * The fields one action leaves to the owner (SEE-86).
 *
 * Every field has a stable [ParameterKey], so what they chose can be kept and compared without
 * depending on how it was shown. Nothing here is a screen: the app owns its own presentation
 * (AGENTS.md#ui), and a provider says what has to be collected.
 */
data class ParameterForm(
    val fields: List<ParameterField> = emptyList(),
    /**
     * Why there is nothing to collect, when that is the reason: the terms a publisher broadcast
     * cannot be read as this action at all (SEE-93).
     *
     * It is told apart from an empty form because the two are opposite situations. An action with
     * no parameters is ready to prepare; a document nothing can be read out of is one nothing will
     * ever be prepared from, and the owner is owed the reason — which term, and what was wrong with
     * it. Core shows it and offers no preparation.
     */
    val problem: PluginFinding? = null,
) {
    val isEmpty: Boolean
        get() = fields.isEmpty()
}

data class ParameterField(
    val key: ParameterKey,
    /** The action's own string resource. Text stays in resources, not in code. */
    @StringRes val label: Int,
    val kind: ParameterKind,
)

/** A field's stable name within its action, such as `input_amount`. */
@JvmInline
value class ParameterKey(val value: String) {
    init {
        require(isDottedName(value)) { "not a parameter: $value" }
    }

    override fun toString(): String = value
}

/** What kind of value a field takes. Base units throughout: nothing here rounds anything. */
sealed interface ParameterKind {
    /**
     * An amount in the asset's own base units — lamports for SOL, the mint's units for a token —
     * which is what the transaction carries and what a rule is written in
     * (docs/policy.md#advisory-thresholds). [decimals] is for display only, and the chain is not
     * named here: it is the one the owner's wallet is selected for, and core supplies it.
     */
    data class Amount(
        /** The SPL mint the amount is in, or null for native SOL. */
        val mint: String?,
        val decimals: Int,
        val most: ULong? = null,
        /**
         * The least that may be entered, in the same base units. Zero unless the operation has a
         * floor of its own — a publisher's minimum, a provider's dust limit — and it is a bound on
         * the field rather than advice about it: below it there is nothing to prepare.
         */
        val least: ULong = 0UL,
    ) : ParameterKind

    /** One of a fixed set: the outcome of a market, the side of a signal. */
    data class Choice(val options: List<ParameterOption>) : ParameterKind

    /** A bounded whole number, such as a slippage tolerance in basis points. */
    data class Count(
        val least: UInt,
        val most: UInt,
        /**
         * What the field starts at before the owner touches it. It is stated because a sensible
         * starting point is knowledge about the action — the usual slippage for a pair — and core
         * has none of that; it is a starting point and never a choice, which is still only ever
         * [ParameterChoice].
         */
        val initial: UInt = least,
    ) : ParameterKind
}

data class ParameterOption(val key: ParameterKey, @StringRes val label: Int)

/**
 * What the owner chose, on their own phone. A public feed never receives it (SEE-89). A private
 * RETURN_TO_ORIGIN request may return only the values its source explicitly declared, through the
 * device's authenticated result path (SEE-109); it is never added to a shared proposal.
 */
data class ParameterChoice(val values: Map<ParameterKey, ParameterValue> = emptyMap()) {
    operator fun get(key: ParameterKey): ParameterValue? = values[key]
}

sealed interface ParameterValue {
    data class Amount(val baseUnits: ULong) : ParameterValue

    data class Selected(val option: ParameterKey) : ParameterValue

    data class Count(val value: UInt) : ParameterValue
}
