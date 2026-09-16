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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
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
    onPaired: (Connection) -> Unit,
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
    val pairing = state.pairing
    LaunchedEffect(pairing) {
        if (pairing is PairingState.Paired) onPaired(pairing.connection)
    }
    // Leaving the screen forgets the code and its token; a rotation keeps them.
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.resetPairing() }
    }
    AddConnectionScreen(
        pairing = pairing,
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
        onConfirm = viewModel::confirmPairing,
        onCancel = viewModel::resetPairing,
        onBack = onBack,
        scanner = { scanner(viewModel::onCode) { camera = CameraAccess.Unavailable } },
        modifier = modifier,
    )
}

/** The Add connection screen: scan or enter a code, then confirm the server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConnectionScreen(
    pairing: PairingState,
    codeDraft: String,
    camera: CameraAccess,
    onScan: () -> Unit,
    onStopScanning: () -> Unit,
    onOpenSettings: () -> Unit,
    onCodeDraftChange: (String) -> Unit,
    onCode: (String) -> Unit,
    onConfirm: () -> Unit,
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
                Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (pairing) {
                is PairingState.Confirm ->
                    ConfirmServer(pairing.confirmation, null, false, onConfirm, onCancel)
                is PairingState.Pairing ->
                    ConfirmServer(pairing.confirmation, null, true, onConfirm, onCancel)
                is PairingState.Failed ->
                    ConfirmServer(pairing.confirmation, pairing.failure, false, onConfirm, onCancel)
                is PairingState.Idle,
                is PairingState.Invalid,
                is PairingState.Paired ->
                    EnterCode(
                        problem = (pairing as? PairingState.Invalid)?.problem,
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
    problem: PairingCodeProblem?,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    if (problem != null) {
        Text(
            problemText(problem),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(ConnectionsTags.CODE_PROBLEM),
        )
    }
    Spacer(Modifier.height(12.dp))
    TextField(
        value = codeDraft,
        onValueChange = onCodeDraftChange,
        label = { Text(stringResource(R.string.code_label)) },
        placeholder = { Text(stringResource(R.string.code_placeholder)) },
        isError = problem != null,
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
