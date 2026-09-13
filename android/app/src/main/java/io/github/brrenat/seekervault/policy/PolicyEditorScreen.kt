package io.github.brrenat.seekervault.policy

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.ChoiceChip
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassCheck
import io.github.brrenat.seekervault.ui.GlassDialog
import io.github.brrenat.seekervault.ui.GlassField
import io.github.brrenat.seekervault.ui.GlassRadio
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.GlassSwitch
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.IconChip
import io.github.brrenat.seekervault.ui.MessageOverlay
import io.github.brrenat.seekervault.ui.MonoText
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.PillButton
import io.github.brrenat.seekervault.ui.PillTone
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.truncateMiddle
import io.github.brrenat.seekervault.wallet.isSolanaAddress
import java.time.Instant

/**
 * The Rules screen (docs/guides/policies.md): where the owner writes down what one connection is
 * expected to ask for.
 *
 * Four labelled section cards, each the same shape — a heading, a master switch, a note that says
 * what the switch means *right now*, the list, and the editor for it. There is no expression
 * builder and no node canvas, because the model behind it is one conjunction of allowlists and
 * thresholds and pretending otherwise would be showing the owner a language they don't have.
 *
 * Two things this screen keeps saying, because both are easy to assume otherwise:
 * - **A switch that is off is not an empty list.** Off configures no check at all; on with an empty
 *   list allows nothing. Every section says which of the two it is in words.
 * - **Nothing here approves anything.** Within the rules and under restrictions both need the
 *   owner's hand on the wallet, and the summary says so every time it is read.
 */
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
    GlassScreen(
        title = stringResource(R.string.policy_rules),
        subtitle = label.ifEmpty { null },
        onBack = leave,
        modifier = modifier,
        overlay = {
            MessageOverlay(snackbar, Modifier.align(Alignment.BottomCenter), overTabBar = false)
        },
    ) {
        val unreadable = state.unreadable
        when {
            !state.loaded ->
                GlassCard {
                    Text(
                        stringResource(R.string.policy_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Nocturne.Neutral400,
                        modifier = Modifier.testTag(PolicyTags.LOADING),
                    )
                }
            unreadable != null -> Unreadable(unreadable, onStartOver, leave)
            else -> Editor(state, onEdit, onSave, leave)
        }
    }
    if (confirmDiscard) {
        GlassDialog(
            title = stringResource(R.string.policy_discard_title),
            onDismiss = { confirmDiscard = false },
            confirm = stringResource(R.string.policy_discard),
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            confirmTag = PolicyTags.DISCARD,
            dismiss = stringResource(R.string.policy_keep_editing),
            dismissTag = PolicyTags.KEEP_EDITING,
        ) {
            Text(
                stringResource(R.string.policy_discard_text),
                style = MaterialTheme.typography.bodyMedium,
                color = Nocturne.Neutral300,
            )
        }
    }
}

/**
 * Rules are stored and this build can't read them. No form is opened over them: an empty form saved
 * on top would delete rules the owner set and never saw, so replacing them is something they ask
 * for by name.
 */
