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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
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
import io.github.brrenat.seekervault.designsystem.DesignTextFieldState
import io.github.brrenat.seekervault.designsystem.FactRow
import io.github.brrenat.seekervault.designsystem.FactRowValueStyle
import io.github.brrenat.seekervault.designsystem.InlineCodeInstruction
import io.github.brrenat.seekervault.designsystem.ScreenCaption
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ScreenScaffold
import io.github.brrenat.seekervault.designsystem.ScreenScrollBody
import io.github.brrenat.seekervault.designsystem.SectionHeader
import io.github.brrenat.seekervault.designsystem.SectionHeaderTrailing
import io.github.brrenat.seekervault.designsystem.SeekerButton
import io.github.brrenat.seekervault.designsystem.SeekerButtonSize
import io.github.brrenat.seekervault.designsystem.SeekerButtonVariant
import io.github.brrenat.seekervault.designsystem.SeekerFab
import io.github.brrenat.seekervault.designsystem.SeekerFabWidth
import io.github.brrenat.seekervault.designsystem.SeekerTextField
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.FeedReferenceProblem
import io.github.brrenat.seekervault.servers.manifest

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

data class AddConnectionScreenState(
    val adding: AddConnectionState,
    val codeDraft: String,
    val camera: CameraAccess,
)

data class AddConnectionScreenCallbacks(
    val onScan: () -> Unit,
    val onStopScanning: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onCodeDraftChange: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onConfirmPairing: () -> Unit,
    val onConfirmFeed: () -> Unit,
    val onOpenFeed: (Connection) -> Unit,
    val onCancel: () -> Unit,
    val onBack: () -> Unit,
    val navigation: ScreenNavigationCallbacks,
)

private val NoAddConnectionNavigation =
    ScreenNavigationCallbacks(onHome = {}, onInbox = {}, onWallet = {}, onActivity = {})

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
    navigationCallbacks: ScreenNavigationCallbacks = NoAddConnectionNavigation,
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
            else -> Unit
        }
    }
    // Leaving the screen forgets the reference and any pairing token; a rotation keeps them.
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.resetAdding() }
    }
    AddConnectionScreen(
        state = AddConnectionScreenState(adding, state.codeDraft, camera),
        callbacks =
            AddConnectionScreenCallbacks(
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
                onOpenFeed = onAdded,
                onCancel = viewModel::resetAdding,
                onBack = onBack,
                navigation = navigationCallbacks,
            ),
        scanner = { scanner(viewModel::onCode) { camera = CameraAccess.Unavailable } },
        modifier = modifier,
    )
}

