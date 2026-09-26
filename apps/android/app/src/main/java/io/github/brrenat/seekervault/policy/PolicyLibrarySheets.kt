package io.github.brrenat.seekervault.policy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.AddAddressSheet
import io.github.brrenat.seekervault.designsystem.AddAddressSheetCallbacks
import io.github.brrenat.seekervault.designsystem.AddAddressSheetState
import io.github.brrenat.seekervault.designsystem.AssetEditorKind
import io.github.brrenat.seekervault.designsystem.AssetEditorNetwork
import io.github.brrenat.seekervault.designsystem.AssetEditorSheet
import io.github.brrenat.seekervault.designsystem.AssetEditorSheetCallbacks
import io.github.brrenat.seekervault.designsystem.AssetEditorSheetState
import io.github.brrenat.seekervault.designsystem.ConnectionRulesSheet
import io.github.brrenat.seekervault.designsystem.ConnectionRulesSheetState
import io.github.brrenat.seekervault.designsystem.GlobalRulesSheet
import io.github.brrenat.seekervault.designsystem.GlobalRulesSheetState
import io.github.brrenat.seekervault.designsystem.RuleRowKind
import io.github.brrenat.seekervault.designsystem.RuleRowModel
import io.github.brrenat.seekervault.designsystem.RulesSectionKind
import io.github.brrenat.seekervault.designsystem.RulesSheetCallbacks
import io.github.brrenat.seekervault.designsystem.RulesSheetItem
import io.github.brrenat.seekervault.designsystem.RulesSheetSection
import io.github.brrenat.seekervault.designsystem.ScopeChipSource
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.notifications.LocalInAppNotices
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SolidDialog
import io.github.brrenat.seekervault.wallet.isSolanaAddress