@Composable
private fun ColumnScope.Unreadable(
    why: UnreadableReason,
    onStartOver: () -> Unit,
    onClose: () -> Unit,
) {
    GlassCard {
        Text(
            stringResource(R.string.policy_unreadable_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Nocturne.Text,
            modifier = Modifier.testTag(PolicyTags.UNREADABLE),
        )
        Text(
            unreadableText(why),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral300,
        )
        Text(
            stringResource(R.string.policy_unreadable_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral400,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
        PillButton(
            stringResource(R.string.policy_start_over),
            onStartOver,
            tone = PillTone.Accent,
            modifier = Modifier.weight(1f).testTag(PolicyTags.START_OVER),
        )
        PillButton(
            stringResource(R.string.policy_cancel),
            onClose,
            tone = PillTone.Ghost,
            modifier = Modifier.weight(1f).testTag(PolicyTags.CANCEL),
        )
    }
}

@Composable
private fun ColumnScope.Editor(
    state: PolicyUiState,
    onEdit: (PolicyDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.draft
    // The instant only dates the document, and this review is about whether it is fit to save.
    val review = remember(draft) { draft.review(Instant.EPOCH) }
    // What these rules are, before what they say: they are a note, and they decide nothing.
    GlassCard {
        Text(
            stringResource(R.string.policy_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral300,
        )
        Text(
            state.storedAt?.let { stringResource(R.string.policy_saved_at, formatInstant(it)) }
                ?: stringResource(R.string.policy_never_saved),
            style = MaterialTheme.typography.bodySmall,
            color = Nocturne.Neutral500,
            modifier = Modifier.testTag(PolicyTags.SAVED_AT),
        )
    }
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
        note = null,
        restricted = draft.restrictPrograms,
        values = draft.programs,
        onRestrict = { onEdit(draft.copy(restrictPrograms = it)) },
        onChange = { onEdit(draft.copy(programs = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
        PillButton(
            stringResource(R.string.policy_save),
            onSave,
            tone = PillTone.Accent,
            enabled = state.changed && !state.saving && review !is DraftReview.Problems,
            modifier = Modifier.weight(1f).testTag(PolicyTags.SAVE),
        )
        PillButton(
            stringResource(R.string.policy_cancel),
            onCancel,
            tone = PillTone.Ghost,
            enabled = !state.saving,
            modifier = Modifier.weight(1f).testTag(PolicyTags.CANCEL),
        )
    }
}

/** The whole draft read back in plain language, and what it still doesn't cover. */
@Composable
private fun Summary(draft: PolicyDraft, removes: Boolean) {
    GlassCard {
        SectionLabel(stringResource(R.string.policy_summary_title))
        Column(
            Modifier.testTag(PolicyTags.SUMMARY),
            verticalArrangement = Arrangement.spacedBy(Space.Xs),
        ) {
            for (line in summaryLines(draft)) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral200,
                )
            }
            if (removes) {
                Text(
                    stringResource(R.string.policy_summary_removes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral200,
                )
            }
        }
    }
}

@Composable
private fun Actions(draft: PolicyDraft, onEdit: (PolicyDraft) -> Unit) {
    Section(
        title = R.string.policy_section_actions,
        switchLabel = R.string.policy_switch_actions,
        list = "actions",
        checked = draft.restrictActions,
        note =
            when {
                !draft.restrictActions -> R.string.policy_actions_off
                draft.actions.isEmpty() -> R.string.policy_actions_empty
                else -> R.string.policy_actions_on
            },
        onCheckedChange = { onEdit(draft.copy(restrictActions = it)) },
    ) {
        if (!draft.restrictActions) return@Section
        for (action in PolicyAction.entries) {
            val ticked = action in draft.actions
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.InnerTight))
                    .testTag(PolicyTags.action(action))
                    .toggleable(value = ticked, role = Role.Checkbox) { on ->
                        val actions = if (on) draft.actions + action else draft.actions - action
                        onEdit(draft.copy(actions = actions))
                    }
                    .padding(horizontal = Space.Sm, vertical = Space.Sm),
                horizontalArrangement = Arrangement.spacedBy(Space.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCheck(ticked)
                Text(
                    actionText(action),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Nocturne.Text,
                )
            }
        }
    }
}

@Composable
private fun Assets(draft: PolicyDraft, review: DraftReview, onEdit: (PolicyDraft) -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val problems = (review as? DraftReview.Problems)?.assets.orEmpty()
    Section(
        title = R.string.policy_section_assets,
        switchLabel = R.string.policy_switch_assets,
        list = "assets",
        checked = draft.restrictAssets,
        note =
            when {
                !draft.restrictAssets -> R.string.policy_assets_off
                draft.assets.isEmpty() -> R.string.policy_assets_empty
                else -> R.string.policy_assets_on
            },
        onCheckedChange = { onEdit(draft.copy(restrictAssets = it)) },
    ) {
        if (draft.assets.isEmpty()) {
            Note(stringResource(R.string.policy_assets_none))
        }
        draft.assets.forEachIndexed { index, asset ->
            Asset(
                index = index,
                asset = asset,
                problems = problems[index] ?: AssetProblems(),
                onChange = { onEdit(draft.copy(assets = draft.assets.replacing(index, it))) },
                onRemove = { onEdit(draft.copy(assets = draft.assets.removing(index))) },
            )
        }
        if (draft.assets.any { it.mint != null }) Note(stringResource(R.string.policy_token_units))
        PillButton(
            stringResource(R.string.policy_add_asset),
            { adding = true },
            icon = Glyph.Add,
            modifier = Modifier.fillMaxWidth().testTag(PolicyTags.ADD_ASSET),
        )
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

/**
 * One listed asset: what it is, what it may move, and — when the row is opened — the two fields
 * that say how much.
 *
 * The limits stay editable after the asset is added. A rule the owner can only delete and rewrite
 * is a rule they will stop adjusting, and a threshold nobody adjusts stops meaning anything.
 */
@Composable
private fun Asset(
    index: Int,
    asset: AssetDraft,
    problems: AssetProblems,
    onChange: (AssetDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var open by rememberSaveable(index) { mutableStateOf(false) }
    val name = assetTitle(asset)
    val remove = stringResource(R.string.policy_asset_remove, name)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.InnerTight))
            .background(Nocturne.text(0.05f))
            .padding(Space.Sm),
        verticalArrangement = Arrangement.spacedBy(Space.Sm),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(Radius.Chip))
                .clickable { open = !open }
                .padding(Space.Xxs)
                .testTag(PolicyTags.asset(index)),
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(Glyph.Transfer, contentDescription = null, size = 34.dp)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = Nocturne.Text)
                Text(
                    limitsText(asset),
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.Neutral500,
                )
            }
            Box(
                Modifier.size(32.dp)
                    .clip(RoundedCornerShape(Radius.ChipTight))
                    .background(Nocturne.text(0.07f))
                    .clickable(onClick = onRemove)
                    .testTag(PolicyTags.removeAsset(index))
                    .semantics { contentDescription = remove },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Glyph.Close,
                    contentDescription = null,
                    tint = Nocturne.Neutral300,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (open) {
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
                label =
                    if (asset.mint == null) R.string.policy_daily_sol
                    else R.string.policy_daily_units,
                asset = asset,
                problem = problems.daily,
                onChange = { onChange(asset.copy(daily = it)) },
            )
            if (problems.dailyBelowPerOperation) Note(stringResource(R.string.policy_daily_below))
        }
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
    GlassField(
        value = value,
        onValueChange = onChange,
        label = stringResource(label),
        // Never colour alone: the field always says in words what it made of what was typed.
        problem = problem?.let { amountProblemText(it, asset) },
        supporting =
            if (entry is AmountEntry.Amount)
                stringResource(R.string.policy_amount_stored, entry.baseUnits.toString())
            else stringResource(R.string.policy_amount_none),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = Modifier.testTag(tag),
    )
}

/** The centred dialog that adds an asset, with its thresholds if the owner sets them here. */
@Composable
private fun AddAsset(listed: List<AssetDraft>, onAdd: (AssetDraft) -> Unit, onDismiss: () -> Unit) {
    var token by rememberSaveable { mutableStateOf(false) }
    var mint by rememberSaveable { mutableStateOf("") }
    var network by rememberSaveable { mutableStateOf(Network.NETWORK_MAINNET) }
    var perRequest by rememberSaveable { mutableStateOf("") }
    var daily by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    GlassDialog(
        title = stringResource(R.string.policy_asset_dialog_title),
        onDismiss = onDismiss,
        confirm = stringResource(R.string.policy_add),
        onConfirm = {
            val value = mint.trim().takeIf { token }
            problem =
                when {
                    token && !isSolanaAddress(value.orEmpty()) -> R.string.policy_mint_invalid
                    listed.any { it.network == network && it.mint == value } ->
                        R.string.policy_address_listed
                    else -> null
                }
            if (problem == null) {
                onAdd(
                    AssetDraft(
                        network = network,
                        mint = value,
                        perOperation = perRequest.trim(),
                        daily = daily.trim(),
                    )
                )
            }
        },
        confirmTag = PolicyTags.DIALOG_ADD,
        dismiss = stringResource(R.string.policy_cancel),
        dismissTag = PolicyTags.DIALOG_CANCEL,
    ) {
        SectionLabel(stringResource(R.string.policy_asset_kind))
        Choice(R.string.policy_asset_sol, !token, PolicyTags.ASSET_SOL) {
            token = false
            problem = null
        }
        Choice(R.string.policy_asset_token, token, PolicyTags.ASSET_TOKEN) {
            token = true
            problem = null
        }
        if (token) {
            GlassField(
                value = mint,
                onValueChange = {
                    mint = it
                    problem = null
                },
                label = stringResource(R.string.policy_mint_field),
                problem = problem?.let { stringResource(it) },
                modifier = Modifier.testTag(PolicyTags.MINT_FIELD),
            )
        }
        SectionLabel(stringResource(R.string.policy_network_field))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
            for (option in POLICY_NETWORKS) {
                ChoiceChip(
                    label = networkText(option),
                    selected = network == option,
                    onSelect = { network = option },
                    modifier = Modifier.testTag(PolicyTags.network(option)),
                )
            }
        }
        SectionLabel(stringResource(R.string.policy_thresholds_optional))
        val draft = AssetDraft(network, mint.trim().takeIf { token })
        Amount(
            tag = PolicyTags.DIALOG_PER_REQUEST,
            value = perRequest,
            label =
                if (draft.mint == null) R.string.policy_per_operation_sol
                else R.string.policy_per_operation_units,
            asset = draft,
            problem = (readAmount(perRequest, draft.decimals) as? AmountEntry.Problem)?.why,
            onChange = { perRequest = it },
        )
        Amount(
            tag = PolicyTags.DIALOG_DAILY,
            value = daily,
            label =
                if (draft.mint == null) R.string.policy_daily_sol else R.string.policy_daily_units,
            asset = draft,
            problem = (readAmount(daily, draft.decimals) as? AmountEntry.Problem)?.why,
            onChange = { daily = it },
        )
        Note(stringResource(R.string.policy_threshold_note))
        if (!token) {
            problem?.let { Note(stringResource(it)) }
        }
    }
}

@Composable
private fun Choice(@StringRes label: Int, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Radius.InnerTight))
            .testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Space.Sm, horizontal = Space.Xxs),
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassRadio(selected)
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            color = Nocturne.Text,
        )
    }
}

