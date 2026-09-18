package io.github.brrenat.seekervault.connections

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.FeedReferenceProblem
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.seekerListItemColors
import io.github.brrenat.seekervault.ui.seekerTextFieldColors

/** Where the camera stands on the Add connection screen. */
enum class CameraAccess {
    /** Not scanning yet. */
    Idle,
    Scanning,
    /** The owner refused the CAMERA permission. */
    Denied,
    /** No camera the app can use. */
    Unavailable,
}

/**
 * The Add connection screen with its camera permission: the permission is asked for only when the
 * owner taps Scan. [scanner] draws the camera; tests replace it.
 */
@Composable
fun AddConnectionRoute(
    viewModel: ConnectionsViewModel,
    onBack: () -> Unit,
    onAdded: (Connection) -> Unit,
    modifier: Modifier = Modifier,
    scanner: @Composable (onText: (String) -> Unit, onUnavailable: () -> Unit) -> Unit =
        { onText, onUnavailable ->
            QrScanner(onText, onUnavailable, Modifier.fillMaxWidth().aspectRatio(1f))
        },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var camera by rememberSaveable {
        mutableStateOf(if (hasCamera) CameraAccess.Idle else CameraAccess.Unavailable)
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            camera = if (granted) CameraAccess.Scanning else CameraAccess.Denied
        }
    val adding = state.adding
    LaunchedEffect(adding) {
        when (adding) {
            is AddConnectionState.Paired -> onAdded(adding.connection)
            is AddConnectionState.FeedAdded -> onAdded(adding.connection)
            is AddConnectionState.InvitationConnected -> onAdded(adding.connection)
            else -> Unit
        }
    }
    // Leaving the screen forgets the reference and any pairing token; a rotation keeps them.
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.resetAdding() }
    }
    AddConnectionScreen(
        adding = adding,
        codeDraft = state.codeDraft,
        camera = camera,
        onScan = {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            when {
                !hasCamera -> camera = CameraAccess.Unavailable
                granted -> camera = CameraAccess.Scanning
                else -> permission.launch(Manifest.permission.CAMERA)
            }
        },
        onStopScanning = { camera = CameraAccess.Idle },
        onOpenSettings = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        },
        onCodeDraftChange = viewModel::onCodeDraftChange,
        onCode = viewModel::onCode,
        onConfirmPairing = viewModel::confirmPairing,
        onConfirmFeed = viewModel::confirmFeed,
        onConfirmInvitation = viewModel::confirmInvitation,
        onOpenFeed = onAdded,
        onCancel = viewModel::resetAdding,
        onBack = onBack,
        scanner = { scanner(viewModel::onCode) { camera = CameraAccess.Unavailable } },
        modifier = modifier,
    )
}