/** App-to-library adapter for both SEE-122 rules sheets. */
@Composable
fun PolicyLibrarySheetScreen(
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
    if (!state.loaded || state.unreadable != null || state.draft == null) {
        PolicyEditorScreen(
            label = label,
            state = state,
            onEdit = onEdit,
            onStartOver = onStartOver,
            onResetConnection = onResetConnection,
            onOpenGlobal = onOpenGlobal,
            onSave = onSave,
            onMessageShown = onMessageShown,
            onClose = onClose,
            closeRequest = closeRequest,
            onCloseRequestCancelled = onCloseRequestCancelled,
            onOpenAsset = onOpenAsset,
            onOpenAddress = onOpenAddress,
            modifier = modifier,
        )
        return
    }

    var introExpanded by rememberSaveable(state.scope, state.connectionId) { mutableStateOf(false) }
    var confirmDiscard by
        rememberSaveable(state.scope, state.connectionId) {
            mutableStateOf(false)
        }
    var confirmGlobalSave by
        rememberSaveable(state.scope, state.connectionId) {
            mutableStateOf(false)
        }
    val notices = LocalInAppNotices.current
    val message = state.message?.let { messageText(it, state.scope) }
    LaunchedEffect(state.message) {
        if (message != null) {
            notices.show(message)
            onMessageShown()
        }
    }
    val leave = { if (state.changed) confirmDiscard = true else onClose() }
    BackHandler(onBack = leave)
    androidx.compose.runtime.LaunchedEffect(closeRequest) { if (closeRequest > 0) leave() }

    val editDraft: (PolicyEditorDraft) -> Unit = onEdit

    val callbacks =
        RulesSheetCallbacks(
            onClose = leave,
            onIntroToggle = { introExpanded = !introExpanded },
            onOpenGlobal = onOpenGlobal,
            onOverrideChange = { kind, override ->
                val current =
                    (state.draft as? PolicyEditorDraft.Connection)?.rules
                        ?: return@RulesSheetCallbacks
                val next =
                    when (kind) {
                        RulesSectionKind.Actions ->
                            if (override) current.copy(overrideActions = true)
                            else
                                current.copy(
                                    overrideActions = false,
                                    restrictActions = false,
                                    actions = emptySet(),
                                )
                        RulesSectionKind.Assets ->
                            if (override) current.copy(overrideAssets = true)
                            else
                                current.copy(
                                    overrideAssets = false,
                                    restrictAssets = false,
                                    assets = emptyList(),
                                )
                        RulesSectionKind.Recipients ->
                            if (override) current.copy(overrideRecipients = true)
                            else
                                current.copy(
                                    overrideRecipients = false,
                                    restrictRecipients = false,
                                    recipients = emptyList(),
                                )
                        RulesSectionKind.Programs ->
                            if (override) current.copy(overridePrograms = true)
                            else
                                current.copy(
                                    overridePrograms = false,
                                    restrictPrograms = false,
                                    programs = emptyList(),
                                )
                    }
                editDraft(PolicyEditorDraft.Connection(next))
            },
            onEnabledChange = { kind, enabled ->
                when (val current = state.draft) {
                    is PolicyEditorDraft.Global -> {
                        val next =
                            when (kind) {
                                RulesSectionKind.Actions ->
                                    current.rules.copy(restrictActions = enabled)
                                RulesSectionKind.Assets ->
                                    current.rules.copy(restrictAssets = enabled)
                                RulesSectionKind.Recipients ->
                                    current.rules.copy(restrictRecipients = enabled)
                                RulesSectionKind.Programs ->
                                    current.rules.copy(restrictPrograms = enabled)
                            }
                        editDraft(PolicyEditorDraft.Global(next))
                    }
                    is PolicyEditorDraft.Connection -> {
                        val next =
                            when (kind) {
                                RulesSectionKind.Actions ->
                                    current.rules.copy(restrictActions = enabled)
                                RulesSectionKind.Assets ->
                                    current.rules.copy(restrictAssets = enabled)
                                RulesSectionKind.Recipients ->
                                    current.rules.copy(restrictRecipients = enabled)
                                RulesSectionKind.Programs ->
                                    current.rules.copy(restrictPrograms = enabled)
                            }
                        editDraft(PolicyEditorDraft.Connection(next))
                    }
                }
            },
            onItemClick = { kind, id ->
                when (kind) {
                    RulesSectionKind.Actions -> {
                        val action = PolicyAction.byCode(id) ?: return@RulesSheetCallbacks
                        when (val current = state.draft) {
                            is PolicyEditorDraft.Global -> {
                                val actions =
                                    if (action in current.rules.actions) {
                                        current.rules.actions - action
                                    } else {
                                        current.rules.actions + action
                                    }
                                editDraft(
                                    PolicyEditorDraft.Global(current.rules.copy(actions = actions))
                                )
                            }
                            is PolicyEditorDraft.Connection -> {
                                val actions =
                                    if (action in current.rules.actions) {
                                        current.rules.actions - action
                                    } else {
                                        current.rules.actions + action
                                    }
                                editDraft(
                                    PolicyEditorDraft.Connection(
                                        current.rules.copy(actions = actions)
                                    )
                                )
                            }
                        }
                    }
                    RulesSectionKind.Assets -> {
                        val current =
                            (state.draft as? PolicyEditorDraft.Connection)?.rules
                                ?: return@RulesSheetCallbacks
                        val asset =
                            (current.assets + current.limits.map { it.asset }).firstOrNull {
                                it.id() == id
                            } ?: return@RulesSheetCallbacks
                        val editorKind =
                            if (asset in current.assets) PolicyAssetEditorKind.Allowlisted
                            else PolicyAssetEditorKind.SpendingLimit
                        onOpenAsset(asset, editorKind)
                    }
                    RulesSectionKind.Recipients,
                    RulesSectionKind.Programs -> Unit
                }
            },
            onDelete = { kind, id ->
                when (val current = state.draft) {
                    is PolicyEditorDraft.Global -> {
                        val next = current.rules.without(kind, id)
                        editDraft(PolicyEditorDraft.Global(next))
                    }
                    is PolicyEditorDraft.Connection -> {
                        val next = current.rules.without(kind, id)
                        editDraft(PolicyEditorDraft.Connection(next))
                    }
                }
            },
            onAdd = { kind ->
                when (kind) {
                    RulesSectionKind.Assets -> {
                        val current = (state.draft as? PolicyEditorDraft.Connection)?.rules
                        onOpenAsset(
                            null,
                            if (current?.overrideAssets == true) {
                                PolicyAssetEditorKind.Allowlisted
                            } else {
                                PolicyAssetEditorKind.SpendingLimit
                            },
                        )
                    }
                    RulesSectionKind.Recipients -> onOpenAddress(PolicyAddressKind.Recipient)
                    RulesSectionKind.Programs -> onOpenAddress(PolicyAddressKind.Program)
                    RulesSectionKind.Actions -> Unit
                }
            },
            onClear = {
                val current =
                    (state.draft as? PolicyEditorDraft.Global)?.rules ?: return@RulesSheetCallbacks
                editDraft(PolicyEditorDraft.Global(PolicyDraft(current.connectionId)))
            },
            onSave = {
                if (state.scope == PolicyEditorScope.Global) confirmGlobalSave = true else onSave()
            },
            onCancel = leave,
        )

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        when (val draft = state.draft) {
            is PolicyEditorDraft.Global ->
                GlobalRulesSheet(
                    state =
                        GlobalRulesSheetState(
                            title = "Global rules",
                            intro =
                                "Rules highlight requests that need attention. You still approve " +
                                    "every request.",
                            introExpanded = introExpanded,
                            introDetails = rulesIntroDetails,
                            defaultsCaption =
                                "These are the defaults every connection starts from.",
                            sections = globalSections(draft.rules),
                            clearLabel = "Clear all global rules",
                            footerCaption =
                                "Daily limits set here count spending across every connection. A " +
                                    "connection can add a tighter limit of its own, but cannot " +
                                    "raise or remove this one.",
                            saveLabel = "Save".takeIf { state.changed },
                            cancelLabel = "Cancel".takeIf { state.changed },
                            actionsEnabled = !state.saving,
                            saveTag = PolicyTags.SAVE,
                            cancelTag = PolicyTags.CANCEL,
                            introTag = PolicyTags.HELP,
                            introContentTag = PolicyTags.HELP_CONTENT,
                        ),
                    callbacks = callbacks,
                    modifier = Modifier.fillMaxWidth(),
                )
            is PolicyEditorDraft.Connection ->
                ConnectionRulesSheet(
                    state =
                        ConnectionRulesSheetState(
                            title = "Rules for $label",
                            intro =
                                "Rules highlight requests that need attention. You still approve " +
                                    "every request.",
                            introExpanded = introExpanded,
                            introDetails = rulesIntroDetails,
                            replacementSummary =
                                "${draft.rules.replacementCount()} sections replace the global " +
                                    "rules. The rest follow them.",
                            replacementCaption = "Global",
                            sections = connectionSections(draft.rules, state.global),
                            footerCaption = "Swap is out of scope in this build.",
                            saveLabel = "Save".takeIf { state.changed },
                            cancelLabel = "Cancel".takeIf { state.changed },
                            actionsEnabled = !state.saving,
                            saveTag = PolicyTags.SAVE,
                            cancelTag = PolicyTags.CANCEL,
                            globalTag = PolicyTags.OPEN_GLOBAL,
                            introTag = PolicyTags.HELP,
                            introContentTag = PolicyTags.HELP_CONTENT,
                        ),
                    callbacks = callbacks,
                    modifier = Modifier.fillMaxWidth(),
                )
        }
        if (confirmDiscard) {
            SolidDialog(
                title = "Discard changes?",
                body = { Text("Your unsaved rule changes will be lost.") },
                actions = {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                    ) {
                        SeekerButton(
                            text = "Keep editing",
                            onClick = {
                                confirmDiscard = false
                                onCloseRequestCancelled()
                            },
                            role = SeekerButtonRole.Neutral,
                            modifier = Modifier.weight(1f).testTag(PolicyTags.KEEP_EDITING),
                        )
                        SeekerButton(
                            text = "Discard",
                            onClick = {
                                confirmDiscard = false
                                onClose()
                            },
                            role = SeekerButtonRole.Error,
                            modifier = Modifier.weight(1f).testTag(PolicyTags.DISCARD),
                        )
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
                        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
                    ) {
                        SeekerButton(
                            text = stringResource(R.string.policy_cancel),
                            onClick = { confirmGlobalSave = false },
                            role = SeekerButtonRole.Neutral,
                            modifier = Modifier.weight(1f).testTag(PolicyTags.DIALOG_CANCEL),
                        )
                        SeekerButton(
                            text = stringResource(R.string.policy_global_confirm),
                            onClick = {
                                confirmGlobalSave = false
                                onSave()
                            },
                            modifier = Modifier.weight(1f).testTag(PolicyTags.CONFIRM_GLOBAL_SAVE),
                        )
                    }
                },
            )
        }
    }
}

