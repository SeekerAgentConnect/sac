package io.github.brrenat.seekervault.operations

import androidx.annotation.StringRes
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.plugins.UnsupportedReason
import io.github.brrenat.seekervault.proposals.BindingProblem
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding

/**
 * The words the operation screens use (SEE-93).
 *
 * Kept apart from the screens for the reason the inbox keeps its own apart: a state has one set of
 * words wherever it is shown, and a state added later has to be given words here before it can be
 * shown anywhere.
 */
object OperationTags {
    const val LIST = "operations.list"
    const val EMPTY = "operations.empty"
    const val REFRESH = "operations.refresh"
    const val NOTE = "operations.note"
    const val TERMS = "operations.terms"
    const val AMOUNT = "operations.amount"
    const val SLIPPAGE = "operations.slippage"
    const val PREPARE = "operations.prepare"
    const val APPROVE = "operations.approve"
    const val DISMISS = "operations.dismiss"
    const val FINDINGS = "operations.findings"
    const val FACTS = "operations.facts"
    const val FAILURE = "operations.failure"
    const val PROBLEM = "operations.problem"
    const val STANDING = "operations.standing"
    const val OUTCOME = "operations.outcome"
    const val ACKNOWLEDGE = "operations.acknowledge"
    const val UNSUPPORTED = "operations.unsupported"
    const val AFTERWARDS = "operations.afterwards"
    const val SANDBOX = "operations.sandbox"
    const val REFERENCES = "operations.references"

    fun link(name: String) = "operations.link.$name"

    fun row(proposalId: String) = "operations.row.$proposalId"
}

/** Where a proposal stands for this owner, in a word. */
@StringRes
fun standingText(standing: ProposalStanding): Int =
    when (standing) {
        is ProposalStanding.Open -> R.string.operation_standing_open
        is ProposalStanding.Executed -> R.string.operation_standing_executed
        is ProposalStanding.Dismissed -> R.string.operation_standing_dismissed
        is ProposalStanding.Refused -> R.string.operation_standing_refused
        is ProposalStanding.Cancelled -> R.string.operation_standing_cancelled
        is ProposalStanding.Expired -> R.string.operation_standing_expired
        is ProposalStanding.Unsupported -> R.string.operation_standing_unsupported
    }

/** What became of the one operation this device executed. */
@StringRes
fun outcomeText(outcome: ProposalOutcome): Int =
    when (outcome) {
        is ProposalOutcome.Pending -> R.string.operation_outcome_pending
        is ProposalOutcome.Submitted -> R.string.operation_outcome_submitted
        is ProposalOutcome.Declined -> R.string.operation_outcome_declined
        is ProposalOutcome.Simulated -> R.string.operation_outcome_simulated
        is ProposalOutcome.Failed -> R.string.operation_outcome_failed
        is ProposalOutcome.Unresolved -> R.string.operation_outcome_unresolved
    }

/** Why an approval stopped. Each is its own sentence, and none of them is a warning. */
@StringRes
fun problemText(problem: OperationProblem): Int =
    when (problem) {
        is OperationProblem.RulesChanged -> R.string.operation_problem_rules_changed
        is OperationProblem.NotAcknowledged -> R.string.operation_problem_not_acknowledged
        is OperationProblem.NoWallet -> R.string.operation_problem_no_wallet
        is OperationProblem.WalletChanged -> R.string.operation_problem_wallet_changed
        is OperationProblem.Gone -> R.string.operation_problem_gone
        is OperationProblem.Binding -> bindingText(problem.problem)
    }

/**
 * Why nothing in this build serves an action here (SEE-145).
 *
 * One sentence per reason, because the owner would do a different thing about each: update the app,
 * connect a wallet on another cluster, switch the connection's environment, go back to the
 * publisher about a signal that disagrees with itself, or nothing at all. Collapsing them into
 * "unsupported" would be telling them less than this phone knows.
 */
@StringRes
fun unservedText(reason: UnsupportedReason): Int =
    when (reason) {
        UnsupportedReason.NoProvider -> R.string.operation_unserved_no_provider
        UnsupportedReason.NameMismatch -> R.string.operation_unserved_name_mismatch
        UnsupportedReason.ContractUnsupported -> R.string.operation_unserved_contract
        UnsupportedReason.ActionUnsupported -> R.string.operation_unserved_action
        UnsupportedReason.SchemaUnsupported -> R.string.operation_unserved_schema
        UnsupportedReason.NetworkUnsupported -> R.string.operation_unserved_network
        UnsupportedReason.EnvironmentUnsupported -> R.string.operation_unserved_environment
        UnsupportedReason.AssetUnsupported -> R.string.operation_unserved_asset
    }

/** One of the rules that stand between a review and the wallet (SEE-89). */
@StringRes
fun bindingText(problem: BindingProblem): Int =
    when (problem) {
        BindingProblem.AlreadyExecuted -> R.string.operation_binding_already_executed
        BindingProblem.Dismissed -> R.string.operation_binding_dismissed
        BindingProblem.ProposalRefused -> R.string.operation_binding_refused
        BindingProblem.ProposalCancelled -> R.string.operation_binding_cancelled
        BindingProblem.ProposalExpired -> R.string.operation_binding_expired
        BindingProblem.ServerUnsupported -> R.string.operation_binding_unsupported
        BindingProblem.NotReviewed -> R.string.operation_binding_not_reviewed
        BindingProblem.ProposalChanged -> R.string.operation_binding_changed
        BindingProblem.ChoiceChanged -> R.string.operation_binding_choice_changed
        BindingProblem.OtherProvider -> R.string.operation_binding_other_provider
        BindingProblem.OtherAction -> R.string.operation_binding_other_action
        BindingProblem.OtherSchema -> R.string.operation_binding_other_schema
        BindingProblem.OtherInstrument -> R.string.operation_binding_other_instrument
        BindingProblem.UnreadableTerms -> R.string.operation_binding_unreadable_terms
        BindingProblem.OtherContract -> R.string.operation_binding_other_contract
        BindingProblem.NothingPrepared -> R.string.operation_binding_nothing_prepared
        BindingProblem.PreparationExpired -> R.string.operation_binding_preparation_expired
        BindingProblem.NoWallet -> R.string.operation_binding_no_wallet
        BindingProblem.OtherWallet -> R.string.operation_binding_other_wallet
        BindingProblem.OtherNetwork -> R.string.operation_binding_other_network
        BindingProblem.OtherEnvironment -> R.string.operation_binding_other_environment
    }
