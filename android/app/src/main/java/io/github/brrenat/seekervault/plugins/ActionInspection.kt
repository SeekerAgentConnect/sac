package io.github.brrenat.seekervault.plugins

import androidx.annotation.StringRes
import io.github.brrenat.seekervault.transactions.Verdict

/**
 * What a plugin established about the bytes it prepared (SEE-86).
 *
 * It is typed on purpose. The owner's rules are applied to facts and never to prose
 * (docs/policy.md#what-is-evaluated), so a plugin reports what it read as values of the same kinds
 * the transfer path already reports — an amount in base units, a recipient, the programs the
 * transaction calls — rather than as a sentence for something else to interpret.
 *
 * [Verdict] is the app's own three-state answer, shared with the transfer inspection: everything
 * read and everything matching, a match with something unread beside it, or a disagreement. Only
 * [approvable] may be put in front of the owner, and that is input validation rather than a rule
 * they could overrule (docs/security.md#verification-versus-advisory-rules).
 */
data class ActionInspection(
    val verdict: Verdict,
    val findings: List<PluginFinding>,
    /** What the bytes turned out to do, when enough of them could be read to say. */
    val facts: InspectedAction?,
    /** The preparation this inspection was made from; an approval names the same one. */
    val version: Int,
    /**
     * What else the bytes said that is worth the owner's while to read, as labelled values
     * (SEE-93).
     *
     * [InspectedAction] is the shape the owner's *rules* are written against, and it is
     * deliberately the same shape for every operation — an amount, an asset, a recipient, the
     * programs called. An operation also establishes things no rule has a field for: the least a
     * swap will pay out, the outcome a market order is for, what the transaction will cost to get
     * picked up. A plugin puts those here, each with its own string resource, and core shows them
     * in order without knowing what any of them mean.
     *
     * They are for reading and never for evaluating. Nothing in here reaches
     * [io.github.brrenat.seekervault.policy.RequestFacts], so no plugin can make a rule pass by
     * saying something reassuring.
     */
    val details: List<PluginFact> = emptyList(),
) {
    /**
     * Whether this preparation may be put in front of the owner to approve. Only bytes the plugin
     * accounted for completely qualify: an unread instruction is a gap in the review, and a review
     * with a gap in it is not a review.
     */
    val approvable: Boolean
        get() = verdict == Verdict.Verified

    companion object {
        /**
         * Nothing was established: the bytes couldn't be read, or there are none yet. It is not a
         * refusal of the operation and not a verdict on it — it is the absence of a reading, which
         * is why it can never be [approvable].
         */
        fun nothingEstablished(
            version: Int = 0,
            findings: List<PluginFinding> = emptyList(),
        ): ActionInspection = ActionInspection(Verdict.Invalid, findings, null, version)
    }
}

/**
 * One thing a plugin found wrong, or couldn't account for.
 *
 * [code] is stable and is what the owner's own record keeps
 * ([io.github.brrenat.seekervault.activity.ActivityLog]); [message] is the plugin's own string
 * resource, so the words stay in resources and out of the code. [invalidates] separates the two
 * kinds of finding the transfer path already separates
 * ([io.github.brrenat.seekervault.transactions.Finding.invalidates]): bytes that disagree with the
 * request, which must not be approved, and bytes the plugin simply didn't read, which leave the
 * review incomplete.
 */
data class PluginFinding(
    val code: String,
    @StringRes val message: Int,
    val invalidates: Boolean = true,
)

/**
 * One labelled thing a plugin read, for the owner to see ([ActionInspection.details]).
 *
 * [label] is the plugin's own string resource and [value] is already formatted for display: an
 * amount written out with its decimals, a percentage, a name. Core shows the pair and interprets
 * neither, which is why this is two strings and not a number with a unit — the plugin knows what it
 * read, and the app knows how to lay out a row.
 */
data class PluginFact(@StringRes val label: Int, val value: String)

/**
 * The facts a plugin read out of its own prepared bytes, in the shape the owner's rules are written
 * against.
 *
 * Every field is something the plugin read. A field it couldn't establish is null, and null passes
 * no check (docs/policy.md#what-is-evaluated). The chain is deliberately absent: a rule is about
 * the chain the owner's wallet is selected for, and core supplies that rather than taking a
 * plugin's word for which network its bytes are on.
 */
data class InspectedAction(
    /** The account that pays and signs, read from the bytes; null when they don't say. */
    val wallet: String?,
    /**
     * Whether these bytes move value at all. An operation that moves nothing satisfies every rule
     * about assets, recipients, programs and amounts by doing nothing with any of them.
     */
    val movesValue: Boolean,
    /** The SPL mint that moves, or null for native SOL. */
    val mint: String?,
    /** The wallet the funds provably reach; null when the bytes don't establish one. */
    val recipient: String?,
    /** Every program the bytes call, including those whose instructions went unread. */
    val programs: List<String>?,
    /** Base units, exactly as the instruction carries them. */
    val amount: ULong?,
    /** The decimals the amount is shown with; display only, and never used to compare. */
    val decimals: Int,
    /** How many instructions the bytes carry. */
    val instructionCount: Int,
    /** How many of them the plugin read. Fewer means the review doesn't cover them all. */
    val recognizedInstructions: Int,
) {
    /**
     * Whether every instruction was accounted for. Derived from the two counts rather than stated
     * separately, so a plugin can't report complete coverage of bytes it didn't finish reading.
     */
    val fullyRead: Boolean
        get() = instructionCount == recognizedInstructions
}
