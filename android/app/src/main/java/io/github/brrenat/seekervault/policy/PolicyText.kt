package io.github.brrenat.seekervault.policy

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.transactions.formatBaseUnits

/** Test tags for the policy editor's controls. */
object PolicyTags {
    const val RULES = "policyRules"
    const val LOADING = "policyLoading"
    const val SUMMARY = "policySummary"
    const val SAVED_AT = "policySavedAt"
    const val UNREADABLE = "policyUnreadable"
    const val START_OVER = "policyStartOver"
    const val SAVE = "policySave"
    const val CANCEL = "policyCancel"
    const val ADD_ASSET = "policyAddAsset"
    const val ASSET_SOL = "policyAssetSol"
    const val ASSET_TOKEN = "policyAssetToken"
    const val MINT_FIELD = "policyMintField"
    const val DIALOG_ADD = "policyDialogAdd"
    const val DIALOG_PER_REQUEST = "policyDialogPerRequest"
    const val DIALOG_DAILY = "policyDialogDaily"
    const val DIALOG_CANCEL = "policyDialogCancel"
    const val DISCARD = "policyDiscard"
    const val KEEP_EDITING = "policyKeepEditing"

    /** The switch that decides whether one list is a check at all. */
    fun restrict(list: String) = "policyRestrict:$list"

    fun action(action: PolicyAction) = "policyAction:${action.code}"

    fun asset(index: Int) = "policyAsset:$index"

    fun perOperation(index: Int) = "policyPerOperation:$index"

    fun daily(index: Int) = "policyDaily:$index"

    fun removeAsset(index: Int) = "policyRemoveAsset:$index"

    fun network(network: Network) = "policyNetwork:${network.name}"

    /** Where an address is typed into one of the address lists. */
    fun entryField(list: String) = "policyEntryField:$list"

    fun add(list: String) = "policyAdd:$list"

    fun entry(list: String, value: String) = "policyEntry:$list:$value"

    fun removeEntry(list: String, value: String) = "policyRemoveEntry:$list:$value"
}

/** The two address lists, by the name their tags carry. */
const val RECIPIENTS = "recipients"

const val PROGRAMS = "programs"

@Composable
fun actionText(action: PolicyAction): String =
    stringResource(
        when (action) {
            PolicyAction.Acknowledgement -> R.string.policy_action_ack
            PolicyAction.MessageSignature -> R.string.policy_action_sign_message
            PolicyAction.Transfer -> R.string.policy_action_transfer
            PolicyAction.Swap -> R.string.policy_action_swap
        }
    )

/** The name an action goes by inside a sentence. */
@Composable
fun actionsText(action: PolicyAction): String =
    stringResource(
        when (action) {
            PolicyAction.Acknowledgement -> R.string.policy_action_ack_short
            PolicyAction.MessageSignature -> R.string.policy_action_sign_message_short
            PolicyAction.Transfer -> R.string.policy_action_transfer_short
            PolicyAction.Swap -> R.string.policy_action_swap_short
        }
    )

@Composable
fun networkText(network: Network): String =
    when (network) {
        Network.NETWORK_DEVNET -> stringResource(R.string.policy_network_devnet)
        Network.NETWORK_TESTNET -> stringResource(R.string.policy_network_testnet)
        else -> stringResource(R.string.policy_network_mainnet)
    }

@Composable
fun amountProblemText(problem: AmountProblem, asset: AssetDraft): String =
    when (problem) {
        AmountProblem.NotANumber -> stringResource(R.string.policy_amount_not_a_number)
        AmountProblem.TooPrecise ->
            stringResource(
                R.string.policy_amount_too_precise,
                assetLabel(asset.asset),
                asset.decimals,
            )
        AmountProblem.TooLarge ->
            stringResource(R.string.policy_amount_too_large, ULong.MAX_VALUE.toString())
        AmountProblem.Zero -> stringResource(R.string.policy_amount_zero)
    }

@Composable
fun unreadableText(why: UnreadableReason): String =
    stringResource(
        when (why) {
            UnreadableReason.Damaged -> R.string.policy_unreadable_damaged
            UnreadableReason.NewerVersion -> R.string.policy_unreadable_newer
            UnreadableReason.UnknownRule -> R.string.policy_unreadable_unknown
        }
    )

@Composable
fun messageText(message: PolicyMessage): String =
    stringResource(
        when (message) {
            PolicyMessage.Saved -> R.string.policy_saved
            PolicyMessage.Removed -> R.string.policy_removed
            PolicyMessage.SaveFailed -> R.string.policy_save_failed
        }
    )

/** An amount of [asset], in the units its thresholds are typed in. */
@Composable
fun amountText(baseUnits: ULong, asset: AssetDraft): String =
    if (asset.mint == null) formatBaseUnits(baseUnits, asset.decimals)
    else stringResource(R.string.policy_base_units, baseUnits.toString())

/**
 * What the draft says, in the owner's own language, one sentence at a time.
 *
 * This is the whole policy read back rather than a highlight of it: every list that is a check, in
 * the order the checks run, then every threshold, then the checks nobody configured — because
 * `ALLOWED` is never a statement about a parameter no rule was written for. The last line is the
 * one that never changes: an assessment approves nothing.
 */
