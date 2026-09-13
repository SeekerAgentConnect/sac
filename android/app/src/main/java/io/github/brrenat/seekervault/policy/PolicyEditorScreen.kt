package io.github.brrenat.seekervault.policy

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.isSolanaAddress
import java.time.Instant

/**
 * The policy editor (docs/guides/policies.md): where the owner writes the rules for one connection.
 *
 * Stock Material 3 and nothing else — switches, checkboxes, radio buttons, chips, text fields,
 * lists, and Save and Cancel. There is no expression builder and no node canvas, because the model
 * behind it is one conjunction of allowlists and thresholds and pretending otherwise would be
 * showing the owner a language they don't have.
 *
 * Two things this screen keeps saying, because both are easy to assume otherwise:
 * - **A switch that is off is not an empty list.** Off configures no check at all; on with an empty
 *   list allows nothing. Every section says which of the two it is in words.
 * - **Nothing here approves anything.** `ALLOWED` and `UNDER_RESTRICTIONS` both need the owner's
 *   hand on the wallet, and the summary says so every time it is read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyEditorScreen(
    label: String,
    state: PolicyUiState,
    onEdit: (PolicyDraft) -> Unit,
    onStartOver: () -> Unit,
    onSave: () -> Unit,
    onMessageShown: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onMessageShown()
        }
    }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val leave = { if (state.changed) confirmDiscard = true else onClose() }
    // Backing out of unsaved rules asks first. Everything else about back is the app's own stack.
    BackHandler(enabled = state.changed) { confirmDiscard = true }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.policy_title, label)) },
                navigationIcon = { BackButton(leave) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).fillMaxWidth()
        ) {
            val unreadable = state.unreadable
            when {
                !state.loaded ->
                    Text(
                        stringResource(R.string.policy_loading),
                        modifier = Modifier.padding(16.dp).testTag(PolicyTags.LOADING),
                    )
                unreadable != null -> Unreadable(unreadable, onStartOver, leave)
                else -> Editor(state, onEdit, onSave, leave)
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.policy_discard_title)) },
            text = { Text(stringResource(R.string.policy_discard_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onClose()
                    },
                    modifier = Modifier.testTag(PolicyTags.DISCARD),
                ) {
                    Text(stringResource(R.string.policy_discard))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDiscard = false },
                    modifier = Modifier.testTag(PolicyTags.KEEP_EDITING),
                ) {
                    Text(stringResource(R.string.policy_keep_editing))
                }
            },
        )
    }
}

/**
 * Rules are stored and this build can't read them. No form is opened over them: an empty form saved
 * on top would delete rules the owner set and never saw, so replacing them is something they ask
 * for by name.
 */
@Composable
private fun Unreadable(why: UnreadableReason, onStartOver: () -> Unit, onClose: () -> Unit) {
    Text(
        stringResource(R.string.policy_unreadable_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(16.dp).testTag(PolicyTags.UNREADABLE),
    )
    Text(unreadableText(why), modifier = Modifier.padding(horizontal = 16.dp))
    Text(
        stringResource(R.string.policy_unreadable_text),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onStartOver, modifier = Modifier.testTag(PolicyTags.START_OVER)) {
            Text(stringResource(R.string.policy_start_over))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.testTag(PolicyTags.CANCEL)) {
            Text(stringResource(R.string.policy_cancel))
        }
    }
}

