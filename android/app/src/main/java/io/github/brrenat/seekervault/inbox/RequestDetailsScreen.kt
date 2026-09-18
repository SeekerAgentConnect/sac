package io.github.brrenat.seekervault.inbox

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.connections.outcomeText
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.request.v1.Action
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.Asset
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.transactions.ASSOCIATED_TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.COMPUTE_BUDGET_PROGRAM
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_2022_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.TransferFacts
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.seekerListItemColors
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.encodeBase58
import io.github.brrenat.seekervault.wallet.networkText
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Request details: who asked, what, and until when. A pending acknowledgement offers Acknowledge
 * and Reject, and a message to sign offers Approve and Reject; once answered, the screen shows the
 * stored outcome instead of the buttons.
 *
 * A message is shown complete, with every invisible character marked, together with the wallet and
 * network that would sign it (docs/guides/message-signing.md). A transfer is shown as this phone
 * read its transaction, with Approve offered only for one the phone could account for whole
 * (docs/guides/transfers.md). Nothing reaches the wallet until the owner taps Approve.
 *
 * Under the facts, and never in place of them, is what the owner's own rules made of the request
 * (SAW-028). It is advisory: a request outside the rules can still be answered, once the owner says
 * they mean to, and one that matches them is no nearer approved than any other. What the rules say
 * never restores an Approve button that input validation took away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDetailsScreen(
    request: ActionRequest,
    source: Connection?,
    result: LocalResult?,
    sending: Boolean,
    now: Instant,
    onAnswer: (Answer) -> Unit,
    onApprove: () -> Unit,
    onSendAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The wallet that would sign a message; null when none is connected. */
    wallet: SelectedWallet? = null,
    /** Why the last approval didn't reach the wallet. */
    signingProblem: SigningProblem? = null,
    /** For a transfer: the prepared transaction and what this phone made of it (SAW-020). */
    preparation: Preparation? = null,
    /** Asks the sidecar to build a new version and reads that one instead. */
    onPrepareAgain: () -> Unit = {},
    /** Approves the transfer as the screen shows it, which opens the wallet (SAW-021). */
    onApproveTransfer: () -> Unit = {},
    /** A status check is running: the server is reading the chain (SAW-022). */
    checking: Boolean = false,
    /** Asks the server what became of a sent transaction. It opens no wallet and sends nothing. */
    onCheckStatus: () -> Unit = {},
    /** What the owner's rules make of this request; null until they have been read (SAW-028). */
    assessment: RequestAssessment? = null,
    /** Whether the owner has said they want to go ahead past the warnings above. */
    acknowledged: Boolean = false,
    onAcknowledge: (Boolean) -> Unit = {},
    onRules: (() -> Unit)? = null,
    /**
     * Whether this build supports the request's server well enough to act for it (SEE-88). When it
     * doesn't, the request is still read in full and can still be rejected, and the affirmative
     * answer is not offered: there is nothing on this phone that would carry the operation out, and
     * no wallet is opened (docs/wiki/server-manifests.md#viewing-without-executing). It is not a
     * warning to overrule, so there is no tick beside it.
     */
    executable: Boolean = true,
) {
    // Warnings are the owner's to overrule, and overruling one is something they say they are
    // doing. Having no rules at all is not a warning: it would be one on every request there is.
    val warns = assessment?.decision?.warns == true
    if (request.transfer() != null) {
        TransferRequestReview(
            request = request,
            source = source,
            result = result,
            sending = sending,
            now = now,
            onAnswer = onAnswer,
            onSendAgain = onSendAgain,
            onBack = onBack,
            modifier = modifier,
            wallet = wallet,
            signingProblem = signingProblem,
            preparation = preparation,
            onPrepareAgain = onPrepareAgain,
            onApprove = onApproveTransfer,
            checking = checking,
            onCheckStatus = onCheckStatus,
            assessment = assessment,
            warns = warns,
            acknowledged = acknowledged,
            onAcknowledge = onAcknowledge,
            onRules = onRules,
            executable = executable,
        )
        return
    }
    if (
        request.action.kindCase == Action.KindCase.ACK ||
            request.action.kindCase == Action.KindCase.SIGN_MESSAGE
    ) {
        SimpleRequestReview(
            request = request,
            source = source,
            result = result,
            sending = sending,
            now = now,
            onAnswer = onAnswer,
            onApprove = onApprove,
            onSendAgain = onSendAgain,
            onBack = onBack,
            modifier = modifier,
            wallet = wallet,
            signingProblem = signingProblem,
            assessment = assessment,
            warns = warns,
            acknowledged = acknowledged,
            onAcknowledge = onAcknowledge,
            onRules = onRules,
            executable = executable,
        )
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                actions = { CloseButton(onBack) },
                expandedHeight = SeekerTheme.dimensions.dp56,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            val problem =
                result != null &&
                    (result.delivery == Delivery.Superseded ||
                        result.delivery == Delivery.Undeliverable)
            Text(
                statusText(request, result, sending, now),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (problem) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.STATUS),
            )
            if (result?.delivery == Delivery.Waiting && result.lastFailure != null && !sending) {
                Text(
                    stringResource(R.string.status_last_failure, outcomeText(result.lastFailure)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp16),
                )
            }
            if (sending || checking) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth()
                        .padding(SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.SENDING),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            // What the server read from the chain, and whose word that is (SAW-022).
            result?.let { answered ->
                confirmationText(answered)?.let { checked ->
                    Text(
                        "$checked ${stringResource(R.string.confirmation_trust)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.CONFIRMATION),
                    )
                }
            }
            Field(
                R.string.request_field_from,
                source?.let { "${it.label} (${PairingCodes.hostOf(it.serverUrl)})" }
                    ?: request.ref.connectionId,
                "from",
            )
            Field(R.string.request_field_action, actionText(request), "action")
            request.text()?.let { text ->
                // The agent's text, verbatim and as plain text: never parsed, linked, or executed.
                ListItem(
                    overlineContent = { Text(stringResource(R.string.request_field_message)) },
                    headlineContent = {
                        Text(text, modifier = Modifier.testTag(InboxTags.MESSAGE))
                    },
                    colors = seekerListItemColors(),
                )
            }
            val message = messagePreview(request)
            if (message != null) {
                // The complete message the wallet would sign, with nothing hidden in it.
                ListItem(
                    overlineContent = { Text(stringResource(R.string.request_field_message)) },
                    headlineContent = {
                        Text(message.display, modifier = Modifier.testTag(InboxTags.MESSAGE))
                    },
                    colors = seekerListItemColors(),
                )
                Field(
                    R.string.request_field_encoding,
                    pluralStringResource(
                        if (message.isText) R.plurals.message_text_bytes
                        else R.plurals.message_data_bytes,
                        message.bytes,
                        message.bytes,
                    ),
                    "encoding",
                )
                if (message.hasHidden) {
                    Text(
                        stringResource(R.string.message_hidden_characters),
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.HIDDEN),
                    )
                }
                Field(
                    R.string.request_field_signs_with,
                    wallet?.let {
                        stringResource(
                            R.string.signing_wallet,
                            it.address,
                            networkText(it.network),
                        )
                    } ?: request.signMessage()?.wallet.orEmpty(),
                    "signsWith",
                )
                Text(
                    stringResource(R.string.message_not_a_payment),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                            .testTag(InboxTags.NOT_A_PAYMENT),
                )
                if (wallet == null && result == null) {
                    Text(
                        stringResource(R.string.message_no_wallet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
                if (signingProblem != null) {
                    Text(
                        stringResource(problemText(signingProblem)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
            }
            val transfer = request.transfer()
            if (transfer != null) {
                TransferReview(
                    preparation = preparation,
                    wallet = wallet,
                    request = request,
                    answered = result != null,
                    sending = sending,
                    signingProblem = signingProblem,
                    onPrepareAgain = onPrepareAgain,
                    onApprove = onApproveTransfer,
                    assessment = assessment,
                    warns = warns,
                    acknowledged = acknowledged,
                    onAcknowledge = onAcknowledge,
                    onRules = onRules,
                    executable = executable,
                )
            } else {
                // A transfer's review has its own place for this, under the facts and above the
                // button. Everything else has nothing between the two.
                PolicyReview(assessment, onRules = onRules)
            }
            if (request.agentNote.isNotEmpty()) {
                // Shown apart from the request itself: the agent wrote it, and nothing checked it.
                ListItem(
                    overlineContent = { Text(stringResource(R.string.request_field_note)) },
                    headlineContent = { Text(request.agentNote) },
                    modifier = Modifier.testTag(InboxTags.NOTE),
                    colors = seekerListItemColors(),
                )
            }
            val created = request.createdAt.instant()
            val expires = request.expiresAt.instant()
            Field(
                R.string.request_field_created,
                "${formatInstant(created)} (${relativeTime(created, now)})",
                "created",
            )
            Field(
                R.string.request_field_expires,
                "${formatInstant(expires)} (${relativeTime(expires, now)})",
                "expires",
            )
            Field(R.string.request_field_id, request.ref.requestId, "requestId")
            if (canAnswer(request, result, now)) {
                if (!executable) UnsupportedServer()
                // A transfer's tick sits with its own Approve button, in the review above.
                if (transfer == null && warns && executable) {
                    ApproveAnyway(acknowledged, onAcknowledge)
                }
                Row(
                    modifier = Modifier.padding(SeekerTheme.dimensions.dp16),
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                ) {
                    if (transfer == null && message == null) {
                        SeekerButton(
                            text =
                                stringResource(
                                    if (warns) R.string.acknowledge_despite_warnings
                                    else R.string.acknowledge
                                ),
                            onClick = { onAnswer(Answer.Acknowledge) },
                            enabled = !sending && executable && (!warns || acknowledged),
                            modifier = Modifier.testTag(InboxTags.ACKNOWLEDGE),
                        )
                    } else {
                        // Approve is the only thing that reaches the wallet, and only once the
                        // owner has a wallet connected to sign with.
                        SeekerButton(
                            text =
                                stringResource(
                                    if (warns) R.string.approve_despite_warnings
                                    else R.string.approve
                                ),
                            onClick = onApprove,
                            enabled =
                                !sending &&
                                    executable &&
                                    wallet != null &&
                                    (!warns || acknowledged),
                            modifier = Modifier.testTag(InboxTags.APPROVE),
                        )
                    }
                    SeekerButton(
                        text = stringResource(R.string.reject),
                        onClick = { onAnswer(Answer.Reject) },
                        enabled = !sending,
                        role = SeekerButtonRole.Neutral,
                        modifier = Modifier.testTag(InboxTags.REJECT),
                    )
                }
            }
            if (result?.delivery == Delivery.Waiting) {
                SeekerButton(
                    text = stringResource(R.string.send_again),
                    onClick = onSendAgain,
                    enabled = !sending,
                    role = SeekerButtonRole.Neutral,
                    modifier =
                        Modifier.padding(SeekerTheme.dimensions.dp16).testTag(InboxTags.SEND_AGAIN),
                )
            }
            // Only while the chain could still settle it. It asks the server and nothing else: no
            // wallet is opened, and the transaction is never sent a second time.
            if (result?.awaitingChain == true) {
                // A check that couldn't be made belongs here, next to the button that makes it.
                // Every other signing problem happens before an answer exists, and shows above.
                if (signingProblem != null) {
                    Text(
                        stringResource(problemText(signingProblem)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
                SeekerButton(
                    text = stringResource(R.string.check_status),
                    onClick = onCheckStatus,
                    enabled = !checking,
                    role = SeekerButtonRole.Neutral,
                    modifier =
                        Modifier.padding(SeekerTheme.dimensions.dp16)
                            .testTag(InboxTags.CHECK_STATUS),
                )
            }
        }
    }
}

/** The v4 review sheet for acknowledgements and message signatures. */
@Composable
private fun SimpleRequestReview(
    request: ActionRequest,
    source: Connection?,
    result: LocalResult?,
    sending: Boolean,
    now: Instant,
    onAnswer: (Answer) -> Unit,
    onApprove: () -> Unit,
    onSendAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    wallet: SelectedWallet?,
    signingProblem: SigningProblem?,
    assessment: RequestAssessment?,
    warns: Boolean,
    acknowledged: Boolean,
    onAcknowledge: (Boolean) -> Unit,
    onRules: (() -> Unit)?,
    executable: Boolean,
) {
    val message = messagePreview(request)
    val acknowledgement = request.action.kindCase == Action.KindCase.ACK
    val sourceLabel = source?.label ?: request.ref.connectionId
    val walletAddress = wallet?.address ?: request.signMessage()?.wallet.orEmpty()
    val waiting = result == null && !sending && canAnswer(request, result, now)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .height(SeekerTheme.dimensions.dp56)
                .padding(start = SeekerTheme.dimensions.dp16, end = SeekerTheme.dimensions.dp8),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    if (acknowledgement) R.string.request_acknowledge
                    else R.string.request_signature
                ),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(
                        start = SeekerTheme.dimensions.dp16,
                        end = SeekerTheme.dimensions.dp16,
                        top = SeekerTheme.dimensions.dp4,
                    ),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
            ) {
                if (acknowledgement) {
                    Text(
                        request.text() ?: actionText(request),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.testTag(InboxTags.MESSAGE),
                    )
                    Identifier(
                        stringResource(R.string.request_source_asks, sourceLabel),
                        maxLines = 2,
                    )
                } else if (message != null) {
                    Text(
                        stringResource(R.string.request_bytes, message.bytes),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.testTag(InboxTags.field("encoding")),
                    )
                    Identifier(
                        message.display,
                        modifier = Modifier.testTag(InboxTags.MESSAGE),
                        maxLines = Int.MAX_VALUE,
                    )
                }
            }
            if (!waiting) {
                SeekerCard(
                    Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    radius = SeekerTheme.dimensions.dp16,
                ) {
                    Column(
                        Modifier.padding(SeekerTheme.dimensions.dp16),
                        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                    ) {
                        Text(
                            statusText(request, result, sending, now),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(InboxTags.STATUS),
                        )
                        if (
                            result?.delivery == Delivery.Waiting &&
                                result.lastFailure != null &&
                                !sending
                        ) {
                            Text(
                                stringResource(
                                    R.string.status_last_failure,
                                    outcomeText(result.lastFailure),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (sending) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.SENDING),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            // A message signature's byte-derived facts outrank advisory rules, so the owner reads
            // them first. An acknowledgement follows the v4 reference with its verdict first.
            if (!acknowledgement) {
                SimpleRequestFacts(request, source, sourceLabel, wallet, walletAddress, message)
            }
            PolicyReview(assessment, onRules = onRules)
            if (acknowledgement) {
                SimpleRequestFacts(request, source, sourceLabel, wallet, walletAddress, message)
            }
            if (request.agentNote.isNotEmpty()) {
                SeekerCard(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.NOTE)
                        .semantics(mergeDescendants = true) {},
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    radius = SeekerTheme.dimensions.dp16,
                ) {
                    Column(
                        Modifier.padding(SeekerTheme.dimensions.dp16),
                        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp4),
                    ) {
                        Text(
                            stringResource(R.string.request_field_note_v4),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(request.agentNote, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (signingProblem != null) {
                SeekerCard(
                    Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
                    color = MaterialTheme.colorScheme.errorContainer,
                    radius = SeekerTheme.dimensions.dp16,
                ) {
                    Text(
                        stringResource(problemText(signingProblem)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier =
                            Modifier.padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
            } else if (!acknowledgement && wallet == null && result == null) {
                SeekerCard(
                    Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
                    color = MaterialTheme.colorScheme.errorContainer,
                    radius = SeekerTheme.dimensions.dp16,
                ) {
                    Text(
                        stringResource(R.string.message_no_wallet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier =
                            Modifier.padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
            }
            Text(
                stringResource(
                    R.string.request_expires_v4,
                    relativeTime(request.expiresAt.instant(), now),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp20),
            )
            Spacer(Modifier.height(SeekerTheme.dimensions.dp12))
        }
        if (canAnswer(request, result, now)) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(
                        start = SeekerTheme.dimensions.dp16,
                        end = SeekerTheme.dimensions.dp16,
                        top = SeekerTheme.dimensions.dp12,
                        bottom = SeekerTheme.dimensions.dp20,
                    ),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
            ) {
                if (!executable) UnsupportedServer()
                if (warns && executable) ApproveAnyway(acknowledged, onAcknowledge)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                ) {
                    SeekerButton(
                        text =
                            stringResource(
                                when {
                                    warns && acknowledgement ->
                                        R.string.acknowledge_despite_warnings
                                    warns -> R.string.approve_despite_warnings
                                    acknowledgement -> R.string.acknowledge
                                    else -> R.string.approve
                                }
                            ),
                        onClick = {
                            if (acknowledgement) onAnswer(Answer.Acknowledge) else onApprove()
                        },
                        enabled =
                            !sending &&
                                executable &&
                                (acknowledgement || wallet != null) &&
                                (!warns || acknowledged),
                        modifier =
                            Modifier.weight(1f)
                                .testTag(
                                    if (acknowledgement) InboxTags.ACKNOWLEDGE
                                    else InboxTags.APPROVE
                                ),
                    )
                    SeekerButton(
                        text = stringResource(R.string.reject),
                        onClick = { onAnswer(Answer.Reject) },
                        enabled = !sending,
                        role = SeekerButtonRole.Neutral,
                        modifier = Modifier.testTag(InboxTags.REJECT),
                    )
                }
            }
        } else if (result?.delivery == Delivery.Waiting) {
            SeekerButton(
                text = stringResource(R.string.send_again),
                onClick = onSendAgain,
                enabled = !sending,
                role = SeekerButtonRole.Neutral,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = SeekerTheme.dimensions.dp16,
                            end = SeekerTheme.dimensions.dp16,
                            top = SeekerTheme.dimensions.dp12,
                            bottom = SeekerTheme.dimensions.dp20,
                        )
                        .testTag(InboxTags.SEND_AGAIN),
            )
        }
    }
}

@Composable
private fun SimpleRequestFacts(
    request: ActionRequest,
    source: Connection?,
    sourceLabel: String,
    wallet: SelectedWallet?,
    walletAddress: String,
    message: MessagePreview?,
) {
    val acknowledgement = request.action.kindCase == Action.KindCase.ACK
    SeekerCard(
        Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
        color = MaterialTheme.colorScheme.surfaceContainer,
        radius = SeekerTheme.dimensions.dp16,
    ) {
        Text(
            stringResource(
                if (acknowledgement) R.string.request_acknowledgement_check
                else R.string.message_not_a_payment
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(SeekerTheme.dimensions.dp16)
                    .then(
                        if (acknowledgement) Modifier else Modifier.testTag(InboxTags.NOT_A_PAYMENT)
                    ),
        )
    }
    RequestFact(stringResource(R.string.request_field_from), sourceLabel, "from")
    source?.let {
        RequestFact(
            stringResource(R.string.field_server),
            it.serverUrl,
            "server",
            mono = true,
            shorten = false,
        )
    }
    RequestFact(
        stringResource(R.string.field_connection_id),
        request.ref.connectionId,
        "connectionId",
        mono = true,
        shorten = false,
    )
    RequestFact(
        stringResource(R.string.request_field_id),
        request.ref.requestId,
        "requestId",
        mono = true,
        shorten = false,
    )
    RequestFact(
        stringResource(R.string.wallet_title),
        walletAddress.ifBlank { stringResource(R.string.network_none) },
        "signsWith",
        mono = true,
    )
    RequestFact(
        stringResource(R.string.request_fact_network),
        wallet?.let { networkText(it.network) } ?: stringResource(R.string.network_none),
        "network",
    )
    if (message?.hasHidden == true) {
        Text(
            stringResource(R.string.message_hidden_characters),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(horizontal = SeekerTheme.dimensions.dp20)
                    .testTag(InboxTags.HIDDEN),
        )
    }
}

@Composable
private fun RequestFact(
    label: String,
    value: String,
    name: String,
    mono: Boolean = false,
    shorten: Boolean = mono,
) {
    SeekerCard(
        Modifier.fillMaxWidth()
            .padding(horizontal = SeekerTheme.dimensions.dp16)
            .testTag(InboxTags.field(name))
            .semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.surfaceContainer,
        radius = SeekerTheme.dimensions.dp16,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = SeekerTheme.dimensions.dp16,
                    vertical = SeekerTheme.dimensions.dp13,
                ),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp16),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mono) {
                Identifier(
                    if (shorten) shortIdentifier(value) else value,
                    modifier = Modifier.weight(1f),
                    maxLines = if (shorten) 1 else 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            } else {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun shortIdentifier(value: String): String =
    if (value.length <= 16) value else "${value.take(9)}…${value.takeLast(7)}"

@Composable
private fun transferPrimaryAmount(
    facts: TransferFacts?,
    transfer: io.github.brrenat.seekervault.request.v1.TransferAction,
): String {
    if (facts != null) {
        return stringResource(
            if (facts.mint == null) R.string.transfer_headline_sol
            else R.string.transfer_headline_token,
            formatBaseUnits(facts.amount, facts.decimals),
        )
    }
    val amount = transfer.amount.toULongOrNull()
    return if (transfer.asset.kindCase == Asset.KindCase.NATIVE_SOL && amount != null) {
        stringResource(
            R.string.transfer_headline_sol,
            formatBaseUnits(amount, LAMPORT_DECIMALS),
        )
    } else {
        stringResource(R.string.transfer_headline_token, transfer.amount)
    }
}

@Composable
private fun TransferStatusBlock(
    request: ActionRequest,
    result: LocalResult?,
    sending: Boolean,
    checking: Boolean,
    now: Instant,
    amount: String,
    ready: Boolean,
) {
    if (!ready && result == null && !sending) return
    val sent = result?.signing as? SigningOutcome.Sent
    val state = result?.request?.state ?: request.state
    val confirmed = sent != null && state == RequestState.REQUEST_STATE_CONFIRMED
    val failedOnNetwork = sent != null && state == RequestState.REQUEST_STATE_FAILED
    val title: String
    val detail: String?
    val supporting: String?
    val icon =
        when {
            sending -> Icons.Outlined.HourglassTop
            confirmed -> Icons.Outlined.CheckCircle
            failedOnNetwork -> Icons.Outlined.WarningAmber
            sent != null && checking -> Icons.Outlined.Sync
            sent != null -> Icons.AutoMirrored.Outlined.Send
            else -> Icons.Outlined.CheckCircle
        }
    when {
        sending -> {
            title = stringResource(R.string.transfer_status_signing)
            detail = stringResource(R.string.transfer_status_signing_detail)
            supporting = null
        }
        confirmed -> {
            title = stringResource(R.string.transfer_status_confirmed)
            detail = stringResource(R.string.transfer_status_confirmed_detail, amount)
            supporting = null
        }
        failedOnNetwork -> {
            title = stringResource(R.string.transfer_status_failed)
            detail =
                result.request.outcome.detail.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.transfer_status_failed_detail)
            supporting = null
        }
        sent != null && checking -> {
            title = stringResource(R.string.transfer_status_confirming)
            detail = stringResource(R.string.transfer_status_confirming_detail)
            supporting = null
        }
        sent != null -> {
            title = stringResource(R.string.transfer_status_sent)
            detail = stringResource(R.string.transfer_status_sent_detail, amount)
            supporting = stringResource(R.string.transfer_status_waiting_confirmation)
        }
        result != null -> {
            title = statusText(request, result, sending = false, now = now)
            detail = null
            supporting = null
        }
        else -> {
            title = stringResource(R.string.transfer_status_ready)
            detail = stringResource(R.string.transfer_status_ready_detail)
            supporting = null
        }
    }
    val container =
        when {
            confirmed -> MaterialTheme.colorScheme.primaryContainer
            failedOnNetwork -> MaterialTheme.colorScheme.errorContainer
            sent != null || sending -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainer
        }
    val ink =
        when {
            confirmed -> MaterialTheme.colorScheme.onPrimaryContainer
            failedOnNetwork -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
    SeekerCard(
        Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
        color = container,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(SeekerTheme.dimensions.dp22),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    modifier = Modifier.testTag(InboxTags.STATUS),
                )
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    modifier =
                        if (sent != null) Modifier.testTag(InboxTags.CONFIRMATION) else Modifier,
                )
            }
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ink)
            }
            sent?.let {
                TransactionId(encodeBase58(it.signature.toByteArray()), ink)
            }
            if (result?.delivery == Delivery.Waiting && result.lastFailure != null && !sending) {
                Text(
                    stringResource(
                        R.string.status_last_failure,
                        outcomeText(result.lastFailure),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink,
                )
            }
        }
    }
}

@Composable
private fun TransactionId(id: String, ink: androidx.compose.ui.graphics.Color) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp3)) {
        Text(
            stringResource(R.string.transfer_transaction_id),
            style = MaterialTheme.typography.labelSmall,
            color = ink,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
        ) {
            Identifier(
                shortIdentifier(id),
                modifier = Modifier.weight(1f).testTag(InboxTags.TRANSACTION_ID),
            )
            Box(
                Modifier.size(SeekerTheme.dimensions.dp40)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        role = Role.Button,
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("Transaction ID", id))
                                )
                                copied = true
                                delay(1_600)
                                copied = false
                            }
                        },
                    )
                    .testTag(InboxTags.TRANSACTION_COPY),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription =
                        stringResource(
                            if (copied) R.string.transfer_transaction_id_copied
                            else R.string.transfer_copy_transaction_id
                        ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(SeekerTheme.dimensions.dp20),
                )
            }
        }
    }
}

@Composable
private fun TransactionSummary(
    approved: Boolean,
    amount: String,
    recipient: String,
    sender: String,
    network: String,
    prepared: PreparedTransaction?,
) {
    SeekerCard(
        Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
        ) {
            Text(
                stringResource(
                    if (approved) R.string.transfer_summary_approved_title
                    else R.string.transfer_summary_title
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier.testTag(InboxTags.field("sends")).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp2),
            ) {
                Text(
                    stringResource(R.string.request_field_sends),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(amount, style = MaterialTheme.typography.headlineSmall)
            }
            SummaryRow(stringResource(R.string.request_field_to), recipient, "to", mono = true)
            SummaryRow(
                stringResource(R.string.request_field_from_wallet),
                sender,
                "signsWith",
                mono = true,
            )
            SummaryRow(stringResource(R.string.request_fact_network), network, "network")
            SummaryRow(
                stringResource(R.string.request_field_network_fee),
                prepared?.let {
                    stringResource(
                        R.string.transfer_fee_value,
                        formatBaseUnits(it.feeLamports.toULong(), LAMPORT_DECIMALS),
                    )
                } ?: stringResource(R.string.transfer_value_unavailable),
                "estimate",
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, name: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().testTag(InboxTags.field(name)).semantics(
            mergeDescendants = true
        ) {},
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp16),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (mono) {
            Identifier(
                shortIdentifier(value),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        } else {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DeviceVerification(verdict: Verdict) {
    val verified = verdict == Verdict.Verified
    val container =
        if (verified) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.errorContainer
    val ink =
        if (verified) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onErrorContainer
    SeekerCard(
        Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
        color = container,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (verified) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = if (verified) SeekerTheme.colors.primaryText else ink,
                modifier = Modifier.size(SeekerTheme.dimensions.dp22),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp4),
            ) {
                Text(
                    stringResource(
                        when (verdict) {
                            Verdict.Verified -> R.string.transfer_verdict_verified
                            Verdict.Unverified -> R.string.transfer_verdict_unverified_title
                            Verdict.Invalid -> R.string.transfer_verdict_invalid_title
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    modifier = Modifier.testTag(InboxTags.TRANSFER_VERDICT),
                )
                Text(
                    stringResource(
                        if (verified) R.string.transfer_verdict_verified_detail
                        else verdictText(verdict)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    modifier = Modifier.testTag(InboxTags.TRANSFER_DERIVED),
                )
            }
        }
    }
}

private data class ProgramInfo(@StringRes val name: Int, @StringRes val purpose: Int)

private const val JUPITER_PROGRAM = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"

private fun programInfo(program: String): ProgramInfo =
    when (program) {
        SYSTEM_PROGRAM ->
            ProgramInfo(R.string.transfer_program_system, R.string.transfer_program_system_purpose)
        TOKEN_PROGRAM ->
            ProgramInfo(R.string.transfer_program_token, R.string.transfer_program_token_purpose)
        TOKEN_2022_PROGRAM ->
            ProgramInfo(
                R.string.transfer_program_token_2022,
                R.string.transfer_program_token_purpose,
            )
        ASSOCIATED_TOKEN_PROGRAM ->
            ProgramInfo(
                R.string.transfer_program_associated_token,
                R.string.transfer_program_associated_token_purpose,
            )
        COMPUTE_BUDGET_PROGRAM ->
            ProgramInfo(
                R.string.transfer_program_compute_budget,
                R.string.transfer_program_compute_budget_purpose,
            )
        JUPITER_PROGRAM ->
            ProgramInfo(
                R.string.transfer_program_jupiter,
                R.string.transfer_program_jupiter_purpose,
            )
        else ->
            ProgramInfo(
                R.string.transfer_program_unknown,
                R.string.transfer_program_unknown_purpose,
            )
    }

@Composable
private fun ProgramSummary(programs: List<String>) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = SeekerTheme.dimensions.dp16)
            .testTag(InboxTags.field("programs"))
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
    ) {
        Text(
            pluralStringResource(R.plurals.transfer_programs_used, programs.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp4),
        )
        programs.forEach { program ->
            val info = programInfo(program)
            SeekerCard(
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
                    verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp3),
                ) {
                    Text(stringResource(info.name), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(info.purpose),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Identifier(program, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun TransferTechnicalDetails(
    facts: TransferFacts?,
    transfer: io.github.brrenat.seekervault.request.v1.TransferAction,
    prepared: PreparedTransaction?,
    result: LocalResult?,
    source: Connection?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sent = result?.signing as? SigningOutcome.Sent
    SeekerCard(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = SeekerTheme.dimensions.dp16)
                .testTag(InboxTags.TECHNICAL_DETAILS),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
            ) {
                Text(
                    stringResource(R.string.transfer_technical_details),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.transfer_hide_technical_details
                            else R.string.transfer_show_technical_details
                        ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(top = SeekerTheme.dimensions.dp14)
                        .testTag(InboxTags.TECHNICAL_DETAILS_CONTENT),
                    verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
                ) {
                    TechnicalValue(
                        stringResource(R.string.transfer_technical_sender),
                        facts?.payer ?: transfer.wallet,
                    )
                    TechnicalValue(
                        stringResource(R.string.transfer_technical_recipient),
                        facts?.recipient ?: transfer.recipient,
                    )
                    facts?.destinationAccount?.let {
                        TechnicalValue(stringResource(R.string.request_field_token_account), it)
                    }
                    facts?.mint?.let {
                        TechnicalValue(stringResource(R.string.request_field_token), it)
                    }
                    facts
                        ?.programs
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            TechnicalValue(
                                stringResource(R.string.transfer_technical_program_ids),
                                it.joinToString("\n"),
                            )
                        }
                    facts?.let {
                        TechnicalValue(
                            stringResource(R.string.transfer_technical_instruction_count),
                            stringResource(
                                R.string.transfer_instructions_read,
                                it.recognizedInstructions,
                                it.instructionCount,
                            ),
                        )
                        TechnicalValue(
                            stringResource(R.string.request_field_blockhash),
                            it.blockhash,
                        )
                        TechnicalValue(
                            stringResource(
                                if (it.mint == null) {
                                    R.string.transfer_technical_amount_lamports
                                } else {
                                    R.string.transfer_technical_amount_base_units
                                }
                            ),
                            it.amount.toString(),
                        )
                        if (it.ensuresRecipientAccount) {
                            TechnicalValue(
                                stringResource(R.string.request_field_creates),
                                stringResource(R.string.transfer_creates_account),
                            )
                        }
                        it.computeUnitPrice?.let { price ->
                            TechnicalValue(
                                stringResource(R.string.request_field_priority),
                                stringResource(R.string.transfer_priority_price, price.toString()),
                            )
                        }
                    }
                    prepared?.let {
                        TechnicalValue(
                            stringResource(R.string.transfer_technical_fee_lamports),
                            it.feeLamports.toULong().toString(),
                        )
                        if (it.rentLamports != 0L) {
                            TechnicalValue(
                                stringResource(R.string.transfer_technical_rent_lamports),
                                it.rentLamports.toULong().toString(),
                            )
                        }
                    }
                    sent?.let {
                        TechnicalValue(
                            stringResource(R.string.transfer_transaction_id),
                            encodeBase58(it.signature.toByteArray()),
                        )
                    }
                    result
                        ?.request
                        ?.outcome
                        ?.takeIf { it.hasConfirmation() }
                        ?.confirmation
                        ?.let {
                            TechnicalValue(
                                stringResource(R.string.transfer_technical_confirmation_source),
                                it.endpoint,
                            )
                            if (it.detail.isNotBlank()) {
                                TechnicalValue(
                                    stringResource(R.string.transfer_technical_network_result),
                                    it.detail,
                                )
                            }
                        }
                    source?.let {
                        TechnicalValue(
                            stringResource(R.string.request_field_from),
                            "${it.label} (${PairingCodes.hostOf(it.serverUrl)})",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TechnicalValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp3)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Identifier(value, maxLines = Int.MAX_VALUE)
    }
}

/** The v4 review sheet for a prepared transfer, without changing its verification or approval. */
@Composable
private fun TransferRequestReview(
    request: ActionRequest,
    source: Connection?,
    result: LocalResult?,
    sending: Boolean,
    now: Instant,
    onAnswer: (Answer) -> Unit,
    onSendAgain: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    wallet: SelectedWallet?,
    signingProblem: SigningProblem?,
    preparation: Preparation?,
    onPrepareAgain: () -> Unit,
    onApprove: () -> Unit,
    checking: Boolean,
    onCheckStatus: () -> Unit,
    assessment: RequestAssessment?,
    warns: Boolean,
    acknowledged: Boolean,
    onAcknowledge: (Boolean) -> Unit,
    onRules: (() -> Unit)?,
    executable: Boolean,
) {
    val transfer = checkNotNull(request.transfer())
    val ready = preparation as? Preparation.Ready
    val facts = ready?.inspection?.facts
    val canAnswer = canAnswer(request, result, now)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .height(SeekerTheme.dimensions.dp56)
                .padding(start = SeekerTheme.dimensions.dp16, end = SeekerTheme.dimensions.dp8),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.request_transfer),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
        ) {
            val amount = transferPrimaryAmount(facts, transfer)
            TransferStatusBlock(
                request = request,
                result = result,
                sending = sending,
                checking = checking,
                now = now,
                amount = amount,
                ready = ready?.inspection?.approvable == true,
            )
            if (sending || checking) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.SENDING),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            TransactionSummary(
                approved = result?.answer == Answer.Approve,
                amount = amount,
                recipient = facts?.recipient ?: transfer.recipient,
                sender = facts?.payer ?: wallet?.address ?: transfer.wallet,
                network =
                    wallet?.let { networkText(it.network) }
                        ?: io.github.brrenat.seekervault.policy.networkText(transfer.network),
                prepared = ready?.prepared,
            )
            // Nothing is prepared for a server this build doesn't support, so there is no
            // transaction to show and none on the way: saying so is the whole of this block, and
            // it must not read as still loading (SEE-88).
            if (!executable) UnsupportedServer()
            else
                when (preparation) {
                    null,
                    Preparation.Running -> {
                        if (result == null) {
                            SeekerCard(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = SeekerTheme.dimensions.dp16),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Text(
                                    stringResource(R.string.transfer_checking),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(SeekerTheme.dimensions.dp16)
                                            .testTag(InboxTags.TRANSFER_CHECKING),
                                )
                            }
                        } else if (result.signing is SigningOutcome.Sent) {
                            DeviceVerification(Verdict.Verified)
                        }
                    }
                    is Preparation.Failed -> {
                        SeekerCard(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = SeekerTheme.dimensions.dp16),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                stringResource(
                                    R.string.transfer_failed,
                                    outcomeText(preparation.outcome),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(SeekerTheme.dimensions.dp16)
                                        .testTag(InboxTags.TRANSFER_FAILED),
                            )
                        }
                        SeekerButton(
                            text = stringResource(R.string.transfer_prepare_again),
                            onClick = onPrepareAgain,
                            role = SeekerButtonRole.Neutral,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = SeekerTheme.dimensions.dp16)
                                    .testTag(InboxTags.TRANSFER_AGAIN),
                        )
                    }
                    is Preparation.Ready -> {
                        val inspection = preparation.inspection
                        DeviceVerification(inspection.verdict)
                        if (inspection.findings.isNotEmpty()) {
                            SeekerCard(
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = SeekerTheme.dimensions.dp16)
                                    .testTag(InboxTags.TRANSFER_FINDINGS)
                                    .semantics(mergeDescendants = true) {},
                                color = MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
                                    verticalArrangement =
                                        Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                                ) {
                                    inspection.findings.forEach {
                                        Text(
                                            stringResource(findingText(it)),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            facts?.programs?.takeIf { it.isNotEmpty() }?.let { ProgramSummary(it) }
            // Device verification and byte-derived facts remain above this advisory layer.
            PolicyReview(assessment, onRules = onRules, transaction = true)
            TransferTechnicalDetails(
                facts = facts,
                transfer = transfer,
                prepared = ready?.prepared,
                result = result,
                source = source,
            )
            if (ready != null) {
                SeekerButton(
                    text = stringResource(R.string.transfer_prepare_again),
                    onClick = onPrepareAgain,
                    enabled = !sending,
                    role = SeekerButtonRole.Neutral,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = SeekerTheme.dimensions.dp16)
                            .testTag(InboxTags.TRANSFER_AGAIN),
                )
                if (!ready.inspection.approvable && result == null) {
                    SeekerCard(
                        Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            stringResource(R.string.transfer_not_approvable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(SeekerTheme.dimensions.dp16)
                                    .testTag(InboxTags.TRANSFER_NOT_APPROVABLE),
                        )
                    }
                }
            }
            if (request.agentNote.isNotEmpty()) {
                SeekerCard(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.NOTE)
                        .semantics(mergeDescendants = true) {},
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        Modifier.padding(SeekerTheme.dimensions.dp16),
                        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp4),
                    ) {
                        Text(
                            stringResource(R.string.request_field_note_v4),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(request.agentNote, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (signingProblem != null) {
                SeekerCard(
                    Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        stringResource(problemText(signingProblem)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
            } else if (ready?.inspection?.approvable == true && wallet == null && result == null) {
                SeekerCard(
                    Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.dimensions.dp16),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        stringResource(R.string.transfer_no_wallet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
            }
            Text(
                stringResource(
                    R.string.request_expires_v4,
                    relativeTime(request.expiresAt.instant(), now),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SeekerTheme.dimensions.dp20),
            )
            Spacer(Modifier.height(SeekerTheme.dimensions.dp12))
        }
        if (canAnswer) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(
                        start = SeekerTheme.dimensions.dp16,
                        end = SeekerTheme.dimensions.dp16,
                        top = SeekerTheme.dimensions.dp12,
                        bottom = SeekerTheme.dimensions.dp20,
                    ),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
            ) {
                // A preparation read before the server's manifest changed under it is not a
                // way past the gate either: both have to hold for an Approve button to exist.
                val approvable = executable && ready?.inspection?.approvable == true
                if (warns && approvable) ApproveAnyway(acknowledged, onAcknowledge)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                ) {
                    if (approvable) {
                        SeekerButton(
                            text =
                                stringResource(
                                    if (warns) R.string.approve_and_send_despite_warnings
                                    else R.string.approve_and_send
                                ),
                            onClick = onApprove,
                            enabled = !sending && wallet != null && (!warns || acknowledged),
                            modifier = Modifier.weight(1f).testTag(InboxTags.TRANSFER_APPROVE),
                        )
                    }
                    SeekerButton(
                        text = stringResource(R.string.reject),
                        onClick = { onAnswer(Answer.Reject) },
                        enabled = !sending,
                        role = SeekerButtonRole.Neutral,
                        modifier =
                            Modifier.then(if (approvable) Modifier else Modifier.weight(1f))
                                .testTag(InboxTags.REJECT),
                    )
                }
            }
        } else if (result?.delivery == Delivery.Waiting) {
            SeekerButton(
                text = stringResource(R.string.send_again),
                onClick = onSendAgain,
                enabled = !sending,
                role = SeekerButtonRole.Neutral,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = SeekerTheme.dimensions.dp16,
                            end = SeekerTheme.dimensions.dp16,
                            top = SeekerTheme.dimensions.dp12,
                            bottom = SeekerTheme.dimensions.dp20,
                        )
                        .testTag(InboxTags.SEND_AGAIN),
            )
        } else if (result?.awaitingChain == true) {
            SeekerButton(
                text = stringResource(R.string.check_status),
                onClick = onCheckStatus,
                enabled = !checking,
                role = SeekerButtonRole.Neutral,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = SeekerTheme.dimensions.dp16,
                            end = SeekerTheme.dimensions.dp16,
                            top = SeekerTheme.dimensions.dp12,
                            bottom = SeekerTheme.dimensions.dp20,
                        )
                        .testTag(InboxTags.CHECK_STATUS),
            )
        }
    }
}

/**
 * What this phone read out of the transfer's own transaction, and the owner's decision about it
 * (docs/security.md#inspecting-a-transfer, docs/guides/transfers.md).
 *
 * The order on screen is the order of trust: the verdict first, then the facts the bytes establish,
 * then what could not be established, and only then the server's own numbers, labelled as theirs.
 * The owner's own rules come after all of it, because they are the weakest thing here: they are a
 * note to themselves, and no rule can put back an Approve button that this phone's own inspection
 * took away (docs/security.md#verification-versus-advisory-rules). The agent's note is rendered by
 * the caller, further down and marked unverified, so that nothing it says can sit next to a fact
 * and borrow its weight. Approve comes last, under everything it approves, and only for a
 * transaction this phone could account for whole.
 */
/**
 * Said in place of an approval when this build doesn't support the request's server (SEE-88).
 *
 * It is deliberately not phrased as a warning about the request. The request may be perfectly
 * ordinary; what is missing is on this phone, and no answer the owner could give would make this
 * build able to carry the operation out.
 */
@Composable
private fun UnsupportedServer() {
    Text(
        stringResource(R.string.server_unsupported),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(SeekerTheme.dimensions.dp16).testTag(InboxTags.SERVER_UNSUPPORTED),
    )
}

@Composable
private fun TransferReview(
    preparation: Preparation?,
    wallet: SelectedWallet?,
    request: ActionRequest,
    answered: Boolean,
    sending: Boolean,
    signingProblem: SigningProblem?,
    onPrepareAgain: () -> Unit,
    onApprove: () -> Unit,
    assessment: RequestAssessment?,
    warns: Boolean,
    acknowledged: Boolean,
    onAcknowledge: (Boolean) -> Unit,
    onRules: (() -> Unit)?,
    executable: Boolean,
) {
    // Nothing is prepared for a server this build doesn't support, so there is no transaction to
    // show and none on the way: saying so is the whole of this block (SEE-88).
    if (!executable) {
        UnsupportedServer()
        return
    }
    when (preparation) {
        null,
        Preparation.Running ->
            Text(
                stringResource(R.string.transfer_checking),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    Modifier.padding(SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.TRANSFER_CHECKING),
            )
        is Preparation.Failed -> {
            Text(
                stringResource(R.string.transfer_failed, outcomeText(preparation.outcome)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.padding(SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.TRANSFER_FAILED),
            )
            SeekerButton(
                text = stringResource(R.string.transfer_prepare_again),
                onClick = onPrepareAgain,
                role = SeekerButtonRole.Neutral,
                modifier =
                    Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.TRANSFER_AGAIN),
            )
        }
        is Preparation.Ready -> {
            val inspection = preparation.inspection
            Text(
                stringResource(verdictText(inspection.verdict)),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (inspection.verdict == Verdict.Verified) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.padding(SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.TRANSFER_VERDICT),
            )
            inspection.facts?.let { facts ->
                Field(R.string.request_field_sends, amountText(facts), "sends")
                Field(
                    R.string.request_field_to,
                    facts.recipient ?: stringResource(R.string.transfer_recipient_unknown),
                    "to",
                )
                facts.destinationAccount?.let {
                    Field(R.string.request_field_token_account, it, "tokenAccount")
                }
                facts.mint?.let { Field(R.string.request_field_token, it, "token") }
                Field(R.string.request_field_pays_fee, facts.payer, "paysFee")
                if (facts.ensuresRecipientAccount) {
                    Field(
                        R.string.request_field_creates,
                        stringResource(R.string.transfer_creates_account),
                        "creates",
                    )
                }
                facts.computeUnitPrice?.let {
                    Field(
                        R.string.request_field_priority,
                        stringResource(R.string.transfer_priority_price, it.toString()),
                        "priority",
                    )
                }
                // Every program the transaction calls, so that a request nothing could be
                // verified about still shows what it would run (SAW-028).
                facts.programs
                    .takeIf { it.isNotEmpty() }
                    ?.let { programs ->
                        Field(
                            R.string.request_field_programs,
                            programs.joinToString("\n"),
                            "programs",
                        )
                    }
                Field(
                    R.string.request_field_instructions,
                    stringResource(
                        R.string.transfer_instructions_read,
                        facts.recognizedInstructions,
                        facts.instructionCount,
                    ),
                    "instructions",
                )
                Field(R.string.request_field_blockhash, facts.blockhash, "blockhash")
                Text(
                    stringResource(R.string.transfer_derived_here),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                            .testTag(InboxTags.TRANSFER_DERIVED),
                )
            }
            if (inspection.findings.isNotEmpty()) {
                Column(
                    Modifier.padding(SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.TRANSFER_FINDINGS)
                ) {
                    inspection.findings.forEach { finding ->
                        Text(
                            stringResource(findingText(finding)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            // The wallet that would pay and sign, as this phone holds it now, so the owner
            // approves a wallet and a network they can see rather than one they last set.
            Field(
                R.string.request_field_signs_with,
                wallet?.let {
                    stringResource(R.string.signing_wallet, it.address, networkText(it.network))
                } ?: request.transfer()?.wallet.orEmpty(),
                "signsWith",
            )
            // The fee can't be read out of a transaction: it depends on the network. It is the
            // server's number, and it is labelled as one rather than mixed in with the facts.
            Field(
                R.string.request_field_estimate,
                estimateText(preparation.prepared),
                "estimate",
            )
            // The owner's own rules, under everything the phone established for itself. What
            // they say changes nothing above: a transaction that failed its own inspection has no
            // Approve button whatever the rules made of it, and this never says otherwise.
            PolicyReview(assessment, onRules = onRules)
            SeekerButton(
                text = stringResource(R.string.transfer_prepare_again),
                onClick = onPrepareAgain,
                enabled = !sending,
                role = SeekerButtonRole.Neutral,
                modifier =
                    Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                        .testTag(InboxTags.TRANSFER_AGAIN),
            )
            if (!answered) {
                if (inspection.approvable) {
                    // A warning is the owner's to overrule, and overruling it is a thing they say
                    // they are doing, next to the button that does it.
                    if (warns) ApproveAnyway(acknowledged, onAcknowledge)
                    // The only thing that opens the wallet, and only for a transaction this phone
                    // read whole and found to match the request.
                    SeekerButton(
                        text =
                            stringResource(
                                if (warns) R.string.approve_and_send_despite_warnings
                                else R.string.approve_and_send
                            ),
                        onClick = onApprove,
                        enabled = !sending && wallet != null && (!warns || acknowledged),
                        modifier =
                            Modifier.padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.TRANSFER_APPROVE),
                    )
                    if (wallet == null) {
                        Text(
                            stringResource(R.string.transfer_no_wallet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier.padding(horizontal = SeekerTheme.dimensions.dp16)
                                    .testTag(InboxTags.SIGNING_PROBLEM),
                        )
                    }
                } else {
                    // No button at all rather than one that refuses: nothing this phone could not
                    // account for is ever put in front of a wallet. This is input validation, and
                    // it is not a rule the owner could tick past — there is nothing to tick.
                    Text(
                        stringResource(R.string.transfer_not_approvable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.padding(SeekerTheme.dimensions.dp16)
                                .testTag(InboxTags.TRANSFER_NOT_APPROVABLE),
                    )
                }
            }
            if (signingProblem != null) {
                Text(
                    stringResource(problemText(signingProblem)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier.padding(SeekerTheme.dimensions.dp16)
                            .testTag(InboxTags.SIGNING_PROBLEM),
                )
            }
        }
    }
}

/** The request isn't known here: it's no longer pending, or the inbox hasn't been fetched yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestGoneScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                actions = { CloseButton(onBack) },
                expandedHeight = SeekerTheme.dimensions.dp56,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { innerPadding ->
        Text(
            stringResource(R.string.status_gone),
            modifier =
                Modifier.padding(innerPadding)
                    .padding(SeekerTheme.dimensions.dp16)
                    .testTag(InboxTags.GONE),
        )
    }
}

/**
 * A notification route does not expose review controls until its IDs have been resolved against
 * current sidecar state. Every message states what is known and, just as importantly, that the tap
 * did not approve or sign anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationRequestStateScreen(
    status: NotificationOpenStatus,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message =
        when (status) {
            NotificationOpenStatus.Loading -> R.string.notification_open_loading
            NotificationOpenStatus.Gone,
            NotificationOpenStatus.Current -> R.string.notification_open_gone
            NotificationOpenStatus.Removed -> R.string.notification_open_removed
            NotificationOpenStatus.Revoked -> R.string.notification_open_revoked
            NotificationOpenStatus.Unavailable -> R.string.notification_open_unavailable
        }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                actions = { CloseButton(onBack) },
                expandedHeight = SeekerTheme.dimensions.dp56,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp16),
        ) {
            if (status == NotificationOpenStatus.Loading) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().testTag(InboxTags.NOTIFICATION_LOADING),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
            Text(stringResource(message), modifier = Modifier.testTag(InboxTags.NOTIFICATION_STATE))
            if (
                status == NotificationOpenStatus.Gone ||
                    status == NotificationOpenStatus.Unavailable
            ) {
                SeekerButton(
                    text = stringResource(R.string.notification_open_retry),
                    onClick = onRetry,
                    automationTag = InboxTags.NOTIFICATION_RETRY,
                )
            }
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String, name: String) {
    SeekerCard(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = SeekerTheme.dimensions.dp16,
                    vertical = SeekerTheme.dimensions.dp5,
                )
                .testTag(InboxTags.field(name))
                .semantics(mergeDescendants = true) {}
    ) {
        Column(Modifier.padding(SeekerTheme.dimensions.dp14)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Identifier(value, Modifier.padding(top = SeekerTheme.dimensions.dp3), maxLines = 6)
        }
    }
}
