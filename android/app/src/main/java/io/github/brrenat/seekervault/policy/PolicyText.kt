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
    const val DIALOG_CANCEL = "policyDialogCancel"
    const val DISCARD = "policyDiscard"
    const val KEEP_EDITING = "policyKeepEditing"
    const val CONFIRM_GLOBAL_SAVE = "policyConfirmGlobalSave"
    const val OPEN_GLOBAL = "policyOpenGlobal"
    const val RESET_OVERRIDES = "policyResetOverrides"
    const val GLOBAL_UNREADABLE = "policyGlobalUnreadable"
    const val ADD_ALLOWED_ASSET = "policyAddAllowedAsset"
    const val ADD_LIMIT_ASSET = "policyAddLimitAsset"
    const val HELP = "policyHelp"
    const val HELP_CONTENT = "policyHelpContent"
    const val CLEAR_GLOBAL = "policyClearGlobal"
    const val SAVE_ERROR = "policySaveError"

    fun section(list: String) = "policySection:$list"

    fun empty(list: String) = "policyEmpty:$list"

    /** The switch that decides whether one list is a check at all. */
    fun restrict(list: String) = "policyRestrict:$list"

    fun inherit(list: String) = "policyInherit:$list"

    fun override(list: String) = "policyOverride:$list"

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

    fun connectionAsset(asset: PolicyAsset) = "policyConnectionAsset:${asset.tagPart}"

    fun removeAllowedAsset(asset: PolicyAsset) = "policyRemoveAllowedAsset:${asset.tagPart}"

    fun inheritPerOperation(asset: PolicyAsset) = "policyInheritPerOperation:${asset.tagPart}"

    fun overridePerOperation(asset: PolicyAsset) = "policyOverridePerOperation:${asset.tagPart}"

    fun connectionPerOperation(asset: PolicyAsset) = "policyConnectionPerOperation:${asset.tagPart}"

    fun globalDaily(asset: PolicyAsset) = "policyGlobalDaily:${asset.tagPart}"

    fun connectionDaily(asset: PolicyAsset) = "policyConnectionDaily:${asset.tagPart}"

    fun connectionDailySource(asset: PolicyAsset) = "policyConnectionDailySource:${asset.tagPart}"
}

private val PolicyAsset.tagPart: String
    get() = "${network.name}:${mint ?: "SOL"}"

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
fun unreadableText(
    why: UnreadableReason,
    scope: PolicyEditorScope? = PolicyEditorScope.Connection,
): String =
    stringResource(
        when (scope) {
            PolicyEditorScope.Global ->
                when (why) {
                    UnreadableReason.Damaged -> R.string.policy_global_unreadable_damaged
                    UnreadableReason.NewerVersion -> R.string.policy_global_unreadable_newer
                    UnreadableReason.UnknownRule -> R.string.policy_global_unreadable_unknown
                }
            else ->
                when (why) {
                    UnreadableReason.Damaged -> R.string.policy_unreadable_damaged
                    UnreadableReason.NewerVersion -> R.string.policy_unreadable_newer
                    UnreadableReason.UnknownRule -> R.string.policy_unreadable_unknown
                }
        }
    )

@Composable
fun messageText(
    message: PolicyMessage,
    scope: PolicyEditorScope? = PolicyEditorScope.Connection,
): String =
    stringResource(
        when (message) {
            PolicyMessage.Saved ->
                if (scope == PolicyEditorScope.Global) R.string.policy_global_saved
                else R.string.policy_saved
            PolicyMessage.Removed ->
                if (scope == PolicyEditorScope.Global) R.string.policy_global_removed
                else R.string.policy_removed
            PolicyMessage.SaveFailed -> R.string.policy_save_failed
        }
    )

@Composable
fun sourceText(source: RuleSource): String =
    stringResource(
        when (source) {
            RuleSource.Global -> R.string.policy_source_global
            RuleSource.ConnectionOverride -> R.string.policy_source_connection
            RuleSource.NotConfigured -> R.string.policy_source_none
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
fun summaryLines(draft: PolicyDraft, global: Boolean = false): List<String> {
    val lines = mutableListOf<String>()
    if (draft.restrictActions) {
        // Named in the order the checkboxes are in, so the summary reads back what was ticked.
        val names = mutableListOf<String>()
        for (action in PolicyAction.entries) {
            if (action in draft.actions) names += actionsText(action)
        }
        lines +=
            if (names.isEmpty()) stringResource(R.string.policy_summary_actions_empty)
            else stringResource(R.string.policy_summary_actions, names.joinToString())
    }
    if (draft.restrictAssets) {
        lines +=
            if (draft.assets.isEmpty()) stringResource(R.string.policy_summary_assets_empty)
            else
                stringResource(
                    R.string.policy_summary_assets,
                    draft.assets.joinToString { assetLabel(it.asset) },
                )
    }
    if (draft.restrictRecipients) {
        lines +=
            if (draft.recipients.isEmpty()) stringResource(R.string.policy_summary_recipients_empty)
            else stringResource(R.string.policy_summary_recipients, draft.recipients.joinToString())
    }
    if (draft.restrictPrograms) {
        lines +=
            if (draft.programs.isEmpty()) stringResource(R.string.policy_summary_programs_empty)
            else stringResource(R.string.policy_summary_programs, draft.programs.joinToString())
    }
    for (asset in draft.assets) {
        (readAmount(asset.perOperation, asset.decimals) as? AmountEntry.Amount)?.let {
            lines +=
                stringResource(
                    R.string.policy_summary_per_operation,
                    amountText(it.baseUnits, asset),
                    assetLabel(asset.asset),
                )
        }
        (readAmount(asset.daily, asset.decimals) as? AmountEntry.Amount)?.let {
            lines +=
                stringResource(
                    R.string.policy_summary_daily,
                    amountText(it.baseUnits, asset),
                    assetLabel(asset.asset),
                )
        }
    }
    if (lines.isEmpty()) {
        lines +=
            stringResource(
                if (global) R.string.policy_global_summary_none else R.string.policy_summary_none
            )
    }
    val unchecked = uncheckedText(draft)
    if (unchecked.isNotEmpty()) {
        lines += stringResource(R.string.policy_summary_unchecked, unchecked.joinToString())
    }
    lines += stringResource(R.string.policy_summary_manual)
    return lines
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