@Composable
private fun Editor(
    state: PolicyUiState,
    onEdit: (PolicyDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.draft
    // The instant only dates the document, and this review is about whether it is fit to save.
    val review = remember(draft) { draft.review(Instant.EPOCH) }
    Text(
        stringResource(R.string.policy_intro),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
    Text(
        state.storedAt?.let { stringResource(R.string.policy_saved_at, formatInstant(it)) }
            ?: stringResource(R.string.policy_never_saved),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp).testTag(PolicyTags.SAVED_AT),
    )
    Summary(draft, removes = review is DraftReview.NoRules && (state.storedAt != null))
    Actions(draft, onEdit)
    Assets(draft, review, onEdit)
    Addresses(
        list = RECIPIENTS,
        title = R.string.policy_section_recipients,
        switchLabel = R.string.policy_switch_recipients,
        off = R.string.policy_recipients_off,
        on = R.string.policy_recipients_on,
        empty = R.string.policy_recipients_empty,
        field = R.string.policy_recipient_field,
        note = R.string.policy_recipient_note,
        restricted = draft.restrictRecipients,
        values = draft.recipients,
        onRestrict = { onEdit(draft.copy(restrictRecipients = it)) },
        onChange = { onEdit(draft.copy(recipients = it)) },
    )
    Addresses(
        list = PROGRAMS,
        title = R.string.policy_section_programs,
        switchLabel = R.string.policy_switch_programs,
        off = R.string.policy_programs_off,
        on = R.string.policy_programs_on,
        empty = R.string.policy_programs_empty,
        field = R.string.policy_program_field,
        note = R.string.policy_program_note,
        restricted = draft.restrictPrograms,
        values = draft.programs,
        onRestrict = { onEdit(draft.copy(restrictPrograms = it)) },
        onChange = { onEdit(draft.copy(programs = it)) },
    )
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onSave,
            enabled = state.changed && !state.saving && review !is DraftReview.Problems,
            modifier = Modifier.testTag(PolicyTags.SAVE),
        ) {
            Text(stringResource(R.string.policy_save))
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = !state.saving,
            modifier = Modifier.testTag(PolicyTags.CANCEL),
        ) {
            Text(stringResource(R.string.policy_cancel))
        }
    }
}