/** The Add connection screen: scan or enter a code, then confirm the server. */
@Composable
fun AddConnectionScreen(
    state: AddConnectionScreenState,
    callbacks: AddConnectionScreenCallbacks,
    scanner: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        title = AddConnectionTitle,
        selectedDestination = null,
        navigationCallbacks = callbacks.navigation,
        onBack = callbacks.onBack,
        backButtonModifier = Modifier.testTag(ConnectionsTags.BACK),
        modifier = modifier,
    ) {
        ScreenScrollBody {
            when (val adding = state.adding) {
                is AddConnectionState.ConfirmPairing ->
                    ConfirmServer(
                        adding.confirmation,
                        null,
                        false,
                        callbacks.onConfirmPairing,
                        callbacks.onCancel,
                    )
                is AddConnectionState.Pairing ->
                    ConfirmServer(
                        adding.confirmation,
                        null,
                        true,
                        callbacks.onConfirmPairing,
                        callbacks.onCancel,
                    )
                is AddConnectionState.PairingFailed ->
                    ConfirmServer(
                        adding.confirmation,
                        adding.failure,
                        false,
                        callbacks.onConfirmPairing,
                        callbacks.onCancel,
                    )
                is AddConnectionState.ConfirmFeed ->
                    ConfirmFeed(
                        adding.reference,
                        null,
                        false,
                        callbacks.onConfirmFeed,
                        callbacks.onCancel,
                    )
                is AddConnectionState.AddingFeed ->
                    ConfirmFeed(
                        adding.reference,
                        null,
                        true,
                        callbacks.onConfirmFeed,
                        callbacks.onCancel,
                    )
                is AddConnectionState.FeedFailed ->
                    ConfirmFeed(
                        adding.reference,
                        adding.failure,
                        false,
                        callbacks.onConfirmFeed,
                        callbacks.onCancel,
                    )
                is AddConnectionState.FeedAdded ->
                    FeedResult(
                        adding.connection,
                        true,
                        callbacks.onOpenFeed,
                        callbacks.onCancel,
                    )
                is AddConnectionState.FeedAlready ->
                    FeedResult(
                        adding.connection,
                        false,
                        callbacks.onOpenFeed,
                        callbacks.onCancel,
                    )
                is AddConnectionState.Idle,
                is AddConnectionState.PairingInvalid,
                is AddConnectionState.FeedInvalid,
                AddConnectionState.RetiredInvitation,
                is AddConnectionState.Paired ->
                    EnterCode(
                        pairingProblem = (adding as? AddConnectionState.PairingInvalid)?.problem,
                        feedProblem = (adding as? AddConnectionState.FeedInvalid)?.problem,
                        retiredInvitation = adding == AddConnectionState.RetiredInvitation,
                        codeDraft = state.codeDraft,
                        camera = state.camera,
                        onScan = callbacks.onScan,
                        onStopScanning = callbacks.onStopScanning,
                        onOpenSettings = callbacks.onOpenSettings,
                        onCodeDraftChange = callbacks.onCodeDraftChange,
                        onCode = callbacks.onCode,
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
    retiredInvitation: Boolean,
    codeDraft: String,
    camera: CameraAccess,
    onScan: () -> Unit,
    onStopScanning: () -> Unit,
    onOpenSettings: () -> Unit,
    onCodeDraftChange: (String) -> Unit,
    onCode: (String) -> Unit,
    scanner: @Composable () -> Unit,
) {
    InlineCodeInstruction(
        beforeCode = AddConnectionInstructionBefore,
        code = AddConnectionInstructionCode,
        afterCode = AddConnectionInstructionAfter,
    )
    when (camera) {
        CameraAccess.Scanning -> {
            scanner()
            ScreenCaption(stringResource(R.string.scan_hint))
            SeekerButton(
                label = stringResource(R.string.stop_scanning),
                onClick = onStopScanning,
                variant = SeekerButtonVariant.Neutral,
                size = SeekerButtonSize.Md,
                modifier = Modifier.testTag(ConnectionsTags.STOP_SCAN),
            )
        }
        CameraAccess.Denied -> {
            ScreenCaption(
                text = stringResource(R.string.camera_denied),
                modifier = Modifier.testTag(ConnectionsTags.CAMERA_DENIED),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
                SeekerButton(
                    label = stringResource(R.string.scan_qr),
                    onClick = onScan,
                    variant = SeekerButtonVariant.Neutral,
                    size = SeekerButtonSize.Md,
                    modifier = Modifier.testTag(ConnectionsTags.SCAN),
                )
                SeekerButton(
                    label = stringResource(R.string.open_settings),
                    onClick = onOpenSettings,
                    variant = SeekerButtonVariant.Neutral,
                    size = SeekerButtonSize.Md,
                    modifier = Modifier.testTag(ConnectionsTags.OPEN_SETTINGS),
                )
            }
        }
        CameraAccess.Unavailable ->
            ScreenCaption(
                text = stringResource(R.string.no_camera),
                modifier = Modifier.testTag(ConnectionsTags.NO_CAMERA),
            )
        CameraAccess.Idle ->
            SeekerFab(
                label = AddConnectionScanLabel,
                icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                onClick = onScan,
                width = SeekerFabWidth.Full,
                modifier = Modifier.testTag(ConnectionsTags.SCAN),
            )
    }
    val invalid = pairingProblem != null || feedProblem != null || retiredInvitation
    val problemMessage =
        when {
            pairingProblem != null -> problemText(pairingProblem)
            feedProblem != null -> feedReferenceProblemText(feedProblem)
            retiredInvitation -> stringResource(R.string.gateway_invitation_retired)
            else -> null
        }
    SeekerTextField(
        label = AddConnectionCodeLabel,
        value = codeDraft,
        onValueChange = onCodeDraftChange,
        state = if (invalid) DesignTextFieldState.Error else DesignTextFieldState.Rest,
        placeholder = AddConnectionCodePlaceholder,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        reserveErrorSpace = invalid,
        inputModifier = Modifier.testTag(ConnectionsTags.CODE_FIELD),
        modifier = Modifier.fillMaxWidth(),
    )
    if (problemMessage != null) {
        ScreenCaption(
            text = problemMessage,
            modifier = Modifier.testTag(ConnectionsTags.CODE_PROBLEM),
        )
    }
    SeekerButton(
        label = AddConnectionContinueLabel,
        onClick = { onCode(codeDraft) },
        variant = SeekerButtonVariant.Tonal,
        size = SeekerButtonSize.Md,
        enabled = codeDraft.isNotBlank(),
        modifier = Modifier.testTag(ConnectionsTags.CONTINUE),
    )
    ScreenCaption(text = AddConnectionCaution)
}

@Composable
private fun ConfirmFeed(
    reference: FeedReference,
    failure: FeedAddFailure?,
    adding: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AddSectionTitle(stringResource(R.string.feed_confirm_title))
    FactRow(
        label = stringResource(R.string.field_gateway),
        value = reference.gatewayUrl,
        valueStyle = FactRowValueStyle.MonoWrap,
        modifier = Modifier.testTag(ConnectionsTags.CONFIRM_FEED),
    )
    FactRow(
        label = stringResource(R.string.field_server_id),
        value = reference.serverId,
        valueStyle = FactRowValueStyle.MonoWrap,
    )
    ScreenCaption(
        stringResource(
            // A reference that says restricted is a hint and a floor, not a permission: the
            // gateway's own manifest decides, and a feed added from a restricted reference whose
            // manifest says public is refused (SEE-156).
            if (reference.restricted) R.string.feed_confirm_restricted
            else R.string.feed_confirm_public
        )
    )
    if (failure != null) {
        ScreenCaption(
            text = feedFailureText(failure, reference.gatewayUrl),
            modifier = Modifier.testTag(ConnectionsTags.FEED_FAILURE),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
        val retry =
            failure is FeedAddFailure.Check &&
                (failure.outcome == CheckOutcome.Unreachable ||
                    failure.outcome == CheckOutcome.Failed)
        if (failure == null || retry) {
            SeekerButton(
                label =
                    stringResource(
                        when {
                            adding -> R.string.feed_adding
                            retry -> R.string.try_again
                            else -> R.string.feed_add
                        }
                    ),
                onClick = onConfirm,
                variant = SeekerButtonVariant.Filled,
                size = SeekerButtonSize.Md,
                enabled = !adding,
                modifier = Modifier.testTag(ConnectionsTags.ADD_FEED),
            )
        }
        SeekerButton(
            label = stringResource(R.string.cancel),
            onClick = onCancel,
            variant = SeekerButtonVariant.Neutral,
            size = SeekerButtonSize.Md,
            enabled = !adding,
            modifier = Modifier.testTag(ConnectionsTags.CANCEL_PAIRING),
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
    AddSectionTitle(
        stringResource(if (added) R.string.feed_added_title else R.string.feed_already_title)
    )
    ScreenCaption(
        stringResource(
            if (added) R.string.feed_added_text else R.string.feed_already_text,
            connection.label,
        )
    )
    FeedSummary(connection)
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
        SeekerButton(
            label = stringResource(R.string.feed_open),
            onClick = { onOpen(connection) },
            variant = SeekerButtonVariant.Filled,
            size = SeekerButtonSize.Md,
            modifier = Modifier.testTag(ConnectionsTags.OPEN_FEED),
        )
        SeekerButton(
            label = stringResource(R.string.cancel),
            onClick = onDone,
            variant = SeekerButtonVariant.Neutral,
            size = SeekerButtonSize.Md,
        )
    }
}

@Composable
private fun FeedSummary(connection: Connection) {
    val required = connection.server.manifest?.required.orEmpty()
    val requiredText =
        if (required.isEmpty()) stringResource(R.string.feed_required_plugins_none)
        else required.joinToString("\n") { it.id.value }
    FactRow(
        label = stringResource(R.string.feed_display_name),
        value = connection.label,
        valueStyle = FactRowValueStyle.Plain,
    )
    FactRow(
        label = stringResource(R.string.field_environment),
        value = stringResource(environmentText(connection.environment)),
        valueStyle = FactRowValueStyle.Plain,
    )
    FactRow(
        label = stringResource(R.string.feed_required_plugins),
        value = requiredText,
        valueStyle = FactRowValueStyle.Plain,
    )
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
    AddSectionTitle(stringResource(R.string.confirm_title))
    // What the phone will contact, so a code can't pair with another host unnoticed. The token
    // itself is never shown.
    FactRow(
        label = stringResource(R.string.field_server),
        value = code.serverUrl,
        valueStyle = FactRowValueStyle.MonoWrap,
        modifier = Modifier.testTag(ConnectionsTags.CONFIRM_SERVER),
    )
    FactRow(
        label = stringResource(R.string.field_server_id),
        value = code.serverId,
        valueStyle = FactRowValueStyle.MonoWrap,
    )
    if (code.serverUrl.startsWith("http://")) {
        ScreenCaption(stringResource(R.string.confirm_development))
    }
    confirmation.sameServer.forEach {
        ScreenCaption(
            text = stringResource(R.string.confirm_same_server, it.label),
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_NOTE),
        )
    }
    confirmation.sameAddress.forEach {
        ScreenCaption(
            text = stringResource(R.string.confirm_same_address, it.label),
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_NOTE),
        )
    }
    ScreenCaption(stringResource(R.string.confirm_trust))
    if (failure != null) {
        ScreenCaption(
            text = failureText(failure, code.serverUrl),
            modifier = Modifier.testTag(ConnectionsTags.PAIRING_FAILURE),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md)) {
        val retry = failure == PairingFailure.Unreachable || failure == PairingFailure.Other
        if (failure == null || retry) {
            SeekerButton(
                label =
                    stringResource(
                        when {
                            pairing -> R.string.pairing_in_progress
                            retry -> R.string.try_again
                            else -> R.string.pair
                        }
                    ),
                onClick = onConfirm,
                variant = SeekerButtonVariant.Filled,
                size = SeekerButtonSize.Md,
                enabled = !pairing,
                modifier = Modifier.testTag(ConnectionsTags.PAIR),
            )
        }
        SeekerButton(
            label = stringResource(R.string.cancel),
            onClick = onCancel,
            variant = SeekerButtonVariant.Neutral,
            size = SeekerButtonSize.Md,
            enabled = !pairing,
            modifier = Modifier.testTag(ConnectionsTags.CANCEL_PAIRING),
        )
    }
}

@Composable
private fun AddSectionTitle(title: String) {
    SectionHeader(title = title, trailing = SectionHeaderTrailing.None)
}

private const val AddConnectionTitle = "Add connection"
private const val AddConnectionInstructionBefore = "On the computer that runs the sidecar, run "
private const val AddConnectionInstructionCode = "pnpm pair"
private const val AddConnectionInstructionAfter =
    ". Scan the QR code it shows, or type the code printed under it."
private const val AddConnectionScanLabel = "Scan QR code"
private const val AddConnectionCodeLabel = "Pairing code"
private const val AddConnectionCodePlaceholder = "seekervault://pair?…"
private const val AddConnectionContinueLabel = "Continue"
private const val AddConnectionCaution =
    "Pair only with a server you run. Whoever controls it can send this phone requests to review."
