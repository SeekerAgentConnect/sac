package io.github.brrenat.seekervault.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.connections.outcomeText
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.networkText
import java.time.Instant

/**
 * Request details: who asked, what, and until when. A pending acknowledgement offers Acknowledge
 * and Reject, and a message to sign offers Approve and Reject; once answered, the screen shows the
 * stored outcome instead of the buttons.
 *
 * A message is shown complete, with every invisible character marked, together with the wallet and
 * network that would sign it (docs/guides/message-signing.md). A transfer is shown as this phone
 * read its transaction, with Approve offered only for one the phone could account for whole
 * (docs/guides/transfers.md). Nothing reaches the wallet until the owner taps Approve.
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
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                navigationIcon = { BackButton(onBack) },
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
                modifier = Modifier.padding(horizontal = 16.dp).testTag(InboxTags.STATUS),
            )
            if (result?.delivery == Delivery.Waiting && result.lastFailure != null && !sending) {
                Text(
                    stringResource(R.string.status_last_failure, outcomeText(result.lastFailure)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (sending || checking) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(16.dp).testTag(InboxTags.SENDING)
                )
            }
            // What the server read from the chain, and whose word that is (SAW-022).
            result?.let { answered ->
                confirmationText(answered)?.let { checked ->
                    Text(
                        "$checked ${stringResource(R.string.confirmation_trust)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier.padding(horizontal = 16.dp).testTag(InboxTags.CONFIRMATION),
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
                )
            }
            val message = messagePreview(request)
            if (message != null) {
                // The complete message the wallet would sign, with nothing hidden in it.
                ListItem(
                    overlineContent = { Text(stringResource(R.string.request_field_message)) },
                    headlineContent = {
                        Text(
                            message.display,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag(InboxTags.MESSAGE),
                        )
                    },
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
                        modifier = Modifier.padding(horizontal = 16.dp).testTag(InboxTags.HIDDEN),
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
                        Modifier.padding(horizontal = 16.dp).testTag(InboxTags.NOT_A_PAYMENT),
                )
                if (wallet == null && result == null) {
                    Text(
                        stringResource(R.string.message_no_wallet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
                if (signingProblem != null) {
                    Text(
                        stringResource(problemText(signingProblem)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).testTag(InboxTags.SIGNING_PROBLEM),
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
                )
            }
            if (request.agentNote.isNotEmpty()) {
                // Shown apart from the request itself: the agent wrote it, and nothing checked it.
                ListItem(
                    overlineContent = { Text(stringResource(R.string.request_field_note)) },
                    headlineContent = { Text(request.agentNote) },
                    modifier = Modifier.testTag(InboxTags.NOTE),
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
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (transfer != null) {
                        // A transfer's Approve sits in the review above, next to the facts it
                        // approves. Only Reject belongs down here with the other answers.
                        Unit
                    } else if (message == null) {
                        Button(
                            onClick = { onAnswer(Answer.Acknowledge) },
                            enabled = !sending,
                            modifier = Modifier.testTag(InboxTags.ACKNOWLEDGE),
                        ) {
                            Text(stringResource(R.string.acknowledge))
                        }
                    } else {
                        // Approve is the only thing that reaches the wallet, and only once the
                        // owner has a wallet connected to sign with.
                        Button(
                            onClick = onApprove,
                            enabled = !sending && wallet != null,
                            modifier = Modifier.testTag(InboxTags.APPROVE),
                        ) {
                            Text(stringResource(R.string.approve))
                        }
                    }
                    OutlinedButton(
                        onClick = { onAnswer(Answer.Reject) },
                        enabled = !sending,
                        modifier = Modifier.testTag(InboxTags.REJECT),
                    ) {
                        Text(stringResource(R.string.reject))
                    }
                }
            }
            if (result?.delivery == Delivery.Waiting) {
                OutlinedButton(
                    onClick = onSendAgain,
                    enabled = !sending,
                    modifier = Modifier.padding(16.dp).testTag(InboxTags.SEND_AGAIN),
                ) {
                    Text(stringResource(R.string.send_again))
                }
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
                            Modifier.padding(horizontal = 16.dp).testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
                OutlinedButton(
                    onClick = onCheckStatus,
                    enabled = !checking,
                    modifier = Modifier.padding(16.dp).testTag(InboxTags.CHECK_STATUS),
                ) {
                    Text(stringResource(R.string.check_status))
                }
            }
        }
    }
}

/**
 * What this phone read out of the transfer's own transaction, and the owner's decision about it
 * (docs/security.md#inspecting-a-transfer, docs/guides/transfers.md).
 *
 * The order on screen is the order of trust: the verdict first, then the facts the bytes establish,
 * then what could not be established, and only then the server's own numbers, labelled as theirs.
 * The agent's note is rendered by the caller, further down and marked unverified, so that nothing
 * it says can sit next to a fact and borrow its weight. Approve comes last, under everything it
 * approves, and only for a transaction this phone could account for whole.
 */
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
) {
    when (preparation) {
        null,
        Preparation.Running ->
            Text(
                stringResource(R.string.transfer_checking),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_CHECKING),
            )
        is Preparation.Failed -> {
            Text(
                stringResource(R.string.transfer_failed, outcomeText(preparation.outcome)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_FAILED),
            )
            OutlinedButton(
                onClick = onPrepareAgain,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(InboxTags.TRANSFER_AGAIN),
            ) {
                Text(stringResource(R.string.transfer_prepare_again))
            }
        }
        is Preparation.Ready -> {
            val inspection = preparation.inspection
            Text(
                stringResource(verdictText(inspection.verdict)),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (inspection.verdict == Verdict.Verified) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_VERDICT),
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
                if (facts.createsRecipientAccount) {
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
                        Modifier.padding(horizontal = 16.dp).testTag(InboxTags.TRANSFER_DERIVED),
                )
            }
            if (inspection.findings.isNotEmpty()) {
                Column(Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_FINDINGS)) {
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
            // Policies are Stage 5. Until they exist this says so, rather than implying that
            // anything here was measured against a rule and allowed.
            Field(
                R.string.request_field_policy,
                stringResource(R.string.transfer_policy_not_evaluated),
                "policy",
            )
            OutlinedButton(
                onClick = onPrepareAgain,
                enabled = !sending,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(InboxTags.TRANSFER_AGAIN),
            ) {
                Text(stringResource(R.string.transfer_prepare_again))
            }
            if (!answered) {
                if (inspection.approvable) {
                    // The only thing that opens the wallet, and only for a transaction this phone
                    // read whole and found to match the request.
                    Button(
                        onClick = onApprove,
                        enabled = !sending && wallet != null,
                        modifier = Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_APPROVE),
                    ) {
                        Text(stringResource(R.string.approve_and_send))
                    }
                    if (wallet == null) {
                        Text(
                            stringResource(R.string.transfer_no_wallet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier =
                                Modifier.padding(horizontal = 16.dp)
                                    .testTag(InboxTags.SIGNING_PROBLEM),
                        )
                    }
                } else {
                    // No button at all rather than one that refuses: nothing this phone could not
                    // account for is ever put in front of a wallet.
                    Text(
                        stringResource(R.string.transfer_not_approvable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_POLICY),
                    )
                }
            }
            if (signingProblem != null) {
                Text(
                    stringResource(problemText(signingProblem)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp).testTag(InboxTags.SIGNING_PROBLEM),
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { innerPadding ->
        Text(
            stringResource(R.string.status_gone),
            modifier = Modifier.padding(innerPadding).padding(16.dp).testTag(InboxTags.GONE),
        )
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String, name: String) {
    ListItem(
        overlineContent = { Text(stringResource(label)) },
        headlineContent = { Text(value) },
        modifier = Modifier.testTag(InboxTags.field(name)),
    )
}
