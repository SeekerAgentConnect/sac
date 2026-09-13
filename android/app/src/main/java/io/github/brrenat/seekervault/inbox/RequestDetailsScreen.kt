package io.github.brrenat.seekervault.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CloseButton
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
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.seekerListItemColors
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
) {
    // Warnings are the owner's to overrule, and overruling one is something they say they are
    // doing. Having no rules at all is not a warning: it would be one on every request there is.
    val warns = assessment?.decision?.warns == true
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                actions = { CloseButton(onBack) },
                expandedHeight = 56.dp,
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
                    Modifier.fillMaxWidth().padding(16.dp).testTag(InboxTags.SENDING),
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
                    assessment = assessment,
                    warns = warns,
                    acknowledged = acknowledged,
                    onAcknowledge = onAcknowledge,
                )
            } else {
                // A transfer's review has its own place for this, under the facts and above the
                // button. Everything else has nothing between the two.
                PolicyReview(assessment)
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
                // A transfer's tick sits with its own Approve button, in the review above.
                if (transfer == null && warns) {
                    ApproveAnyway(acknowledged, onAcknowledge)
                }
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (transfer == null && message == null) {
                        SeekerButton(
                            text =
                                stringResource(
                                    if (warns) R.string.acknowledge_despite_warnings
                                    else R.string.acknowledge
                                ),
                            onClick = { onAnswer(Answer.Acknowledge) },
                            enabled = !sending && (!warns || acknowledged),
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
                            enabled = !sending && wallet != null && (!warns || acknowledged),
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
                    modifier = Modifier.padding(16.dp).testTag(InboxTags.SEND_AGAIN),
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
                            Modifier.padding(horizontal = 16.dp).testTag(InboxTags.SIGNING_PROBLEM),
                    )
                }
                SeekerButton(
                    text = stringResource(R.string.check_status),
                    onClick = onCheckStatus,
                    enabled = !checking,
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.padding(16.dp).testTag(InboxTags.CHECK_STATUS),
                )
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
 * The owner's own rules come after all of it, because they are the weakest thing here: they are a
 * note to themselves, and no rule can put back an Approve button that this phone's own inspection
 * took away (docs/security.md#verification-versus-advisory-rules). The agent's note is rendered by
 * the caller, further down and marked unverified, so that nothing it says can sit next to a fact
 * and borrow its weight. Approve comes last, under everything it approves, and only for a
 * transaction this phone could account for whole.
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
    assessment: RequestAssessment?,
    warns: Boolean,
    acknowledged: Boolean,
    onAcknowledge: (Boolean) -> Unit,
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
            SeekerButton(
                text = stringResource(R.string.transfer_prepare_again),
                onClick = onPrepareAgain,
                role = SeekerButtonRole.Neutral,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(InboxTags.TRANSFER_AGAIN),
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
            // The owner's own rules, under everything the phone established for itself. What
            // they say changes nothing above: a transaction that failed its own inspection has no
            // Approve button whatever the rules made of it, and this never says otherwise.
            PolicyReview(assessment)
            SeekerButton(
                text = stringResource(R.string.transfer_prepare_again),
                onClick = onPrepareAgain,
                enabled = !sending,
                role = SeekerButtonRole.Neutral,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(InboxTags.TRANSFER_AGAIN),
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
                        modifier = Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_APPROVE),
                    )
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
                    // account for is ever put in front of a wallet. This is input validation, and
                    // it is not a rule the owner could tick past — there is nothing to tick.
                    Text(
                        stringResource(R.string.transfer_not_approvable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.padding(16.dp).testTag(InboxTags.TRANSFER_NOT_APPROVABLE),
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
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.request_title)) },
                actions = { CloseButton(onBack) },
                expandedHeight = 56.dp,
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
            modifier = Modifier.padding(innerPadding).padding(16.dp).testTag(InboxTags.GONE),
        )
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String, name: String) {
    SeekerCard(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .testTag(InboxTags.field(name))
                .semantics(mergeDescendants = true) {}
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Identifier(value, Modifier.padding(top = 3.dp), maxLines = 6)
        }
    }
}