/** The Add connection screen: scan or enter a code, then confirm the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectionScreen(
    adding: AddConnectionState,
    codeDraft: String,
    camera: CameraAccess,
    onScan: () -> Unit,
    onStopScanning: () -> Unit,
    onOpenSettings: () -> Unit,
    onCodeDraftChange: (String) -> Unit,
    onCode: (String) -> Unit,
    onConfirmPairing: () -> Unit,
    onConfirmFeed: () -> Unit,
    onConfirmInvitation: () -> Unit,
    onOpenFeed: (Connection) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    scanner: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = { BackButton(onBack) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier.padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
        ) {
            when (adding) {
                is AddConnectionState.ConfirmPairing ->
                    ConfirmServer(
                        adding.confirmation,
                        null,
                        false,
                        onConfirmPairing,
                        onCancel,
                    )
                is AddConnectionState.Pairing ->
                    ConfirmServer(
                        adding.confirmation,
                        null,
                        true,
                        onConfirmPairing,
                        onCancel,
                    )
                is AddConnectionState.PairingFailed ->
                    ConfirmServer(
                        adding.confirmation,
                        adding.failure,
                        false,
                        onConfirmPairing,
                        onCancel,
                    )
                is AddConnectionState.ConfirmFeed ->
                    ConfirmFeed(adding.reference, null, false, onConfirmFeed, onCancel)
                is AddConnectionState.AddingFeed ->
                    ConfirmFeed(adding.reference, null, true, onConfirmFeed, onCancel)
                is AddConnectionState.FeedFailed ->
                    ConfirmFeed(
                        adding.reference,
                        adding.failure,
                        false,
                        onConfirmFeed,
                        onCancel,
                    )
                is AddConnectionState.FeedAdded ->
                    FeedResult(adding.connection, true, onOpenFeed, onCancel)
                is AddConnectionState.FeedAlready ->
                    FeedResult(adding.connection, false, onOpenFeed, onCancel)
                is AddConnectionState.ResolvingInvitation -> {
                    Text(stringResource(R.string.invitation_pending))
                    SeekerButton(
                        text = stringResource(R.string.cancel),
                        onClick = onCancel,
                        role = SeekerButtonRole.Neutral,
                    )
                }
                is AddConnectionState.ConfirmInvitation ->
                    ConfirmInvitation(
                        adding.confirmation,
                        null,
                        false,
                        onConfirmInvitation,
                        onCancel,
                    )
                is AddConnectionState.RedeemingInvitation ->
                    ConfirmInvitation(
                        adding.confirmation,
                        null,
                        true,
                        onConfirmInvitation,
                        onCancel,
                    )
                is AddConnectionState.InvitationFailed ->
                    ConfirmInvitation(
                        adding.confirmation,
                        adding.problem,
                        false,
                        onConfirmInvitation,
                        onCancel,
                    )
                is AddConnectionState.InvitationConnected ->
                    InvitationResult(adding.connection, true, onOpenFeed, onCancel)
                is AddConnectionState.InvitationAlready ->
                    InvitationResult(adding.connection, false, onOpenFeed, onCancel)
                is AddConnectionState.Idle,
                is AddConnectionState.PairingInvalid,
                is AddConnectionState.FeedInvalid,
                is AddConnectionState.InvitationInvalid,
                is AddConnectionState.Paired ->
                    EnterCode(
                        pairingProblem = (adding as? AddConnectionState.PairingInvalid)?.problem,
                        feedProblem = (adding as? AddConnectionState.FeedInvalid)?.problem,
                        invitationProblem =
                            (adding as? AddConnectionState.InvitationInvalid)?.problem,
                        codeDraft = codeDraft,
                        camera = camera,
                        onScan = onScan,
                        onStopScanning = onStopScanning,
                        onOpenSettings = onOpenSettings,
                        onCodeDraftChange = onCodeDraftChange,
                        onCode = onCode,
                        scanner = scanner,
                    )
            }
        }
    }
}

@Composable
private fun EnterCode(
    pairingProblem: PairingCodeProblem?,
    feedProblem: FeedReferenceProblem?,
    invitationProblem: InvitationProblem?,
    codeDraft: String,
    camera: CameraAccess,
    onScan: () -> Unit,
    onStopScanning: () -> Unit,
    onOpenSettings: () -> Unit,
    onCodeDraftChange: (String) -> Unit,
    onCode: (String) -> Unit,
    scanner: @Composable () -> Unit,
) {
    Text(stringResource(R.string.add_instructions))
    when (camera) {
        CameraAccess.Scanning -> {
            scanner()
            Text(stringResource(R.string.scan_hint))
            SeekerButton(
                text = stringResource(R.string.stop_scanning),
                onClick = onStopScanning,
                role = SeekerButtonRole.Neutral,
                modifier = Modifier.testTag(ConnectionsTags.STOP_SCAN),
            )
        }
        CameraAccess.Denied -> {
            Text(
                stringResource(R.string.camera_denied),
                modifier = Modifier.testTag(ConnectionsTags.CAMERA_DENIED),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
                SeekerButton(
                    text = stringResource(R.string.scan_qr),
                    onClick = onScan,
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.testTag(ConnectionsTags.SCAN),
                )
                SeekerButton(
                    text = stringResource(R.string.open_settings),
                    onClick = onOpenSettings,
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.testTag(ConnectionsTags.OPEN_SETTINGS),
                )
            }
        }
        CameraAccess.Unavailable ->
            Text(
                stringResource(R.string.no_camera),
                modifier = Modifier.testTag(ConnectionsTags.NO_CAMERA),
            )
        CameraAccess.Idle ->
            SeekerButton(
                text = stringResource(R.string.scan_qr),
                onClick = onScan,
                modifier = Modifier.testTag(ConnectionsTags.SCAN),
            )
    }
    val invalid = pairingProblem != null || feedProblem != null || invitationProblem != null
    if (invalid) {
        val problemMessage =
            when {
                pairingProblem != null -> problemText(pairingProblem)
                feedProblem != null -> feedReferenceProblemText(feedProblem)
                invitationProblem != null -> invitationProblemText(invitationProblem)
                else -> ""
            }
        Text(
            problemMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(ConnectionsTags.CODE_PROBLEM),
        )
    }
    Spacer(Modifier.height(SeekerTheme.dimensions.dp12))
    TextField(
        value = codeDraft,
        onValueChange = onCodeDraftChange,
        label = { Text(stringResource(R.string.code_label)) },
        placeholder = { Text(stringResource(R.string.code_placeholder)) },
        isError = invalid,
        minLines = 2,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        colors = seekerTextFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.CODE_FIELD),
    )
    SeekerButton(
        text = stringResource(R.string.continue_pairing),
        onClick = { onCode(codeDraft) },
        enabled = codeDraft.isNotBlank(),
        modifier = Modifier.testTag(ConnectionsTags.CONTINUE),
    )
}

@Composable
private fun ConfirmFeed(
    reference: FeedReference,
    failure: FeedAddFailure?,
    adding: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(stringResource(R.string.feed_confirm_title), style = MaterialTheme.typography.titleLarge)
    SeekerCard(Modifier.fillMaxWidth()) {
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_gateway)) },
            headlineContent = { Text(reference.gatewayUrl) },
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_FEED),
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_server_id)) },
            headlineContent = { Text(reference.serverId) },
            colors = seekerListItemColors(),
        )
    }
    Text(stringResource(R.string.feed_confirm_public), style = MaterialTheme.typography.bodySmall)
    if (failure != null) {
        Text(
            feedFailureText(failure, reference.gatewayUrl),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(ConnectionsTags.FEED_FAILURE),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
        val retry =
            failure is FeedAddFailure.Check &&
                (failure.outcome == CheckOutcome.Unreachable ||
                    failure.outcome == CheckOutcome.Failed)
        if (failure == null || retry) {
            SeekerButton(
                text =
                    stringResource(
                        when {
                            adding -> R.string.feed_adding
                            retry -> R.string.try_again
                            else -> R.string.feed_add
                        }
                    ),
                onClick = onConfirm,
                enabled = !adding,
                modifier = Modifier.testTag(ConnectionsTags.ADD_FEED),
            )
        }
        SeekerButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel,
            enabled = !adding,
            role = SeekerButtonRole.Neutral,
            modifier = Modifier.testTag(ConnectionsTags.CANCEL_PAIRING),
        )
    }
}

@Composable
private fun ConfirmInvitation(
    confirmation: InvitationConfirmation,
    failure: InvitationProblem?,
    connecting: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val invitation = confirmation.invitation
    val environment = startingEnvironment(ServerRecord.Known(confirmation.manifest))
    val required = confirmation.manifest.required
    val requiredText =
        if (required.isEmpty()) stringResource(R.string.feed_required_plugins_none)
        else required.joinToString("\n") { it.id.value }
    Text(
        stringResource(R.string.invitation_confirm_title),
        style = MaterialTheme.typography.titleLarge,
    )
    SeekerCard(Modifier.fillMaxWidth()) {
        ListItem(
            overlineContent = { Text(stringResource(R.string.feed_display_name)) },
            headlineContent = { Text(invitation.displayName.ifEmpty { invitation.serverId }) },
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_INVITATION),
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_gateway)) },
            headlineContent = { Text(confirmation.reference.gatewayUrl) },
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_server_id)) },
            headlineContent = { Text(invitation.serverId) },
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_environment)) },
            headlineContent = { Text(stringResource(environmentText(environment))) },
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.feed_required_plugins)) },
            headlineContent = { Text(requiredText) },
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.invitation_expires)) },
            headlineContent = { Text(invitation.expiresAt.toString()) },
            colors = seekerListItemColors(),
        )
    }
    Text(
        stringResource(R.string.invitation_confirm_scope),
        style = MaterialTheme.typography.bodySmall,
    )
    if (failure != null) {
        Text(
            invitationProblemText(failure),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(ConnectionsTags.INVITATION_FAILURE),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
        if (failure == null || failure == InvitationProblem.Failed) {
            SeekerButton(
                text =
                    stringResource(
                        if (connecting) R.string.invitation_connecting
                        else R.string.invitation_connect
                    ),
                onClick = onConfirm,
                enabled = !connecting,
                modifier = Modifier.testTag(ConnectionsTags.CONNECT_INVITATION),
            )
        }
        SeekerButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel,
            enabled = !connecting,
            role = SeekerButtonRole.Neutral,
        )
    }
}

@Composable
private fun InvitationResult(
    connection: Connection,
    connected: Boolean,
    onOpen: (Connection) -> Unit,
    onDone: () -> Unit,
) {
    Text(
        stringResource(
            if (connected) R.string.invitation_connected else R.string.invitation_already
        ),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(connection.label)
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
        SeekerButton(text = stringResource(R.string.feed_open), onClick = { onOpen(connection) })
        SeekerButton(
            text = stringResource(R.string.cancel),
            onClick = onDone,
            role = SeekerButtonRole.Neutral,
        )
    }
}

/** The duplicate result stays on screen so it is never mistaken for a second stored feed. */
@Composable
private fun FeedResult(
    connection: Connection,
    added: Boolean,
    onOpen: (Connection) -> Unit,
    onDone: () -> Unit,
) {
    Text(
        stringResource(if (added) R.string.feed_added_title else R.string.feed_already_title),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        stringResource(
            if (added) R.string.feed_added_text else R.string.feed_already_text,
            connection.label,
        )
    )
    FeedSummary(connection)
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
        SeekerButton(
            text = stringResource(R.string.feed_open),
            onClick = { onOpen(connection) },
            modifier = Modifier.testTag(ConnectionsTags.OPEN_FEED),
        )
        SeekerButton(
            text = stringResource(R.string.cancel),
            onClick = onDone,
            role = SeekerButtonRole.Neutral,
        )
    }
}