@Composable
fun summaryLines(draft: PolicyDraft): List<String> {
    val lines = mutableListOf<String>()
    val checked = checkedText(draft)
    lines +=
        if (checked.isEmpty()) stringResource(R.string.policy_summary_none)
        else stringResource(R.string.policy_summary_checked, joinWithAnd(checked))
    val unchecked = uncheckedText(draft)
    if (unchecked.isNotEmpty()) {
        lines += stringResource(R.string.policy_summary_unchecked, joinWithAnd(unchecked))
    }
    // A threshold binds the asset it names whether or not the asset list is a check at all, which
    // is the one thing about this screen that is not read off the switches.
    val limited =
        draft.assets.count {
            readAmount(it.perOperation, it.decimals) is AmountEntry.Amount ||
                readAmount(it.daily, it.decimals) is AmountEntry.Amount
        }
    if (limited == 1) lines += stringResource(R.string.policy_summary_limit_one)
    if (limited > 1) lines += stringResource(R.string.policy_summary_limit_many, limited)
    lines += stringResource(R.string.policy_summary_manual)
    return lines
}

/** The checks this draft does configure, named in the owner's terms. */
@Composable
private fun checkedText(draft: PolicyDraft): List<String> {
    val names = mutableListOf<String>()
    if (draft.restrictActions) names += stringResource(R.string.policy_check_action)
    if (draft.restrictAssets) names += stringResource(R.string.policy_check_asset)
    if (draft.restrictRecipients) names += stringResource(R.string.policy_check_recipient)
    if (draft.restrictPrograms) names += stringResource(R.string.policy_check_program)
    return names
}

/** "a, b and c": the list read the way it would be said. */
private fun joinWithAnd(names: List<String>): String =
    when (names.size) {
        0 -> ""
        1 -> names.single()
        else -> "${names.dropLast(1).joinToString()} and ${names.last()}"
    }

/** The checks this draft configures none of, named in the owner's terms. */
@Composable
private fun uncheckedText(draft: PolicyDraft): List<String> {
    val unchecked = mutableListOf<String>()
    if (!draft.restrictActions) unchecked += stringResource(R.string.policy_check_action)
    if (!draft.restrictAssets) unchecked += stringResource(R.string.policy_check_asset)
    if (!draft.restrictRecipients) unchecked += stringResource(R.string.policy_check_recipient)
    if (!draft.restrictPrograms) unchecked += stringResource(R.string.policy_check_program)
    if (draft.assets.none { readAmount(it.perOperation, it.decimals) is AmountEntry.Amount }) {
        unchecked += stringResource(R.string.policy_check_per_operation)
    }
    if (draft.assets.none { readAmount(it.daily, it.decimals) is AmountEntry.Amount }) {
        unchecked += stringResource(R.string.policy_check_daily)
    }
    return unchecked
}

/**
 * The assessment in the owner's own words (SAW-028). Neither verdict is a decision: one says the
 * request matched what they wrote down, the other that something didn't or couldn't be checked, and
 * both still need their hand on the wallet.
 */
@StringRes
fun assessmentText(assessment: PolicyAssessment): Int =
    when (assessment) {
        PolicyAssessment.Allowed -> R.string.policy_verdict_allowed
        PolicyAssessment.UnderRestrictions -> R.string.policy_verdict_restricted
    }

/** One check, named the way the review screen heads it. */
@StringRes
fun checkText(check: PolicyCheck): Int =
    when (check) {
        PolicyCheck.Action -> R.string.policy_review_check_action
        PolicyCheck.Asset -> R.string.policy_review_check_asset
        PolicyCheck.Recipient -> R.string.policy_review_check_recipient
        PolicyCheck.Program -> R.string.policy_review_check_program
        PolicyCheck.PerOperationLimit -> R.string.policy_review_check_per_operation
        PolicyCheck.DailyLimit -> R.string.policy_review_check_daily
    }

/**
 * Why a request isn't ALLOWED, in the owner's language. The stored reason is a code, and this is
 * where it becomes words: a record keeps the code, so what it means can be said better later
 * without the record having to be rewritten.
 */
@StringRes
fun reasonText(reason: PolicyReason): Int =
    when (reason) {
        PolicyReason.NoPolicyConfigured -> R.string.policy_reason_no_policy
        PolicyReason.PolicyUnreadable -> R.string.policy_reason_unreadable
        PolicyReason.ActionNotAllowed -> R.string.policy_reason_action
        PolicyReason.AssetNotAllowed -> R.string.policy_reason_asset
        PolicyReason.RecipientNotAllowed -> R.string.policy_reason_recipient
        PolicyReason.ProgramNotAllowed -> R.string.policy_reason_program
        PolicyReason.OverPerOperationLimit -> R.string.policy_reason_per_operation
        PolicyReason.OverDailyLimit -> R.string.policy_reason_daily
        PolicyReason.ActionUnverified -> R.string.policy_reason_action_unverified
        PolicyReason.AssetUnverified -> R.string.policy_reason_asset_unverified
        PolicyReason.RecipientUnverified -> R.string.policy_reason_recipient_unverified
        PolicyReason.ProgramUnverified -> R.string.policy_reason_program_unverified
        PolicyReason.AmountUnverified -> R.string.policy_reason_amount_unverified
        PolicyReason.DailyTotalUnverified -> R.string.policy_reason_daily_unverified
        PolicyReason.RequestUnverified -> R.string.policy_reason_request_unverified
    }

/** The reason a stored code names, or null for one this build has no name for. */
fun reasonOf(code: String): PolicyReason? = PolicyReason.entries.firstOrNull { it.code == code }

/** The check a stored code names, or null for one this build has no name for. */
fun checkOf(code: String): PolicyCheck? = PolicyCheck.entries.firstOrNull { it.code == code }

/** The verdict a stored code names, or null for one this build has no name for. */
fun assessmentOf(code: String): PolicyAssessment? =
    PolicyAssessment.entries.firstOrNull { it.code == code }
