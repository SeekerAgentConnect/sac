package io.github.brrenat.seekervault.connections

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tags for the connection screens' controls. */
object ConnectionsTags {
    const val LIVE_TEST = "liveTest"
    const val ADD = "addConnection"
    const val EMPTY = "connectionsEmpty"
    const val BACK = "back"
    const val STATUS = "connectionStatus"
    const val REFRESH = "refresh"
    const val RENAME = "rename"
    const val DISCONNECT = "disconnectConnection"
    const val REMOVE = "removeConnection"
    const val LABEL_FIELD = "labelField"
    const val DIALOG_CONFIRM = "dialogConfirm"
    const val DIALOG_DISMISS = "dialogDismiss"
    const val SCAN = "scan"
    const val STOP_SCAN = "stopScan"
    const val CODE_FIELD = "codeField"
    const val CONTINUE = "continue"
    const val CODE_PROBLEM = "codeProblem"
    const val CAMERA_DENIED = "cameraDenied"
    const val NO_CAMERA = "noCamera"
    const val OPEN_SETTINGS = "openSettings"
    const val CONFIRM_SERVER = "confirmServer"
    const val CONFIRM_NOTE = "confirmNote"
    const val PAIR = "pair"
    const val CANCEL_PAIRING = "cancelPairing"
    const val PAIRING_FAILURE = "pairingFailure"

    fun item(id: String) = "connection:$id"

    fun field(name: String) = "field:$name"
}

/** What the phone knows about the connection, in one sentence. */
@Composable
fun statusText(connection: Connection): String {
    val check = connection.lastCheck
    return when {
        connection.revokedAt != null -> stringResource(R.string.connection_status_revoked)
        !connection.hasCredential -> stringResource(R.string.connection_status_credential_missing)
        check == null -> stringResource(R.string.connection_status_not_checked)
        check.outcome == CheckOutcome.Ok && check.morePending ->
            stringResource(R.string.connection_status_ok_more, check.pending ?: 0)
        check.outcome == CheckOutcome.Ok ->
            stringResource(R.string.connection_status_ok, check.pending ?: 0)
        else -> outcomeText(check.outcome)
    }
}

/** Whether the connection needs the owner's attention. */
fun hasProblem(connection: Connection): Boolean =
    !connection.usable || connection.lastCheck.let { it != null && it.outcome != CheckOutcome.Ok }

@Composable
fun outcomeText(outcome: CheckOutcome): String =
    stringResource(
        when (outcome) {
            CheckOutcome.Ok -> R.string.connection_status_not_checked
            CheckOutcome.Unreachable -> R.string.connection_status_unreachable
            CheckOutcome.CertificateRejected -> R.string.connection_status_certificate
            CheckOutcome.CleartextBlocked -> R.string.connection_status_cleartext
            CheckOutcome.Failed -> R.string.connection_status_failed
        }
    )

@Composable
fun problemText(problem: PairingCodeProblem): String =
    stringResource(
        when (problem) {
            PairingCodeProblem.NotACode -> R.string.code_not_a_code
            PairingCodeProblem.NotSeekerVault -> R.string.code_not_seeker_vault
            PairingCodeProblem.OtherVersion -> R.string.code_other_version
            PairingCodeProblem.BadServerUrl -> R.string.code_bad_server_url
            PairingCodeProblem.InsecureServerUrl -> R.string.code_insecure_server_url
            PairingCodeProblem.BadServerId -> R.string.code_bad_server_id
            PairingCodeProblem.BadToken -> R.string.code_bad_token
        }
    )

@Composable
fun failureText(failure: PairingFailure, serverUrl: String): String =
    when (failure) {
        PairingFailure.CodeRefused -> stringResource(R.string.pair_failed_code)
        PairingFailure.WrongAddress -> stringResource(R.string.pair_failed_address)
        PairingFailure.CertificateRejected -> stringResource(R.string.pair_failed_certificate)
        PairingFailure.CleartextBlocked -> stringResource(R.string.pair_failed_cleartext)
        PairingFailure.Unreachable -> stringResource(R.string.pair_failed_unreachable, serverUrl)
        PairingFailure.BadResponse -> stringResource(R.string.pair_failed_response)
        PairingFailure.Storage -> stringResource(R.string.pair_failed_storage)
        PairingFailure.Other -> stringResource(R.string.pair_failed_other)
    }

@Composable
fun labelProblemText(problem: LabelProblem): String =
    stringResource(
        when (problem) {
            LabelProblem.Blank -> R.string.rename_blank
            LabelProblem.TooLong -> R.string.rename_too_long
        }
    )

@Composable
fun messageText(message: ConnectionMessage): String =
    when (message) {
        is ConnectionMessage.Paired -> stringResource(R.string.message_paired, message.label)
        is ConnectionMessage.Disconnected ->
            stringResource(R.string.message_disconnected, message.label)
        is ConnectionMessage.Removed -> stringResource(R.string.message_removed, message.label)
        is ConnectionMessage.Renamed -> stringResource(R.string.message_renamed, message.label)
    }

@Composable
fun formatInstant(instant: Instant): String =
    remember(instant) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }

/** Shows [message] in a snackbar once, then reports it shown. */
@Composable
fun MessageEffect(message: ConnectionMessage?, host: SnackbarHostState, onShown: () -> Unit) {
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            host.showSnackbar(text)
            onShown()
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.testTag(ConnectionsTags.BACK)) {
        Icon(
            painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.back),
        )
    }
}