@Composable
private fun FeedSummary(connection: Connection) {
    val required = connection.server.manifest?.required.orEmpty()
    val requiredText =
        if (required.isEmpty()) stringResource(R.string.feed_required_plugins_none)
        else required.joinToString("\n") { it.id.value }
    SeekerCard(Modifier.fillMaxWidth()) {
        ListItem(
            overlineContent = { Text(stringResource(R.string.feed_display_name)) },
            headlineContent = { Text(connection.label) },
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_environment)) },
            headlineContent = { Text(stringResource(environmentText(connection.environment))) },
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.feed_required_plugins)) },
            headlineContent = { Text(requiredText) },
            colors = seekerListItemColors(),
        )
    }
}

@Composable
private fun ConfirmServer(
    confirmation: Confirmation,
    failure: PairingFailure?,
    pairing: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val code = confirmation.code
    Text(stringResource(R.string.confirm_title), style = MaterialTheme.typography.titleLarge)
    // What the phone will contact, so a code can't pair with another host unnoticed. The token
    // itself is never shown.
    SeekerCard(Modifier.fillMaxWidth()) {
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_server)) },
            headlineContent = { Text(code.serverUrl) },
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_SERVER),
            colors = seekerListItemColors(),
        )
        ListItem(
            overlineContent = { Text(stringResource(R.string.field_server_id)) },
            headlineContent = { Text(code.serverId) },
            colors = seekerListItemColors(),
        )
    }
    if (code.serverUrl.startsWith("http://")) {
        Text(stringResource(R.string.confirm_development))
    }
    confirmation.sameServer.forEach {
        Text(
            stringResource(R.string.confirm_same_server, it.label),
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_NOTE),
        )
    }
    confirmation.sameAddress.forEach {
        Text(
            stringResource(R.string.confirm_same_address, it.label),
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_NOTE),
        )
    }
    Text(stringResource(R.string.confirm_trust), style = MaterialTheme.typography.bodySmall)
    if (failure != null) {
        Text(
            failureText(failure, code.serverUrl),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(ConnectionsTags.PAIRING_FAILURE),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8)) {
        val retry = failure == PairingFailure.Unreachable || failure == PairingFailure.Other
        if (failure == null || retry) {
            SeekerButton(
                text =
                    stringResource(
                        when {
                            pairing -> R.string.pairing_in_progress
                            retry -> R.string.try_again
                            else -> R.string.pair
                        }
                    ),
                onClick = onConfirm,
                enabled = !pairing,
                modifier = Modifier.testTag(ConnectionsTags.PAIR),
            )
        }
        SeekerButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel,
            enabled = !pairing,
            role = SeekerButtonRole.Neutral,
            modifier = Modifier.testTag(ConnectionsTags.CANCEL_PAIRING),
        )
    }
}