/** One list of addresses: what is in it, how to add to it, and how to take something out. */
@Composable
@Suppress("LongParameterList")
private fun Addresses(
    list: String,
    @StringRes title: Int,
    @StringRes switchLabel: Int,
    @StringRes off: Int,
    @StringRes on: Int,
    @StringRes empty: Int,
    @StringRes field: Int,
    @StringRes note: Int?,
    restricted: Boolean,
    values: List<String>,
    onRestrict: (Boolean) -> Unit,
    onChange: (List<String>) -> Unit,
) {
    var typed by rememberSaveable(list) { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    Section(
        title = title,
        switchLabel = switchLabel,
        list = list,
        checked = restricted,
        note =
            when {
                !restricted -> off
                values.isEmpty() -> empty
                else -> on
            },
        onCheckedChange = onRestrict,
    ) {
        if (!restricted) return@Section
        if (note != null) Note(stringResource(note))
        for (value in values) {
            val remove = stringResource(R.string.policy_remove_entry, value)
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.InnerTight))
                    .background(Nocturne.text(0.05f))
                    .padding(Space.Sm)
                    .testTag(PolicyTags.entry(list, value))
                    .semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Space.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconChip(
                    if (list == PROGRAMS) Glyph.Program else Glyph.Wallet,
                    contentDescription = null,
                    size = 32.dp,
                    accent = false,
                )
                // Shortened in the middle, never cut at one end: the whole address is read out
                // by a screen reader, and both ends are on screen for a human to compare.
                MonoText(truncateMiddle(value), modifier = Modifier.weight(1f), maxLines = 1)
                Box(
                    Modifier.size(32.dp)
                        .clip(RoundedCornerShape(Radius.ChipTight))
                        .background(Nocturne.text(0.07f))
                        .clickable { onChange(values - value) }
                        .testTag(PolicyTags.removeEntry(list, value))
                        .semantics { contentDescription = remove },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Glyph.Close,
                        contentDescription = null,
                        tint = Nocturne.Neutral300,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        GlassField(
            value = typed,
            onValueChange = {
                typed = it
                problem = null
            },
            label = stringResource(field),
            problem = problem?.let { stringResource(it) },
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            modifier = Modifier.testTag(PolicyTags.entryField(list)),
        )
        PillButton(
            stringResource(R.string.policy_add),
            {
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
            modifier = Modifier.fillMaxWidth().testTag(PolicyTags.add(list)),
        )
    }
}

/**
 * One section card: its name, its switch, what the switch means as it stands, and its contents.
 *
 * The note under the switch is not decoration. The switch decides whether the check exists at all;
 * what is in the list decides what passes it, and those are two different things that look the same
 * when they are both "empty".
 */
@Composable
private fun Section(
    @StringRes title: Int,
    @StringRes switchLabel: Int,
    list: String,
    checked: Boolean,
    @StringRes note: Int,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard {
        SectionLabel(stringResource(title))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(Radius.InnerTight))
                .testTag(PolicyTags.restrict(list))
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(vertical = Space.Sm),
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(switchLabel),
                style = MaterialTheme.typography.titleMedium,
                color = Nocturne.Text,
                modifier = Modifier.weight(1f),
            )
            GlassSwitch(checked)
        }
        Note(stringResource(note))
        CardDivider()
        content()
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Nocturne.Neutral500)
}