/** The whole draft read back in plain language, and what it still doesn't cover. */
@Composable
private fun Summary(draft: PolicyDraft, removes: Boolean) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        stringResource(R.string.policy_summary_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Column(Modifier.testTag(PolicyTags.SUMMARY)) {
        for (line in summaryLines(draft)) {
            Text(line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        if (removes) {
            Text(
                stringResource(R.string.policy_summary_removes),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Actions(draft: PolicyDraft, onEdit: (PolicyDraft) -> Unit) {
    Restrict(
        title = R.string.policy_section_actions,
        switchLabel = R.string.policy_switch_actions,
        list = "actions",
        checked = draft.restrictActions,
        off = R.string.policy_actions_off,
        on = R.string.policy_actions_on,
        onCheckedChange = { onEdit(draft.copy(restrictActions = it)) },
    )
    if (!draft.restrictActions) return
    if (draft.actions.isEmpty()) Note(R.string.policy_actions_empty)
    for (action in PolicyAction.entries) {
        val ticked = action in draft.actions
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .testTag(PolicyTags.action(action))
                    .toggleable(value = ticked, role = Role.Checkbox) { on ->
                        val actions = if (on) draft.actions + action else draft.actions - action
                        onEdit(draft.copy(actions = actions))
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = ticked, onCheckedChange = null)
            Text(actionText(action), modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun Assets(draft: PolicyDraft, review: DraftReview, onEdit: (PolicyDraft) -> Unit) {
    Restrict(
        title = R.string.policy_section_assets,
        switchLabel = R.string.policy_switch_assets,
        list = "assets",
        checked = draft.restrictAssets,
        off = R.string.policy_assets_off,
        on = R.string.policy_assets_on,
        onCheckedChange = { onEdit(draft.copy(restrictAssets = it)) },
    )
    when {
        draft.assets.isNotEmpty() -> Unit
        draft.restrictAssets -> Note(R.string.policy_assets_empty)
        else -> Note(R.string.policy_assets_none)
    }
    val problems = (review as? DraftReview.Problems)?.assets.orEmpty()
    draft.assets.forEachIndexed { index, asset ->
        Asset(
            index = index,
            asset = asset,
            problems = problems[index] ?: AssetProblems(),
            onChange = { onEdit(draft.copy(assets = draft.assets.replacing(index, it))) },
            onRemove = { onEdit(draft.copy(assets = draft.assets.removing(index))) },
        )
    }
    if (draft.assets.any { it.mint != null }) Note(R.string.policy_token_units)
    var adding by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { adding = true },
        modifier = Modifier.padding(16.dp).testTag(PolicyTags.ADD_ASSET),
    ) {
        Text(stringResource(R.string.policy_add_asset))
    }
    if (adding) {
        AddAsset(
            listed = draft.assets,
            onAdd = {
                onEdit(draft.copy(assets = draft.assets + it))
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun Asset(
    index: Int,
    asset: AssetDraft,
    problems: AssetProblems,
    onChange: (AssetDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val name = assetLabel(asset.asset)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        name,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp).testTag(PolicyTags.asset(index)),
    )
    Amount(
        tag = PolicyTags.perOperation(index),
        value = asset.perOperation,
        label =
            if (asset.mint == null) R.string.policy_per_operation_sol
            else R.string.policy_per_operation_units,
        asset = asset,
        problem = problems.perOperation,
        onChange = { onChange(asset.copy(perOperation = it)) },
    )
    Amount(
        tag = PolicyTags.daily(index),
        value = asset.daily,
        label = if (asset.mint == null) R.string.policy_daily_sol else R.string.policy_daily_units,
        asset = asset,
        problem = problems.daily,
        onChange = { onChange(asset.copy(daily = it)) },
    )
    if (problems.dailyBelowPerOperation) Note(R.string.policy_daily_below)
    TextButton(
        onClick = onRemove,
        modifier = Modifier.padding(horizontal = 8.dp).testTag(PolicyTags.removeAsset(index)),
    ) {
        Text(stringResource(R.string.policy_asset_remove, name))
    }
}

/**
 * One threshold, typed in the asset's own units. What will be stored is shown under the field as it
 * is typed, so the owner reads the base-unit number the rule is actually compared in rather than
 * trusting a conversion they can't see.
 */
@Composable
private fun Amount(
    tag: String,
    value: String,
    @StringRes label: Int,
    asset: AssetDraft,
    problem: AmountProblem?,
    onChange: (String) -> Unit,
) {
    val entry = readAmount(value, asset.decimals)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = problem != null,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        // Never colour alone: the field always says in words what it made of what was typed.
        supportingText = {
            Text(
                when {
                    problem != null -> amountProblemText(problem, asset)
                    entry is AmountEntry.Amount ->
                        stringResource(R.string.policy_amount_stored, entry.baseUnits.toString())
                    else -> stringResource(R.string.policy_amount_none)
                }
            )
        },
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag),
    )
}

@Composable
private fun AddAsset(
    listed: List<AssetDraft>,
    onAdd: (AssetDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf(false) }
    var mint by rememberSaveable { mutableStateOf("") }
    var network by rememberSaveable { mutableStateOf(Network.NETWORK_MAINNET) }
    var problem by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.policy_asset_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choice(R.string.policy_asset_sol, !token, PolicyTags.ASSET_SOL) {
                    token = false
                    problem = null
                }
                Choice(R.string.policy_asset_token, token, PolicyTags.ASSET_TOKEN) {
                    token = true
                    problem = null
                }
                if (token) {
                    OutlinedTextField(
                        value = mint,
                        onValueChange = {
                            mint = it
                            problem = null
                        },
                        label = { Text(stringResource(R.string.policy_mint_field)) },
                        singleLine = true,
                        isError = problem != null,
                        modifier = Modifier.fillMaxWidth().testTag(PolicyTags.MINT_FIELD),
                    )
                }
                Text(
                    stringResource(R.string.policy_network_field),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in POLICY_NETWORKS) {
                        FilterChip(
                            selected = network == option,
                            onClick = { network = option },
                            label = { Text(networkText(option)) },
                            modifier = Modifier.testTag(PolicyTags.network(option)),
                        )
                    }
                }
                problem?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = mint.trim().takeIf { token }
                    problem =
                        when {
                            token && !isSolanaAddress(value.orEmpty()) ->
                                R.string.policy_address_invalid
                            listed.any { it.network == network && it.mint == value } ->
                                R.string.policy_address_listed
                            else -> null
                        }
                    if (problem == null) onAdd(AssetDraft(network, value))
                },
                modifier = Modifier.testTag(PolicyTags.DIALOG_ADD),
            ) {
                Text(stringResource(R.string.policy_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(PolicyTags.DIALOG_CANCEL)) {
                Text(stringResource(R.string.policy_cancel))
            }
        },
    )
}

@Composable
private fun Choice(@StringRes label: Int, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .testTag(tag)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), modifier = Modifier.padding(start = 16.dp))
    }
}

/** One list of addresses: what is in it, how to add to it, and how to take something out. */
@Composable
private fun Addresses(
    list: String,
    @StringRes title: Int,
    @StringRes switchLabel: Int,
    @StringRes off: Int,
    @StringRes on: Int,
    @StringRes empty: Int,
    @StringRes field: Int,
    @StringRes note: Int,
    restricted: Boolean,
    values: List<String>,
    onRestrict: (Boolean) -> Unit,
    onChange: (List<String>) -> Unit,
) {
    Restrict(title, switchLabel, list, restricted, off, on, onRestrict)
    if (!restricted) return
    Note(note)
    if (values.isEmpty()) Note(empty)
    for (value in values) {
        val remove = stringResource(R.string.policy_remove_entry, value)
        ListItem(
            // The whole address, wrapped rather than cut short: half an address read out of a
            // list is worse than none, because it looks like the one the owner meant.
            headlineContent = { Text(value) },
            trailingContent = {
                TextButton(
                    onClick = { onChange(values - value) },
                    modifier =
                        Modifier.testTag(PolicyTags.removeEntry(list, value)).semantics {
                            contentDescription = remove
                        },
                ) {
                    Text(stringResource(R.string.policy_remove))
                }
            },
            modifier = Modifier.testTag(PolicyTags.entry(list, value)),
        )
    }
    var typed by rememberSaveable(list) { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    OutlinedTextField(
        value = typed,
        onValueChange = {
            typed = it
            problem = null
        },
        label = { Text(stringResource(field)) },
        singleLine = true,
        isError = problem != null,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        supportingText = problem?.let { { Text(stringResource(it)) } },
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag(PolicyTags.entryField(list)),
    )
    OutlinedButton(
        onClick = {
            val value = typed.trim()
            problem =
                when {
                    !isSolanaAddress(value) -> R.string.policy_address_invalid
                    value in values -> R.string.policy_address_listed
                    else -> null
                }
            if (problem == null) {
                onChange(values + value)
                typed = ""
            }
        },
        modifier = Modifier.padding(16.dp).testTag(PolicyTags.add(list)),
    ) {
        Text(stringResource(R.string.policy_add))
    }
}

/**
 * One list's switch, with what it means in words both ways round. The switch decides whether the
 * check exists at all; what is in the list decides what passes it.
 */
@Composable
private fun Restrict(
    @StringRes title: Int,
    @StringRes switchLabel: Int,
    list: String,
    checked: Boolean,
    @StringRes off: Int,
    @StringRes on: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .testTag(PolicyTags.restrict(list))
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(switchLabel), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
    Note(if (checked) on else off)
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private fun List<AssetDraft>.replacing(index: Int, asset: AssetDraft): List<AssetDraft> =
    mapIndexed { at, current ->
        if (at == index) asset else current
    }

private fun List<AssetDraft>.removing(index: Int): List<AssetDraft> = filterIndexed { at, _ ->
    at != index
}
