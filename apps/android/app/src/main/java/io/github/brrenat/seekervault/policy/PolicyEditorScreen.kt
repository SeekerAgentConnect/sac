package io.github.brrenat.seekervault.policy

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.notifications.LocalInAppNotices
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.SolidDialog
import io.github.brrenat.seekervault.ui.seekerListItemColors
import io.github.brrenat.seekervault.ui.seekerTextFieldColors
import io.github.brrenat.seekervault.wallet.isSolanaAddress
import java.time.Instant

/**
 * The policy editors (docs/guides/policies.md): global defaults and one connection's overrides.
 *
 * Material 3 controls under the approved v4 theme — switches, checkboxes, radio buttons, chips,
 * text fields, lists, and Save and Cancel. There is no expression builder and no node canvas,
 * because the model behind it is one conjunction of allowlists and thresholds and pretending
 * otherwise would be showing the owner a language they don't have.
 *
 * Two things this screen keeps saying, because both are easy to assume otherwise:
 * - **Inheritance, no check, and an empty list differ.** Each connection section first chooses the
 *   global value or a full override; the inner switch then keeps no-check apart from empty.
 * - **Nothing here approves anything.** `ALLOWED` and `UNDER_RESTRICTIONS` both need the owner's
 *   hand on the wallet, and the summary says so every time it is read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyEditorScreen(
    label: String,
    state: PolicyUiState,
    onEdit: (PolicyEditorDraft) -> Unit,
    onStartOver: () -> Unit,
    onResetConnection: () -> Unit,
    onOpenGlobal: () -> Unit,
    onSave: () -> Unit,
    onMessageShown: () -> Unit,
    onClose: () -> Unit,
    closeRequest: Int = 0,
    onCloseRequestCancelled: () -> Unit = {},
    onOpenAsset: (PolicyAsset?, PolicyAssetEditorKind) -> Unit = { _, _ -> },
    onOpenAddress: (PolicyAddressKind) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val notices = LocalInAppNotices.current
    val inlineGlobalSaveFailure =
        state.scope == PolicyEditorScope.Global && state.message == PolicyMessage.SaveFailed
    val message =
        state.message?.takeUnless { inlineGlobalSaveFailure }?.let { messageText(it, state.scope) }
    LaunchedEffect(state.message, inlineGlobalSaveFailure) {
        if (message != null) {
            notices.show(message)
            onMessageShown()
        }
    }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmGlobalSave by rememberSaveable { mutableStateOf(false) }
    val leave = { if (state.changed) confirmDiscard = true else onClose() }
    val globalReview =
        remember(state.draft) {
            (state.draft as? PolicyEditorDraft.Global)?.rules?.review(Instant.EPOCH)
        }
    // Route every system Back through the same close path as the app bar. Besides asking before a
    // dirty draft is discarded, this lets the caller refresh inherited context after Global rules
    // closes and clear the ViewModel for an ordinary connection exit.
    BackHandler { leave() }
    // An exposed sheet backplate asks the active editor to leave through this same guarded path.
    // Zero is the idle value so a newly composed editor does not close itself.
    LaunchedEffect(closeRequest) { if (closeRequest > 0) leave() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (state.scope == PolicyEditorScope.Global) {
                                stringResource(R.string.policy_global_title)
                            } else {
                                stringResource(R.string.policy_title, label)
                            }
                        )
                    },
                    actions = {
                        CloseButton(leave, MaterialTheme.colorScheme.surfaceContainerHigh)
                    },
                    expandedHeight = SeekerTheme.dimensions.dp56,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                )
            },
            bottomBar = {
                if (
                    state.scope == PolicyEditorScope.Global &&
                        state.loaded &&
                        state.unreadable == null &&
                        state.changed &&
                        globalReview != null
                ) {
                    GlobalPolicyFooter(
                        saving = state.saving,
                        valid = globalReview !is DraftReview.Problems,
                        saveFailed = inlineGlobalSaveFailure,
                        onCancel = leave,
                        onSave = { confirmGlobalSave = true },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).fillMaxWidth()
            ) {
                val unreadable = state.unreadable
                when {
                    !state.loaded ->
                        Text(
                            stringResource(
                                if (state.scope == PolicyEditorScope.Global) {
                                    R.string.policy_global_loading
                                } else {
                                    R.string.policy_loading
                                }
                            ),
                            modifier =
                                Modifier.padding(SeekerTheme.dimensions.dp16)
                                    .testTag(PolicyTags.LOADING),
                        )
                    unreadable != null -> Unreadable(unreadable, state.scope, onStartOver, leave)
                    state.scope == PolicyEditorScope.Global -> GlobalEditor(state, onEdit)
                    else ->
                        ConnectionEditor(
                            state,
                            onEdit,
                            onResetConnection,
                            onOpenGlobal,
                            onSave,
                            leave,
                            onOpenAsset,
                            onOpenAddress,
                        )
                }
            }
        }
        if (confirmDiscard) {
            SolidDialog(
                title = stringResource(R.string.policy_discard_title),
                body = { Text(stringResource(R.string.policy_discard_text)) },
                actions = {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                    ) {
                        NeutralPolicyButton(
                            onClick = {
                                confirmDiscard = false
                                onCloseRequestCancelled()
                            },
                            modifier = Modifier.weight(1f).testTag(PolicyTags.KEEP_EDITING),
                        ) {
                            Text(stringResource(R.string.policy_keep_editing))
                        }
                        PrimaryPolicyButton(
                            onClick = {
                                confirmDiscard = false
                                onClose()
                            },
                            modifier = Modifier.weight(1f).testTag(PolicyTags.DISCARD),
                        ) {
                            Text(stringResource(R.string.policy_discard))
                        }
                    }
                },
            )
        }
        if (confirmGlobalSave) {
            SolidDialog(
                title = stringResource(R.string.policy_global_confirm_title),
                body = { Text(stringResource(R.string.policy_global_confirm_text)) },
                actions = {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                    ) {
                        NeutralPolicyButton(
                            onClick = { confirmGlobalSave = false },
                            modifier = Modifier.weight(1f).testTag(PolicyTags.DIALOG_CANCEL),
                        ) {
                            Text(stringResource(R.string.policy_cancel))
                        }
                        PrimaryPolicyButton(
                            onClick = {
                                confirmGlobalSave = false
                                onSave()
                            },
                            modifier = Modifier.weight(1f).testTag(PolicyTags.CONFIRM_GLOBAL_SAVE),
                        ) {
                            Text(stringResource(R.string.policy_global_confirm))
                        }
                    }
                },
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
private fun Unreadable(
    why: UnreadableReason,
    scope: PolicyEditorScope?,
    onStartOver: () -> Unit,
    onClose: () -> Unit,
) {
    Text(
        stringResource(R.string.policy_unreadable_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(SeekerTheme.dimensions.dp16).testTag(PolicyTags.UNREADABLE),
    )
    Text(
        unreadableText(why, scope),
        modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
    )
    Text(
        stringResource(
            if (scope == PolicyEditorScope.Global) {
                R.string.policy_global_unreadable_text
            } else {
                R.string.policy_unreadable_text
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(SeekerTheme.dimensions.dp16),
    )
    Row(
        modifier =
            Modifier.padding(
                horizontal = SeekerTheme.dimensions.dp16,
                vertical = SeekerTheme.dimensions.dp8,
            ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
    ) {
        PrimaryPolicyButton(
            onClick = onStartOver,
            modifier = Modifier.testTag(PolicyTags.START_OVER),
        ) {
            Text(stringResource(R.string.policy_start_over))
        }
        NeutralPolicyButton(onClick = onClose, modifier = Modifier.testTag(PolicyTags.CANCEL)) {
            Text(stringResource(R.string.policy_cancel))
        }
    }
}

@Composable
private fun GlobalEditor(
    state: PolicyUiState,
    onEdit: (PolicyEditorDraft) -> Unit,
) {
    val draft = (state.draft as? PolicyEditorDraft.Global)?.rules ?: return
    // The instant only dates the document, and this review is about whether it is fit to save.
    val review = remember(draft) { draft.review(Instant.EPOCH) }
    val empty = review is DraftReview.NoRules
    val edit = { next: PolicyDraft -> onEdit(PolicyEditorDraft.Global(next)) }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = SeekerTheme.dimensions.dp16,
                    vertical = SeekerTheme.dimensions.dp4,
                ),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
    ) {
        GlobalRulesHelp()
        if (empty) GlobalEmptyWarning()
        Text(
            stringResource(
                if (empty) R.string.policy_global_empty_caption else R.string.policy_global_caption
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp4),
        )
        Actions(draft, edit, global = true)
        Assets(draft, review, edit, global = true)
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
            onRestrict = { edit(draft.copy(restrictRecipients = it)) },
            onChange = { edit(draft.copy(recipients = it)) },
            global = true,
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
            onRestrict = { edit(draft.copy(restrictPrograms = it)) },
            onChange = { edit(draft.copy(programs = it)) },
            global = true,
        )
        if (!empty) {
            ErrorPolicyButton(
                onClick = { edit(PolicyDraft(draft.connectionId)) },
                modifier = Modifier.testTag(PolicyTags.CLEAR_GLOBAL),
            ) {
                Text(stringResource(R.string.policy_clear_global))
            }
        }
        Text(
            stringResource(R.string.policy_global_daily_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp4),
        )
    }
}

@Composable
private fun GlobalPolicyFooter(
    saving: Boolean,
    valid: Boolean,
    saveFailed: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(
                    horizontal = SeekerTheme.dimensions.dp16,
                    vertical = SeekerTheme.dimensions.dp12,
                ),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
    ) {
        if (saveFailed) {
            SeekerCard(
                modifier = Modifier.fillMaxWidth().testTag(PolicyTags.SAVE_ERROR),
                color = MaterialTheme.colorScheme.errorContainer,
                radius = SeekerTheme.dimensions.dp16,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp14),
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(SeekerTheme.dimensions.dp20),
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp4),
                    ) {
                        Text(
                            stringResource(R.string.policy_save_error_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            stringResource(R.string.policy_save_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
        ) {
            Text(
                stringResource(
                    if (saveFailed) R.string.policy_not_saved_yet
                    else R.string.policy_unsaved_changes
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NeutralPolicyButton(
                onClick = onCancel,
                enabled = !saving,
                modifier = Modifier.testTag(PolicyTags.CANCEL),
            ) {
                Text(stringResource(R.string.policy_discard))
            }
            PrimaryPolicyButton(
                onClick = onSave,
                enabled = !saving && valid,
                modifier = Modifier.testTag(PolicyTags.SAVE),
            ) {
                Text(
                    stringResource(
                        if (saveFailed) R.string.policy_try_again else R.string.policy_save
                    )
                )
            }
        }
    }
}

@Composable
private fun GlobalEmptyWarning() {
    SeekerCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(SeekerTheme.dimensions.dp20),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp4),
            ) {
                Text(
                    stringResource(R.string.policy_global_empty_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    stringResource(R.string.policy_global_empty_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun GlobalRulesHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SeekerCard(
        modifier = Modifier.fillMaxWidth().testTag(PolicyTags.HELP),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { expanded = !expanded },
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal = SeekerTheme.dimensions.dp16,
                        vertical = SeekerTheme.dimensions.dp14,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
        ) {
            Text(
                stringResource(R.string.policy_global_intro),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SeekerTheme.dimensions.dp20),
            )
        }
    }
    if (expanded) {
        SeekerCard(
            modifier = Modifier.fillMaxWidth().testTag(PolicyTags.HELP_CONTENT),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
            ) {
                HelpLine(Icons.Outlined.Block, R.string.policy_help_rule)
                HelpLine(Icons.Outlined.Public, R.string.policy_help_global)
                HelpLine(Icons.Outlined.Schedule, R.string.policy_help_daily)
                HelpLine(Icons.Outlined.Fingerprint, R.string.policy_help_wallet)
            }
        }
    }
}

@Composable
private fun HelpLine(icon: ImageVector, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10)) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SeekerTheme.dimensions.dp18),
        )
        Text(
            stringResource(text),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionEditor(
    state: PolicyUiState,
    onEdit: (PolicyEditorDraft) -> Unit,
    onReset: () -> Unit,
    onOpenGlobal: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onOpenAsset: (PolicyAsset?, PolicyAssetEditorKind) -> Unit,
    onOpenAddress: (PolicyAddressKind) -> Unit,
) {
    val draft = (state.draft as? PolicyEditorDraft.Connection)?.rules ?: return
    val review = remember(draft) { draft.review(Instant.EPOCH) }
    val edit = { next: ConnectionPolicyDraft -> onEdit(PolicyEditorDraft.Connection(next)) }
    Text(
        stringResource(R.string.policy_connection_intro),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(SeekerTheme.dimensions.dp16),
    )
    Text(
        state.storedAt?.let { stringResource(R.string.policy_override_saved_at, formatInstant(it)) }
            ?: stringResource(R.string.policy_override_never_saved),
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier.padding(horizontal = SeekerTheme.dimensions.dp16).testTag(PolicyTags.SAVED_AT),
    )
    if (state.globalUnreadable != null) {
        Text(
            stringResource(R.string.policy_global_context_unreadable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier =
                Modifier.padding(SeekerTheme.dimensions.dp16).testTag(PolicyTags.GLOBAL_UNREADABLE),
        )
    } else {
        EffectiveSummary(draft, state.global, review)
    }
    NeutralPolicyButton(
        onClick = onOpenGlobal,
        modifier =
            Modifier.padding(horizontal = SeekerTheme.dimensions.dp8)
                .testTag(PolicyTags.OPEN_GLOBAL),
    ) {
        Text(stringResource(R.string.policy_open_global))
    }

    OverrideSelector(
        title = R.string.policy_section_actions,
        list = "actions",
        overrides = draft.overrideActions,
        onChange = {
            edit(
                if (it) draft.copy(overrideActions = true)
                else
                    draft.copy(
                        overrideActions = false,
                        restrictActions = false,
                        actions = emptySet(),
                    )
            )
        },
    )
    if (draft.overrideActions) {
        val rules =
            PolicyDraft(
                draft.connectionId,
                restrictActions = draft.restrictActions,
                actions = draft.actions,
            )
        Actions(
            rules,
            {
                edit(
                    draft.copy(
                        restrictActions = it.restrictActions,
                        actions = it.actions,
                    )
                )
            },
            showHeader = false,
        )
    }

    ConnectionAssets(
        draft,
        state.global,
        globalUnreadable = state.globalUnreadable != null,
        review = review,
        onEdit = edit,
        onOpenAsset = onOpenAsset,
    )
    OverrideSelector(
        title = R.string.policy_section_recipients,
        list = RECIPIENTS,
        overrides = draft.overrideRecipients,
        onChange = {
            edit(
                if (it) draft.copy(overrideRecipients = true)
                else
                    draft.copy(
                        overrideRecipients = false,
                        restrictRecipients = false,
                        recipients = emptyList(),
                    )
            )
        },
    )
    if (draft.overrideRecipients) {
        Addresses(
            list = RECIPIENTS,
            title = R.string.policy_section_recipients,
            switchLabel = R.string.policy_switch_recipients,
            off = R.string.policy_connection_recipients_off,
            on = R.string.policy_recipients_on,
            empty = R.string.policy_recipients_empty,
            field = R.string.policy_recipient_field,
            note = R.string.policy_recipient_note,
            restricted = draft.restrictRecipients,
            values = draft.recipients,
            onRestrict = { edit(draft.copy(restrictRecipients = it)) },
            onChange = { edit(draft.copy(recipients = it)) },
            showHeader = false,
            onAdd = { onOpenAddress(PolicyAddressKind.Recipient) },
        )
    }
    OverrideSelector(
        title = R.string.policy_section_programs,
        list = PROGRAMS,
        overrides = draft.overridePrograms,
        onChange = {
            edit(
                if (it) draft.copy(overridePrograms = true)
                else
                    draft.copy(
                        overridePrograms = false,
                        restrictPrograms = false,
                        programs = emptyList(),
                    )
            )
        },
    )
    if (draft.overridePrograms) {
        Addresses(
            list = PROGRAMS,
            title = R.string.policy_section_programs,
            switchLabel = R.string.policy_switch_programs,
            off = R.string.policy_connection_programs_off,
            on = R.string.policy_programs_on,
            empty = R.string.policy_programs_empty,
            field = R.string.policy_program_field,
            note = R.string.policy_program_note,
            restricted = draft.restrictPrograms,
            values = draft.programs,
            onRestrict = { edit(draft.copy(restrictPrograms = it)) },
            onChange = { edit(draft.copy(programs = it)) },
            showHeader = false,
            onAdd = { onOpenAddress(PolicyAddressKind.Program) },
        )
    }

    SectionGap(Modifier.padding(top = SeekerTheme.dimensions.dp16))
    NeutralPolicyButton(
        onClick = onReset,
        enabled = review !is ConnectionDraftReview.InheritAll && !state.saving,
        modifier =
            Modifier.padding(horizontal = SeekerTheme.dimensions.dp8)
                .testTag(PolicyTags.RESET_OVERRIDES),
    ) {
        Text(stringResource(R.string.policy_reset_overrides))
    }
    Text(
        stringResource(R.string.policy_reset_overrides_note),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
    )
    Row(
        modifier = Modifier.padding(SeekerTheme.dimensions.dp16),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
    ) {
        PrimaryPolicyButton(
            onClick = onSave,
            enabled = state.changed && !state.saving && review !is ConnectionDraftReview.Problems,
            modifier = Modifier.testTag(PolicyTags.SAVE),
        ) {
            Text(stringResource(R.string.policy_save))
        }
        NeutralPolicyButton(
            onClick = onCancel,
            enabled = !state.saving,
            modifier = Modifier.testTag(PolicyTags.CANCEL),
        ) {
            Text(stringResource(R.string.policy_cancel))
        }
    }
}

@Composable
private fun EffectiveSummary(
    draft: ConnectionPolicyDraft,
    global: GlobalPolicy?,
    review: ConnectionDraftReview,
) {
    SectionGap(Modifier.padding(vertical = SeekerTheme.dimensions.dp8))
    Text(
        stringResource(R.string.policy_effective_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
    )
    val overrides =
        when (review) {
            ConnectionDraftReview.InheritAll ->
                ConnectionPolicyOverrides.inheritAll(draft.connectionId, Instant.EPOCH)
            is ConnectionDraftReview.Ready -> review.overrides
            is ConnectionDraftReview.Problems -> null
        }
    if (overrides == null) {
        Text(
            stringResource(R.string.policy_effective_fix_errors),
            modifier = Modifier.padding(SeekerTheme.dimensions.dp16).testTag(PolicyTags.SUMMARY),
        )
        return
    }
    val effective = resolveEffectivePolicy(draft.connectionId, global, overrides)
    Column(Modifier.testTag(PolicyTags.SUMMARY)) {
        EffectiveLine(
            stringResource(R.string.policy_section_actions),
            effective.actions,
        ) { values ->
            if (values.values.isEmpty()) {
                stringResource(R.string.policy_effective_nothing)
            } else {
                val names = mutableListOf<String>()
                for (action in values.values) names += actionsText(action)
                names.joinToString()
            }
        }
        EffectiveLine(
            stringResource(R.string.policy_section_assets),
            effective.assets,
        ) { values ->
            if (values.values.isEmpty()) stringResource(R.string.policy_effective_nothing)
            else values.values.joinToString { assetLabel(it) }
        }
        EffectiveLine(
            stringResource(R.string.policy_section_recipients),
            effective.recipients,
        ) { values ->
            if (values.values.isEmpty()) stringResource(R.string.policy_effective_nothing)
            else values.values.joinToString()
        }
        EffectiveLine(
            stringResource(R.string.policy_section_programs),
            effective.programs,
        ) { values ->
            if (values.values.isEmpty()) stringResource(R.string.policy_effective_nothing)
            else values.values.joinToString()
        }
        Text(
            stringResource(R.string.policy_summary_manual),
            modifier =
                Modifier.padding(
                    horizontal = SeekerTheme.dimensions.dp16,
                    vertical = SeekerTheme.dimensions.dp2,
                ),
        )
    }
}

@Composable
private fun <T : Any> EffectiveLine(
    label: String,
    rule: EffectiveRule<T>,
    value: @Composable (T) -> String,
) {
    val rendered =
        rule.value?.let { value(it) } ?: stringResource(R.string.policy_effective_not_checked)
    Text(
        stringResource(
            R.string.policy_effective_line,
            label,
            rendered,
            sourceText(rule.source),
        ),
        modifier =
            Modifier.padding(
                horizontal = SeekerTheme.dimensions.dp16,
                vertical = SeekerTheme.dimensions.dp2,
            ),
    )
}

@Composable
private fun OverrideSelector(
    @StringRes title: Int,
    list: String,
    overrides: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SectionGap(Modifier.padding(vertical = SeekerTheme.dimensions.dp8))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
    )
    Choice(
        R.string.policy_use_global,
        !overrides,
        PolicyTags.inherit(list),
    ) {
        onChange(false)
    }
    Choice(
        R.string.policy_override,
        overrides,
        PolicyTags.override(list),
    ) {
        onChange(true)
    }
    Note(if (overrides) R.string.policy_override_replaces else R.string.policy_inherit_section)
}

@Composable
private fun ConnectionAssets(
    draft: ConnectionPolicyDraft,
    global: GlobalPolicy?,
    globalUnreadable: Boolean,
    review: ConnectionDraftReview,
    onEdit: (ConnectionPolicyDraft) -> Unit,
    onOpenAsset: (PolicyAsset?, PolicyAssetEditorKind) -> Unit,
) {
    OverrideSelector(
        title = R.string.policy_section_assets,
        list = "assets",
        overrides = draft.overrideAssets,
        onChange = {
            onEdit(
                if (it) draft.copy(overrideAssets = true)
                else
                    draft.copy(
                        overrideAssets = false,
                        restrictAssets = false,
                        assets = emptyList(),
                    )
            )
        },
    )
    if (draft.overrideAssets) {
        Restrict(
            title = R.string.policy_section_assets,
            switchLabel = R.string.policy_switch_assets,
            list = "assets",
            checked = draft.restrictAssets,
            off = R.string.policy_connection_assets_off,
            on = R.string.policy_assets_on,
            onCheckedChange = { onEdit(draft.copy(restrictAssets = it)) },
            showHeader = false,
        )
        if (draft.restrictAssets) {
            if (draft.assets.isEmpty()) Note(R.string.policy_assets_empty)
            for (asset in draft.assets) {
                val label = assetLabel(asset)
                val removeDescription = stringResource(R.string.policy_asset_remove, label)
                val local = draft.limits.firstOrNull { it.asset == asset }
                val asAsset = local?.asAssetDraft() ?: AssetDraft(asset.network, asset.mint)
                val inherited = global?.limitsFor(asset)
                val perOperation =
                    if (local?.overridePerOperation == true) local.perOperation
                    else inherited?.perOperation?.let { amountText(it, asAsset) }.orEmpty()
                val daily = local?.daily.orEmpty()
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.policy_connection_asset_limits,
                                perOperation.ifBlank {
                                    stringResource(R.string.policy_effective_not_checked)
                                },
                                daily.ifBlank {
                                    stringResource(R.string.policy_effective_not_checked)
                                },
                            )
                        )
                    },
                    trailingContent = {
                        NeutralPolicyButton(
                            onClick = { onEdit(draft.copy(assets = draft.assets - asset)) },
                            modifier =
                                Modifier.testTag(PolicyTags.removeAllowedAsset(asset)).semantics {
                                    contentDescription = removeDescription
                                },
                        ) {
                            Text(stringResource(R.string.policy_remove))
                        }
                    },
                    modifier =
                        Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                role = Role.Button,
                                onClick = {
                                    onOpenAsset(asset, PolicyAssetEditorKind.Allowlisted)
                                },
                            )
                            .testTag(PolicyTags.connectionAsset(asset)),
                    colors = seekerListItemColors(),
                )
            }
            AddPolicyAssetButton(
                tag = PolicyTags.ADD_ALLOWED_ASSET,
                onOpen = { onOpenAsset(null, PolicyAssetEditorKind.Allowlisted) },
            )
        }
    }
    ConnectionThresholds(draft, global, globalUnreadable, review, onOpenAsset)
}

@Composable
private fun AddPolicyAssetButton(tag: String, onOpen: () -> Unit) {
    NeutralPolicyButton(
        onClick = onOpen,
        modifier = Modifier.padding(SeekerTheme.dimensions.dp16).testTag(tag),
    ) {
        Text(stringResource(R.string.policy_add_asset))
    }
}

@Composable
private fun ConnectionThresholds(
    draft: ConnectionPolicyDraft,
    global: GlobalPolicy?,
    globalUnreadable: Boolean,
    review: ConnectionDraftReview,
    onOpenAsset: (PolicyAsset?, PolicyAssetEditorKind) -> Unit,
) {
    Text(
        stringResource(R.string.policy_connection_thresholds),
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier.padding(
                horizontal = SeekerTheme.dimensions.dp16,
                vertical = SeekerTheme.dimensions.dp8,
            ),
    )
    Text(
        stringResource(R.string.policy_connection_thresholds_note),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
    )
    val assets = linkedSetOf<PolicyAsset>()
    assets += global?.assets?.values.orEmpty()
    assets += global?.limits.orEmpty().keys
    assets += draft.assets
    assets += draft.limits.map { it.asset }
    for (asset in assets.filterNot { it in draft.assets }) {
        val local = draft.limits.firstOrNull { it.asset == asset }
        val asAsset = local?.asAssetDraft() ?: AssetDraft(asset.network, asset.mint)
        val inherited = global?.limitsFor(asset)
        val perOperation =
            if (local?.overridePerOperation == true) local.perOperation
            else inherited?.perOperation?.let { amountText(it, asAsset) }.orEmpty()
        val daily = local?.daily.orEmpty()
        ListItem(
            headlineContent = { Text(assetLabel(asset)) },
            supportingContent = {
                Text(
                    stringResource(
                        R.string.policy_connection_asset_limits,
                        perOperation.ifBlank {
                            stringResource(R.string.policy_effective_not_checked)
                        },
                        daily.ifBlank { stringResource(R.string.policy_effective_not_checked) },
                    )
                )
            },
            modifier =
                Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        role = Role.Button,
                        onClick = { onOpenAsset(asset, PolicyAssetEditorKind.SpendingLimit) },
                    )
                    .testTag(PolicyTags.connectionAsset(asset)),
            colors = seekerListItemColors(),
        )
    }
    if (
        (review as? ConnectionDraftReview.Problems)
            ?.policy
            .orEmpty()
            .contains(PolicyProblem.LimitForUnlistedAsset)
    ) {
        Text(
            stringResource(R.string.policy_limit_unlisted),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(SeekerTheme.dimensions.dp16),
        )
    }
    AddPolicyAssetButton(
        tag = PolicyTags.ADD_LIMIT_ASSET,
        onOpen = { onOpenAsset(null, PolicyAssetEditorKind.SpendingLimit) },
    )
    if (globalUnreadable) Note(R.string.policy_global_context_unreadable)
    if (assets.any { it.mint != null }) Note(R.string.policy_token_units)
}

@Composable
private fun GlobalSectionCard(
    list: String,
    @StringRes title: Int,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    SeekerCard(
        modifier = Modifier.fillMaxWidth().testTag(PolicyTags.section(list)),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = SeekerTheme.colors.primaryText,
                    modifier = Modifier.size(SeekerTheme.dimensions.dp20),
                )
                Text(
                    stringResource(title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                GlobalChip()
            }
            content()
        }
    }
}

@Composable
private fun GlobalChip() {
    Row(
        modifier =
            Modifier.heightIn(min = SeekerTheme.dimensions.dp24)
                .clip(RoundedCornerShape(SeekerTheme.dimensions.dp8))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(
                    horizontal = SeekerTheme.dimensions.dp9,
                    vertical = SeekerTheme.dimensions.dp2,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp5),
    ) {
        Icon(
            Icons.Outlined.Public,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.dimensions.dp13),
        )
        Text(
            stringResource(R.string.policy_source_global),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** The whole draft read back in plain language, and what it still doesn't cover. */
