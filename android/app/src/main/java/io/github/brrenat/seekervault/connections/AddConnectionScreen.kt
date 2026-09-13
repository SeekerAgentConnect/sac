package io.github.brrenat.seekervault.connections

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassField
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.MonoText
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.PillButton
import io.github.brrenat.seekervault.ui.PillTone
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space

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
    )
}

/** The Add connection screen: scan or enter a code, then confirm the server. */
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
    GlassScreen(
        title = stringResource(R.string.add_title),
        onBack = onBack,
        modifier = modifier,
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

@Composable
private fun ColumnScope.EnterCode(
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
    GlassCard {
        Text(
            stringResource(R.string.add_instructions),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral300,
        )
        Box(
            Modifier.clip(RoundedCornerShape(Radius.Chip))
                .background(Nocturne.bg(0.5f))
                .padding(horizontal = Space.Md, vertical = Space.Sm)
        ) {
            Text(
                PAIR_COMMAND,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = Nocturne.Accent300,
            )
        }
    }
    when (camera) {
        CameraAccess.Scanning -> {
            // The viewfinder, lit by the accent, with the app's own frame inset into it.
            Box(
                Modifier.fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Nocturne.accent(0.10f))
                    .border(1.dp, Nocturne.accent(0.40f), RoundedCornerShape(26.dp))
            ) {
                scanner()
            }
            Text(
                stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
            )
            PillButton(
                stringResource(R.string.stop_scanning),
                onStopScanning,
                tone = PillTone.Ghost,
                modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.STOP_SCAN),
            )
        }
        CameraAccess.Denied -> {
            GlassCard {
                Text(
                    stringResource(R.string.camera_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral300,
                    modifier = Modifier.testTag(ConnectionsTags.CAMERA_DENIED),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
                PillButton(
                    stringResource(R.string.scan_qr),
                    onScan,
                    icon = Glyph.Scan,
                    modifier = Modifier.weight(1f).testTag(ConnectionsTags.SCAN),
                )
                PillButton(
                    stringResource(R.string.open_settings),
                    onOpenSettings,
                    tone = PillTone.Ghost,
                    modifier = Modifier.weight(1f).testTag(ConnectionsTags.OPEN_SETTINGS),
                )
            }
        }
        CameraAccess.Unavailable ->
            GlassCard {
                Text(
                    stringResource(R.string.no_camera),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral300,
                    modifier = Modifier.testTag(ConnectionsTags.NO_CAMERA),
                )
            }
        CameraAccess.Idle ->
            PillButton(
                stringResource(R.string.scan_qr),
                onScan,
                tone = PillTone.Accent,
                icon = Glyph.Scan,
                modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.SCAN),
            )
    }
    if (problem != null) {
        Text(
            problemText(problem),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Danger,
            modifier = Modifier.testTag(ConnectionsTags.CODE_PROBLEM),
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Nocturne.text(0.12f)))
        Text(
            stringResource(R.string.add_or),
            style = MaterialTheme.typography.bodySmall,
            color = Nocturne.Neutral600,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Nocturne.text(0.12f)))
    }
    GlassField(
        value = codeDraft,
        onValueChange = onCodeDraftChange,
        label = stringResource(R.string.code_label),
        placeholder = stringResource(R.string.code_placeholder),
        problem = if (problem != null) problemText(problem) else null,
        singleLine = false,
        minLines = 2,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.testTag(ConnectionsTags.CODE_FIELD),
    )
    PillButton(
        stringResource(R.string.continue_pairing),
        { onCode(codeDraft) },
        enabled = codeDraft.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag(ConnectionsTags.CONTINUE),
    )
    Text(
        stringResource(R.string.confirm_trust),
        style = MaterialTheme.typography.bodySmall,
        color = Nocturne.Neutral500,
    )
}

@Composable
private fun ColumnScope.ConfirmServer(
    confirmation: Confirmation,
    failure: PairingFailure?,
    pairing: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val code = confirmation.code
    Text(
        stringResource(R.string.confirm_title),
        style = MaterialTheme.typography.headlineSmall,
        color = Nocturne.Text,
    )
    // What the phone will contact, so a code can't pair with another host unnoticed. The token
    // itself is never shown.
    GlassCard(spacing = 0.dp) {
        Column(
            Modifier.fillMaxWidth()
                .padding(vertical = Space.Md)
                .testTag(ConnectionsTags.CONFIRM_SERVER)
                .semantics(mergeDescendants = true) {}
        ) {
            SectionLabel(stringResource(R.string.field_server))
            MonoText(code.serverUrl)
        }
        CardDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = Space.Md)) {
            SectionLabel(stringResource(R.string.field_server_id))
            MonoText(code.serverId)
        }
    }
    if (code.serverUrl.startsWith("http://")) {
        Text(
            stringResource(R.string.confirm_development),
            style = MaterialTheme.typography.bodySmall,
            color = Nocturne.Neutral500,
        )
    }
    confirmation.sameServer.forEach {
        Text(
            stringResource(R.string.confirm_same_server, it.label),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral300,
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_NOTE),
        )
    }
    confirmation.sameAddress.forEach {
        Text(
            stringResource(R.string.confirm_same_address, it.label),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Neutral300,
            modifier = Modifier.testTag(ConnectionsTags.CONFIRM_NOTE),
        )
    }
    Text(
        stringResource(R.string.confirm_trust),
        style = MaterialTheme.typography.bodySmall,
        color = Nocturne.Neutral500,
    )
    if (failure != null) {
        Text(
            failureText(failure, code.serverUrl),
            style = MaterialTheme.typography.bodyMedium,
            color = Nocturne.Danger,
            modifier = Modifier.testTag(ConnectionsTags.PAIRING_FAILURE),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
        val retry = failure == PairingFailure.Unreachable || failure == PairingFailure.Other
        if (failure == null || retry) {
            PillButton(
                stringResource(
                    when {
                        pairing -> R.string.pairing_in_progress
                        retry -> R.string.try_again
                        else -> R.string.pair
                    }
                ),
                onConfirm,
                tone = PillTone.Accent,
                enabled = !pairing,
                modifier = Modifier.weight(1f).testTag(ConnectionsTags.PAIR),
            )
        }
        PillButton(
            stringResource(R.string.cancel),
            onCancel,
            tone = PillTone.Ghost,
            enabled = !pairing,
            modifier = Modifier.weight(1f).testTag(ConnectionsTags.CANCEL_PAIRING),
        )
    }
}

/** The command the owner runs on the computer that runs the sidecar. */
private const val PAIR_COMMAND = "pnpm pair"