/** `Native SOL · devnet`, or `Token 4k3Dyj…9yzQ · mainnet`. */
@Composable
private fun assetTitle(asset: AssetDraft): String =
    if (asset.mint == null) stringResource(R.string.policy_asset_native, networkText(asset.network))
    else
        stringResource(
            R.string.policy_asset_token_named,
            truncateMiddle(asset.mint),
            networkText(asset.network),
        )

/** `0.005 per request · 0.05 a day`, or that there is no limit at all. */
@Composable
private fun limitsText(asset: AssetDraft): String {
    val perRequest = (readAmount(asset.perOperation, asset.decimals) as? AmountEntry.Amount)
    val daily = (readAmount(asset.daily, asset.decimals) as? AmountEntry.Amount)
    val one = perRequest?.let { amountText(it.baseUnits, asset) }
    val day = daily?.let { amountText(it.baseUnits, asset) }
    return when {
        one != null && day != null -> stringResource(R.string.policy_asset_limits, one, day)
        one != null -> stringResource(R.string.policy_asset_limit_per_request, one)
        day != null -> stringResource(R.string.policy_asset_limit_daily, day)
        else -> stringResource(R.string.policy_asset_no_limit)
    }
}

private fun List<AssetDraft>.replacing(index: Int, asset: AssetDraft): List<AssetDraft> =
    mapIndexed { at, current ->
        if (at == index) asset else current
    }

private fun List<AssetDraft>.removing(index: Int): List<AssetDraft> = filterIndexed { at, _ ->
    at != index
}