@Composable
private fun Summary(draft: PolicyDraft, removes: Boolean, global: Boolean = false) {
    SectionGap(Modifier.padding(vertical = SeekerTheme.dimensions.dp8))
    Text(
        stringResource(R.string.policy_summary_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
    )
    Column(Modifier.testTag(PolicyTags.SUMMARY)) {
        for (line in summaryLines(draft, global)) {
            Text(
                line,
                modifier =
                    Modifier.padding(
                        horizontal = SeekerTheme.dimensions.dp16,
                        vertical = SeekerTheme.dimensions.dp2,
                    ),
            )
        }
        if (removes) {
            Text(
                stringResource(
                    if (global) R.string.policy_global_summary_removes
                    else R.string.policy_summary_removes
                ),
                modifier =
                    Modifier.padding(
                        horizontal = SeekerTheme.dimensions.dp16,
                        vertical = SeekerTheme.dimensions.dp2,
                    ),
            )
        }
    }
}

@Composable
private fun Actions(
    draft: PolicyDraft,
    onEdit: (PolicyDraft) -> Unit,
    showHeader: Boolean = true,
    global: Boolean = false,
) {
    if (global) {
        GlobalSectionCard(
            list = "actions",
            title = R.string.policy_section_actions,
            icon = Icons.Outlined.Bolt,
        ) {
            ActionsContent(draft, onEdit, global = true)
        }
        return
    }
    ActionsContent(draft, onEdit, showHeader)
}

@Composable
private fun ActionsContent(
    draft: PolicyDraft,
    onEdit: (PolicyDraft) -> Unit,
    showHeader: Boolean = true,
    global: Boolean = false,
) {
    Restrict(
        title = R.string.policy_section_actions,
        switchLabel = R.string.policy_switch_actions,
        list = "actions",
        checked = draft.restrictActions,
        off = R.string.policy_actions_off,
        on = R.string.policy_actions_on,
        onCheckedChange = { onEdit(draft.copy(restrictActions = it)) },
        showHeader = showHeader,
        global = global,
        empty = draft.actions.isEmpty(),
    )
    if (!global && !draft.restrictActions) return
    if (!global && draft.actions.isEmpty()) Note(R.string.policy_actions_empty)
    for (action in PolicyAction.entries.filterNot { global && it == PolicyAction.Swap }) {
        val ticked = action in draft.actions
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .testTag(PolicyTags.action(action))
                    .then(
                        if (global) {
                            Modifier.clip(RoundedCornerShape(SeekerTheme.dimensions.dp12))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        } else {
                            Modifier
                        }
                    )
                    .toggleable(
                        value = ticked,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Checkbox,
                    ) { on ->
                        val actions = if (on) draft.actions + action else draft.actions - action
                        onEdit(draft.copy(actions = actions))
                    }
                    .padding(
                        horizontal =
                            if (global) SeekerTheme.dimensions.dp12
                            else SeekerTheme.dimensions.dp16,
                        vertical = SeekerTheme.dimensions.dp12,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ticked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                contentDescription = null,
                tint =
                    if (ticked) SeekerTheme.colors.primaryText
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.padding(start = SeekerTheme.dimensions.dp12)) {
                Text(actionText(action), style = MaterialTheme.typography.bodyMedium)
                if (global) {
                    Text(
                        stringResource(
                            if (ticked) R.string.policy_action_expected
                            else R.string.policy_action_not_expected
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Assets(
    draft: PolicyDraft,
    review: DraftReview,
    onEdit: (PolicyDraft) -> Unit,
    global: Boolean = false,
) {
    if (global) {
        GlobalSectionCard(
            list = "assets",
            title = R.string.policy_section_assets,
            icon = Icons.Outlined.Toll,
        ) {
            AssetsContent(draft, review, onEdit, global = true)
        }
        return
    }
    AssetsContent(draft, review, onEdit)
}

@Composable
private fun AssetsContent(
    draft: PolicyDraft,
    review: DraftReview,
    onEdit: (PolicyDraft) -> Unit,
    global: Boolean = false,
) {
    Restrict(
        title = R.string.policy_section_assets,
        switchLabel = R.string.policy_switch_assets,
        list = "assets",
        checked = draft.restrictAssets,
        off = R.string.policy_assets_off,
        on = R.string.policy_assets_on,
        onCheckedChange = { onEdit(draft.copy(restrictAssets = it)) },
        global = global,
        empty = draft.assets.isEmpty(),
    )
    when {
        draft.assets.isNotEmpty() -> Unit
        global -> Unit
        draft.restrictAssets -> Note(R.string.policy_assets_empty)
        else -> Note(R.string.policy_assets_none)
    }
    val problems = (review as? DraftReview.Problems)?.assets.orEmpty()
    var expandedAsset by rememberSaveable { mutableStateOf<Int?>(null) }
    draft.assets.forEachIndexed { index, asset ->
        Asset(
            index = index,
            asset = asset,
            problems = problems[index] ?: AssetProblems(),
            onChange = { onEdit(draft.copy(assets = draft.assets.replacing(index, it))) },
            onRemove = { onEdit(draft.copy(assets = draft.assets.removing(index))) },
            global = global,
            expanded = expandedAsset == index,
            onToggle = { expandedAsset = if (expandedAsset == index) null else index },
        )
    }
    if (global && draft.assets.isEmpty()) GlobalEmptyListRow("assets")
    if (draft.assets.any { it.mint != null }) Note(R.string.policy_token_units)
    var adding by rememberSaveable { mutableStateOf(false) }
    val addButton: @Composable (@Composable RowScope.() -> Unit) -> Unit = { content ->
        if (global) {
            TonalPolicyButton(
                onClick = { adding = true },
                modifier = Modifier.testTag(PolicyTags.ADD_ASSET),
                content = content,
            )
        } else {
            NeutralPolicyButton(
                onClick = { adding = true },
                modifier =
                    Modifier.padding(SeekerTheme.dimensions.dp16).testTag(PolicyTags.ADD_ASSET),
                content = content,
            )
        }
    }
    addButton {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.dimensions.dp20),
        )
        Text(
            stringResource(R.string.policy_add_asset),
            modifier = Modifier.padding(start = SeekerTheme.dimensions.dp8),
        )
    }
    if (adding) {
        AddAsset(
            listed = draft.assets,
            onAdd = {
                onEdit(draft.copy(assets = draft.assets + it))
                if (global) expandedAsset = draft.assets.size
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
    global: Boolean = false,
    expanded: Boolean = true,
    onToggle: () -> Unit = {},
) {
    val name = assetLabel(asset.asset)
    if (global) {
        val remove = stringResource(R.string.policy_asset_remove, name)
        SeekerCard(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            radius = SeekerTheme.dimensions.dp12,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                role = Role.Button,
                                onClick = onToggle,
                            )
                            .padding(SeekerTheme.dimensions.dp12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
                ) {
                    Icon(
                        Icons.Outlined.Toll,
                        contentDescription = null,
                        tint = SeekerTheme.colors.primaryText,
                        modifier = Modifier.size(SeekerTheme.dimensions.dp20),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (asset.mint == null) {
                                stringResource(
                                    R.string.policy_asset_row_sol,
                                    networkText(asset.network),
                                )
                            } else {
                                stringResource(
                                    R.string.policy_asset_row_token,
                                    networkText(asset.network),
                                )
                            },
                            modifier = Modifier.testTag(PolicyTags.asset(index)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            globalAssetLimits(asset),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (asset.mint != null) {
                            Text(
                                shortPolicyAddress(asset.mint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box(
                        modifier =
                            Modifier.size(SeekerTheme.dimensions.dp40)
                                .clip(RoundedCornerShape(SeekerTheme.dimensions.dp20))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    role = Role.Button,
                                    onClick = onRemove,
                                )
                                .testTag(PolicyTags.removeAsset(index))
                                .semantics { contentDescription = remove },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                    }
                }
                if (expanded) {
                    AssetFields(index, asset, problems, onChange)
                }
            }
        }
        return
    }
    SectionGap(Modifier.padding(vertical = SeekerTheme.dimensions.dp8))
    Text(
        name,
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                .testTag(PolicyTags.asset(index)),
    )
    AssetFields(index, asset, problems, onChange)
    NeutralPolicyButton(
        onClick = onRemove,
        modifier =
            Modifier.padding(horizontal = SeekerTheme.dimensions.dp8)
                .testTag(PolicyTags.removeAsset(index)),
    ) {
        Text(stringResource(R.string.policy_asset_remove, name))
    }
}

@Composable
private fun AssetFields(
    index: Int,
    asset: AssetDraft,
    problems: AssetProblems,
    onChange: (AssetDraft) -> Unit,
) {
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
}

@Composable
private fun globalAssetLimits(asset: AssetDraft): String {
    val perRequest =
        asset.perOperation
            .ifBlank { null }
            ?.let {
                stringResource(R.string.policy_asset_per_request_value, it)
            } ?: stringResource(R.string.policy_asset_no_per_request)
    val daily =
        asset.daily
            .ifBlank { null }
            ?.let {
                stringResource(R.string.policy_asset_daily_value, it)
            } ?: stringResource(R.string.policy_asset_no_daily)
    return stringResource(R.string.policy_asset_limits, perRequest, daily)
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
    SolidTextField(
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
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = SeekerTheme.dimensions.dp16,
                    vertical = SeekerTheme.dimensions.dp4,
                )
                .testTag(tag),
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
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceDim)
                .padding(top = SeekerTheme.dimensions.dp80),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SeekerCard(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                radius = SeekerTheme.dimensions.dp28,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(SeekerTheme.dimensions.dp20),
                    verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
                ) {
                    Text(
                        stringResource(R.string.policy_asset_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
                    ) {
                        Choice(R.string.policy_asset_sol, !token, PolicyTags.ASSET_SOL) {
                            token = false
                            problem = null
                        }
                        Choice(R.string.policy_asset_token, token, PolicyTags.ASSET_TOKEN) {
                            token = true
                            problem = null
                        }
                        if (token) {
                            SolidTextField(
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
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)
                        ) {
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
                            Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                    ) {
                        NeutralPolicyButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).testTag(PolicyTags.DIALOG_CANCEL),
                        ) {
                            Text(stringResource(R.string.policy_cancel))
                        }
                        PrimaryPolicyButton(
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
                            modifier = Modifier.weight(1f).testTag(PolicyTags.DIALOG_ADD),
                        ) {
                            Text(stringResource(R.string.policy_add))
                        }
                    }
                }
            }
        }
    }
}

/** The two connection-owned address editors represented by the typed navigation graph. */
enum class PolicyAddressKind {
    Recipient,
    Program,
}

/** Whether an asset editor was opened from the allowlist or the independent limits section. */
enum class PolicyAssetEditorKind {
    Allowlisted,
    SpendingLimit,
}

/**
 * Edits one connection asset in its own sheet destination.
 *
 * Changes stay local to this destination until Save. Closing therefore reveals the unchanged
 * connection-rules sheet underneath, while Save updates that sheet's existing ViewModel draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyAssetEditorScreen(
    state: PolicyUiState,
    asset: PolicyAsset?,
    kind: PolicyAssetEditorKind,
    onEdit: (PolicyEditorDraft) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = asset
    val draft = (state.draft as? PolicyEditorDraft.Connection)?.rules
    val stored = draft?.limits?.firstOrNull { it.asset == target }
    var token by rememberSaveable(target, kind) { mutableStateOf(target?.mint != null) }
    var mint by rememberSaveable(target, kind) { mutableStateOf(target?.mint.orEmpty()) }
    var network by
        rememberSaveable(target, kind) {
            mutableStateOf(target?.network ?: Network.NETWORK_MAINNET)
        }
    var overridePerOperation by
        rememberSaveable(target, stored, kind) {
            mutableStateOf(stored?.overridePerOperation ?: false)
        }
    var perOperation by
        rememberSaveable(target, stored, kind) {
            mutableStateOf(stored?.perOperation.orEmpty())
        }
    var daily by rememberSaveable(target, stored, kind) { mutableStateOf(stored?.daily.orEmpty()) }
    var identityProblem by remember { mutableStateOf<Int?>(null) }
    val editing = target != null
    val candidate = AssetDraft(network, mint.trim().takeIf { token }, perOperation, daily)
    val problems = problemsOf(candidate)
    val perOperationProblem = problems.perOperation.takeIf { overridePerOperation }
    val validThresholds =
        perOperationProblem == null &&
            problems.daily == null &&
            !(overridePerOperation && problems.dailyBelowPerOperation)

    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing) R.string.policy_asset_edit_title
                            else R.string.policy_asset_dialog_title
                        )
                    )
                },
                actions = {
                    PrimaryPolicyButton(
                        onClick = {
                            val current = draft ?: return@PrimaryPolicyButton
                            val asset = candidate.asset
                            identityProblem =
                                when {
                                    token && !isSolanaAddress(asset.mint.orEmpty()) ->
                                        R.string.policy_address_invalid
                                    (current.assets + current.limits.map { it.asset }).any {
                                        it != target && it == asset
                                    } -> R.string.policy_address_listed
                                    else -> null
                                }
                            if (identityProblem != null || !validThresholds) {
                                return@PrimaryPolicyButton
                            }
                            val allowed =
                                when {
                                    kind == PolicyAssetEditorKind.Allowlisted && target == null ->
                                        current.assets + asset
                                    kind == PolicyAssetEditorKind.Allowlisted &&
                                        target in current.assets ->
                                        current.assets.map { if (it == target) asset else it }
                                    else -> current.assets
                                }.distinct()
                            val nextLimit =
                                ConnectionAssetDraft(
                                    network = asset.network,
                                    mint = asset.mint,
                                    overridePerOperation = overridePerOperation,
                                    perOperation = if (overridePerOperation) perOperation else "",
                                    daily = daily,
                                )
                            val withoutEdited =
                                current.limits.filterNot { it.asset == target || it.asset == asset }
                            val limits =
                                if (nextLimit.configuresSomething) withoutEdited + nextLimit
                                else withoutEdited
                            onEdit(
                                PolicyEditorDraft.Connection(
                                    current.copy(assets = allowed, limits = limits)
                                )
                            )
                            onBack()
                        },
                        enabled = draft != null && validThresholds,
                        modifier = Modifier.testTag(PolicyTags.DIALOG_ADD),
                    ) {
                        Text(stringResource(R.string.policy_save))
                    }
                    CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
                },
                expandedHeight = SeekerTheme.dimensions.dp56,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
        ) {
            Text(
                stringResource(R.string.policy_connection_only),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Choice(R.string.policy_asset_sol, !token, PolicyTags.ASSET_SOL) {
                token = false
                identityProblem = null
            }
            Choice(R.string.policy_asset_token, token, PolicyTags.ASSET_TOKEN) {
                token = true
                identityProblem = null
            }
            if (token) {
                SolidTextField(
                    value = mint,
                    onValueChange = {
                        mint = it
                        identityProblem = null
                    },
                    label = { Text(stringResource(R.string.policy_mint_field)) },
                    singleLine = true,
                    isError = identityProblem != null,
                    supportingText = identityProblem?.let { { Text(stringResource(it)) } },
                    modifier = Modifier.fillMaxWidth().testTag(PolicyTags.MINT_FIELD),
                )
            } else if (identityProblem != null) {
                Text(stringResource(identityProblem!!), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.policy_network_field),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
                for (option in POLICY_NETWORKS) {
                    FilterChip(
                        selected = network == option,
                        onClick = {
                            network = option
                            identityProblem = null
                        },
                        label = { Text(networkText(option)) },
                        modifier = Modifier.testTag(PolicyTags.network(option)),
                    )
                }
            }
            SectionGap(Modifier.padding(vertical = SeekerTheme.dimensions.dp4))
            Text(
                stringResource(R.string.policy_connection_thresholds),
                style = MaterialTheme.typography.titleSmall,
            )
            Choice(
                R.string.policy_use_global_per_request,
                !overridePerOperation,
                PolicyTags.inheritPerOperation(candidate.asset),
            ) {
                overridePerOperation = false
            }
            Choice(
                R.string.policy_override_per_request,
                overridePerOperation,
                PolicyTags.overridePerOperation(candidate.asset),
            ) {
                overridePerOperation = true
            }
            if (overridePerOperation) {
                Amount(
                    tag = PolicyTags.connectionPerOperation(candidate.asset),
                    value = perOperation,
                    label =
                        if (candidate.mint == null) R.string.policy_per_operation_sol
                        else R.string.policy_per_operation_units,
                    asset = candidate,
                    problem = perOperationProblem,
                    onChange = { perOperation = it },
                )
                if (perOperation.isBlank()) Note(R.string.policy_local_per_request_none)
            }
            Amount(
                tag = PolicyTags.connectionDaily(candidate.asset),
                value = daily,
                label =
                    if (candidate.mint == null) R.string.policy_connection_daily_sol
                    else R.string.policy_connection_daily_units,
                asset = candidate,
                problem = problems.daily,
                onChange = { daily = it },
            )
            if (overridePerOperation && problems.dailyBelowPerOperation) {
                Note(R.string.policy_daily_below)
            }
            if (state.globalUnreadable == null) {
                val globalDaily = state.global?.limitsFor(candidate.asset)?.daily
                Text(
                    stringResource(
                        R.string.policy_global_daily_context,
                        globalDaily?.let { amountText(it, candidate) }
                            ?: stringResource(R.string.policy_effective_not_checked),
                        sourceText(
                            if (globalDaily == null) RuleSource.NotConfigured else RuleSource.Global
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(PolicyTags.globalDaily(candidate.asset)),
                )
            }
            Text(
                stringResource(R.string.policy_connection_thresholds_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Adds one recipient or program through its own sheet destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyAddressEditorScreen(
    kind: PolicyAddressKind,
    state: PolicyUiState,
    onEdit: (PolicyEditorDraft) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = (state.draft as? PolicyEditorDraft.Connection)?.rules
    val values =
        when (kind) {
            PolicyAddressKind.Recipient -> draft?.recipients.orEmpty()
            PolicyAddressKind.Program -> draft?.programs.orEmpty()
        }
    var typed by rememberSaveable(kind) { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    val title =
        if (kind == PolicyAddressKind.Recipient) R.string.policy_add_recipient
        else R.string.policy_add_program
    val field =
        if (kind == PolicyAddressKind.Recipient) R.string.policy_recipient_field
        else R.string.policy_program_field
    val note =
        if (kind == PolicyAddressKind.Recipient) R.string.policy_recipient_note
        else R.string.policy_program_note

    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                actions = {
                    PrimaryPolicyButton(
                        onClick = {
                            val current = draft ?: return@PrimaryPolicyButton
                            val value = typed.trim()
                            problem =
                                when {
                                    !isSolanaAddress(value) -> R.string.policy_address_invalid
                                    value in values -> R.string.policy_address_listed
                                    else -> null
                                }
                            if (problem != null) return@PrimaryPolicyButton
                            val next =
                                when (kind) {
                                    PolicyAddressKind.Recipient ->
                                        current.copy(recipients = current.recipients + value)
                                    PolicyAddressKind.Program ->
                                        current.copy(programs = current.programs + value)
                                }
                            onEdit(PolicyEditorDraft.Connection(next))
                            onBack()
                        },
                        enabled = draft != null,
                        modifier = Modifier.testTag(PolicyTags.add(kind.listTag)),
                    ) {
                        Text(stringResource(R.string.policy_add))
                    }
                    CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
                },
                expandedHeight = SeekerTheme.dimensions.dp56,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
        ) {
            Text(
                stringResource(R.string.policy_connection_only),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SolidTextField(
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
                modifier = Modifier.fillMaxWidth().testTag(PolicyTags.entryField(kind.listTag)),
            )
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val PolicyAddressKind.listTag: String
    get() = if (this == PolicyAddressKind.Recipient) RECIPIENTS else PROGRAMS

@Composable
private fun Choice(@StringRes label: Int, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .testTag(tag)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                .padding(vertical = SeekerTheme.dimensions.dp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint =
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(label),
            modifier = Modifier.padding(start = SeekerTheme.dimensions.dp16),
        )
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
    showHeader: Boolean = true,
    global: Boolean = false,
    onAdd: (() -> Unit)? = null,
) {
    if (global) {
        GlobalSectionCard(
            list = list,
            title = title,
            icon =
                if (list == RECIPIENTS) Icons.Outlined.AccountBalanceWallet
                else Icons.Outlined.Code,
        ) {
            AddressesContent(
                list,
                title,
                switchLabel,
                off,
                on,
                empty,
                field,
                note,
                restricted,
                values,
                onRestrict,
                onChange,
                showHeader = false,
                global = true,
                onAdd = null,
            )
        }
        return
    }
    AddressesContent(
        list,
        title,
        switchLabel,
        off,
        on,
        empty,
        field,
        note,
        restricted,
        values,
        onRestrict,
        onChange,
        showHeader,
        onAdd = onAdd,
    )
}

@Composable
private fun AddressesContent(
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
    showHeader: Boolean = true,
    global: Boolean = false,
    onAdd: (() -> Unit)? = null,
) {
    Restrict(
        title,
        switchLabel,
        list,
        restricted,
        off,
        on,
        onRestrict,
        showHeader,
        global = global,
        empty = values.isEmpty(),
    )
    if (!global && !restricted) return
    if (!global) Note(note)
    if (!global && values.isEmpty()) Note(empty)
    for (value in values) {
        val remove = stringResource(R.string.policy_remove_entry, value)
        if (global) {
            GlobalAddressRow(
                list = list,
                value = value,
                removeDescription = remove,
                onRemove = { onChange(values - value) },
            )
        } else {
            ListItem(
                // The whole address, wrapped rather than cut short: half an address read out of a
                // list is worse than none, because it looks like the one the owner meant.
                headlineContent = { Text(value) },
                trailingContent = {
                    NeutralPolicyButton(
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
                colors = seekerListItemColors(),
            )
        }
    }
    if (global && values.isEmpty()) GlobalEmptyListRow(list)
    if (!global && onAdd != null) {
        NeutralPolicyButton(
            onClick = onAdd,
            modifier = Modifier.padding(SeekerTheme.dimensions.dp16).testTag(PolicyTags.add(list)),
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(SeekerTheme.dimensions.dp20),
            )
            Text(
                stringResource(
                    if (list == RECIPIENTS) R.string.policy_add_recipient
                    else R.string.policy_add_program
                ),
                modifier = Modifier.padding(start = SeekerTheme.dimensions.dp8),
            )
        }
        return
    }
    var typed by rememberSaveable(list) { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    SolidTextField(
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
                .then(
                    if (global) Modifier
                    else Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                )
                .testTag(PolicyTags.entryField(list)),
    )
    val addAddress = {
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
    }
    val addContent: @Composable RowScope.() -> Unit = {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(SeekerTheme.dimensions.dp20),
        )
        Text(
            stringResource(
                if (!global) R.string.policy_add
                else if (list == RECIPIENTS) R.string.policy_add_recipient
                else R.string.policy_add_program
            ),
            modifier = Modifier.padding(start = SeekerTheme.dimensions.dp8),
        )
    }
    if (global) {
        TonalPolicyButton(
            onClick = addAddress,
            modifier = Modifier.testTag(PolicyTags.add(list)),
            content = addContent,
        )
    } else {
        NeutralPolicyButton(
            onClick = addAddress,
            modifier = Modifier.padding(SeekerTheme.dimensions.dp16).testTag(PolicyTags.add(list)),
            content = addContent,
        )
    }
}

@Composable
private fun GlobalEmptyListRow(list: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(PolicyTags.empty(list)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
    ) {
        Icon(
            Icons.Outlined.DoNotDisturbOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SeekerTheme.dimensions.dp20),
        )
        Text(
            stringResource(R.string.policy_nothing_listed),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun GlobalAddressRow(
    list: String,
    value: String,
    removeDescription: String,
    onRemove: () -> Unit,
) {
    val recipient = list == RECIPIENTS
    SeekerCard(
        modifier = Modifier.fillMaxWidth().testTag(PolicyTags.entry(list, value)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        radius = SeekerTheme.dimensions.dp12,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
        ) {
            Icon(
                if (recipient) Icons.Outlined.AccountBalanceWallet else Icons.Outlined.Code,
                contentDescription = null,
                tint = SeekerTheme.colors.primaryText,
                modifier = Modifier.size(SeekerTheme.dimensions.dp20),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (recipient) shortPolicyAddress(value) else policyProgramName(value),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (recipient) stringResource(R.string.policy_recipient_owner)
                    else shortPolicyAddress(value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier =
                    Modifier.size(SeekerTheme.dimensions.dp40)
                        .clip(RoundedCornerShape(SeekerTheme.dimensions.dp20))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            role = Role.Button,
                            onClick = onRemove,
                        )
                        .testTag(PolicyTags.removeEntry(list, value))
                        .semantics { contentDescription = removeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
            }
        }
    }
}

private fun shortPolicyAddress(value: String): String =
    if (value.length <= 14) value else "${value.take(6)}…${value.takeLast(5)}"

@Composable
private fun policyProgramName(value: String): String =
    stringResource(
        when (value) {
            SYSTEM_PROGRAM_ID -> R.string.policy_program_system
            COMPUTE_BUDGET_PROGRAM_ID -> R.string.policy_program_compute_budget
            TOKEN_PROGRAM_ID -> R.string.policy_program_token
            TOKEN_2022_PROGRAM_ID -> R.string.policy_program_token_2022
            else -> R.string.policy_program_unknown
        }
    )

private const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"
private const val COMPUTE_BUDGET_PROGRAM_ID = "ComputeBudget111111111111111111111111111111"
private const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
private const val TOKEN_2022_PROGRAM_ID = "TokenzQdYhJTJRPzxtMHvJkKYFjRkMNs9qYkQRiLT9"

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
    showHeader: Boolean = true,
    global: Boolean = false,
    empty: Boolean = false,
) {
    if (showHeader) {
        SectionGap(Modifier.padding(vertical = SeekerTheme.dimensions.dp8))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
        )
    }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .testTag(PolicyTags.restrict(list))
                .toggleable(
                    value = checked,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(
                    vertical =
                        if (global) SeekerTheme.dimensions.dp0 else SeekerTheme.dimensions.dp16
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(switchLabel), modifier = Modifier.weight(1f))
        SolidSwitch(checked)
    }
    if (global) {
        Text(
            stringResource(
                when {
                    !checked -> R.string.policy_section_off
                    empty -> R.string.policy_section_empty
                    else -> R.string.policy_section_on
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Note(if (checked) on else off)
    }
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(
                horizontal = SeekerTheme.dimensions.dp16,
                vertical = SeekerTheme.dimensions.dp4,
            ),
    )
}

private fun List<AssetDraft>.replacing(index: Int, asset: AssetDraft): List<AssetDraft> =
    mapIndexed { at, current ->
        if (at == index) asset else current
    }

private fun List<AssetDraft>.removing(index: Int): List<AssetDraft> = filterIndexed { at, _ ->
    at != index
}

@Composable
private fun PrimaryPolicyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SolidPolicyButton(onClick, modifier, enabled, PolicyButtonTone.Primary, content)

@Composable
private fun NeutralPolicyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SolidPolicyButton(onClick, modifier, enabled, PolicyButtonTone.Neutral, content)

@Composable
private fun TonalPolicyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SolidPolicyButton(onClick, modifier, enabled, PolicyButtonTone.Tonal, content)

@Composable
private fun ErrorPolicyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = SolidPolicyButton(onClick, modifier, enabled, PolicyButtonTone.Error, content)

private enum class PolicyButtonTone {
    Primary,
    Tonal,
    Neutral,
    Error,
}

@Composable
private fun SolidPolicyButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    tone: PolicyButtonTone,
    content: @Composable RowScope.() -> Unit,
) {
    val container =
        when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
            tone == PolicyButtonTone.Primary -> MaterialTheme.colorScheme.primary
            tone == PolicyButtonTone.Error -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val foreground =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            tone == PolicyButtonTone.Primary -> MaterialTheme.colorScheme.onPrimary
            tone == PolicyButtonTone.Tonal -> SeekerTheme.colors.primaryText
            tone == PolicyButtonTone.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        }
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Row(
            modifier =
                modifier
                    .heightIn(min = SeekerTheme.dimensions.dp40)
                    .clip(RoundedCornerShape(SeekerTheme.dimensions.dp20))
                    .background(container)
                    .clickable(
                        enabled = enabled,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .padding(horizontal = SeekerTheme.dimensions.dp18),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun SectionGap(modifier: Modifier = Modifier) {
    Spacer(modifier.height(SeekerTheme.dimensions.dp12))
}

@Composable
private fun SolidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    supportingText: @Composable (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        singleLine = singleLine,
        isError = isError,
        keyboardOptions = keyboardOptions,
        supportingText = supportingText,
        colors = seekerTextFieldColors(),
    )
}

@Composable
private fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalContentColor provides
            if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier =
                modifier
                    .heightIn(min = SeekerTheme.dimensions.dp40)
                    .clip(RoundedCornerShape(SeekerTheme.dimensions.dp20))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    .selectable(
                        selected = selected,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                    .padding(horizontal = SeekerTheme.dimensions.dp14),
            verticalAlignment = Alignment.CenterVertically,
            content = { label() },
        )
    }
}

@Composable
private fun SolidSwitch(checked: Boolean) {
    val shape = RoundedCornerShape(SeekerTheme.dimensions.dp16)
    val knobSize by
        animateDpAsState(
            targetValue = if (checked) SeekerTheme.dimensions.dp24 else SeekerTheme.dimensions.dp16,
            animationSpec = tween(220),
            label = "policySwitchKnobSize",
        )
    val knobOffset by
        animateDpAsState(
            targetValue = if (checked) SeekerTheme.dimensions.dp24 else SeekerTheme.dimensions.dp8,
            animationSpec = tween(220),
            label = "policySwitchKnobOffset",
        )
    Box(
        Modifier.size(width = SeekerTheme.dimensions.dp52, height = SeekerTheme.dimensions.dp32)
            .clip(shape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .then(
                if (checked) Modifier
                else
                    Modifier.border(
                        SeekerTheme.dimensions.dp2,
                        MaterialTheme.colorScheme.outline,
                        shape,
                    )
            )
    ) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = knobOffset)
                .size(knobSize)
                .background(
                    if (checked) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(knobSize / 2),
                )
        )
    }
}