/** App-to-library adapter for the connection-scoped asset sheet. */
@Composable
fun PolicyAssetLibraryScreen(
    state: PolicyUiState,
    asset: PolicyAsset?,
    kind: PolicyAssetEditorKind,
    onEdit: (PolicyEditorDraft) -> Unit,
    onBack: () -> Unit,
    onEditGlobal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = (state.draft as? PolicyEditorDraft.Connection)?.rules
    val stored = draft?.limits?.firstOrNull { it.asset == asset }
    var editorKind by
        rememberSaveable(asset, kind) {
            mutableStateOf(
                if (asset?.mint == null) AssetEditorKind.NativeSol else AssetEditorKind.Token
            )
        }
    var mint by rememberSaveable(asset, kind) { mutableStateOf(asset?.mint.orEmpty()) }
    var network by
        rememberSaveable(asset, kind) { mutableStateOf(asset?.network ?: POLICY_NETWORKS.first()) }
    var perRequest by
        rememberSaveable(asset, stored, kind) { mutableStateOf(stored?.perOperation.orEmpty()) }
    var daily by rememberSaveable(asset, stored, kind) { mutableStateOf(stored?.daily.orEmpty()) }
    var identityError by remember { mutableStateOf<String?>(null) }
    val candidate =
        AssetDraft(
            network = network,
            mint = mint.trim().takeIf { editorKind == AssetEditorKind.Token },
            perOperation = perRequest,
            daily = daily,
        )
    val problems = problemsOf(candidate)
    val valid = problems.none && identityError == null && draft != null
    val globalDaily = state.global?.limitsFor(candidate.asset)?.daily
    BackHandler(onBack = onBack)

    fun save() {
        val current = draft ?: return
        val nextAsset = candidate.asset
        identityError =
            when {
                editorKind == AssetEditorKind.Token && !isSolanaAddress(nextAsset.mint.orEmpty()) ->
                    "Not a Solana address. That is base58 for 32 bytes."
                (current.assets + current.limits.map { it.asset }).any {
                    it != asset && it == nextAsset
                } -> "That asset is already listed."
                else -> null
            }
        if (identityError != null || !problems.none) return
        val allowed =
            when {
                kind == PolicyAssetEditorKind.Allowlisted && asset == null ->
                    current.assets + nextAsset
                kind == PolicyAssetEditorKind.Allowlisted && asset in current.assets ->
                    current.assets.map { if (it == asset) nextAsset else it }
                else -> current.assets
            }.distinct()
        val nextLimit =
            ConnectionAssetDraft(
                network = nextAsset.network,
                mint = nextAsset.mint,
                overridePerOperation = perRequest.isNotBlank(),
                perOperation = perRequest,
                daily = daily,
            )
        val without = current.limits.filterNot { it.asset == asset || it.asset == nextAsset }
        val limits = if (nextLimit.configuresSomething) without + nextLimit else without
        onEdit(PolicyEditorDraft.Connection(current.copy(assets = allowed, limits = limits)))
        onBack()
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AssetEditorSheet(
            state =
                AssetEditorSheetState(
                    title = if (asset == null) "Add asset" else "Edit asset",
                    saveLabel = "Save",
                    scopeLabel = "This connection only",
                    kind = editorKind,
                    nativeLabel = "Native SOL",
                    tokenLabel = "A token",
                    mintLabel = "Token mint address",
                    mint = mint,
                    mintError = identityError,
                    networkLabel = "Network",
                    networks =
                        POLICY_NETWORKS.map { AssetEditorNetwork(it.name, networkLabel(it)) },
                    selectedNetwork = POLICY_NETWORKS.indexOf(network).coerceAtLeast(0),
                    thresholdsLabel = "Thresholds",
                    perRequestLabel = "Most per request",
                    perRequest = perRequest,
                    perRequestError = problems.perOperation?.display(candidate),
                    dailyLabel = "Most a day through this connection",
                    daily = daily,
                    dailyError =
                        problems.daily?.display(candidate)
                            ?: if (problems.dailyBelowPerOperation) {
                                "The daily limit must not be below the per-request limit."
                            } else null,
                    globalDailyTitle =
                        "Global daily limit " +
                            (globalDaily?.let { amountText(it, candidate) } ?: "not set"),
                    globalDailySupportingText = "Read-only here · applies across all connections",
                    editGlobalLabel = "Edit",
                    footerCaption =
                        "A limit here counts only what moves through this connection. The global " +
                            "daily limit still applies on top.",
                    canSave = valid,
                    saveTag = PolicyTags.DIALOG_ADD,
                    nativeTag = PolicyTags.ASSET_SOL,
                    tokenTag = PolicyTags.ASSET_TOKEN,
                    mintTag = PolicyTags.MINT_FIELD,
                    networkTags = POLICY_NETWORKS.map(PolicyTags::network),
                    perRequestTag = PolicyTags.connectionPerOperation(candidate.asset),
                    dailyTag = PolicyTags.connectionDaily(candidate.asset),
                    globalDailyTag = PolicyTags.globalDaily(candidate.asset),
                ),
            callbacks =
                AssetEditorSheetCallbacks(
                    onClose = onBack,
                    onSave = ::save,
                    onKindChange = {
                        editorKind = it
                        identityError = null
                    },
                    onMintChange = {
                        mint = it
                        identityError = null
                    },
                    onNetworkChange = {
                        network = POLICY_NETWORKS[it]
                        identityError = null
                    },
                    onPerRequestChange = { perRequest = it },
                    onDailyChange = { daily = it },
                    onEditGlobal = onEditGlobal,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** App-to-library adapter for recipient and program addresses. */
@Composable
fun PolicyAddressLibraryScreen(
    kind: PolicyAddressKind,
    state: PolicyUiState,
    onEdit: (PolicyEditorDraft) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = (state.draft as? PolicyEditorDraft.Connection)?.rules
    val values =
        if (kind == PolicyAddressKind.Recipient) draft?.recipients.orEmpty()
        else draft?.programs.orEmpty()
    var value by rememberSaveable(kind) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)

    fun add() {
        val current = draft ?: return
        val address = value.trim()
        error =
            when {
                !isSolanaAddress(address) -> "Not a Solana address. That is base58 for 32 bytes."
                address in values -> "That address is already listed."
                else -> null
            }
        if (error != null) return
        val next =
            if (kind == PolicyAddressKind.Recipient) {
                current.copy(recipients = current.recipients + address)
            } else {
                current.copy(programs = current.programs + address)
            }
        onEdit(PolicyEditorDraft.Connection(next))
        onBack()
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AddAddressSheet(
            state =
                AddAddressSheetState(
                    title =
                        if (kind == PolicyAddressKind.Recipient) "Add a wallet"
                        else "Add a program",
                    addLabel = "Add",
                    scopeLabel = "This connection only",
                    fieldLabel =
                        if (kind == PolicyAddressKind.Recipient) "Wallet address"
                        else "Program address",
                    value = value,
                    placeholder = "Base58 address",
                    error = error,
                    caption =
                        if (kind == PolicyAddressKind.Recipient) {
                            "Write the wallet that owns the funds, never a token account. The " +
                                "phone works the owner out of the transaction itself."
                        } else {
                            "Write the program address exactly as it appears on Solana."
                        },
                    canAdd = draft != null && value.isNotBlank(),
                    addTag = PolicyTags.add(kind.listTag),
                    fieldTag = PolicyTags.entryField(kind.listTag),
                ),
            callbacks =
                AddAddressSheetCallbacks(
                    onClose = onBack,
                    onAdd = ::add,
                    onValueChange = {
                        value = it
                        error = null
                    },
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val rulesIntroDetails =
    listOf(
        "A rule never approves, stops or filters a request. It only highlights it for review.",
        "Global rules are defaults. A connection can follow or replace each section.",
        "Global daily limits count every connection and cannot be raised locally.",
        "Every request still needs your approval, and the wallet asks again before signing.",
    )

@Composable
private fun globalSections(draft: PolicyDraft): List<RulesSheetSection> =
    listOf(
        actionSection(
            source = ScopeChipSource.Global,
            override = null,
            enabled = draft.restrictActions,
            actions = draft.actions,
        ),
        assetSection(
            source = ScopeChipSource.Global,
            override = null,
            enabled = draft.restrictAssets,
            assets = draft.assets,
            global = true,
            editableIds = emptySet(),
        ),
        addressSection(
            kind = RulesSectionKind.Recipients,
            source = ScopeChipSource.Global,
            override = null,
            enabled = draft.restrictRecipients,
            values = draft.recipients,
            global = true,
        ),
        addressSection(
            kind = RulesSectionKind.Programs,
            source = ScopeChipSource.Global,
            override = null,
            enabled = draft.restrictPrograms,
            values = draft.programs,
            global = true,
        ),
    )

@Composable
private fun connectionSections(
    draft: ConnectionPolicyDraft,
    global: GlobalPolicy?,
): List<RulesSheetSection> {
    val localAssets =
        (draft.assets + draft.limits.map { it.asset }).distinct().map { asset ->
            val limits = draft.limits.firstOrNull { it.asset == asset }
            AssetDraft(
                asset.network,
                asset.mint,
                limits?.perOperation.orEmpty(),
                limits?.daily.orEmpty(),
            )
        }
    val inheritedAssets =
        global?.assets?.values.orEmpty().map { asset ->
            val limits = global?.limitsFor(asset)
            AssetDraft(
                asset.network,
                asset.mint,
                limits
                    ?.perOperation
                    ?.let { amountText(it, AssetDraft(asset.network, asset.mint)) }
                    .orEmpty(),
                limits
                    ?.daily
                    ?.let { amountText(it, AssetDraft(asset.network, asset.mint)) }
                    .orEmpty(),
            )
        }
    return listOf(
        actionSection(
            source =
                if (draft.overrideActions) ScopeChipSource.Connection else ScopeChipSource.Global,
            override = draft.overrideActions,
            enabled = if (draft.overrideActions) draft.restrictActions else global?.actions != null,
            actions =
                if (draft.overrideActions) draft.actions else global?.actions?.values.orEmpty(),
        ),
        assetSection(
            source =
                if (draft.overrideAssets || draft.limits.any { it.configuresSomething }) {
                    ScopeChipSource.Connection
                } else {
                    ScopeChipSource.Global
                },
            override = draft.overrideAssets,
            enabled = if (draft.overrideAssets) draft.restrictAssets else global?.assets != null,
            assets =
                if (draft.overrideAssets) {
                    localAssets
                } else {
                    (localAssets + inheritedAssets).distinctBy { it.asset }
                },
            global = false,
            editableIds = localAssets.mapTo(mutableSetOf()) { it.asset.id() },
        ),
        addressSection(
            kind = RulesSectionKind.Recipients,
            source =
                if (draft.overrideRecipients) ScopeChipSource.Connection
                else ScopeChipSource.Global,
            override = draft.overrideRecipients,
            enabled =
                if (draft.overrideRecipients) draft.restrictRecipients
                else global?.recipients != null,
            values =
                if (draft.overrideRecipients) draft.recipients
                else global?.recipients?.values.orEmpty().toList(),
            global = false,
        ),
        addressSection(
            kind = RulesSectionKind.Programs,
            source =
                if (draft.overridePrograms) ScopeChipSource.Connection else ScopeChipSource.Global,
            override = draft.overridePrograms,
            enabled =
                if (draft.overridePrograms) draft.restrictPrograms else global?.programs != null,
            values =
                if (draft.overridePrograms) draft.programs
                else global?.programs?.values.orEmpty().toList(),
            global = false,
        ),
    )
}

@Composable
private fun actionSection(
    source: ScopeChipSource,
    override: Boolean?,
    enabled: Boolean,
    actions: Set<PolicyAction>,
) =
    RulesSheetSection(
        kind = RulesSectionKind.Actions,
        title = "Actions",
        source = source,
        override = override,
        enabled = enabled,
        switchLabel = "Only these actions may be asked for",
        statusText = if (enabled) "On in global rules" else "Off in global rules",
        supportingText = "On. Anything not listed is flagged for attention.",
        // Every kind a request or a plugin operation can be (SEE-160). An action the owner cannot
        // tick here is one that always warns "not on your list" once the list is on.
        items =
            PolicyAction.entries.map { action ->
                val checked = action in actions
                RulesSheetItem(
                    id = action.code,
                    model =
                        RuleRowModel(
                            title = actionText(action),
                            supportingText = if (checked) "Expected" else "Not expected",
                            iconName = if (checked) "check_box" else "check_box_outline_blank",
                        ),
                    kind = RuleRowKind.Action,
                    checked = checked,
                    readOnly = override == false,
                    tag = PolicyTags.action(action),
                )
            },
        sectionTag = PolicyTags.section("actions"),
        switchTag = PolicyTags.restrict("actions"),
        inheritTag = PolicyTags.inherit("actions"),
        overrideTag = PolicyTags.override("actions"),
    )

@Composable
private fun assetSection(
    source: ScopeChipSource,
    override: Boolean?,
    enabled: Boolean,
    assets: List<AssetDraft>,
    global: Boolean,
    editableIds: Set<String>,
) =
    RulesSheetSection(
        kind = RulesSectionKind.Assets,
        title = "Assets and thresholds",
        source = source,
        override = override,
        enabled = enabled,
        switchLabel = "Only these assets may move",
        statusText = if (enabled) "On in global rules" else "Off in global rules",
        supportingText = "On. Anything not listed is flagged for attention.",
        items =
            assets.mapIndexed { index, asset ->
                RulesSheetItem(
                    id = asset.asset.id(),
                    model =
                        RuleRowModel(
                            title = asset.displayName(),
                            supportingText = asset.limitSummary(global),
                            iconName = "toll",
                        ),
                    kind = RuleRowKind.Asset,
                    readOnly = !global && override == false && asset.asset.id() !in editableIds,
                    clickable = asset.asset.id() in editableIds,
                    tag =
                        if (global) PolicyTags.asset(index)
                        else PolicyTags.connectionAsset(asset.asset),
                )
            },
        addLabel = "Add an asset".takeUnless { global },
        sectionTag = PolicyTags.section("assets"),
        switchTag = PolicyTags.restrict("assets"),
        inheritTag = PolicyTags.inherit("assets"),
        overrideTag = PolicyTags.override("assets"),
        addTag = PolicyTags.ADD_ALLOWED_ASSET,
    )

private fun addressSection(
    kind: RulesSectionKind,
    source: ScopeChipSource,
    override: Boolean?,
    enabled: Boolean,
    values: List<String>,
    global: Boolean,
) =
    RulesSheetSection(
        kind = kind,
        title = if (kind == RulesSectionKind.Recipients) "Recipients" else "Programs",
        source = source,
        override = override,
        enabled = enabled,
        switchLabel =
            if (kind == RulesSectionKind.Recipients) "Only these wallets may receive funds"
            else "Only these programs may be called",
        statusText = if (enabled) "On in global rules" else "Off in global rules",
        supportingText = "On. Anything not listed is flagged for attention.",
        items =
            values.map { address ->
                RulesSheetItem(
                    id = address,
                    model =
                        RuleRowModel(
                            title = address.knownAddressName(kind),
                            supportingText =
                                if (kind == RulesSectionKind.Recipients) {
                                    "Wallet that owns the funds"
                                } else {
                                    address.shortAddress()
                                },
                            iconName =
                                if (kind == RulesSectionKind.Recipients) {
                                    "account_balance_wallet"
                                } else {
                                    "code"
                                },
                        ),
                    kind =
                        if (kind == RulesSectionKind.Recipients) RuleRowKind.Recipient
                        else RuleRowKind.Program,
                    readOnly = override == false,
                    clickable = false,
                    tag =
                        PolicyTags.entry(
                            if (kind == RulesSectionKind.Recipients) RECIPIENTS else PROGRAMS,
                            address,
                        ),
                )
            },
        addLabel =
            if (global || override == false) null
            else if (kind == RulesSectionKind.Recipients) "Add a wallet" else "Add a program",
        sectionTag =
            PolicyTags.section(if (kind == RulesSectionKind.Recipients) RECIPIENTS else PROGRAMS),
        switchTag =
            PolicyTags.restrict(if (kind == RulesSectionKind.Recipients) RECIPIENTS else PROGRAMS),
        inheritTag =
            PolicyTags.inherit(if (kind == RulesSectionKind.Recipients) RECIPIENTS else PROGRAMS),
        overrideTag =
            PolicyTags.override(if (kind == RulesSectionKind.Recipients) RECIPIENTS else PROGRAMS),
        addTag = PolicyTags.add(if (kind == RulesSectionKind.Recipients) RECIPIENTS else PROGRAMS),
    )

private fun PolicyDraft.without(kind: RulesSectionKind, id: String): PolicyDraft =
    when (kind) {
        RulesSectionKind.Actions -> copy(actions = actions.filterNot { it.code == id }.toSet())
        RulesSectionKind.Assets -> copy(assets = assets.filterNot { it.asset.id() == id })
        RulesSectionKind.Recipients -> copy(recipients = recipients - id)
        RulesSectionKind.Programs -> copy(programs = programs - id)
    }

private fun ConnectionPolicyDraft.without(
    kind: RulesSectionKind,
    id: String,
): ConnectionPolicyDraft =
    when (kind) {
        RulesSectionKind.Actions -> copy(actions = actions.filterNot { it.code == id }.toSet())
        RulesSectionKind.Assets -> {
            val removed = (assets + limits.map { it.asset }).firstOrNull { it.id() == id }
            copy(
                assets = assets.filterNot { it == removed },
                limits = limits.filterNot { it.asset == removed },
            )
        }
        RulesSectionKind.Recipients -> copy(recipients = recipients - id)
        RulesSectionKind.Programs -> copy(programs = programs - id)
    }

private fun ConnectionPolicyDraft.replacementCount(): Int =
    listOf(
            overrideActions,
            overrideAssets || limits.any { it.configuresSomething },
            overrideRecipients,
            overridePrograms,
        )
        .count { it }

private fun PolicyAsset.id(): String = "${network.name}:${mint.orEmpty()}"

@Composable
private fun AssetDraft.displayName(): String =
    if (mint == null) "Native SOL · ${networkLabel(network)}"
    else "A token · ${networkLabel(network)}"

private fun AssetDraft.limitSummary(global: Boolean): String {
    val suffix = if (global) "all connections" else "here"
    return when {
        perOperation.isNotBlank() && daily.isNotBlank() ->
            "$perOperation per request · $daily a day, $suffix"
        perOperation.isNotBlank() -> "$perOperation per request"
        daily.isNotBlank() -> "$daily a day, $suffix"
        else -> "No thresholds"
    }
}

private fun networkLabel(network: Network): String =
    network.name.removePrefix("NETWORK_").lowercase()

private fun String.knownAddressName(kind: RulesSectionKind): String =
    when {
        kind == RulesSectionKind.Recipients -> shortAddress()
        this == "11111111111111111111111111111111" -> "System Program"
        startsWith("ComputeBudget") || startsWith("Comput") -> "Compute Budget"
        else -> shortAddress()
    }

private fun String.shortAddress(): String = if (length <= 14) this else "${take(6)}…${takeLast(5)}"

private val PolicyAddressKind.listTag: String
    get() = if (this == PolicyAddressKind.Recipient) RECIPIENTS else PROGRAMS

private fun AmountProblem.display(asset: AssetDraft): String =
    when (this) {
        AmountProblem.NotANumber -> "Write a plain decimal number."
        AmountProblem.TooPrecise -> "Too many decimal places for ${asset.displayNamePlain()}."
        AmountProblem.TooLarge -> "That amount is too large."
        AmountProblem.Zero -> "A threshold must be more than zero."
    }

private fun AssetDraft.displayNamePlain(): String = mint ?: "SOL"
