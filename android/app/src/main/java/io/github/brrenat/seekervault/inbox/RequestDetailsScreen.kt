package io.github.brrenat.seekervault.inbox

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.Answer
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
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.MonoText
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.PillButton
import io.github.brrenat.seekervault.ui.PillTone
import io.github.brrenat.seekervault.ui.Radius
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space
import io.github.brrenat.seekervault.ui.Tag
import io.github.brrenat.seekervault.ui.TagTone
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.networkText
import java.time.Instant

/**
 * What one request says, and the answers to it. It is the body of the review panel (SEE-57): the
 * panel draws the glass, the handle and the pager round it, and this draws the request.
 *
 * A pending acknowledgement offers Acknowledge and Reject, and a message to sign offers Approve and
 * Reject; once answered, the screen shows the stored outcome instead of the buttons.
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
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
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
    /** The way to the rules themselves, from the card that reports them. */
    onRules: (() -> Unit)? = null,
) {
    // Warnings are the owner's to overrule, and overruling one is something they say they are
    // doing. Having no rules at all is not a warning: it would be one on every request there is.
    val warns = assessment?.decision?.warns == true
    val kind = kindOf(request)
    val transfer = request.transfer()
    val message = messagePreview(request)
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.Inset, vertical = Space.Sm),
        verticalArrangement = Arrangement.spacedBy(Space.Gap),
    ) {
        // The kind, what is at stake, and the one line that names the request.
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                kindIcon(kind),
                contentDescription = null,
                tint = Nocturne.Accent200,
                modifier = Modifier.size(20.dp),
            )
            Text(
                actionText(request),
                style = MaterialTheme.typography.titleMedium,
                color = Nocturne.Text,
                modifier = Modifier.weight(1f),
            )
            Tag(stakeText(kind))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                headlineOf(request),
                style = MaterialTheme.typography.displayLarge,
                color = Nocturne.Text,
            )
            MonoText(detailOf(request))
        }
        val problem =
            result != null &&
                (result.delivery == Delivery.Superseded ||
                    result.delivery == Delivery.Undeliverable)
        Text(
            statusText(request, result, sending, now),
            style = MaterialTheme.typography.bodyLarge,
            color = if (problem) Nocturne.Danger else Nocturne.Neutral200,
            modifier = Modifier.testTag(InboxTags.STATUS),
        )
        if (result?.delivery == Delivery.Waiting && result.lastFailure != null && !sending) {
            Note(stringResource(R.string.status_last_failure, outcomeText(result.lastFailure)))
        }
        if (sending || checking) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag(InboxTags.SENDING))
        }
        // What the server read from the chain, and whose word that is (SAW-022).
        result?.let { answered ->
            confirmationText(answered)?.let { checked ->
                Note(
                    "$checked ${stringResource(R.string.confirmation_trust)}",
                    Modifier.testTag(InboxTags.CONFIRMATION),
                )
            }
        }
        // The owner's own rules, and what they made of this. Above the facts for an
        // acknowledgement, which has none of its own; a transfer's sits inside its review, under
        // everything the phone established for itself.
        if (transfer == null) PolicyReview(assessment, onRules = onRules)
        // What this phone can say for itself about what is on screen.
        GlassCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Glyph.WithinRules,
                    contentDescription = null,
                    tint = Nocturne.Neutral400,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(
                        when (kind) {
                            RequestKind.Transfer -> R.string.review_local_check_transfer
                            RequestKind.Signature -> R.string.review_local_check_signature
                            else -> R.string.review_local_check_acknowledge
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral400,
                    modifier = Modifier.testTag(InboxTags.LOCAL_CHECK),
                )
            }
        }
        GlassCard(spacing = 0.dp) {
            Field(R.string.request_field_from, "from") {
                Text(
                    source?.let { "${it.label} (${PairingCodes.hostOf(it.serverUrl)})" }
                        ?: request.ref.connectionId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Text,
                )
            }
            CardDivider()
            Field(R.string.request_field_action, "action") { Value(actionText(request)) }
            request.text()?.let { text ->
                CardDivider()
                // The agent's text, verbatim and as plain text: never parsed, linked, or executed.
                Field(R.string.request_field_message, "text") {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Nocturne.Text,
                        modifier = Modifier.testTag(InboxTags.MESSAGE),
                    )
                }
            }
            if (message != null) {
                CardDivider()
                // The complete message the wallet would sign, with nothing hidden in it.
                Field(R.string.request_field_message, "message") {
                    Text(
                        message.display,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Nocturne.Text,
                        modifier = Modifier.testTag(InboxTags.MESSAGE),
                    )
                }
                CardDivider()
                Field(R.string.request_field_encoding, "encoding") {
                    Value(
                        pluralStringResource(
                            if (message.isText) R.plurals.message_text_bytes
                            else R.plurals.message_data_bytes,
                            message.bytes,
                            message.bytes,
                        )
                    )
                }
                CardDivider()
                Field(R.string.request_field_signs_with, "signsWith") {
                    MonoText(
                        wallet?.let {
                            stringResource(
                                R.string.signing_wallet,
                                it.address,
                                networkText(it.network),
                            )
                        } ?: request.signMessage()?.wallet.orEmpty()
                    )
                }
            }
        }
        if (message != null) {
            if (message.hasHidden) {
                Note(
                    stringResource(R.string.message_hidden_characters),
                    Modifier.testTag(InboxTags.HIDDEN),
                )
            }
            Note(
                stringResource(R.string.message_not_a_payment),
                Modifier.testTag(InboxTags.NOT_A_PAYMENT),
            )
            if (wallet == null && result == null) {
                Danger(
                    stringResource(R.string.message_no_wallet),
                    Modifier.testTag(InboxTags.SIGNING_PROBLEM),
                )
            }
            if (signingProblem != null) {
                Danger(
                    stringResource(problemText(signingProblem)),
                    Modifier.testTag(InboxTags.SIGNING_PROBLEM),
                )
            }
        }
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
            )
        }
        if (request.agentNote.isNotEmpty()) {
            // Shown apart from the request itself: the agent wrote it, and nothing checked it.
            GlassCard(
                modifier = Modifier.testTag(InboxTags.NOTE).semantics(mergeDescendants = true) {}
            ) {
                SectionLabel(stringResource(R.string.request_field_note))
                Text(
                    request.agentNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Nocturne.Neutral300,
                )
            }
        }
        val created = request.createdAt.instant()
        val expires = request.expiresAt.instant()
        GlassCard(spacing = 0.dp) {
            Field(R.string.request_field_created, "created") {
                Value("${formatInstant(created)} (${relativeTime(created, now)})")
            }
            CardDivider()
            Field(R.string.request_field_expires, "expires") {
                Value("${formatInstant(expires)} (${relativeTime(expires, now)})")
            }
            CardDivider()
            Field(R.string.request_field_id, "requestId") { MonoText(request.ref.requestId) }
        }
        if (canAnswer(request, result, now)) {
            Note(
                stringResource(R.string.review_leave_waiting, relativeTime(expires, now)),
                Modifier.testTag(InboxTags.EXPIRY),
            )
            // A transfer's tick sits with its own Approve button, in the review above.
            if (transfer == null && warns) {
                ApproveAnyway(acknowledged, onAcknowledge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
                if (transfer != null) {
                    // A transfer's Approve sits in the review above, next to the facts it
                    // approves. Only Reject belongs down here with the other answers.
                    Unit
                } else if (message == null) {
                    PillButton(
                        stringResource(
                            if (warns) R.string.acknowledge_despite_warnings
                            else R.string.acknowledge
                        ),
                        onClick = { onAnswer(Answer.Acknowledge) },
                        tone = PillTone.Accent,
                        enabled = !sending && (!warns || acknowledged),
                        modifier = Modifier.weight(1f).testTag(InboxTags.ACKNOWLEDGE),
                    )
                } else {
                    // Approve is the only thing that reaches the wallet, and only once the
                    // owner has a wallet connected to sign with.
                    PillButton(
                        stringResource(
                            if (warns) R.string.approve_despite_warnings else R.string.approve
                        ),
                        onClick = onApprove,
                        tone = PillTone.Accent,
                        enabled = !sending && wallet != null && (!warns || acknowledged),
                        modifier = Modifier.weight(1f).testTag(InboxTags.APPROVE),
                    )
                }
                PillButton(
                    stringResource(R.string.reject),
                    onClick = { onAnswer(Answer.Reject) },
                    enabled = !sending,
                    modifier = Modifier.weight(1f).testTag(InboxTags.REJECT),
                )
            }
        }
        if (result?.delivery == Delivery.Waiting) {
            PillButton(
                stringResource(R.string.send_again),
                onClick = onSendAgain,
                enabled = !sending,
                modifier = Modifier.testTag(InboxTags.SEND_AGAIN),
            )
        }
        // Only while the chain could still settle it. It asks the server and nothing else: no
        // wallet is opened, and the transaction is never sent a second time.
        if (result?.awaitingChain == true) {
            // A check that couldn't be made belongs here, next to the button that makes it.
            // Every other signing problem happens before an answer exists, and shows above.
            if (signingProblem != null) {
                Danger(
                    stringResource(problemText(signingProblem)),
                    Modifier.testTag(InboxTags.SIGNING_PROBLEM),
                )
            }
            PillButton(
                stringResource(R.string.check_status),
                onClick = onCheckStatus,
                enabled = !checking,
                modifier = Modifier.testTag(InboxTags.CHECK_STATUS),
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
@Composable
@Suppress("LongMethod")
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
) {
    when (preparation) {
        null,
        Preparation.Running ->
            Note(
                stringResource(R.string.transfer_checking),
                Modifier.testTag(InboxTags.TRANSFER_CHECKING),
            )
        is Preparation.Failed -> {
            Danger(
                stringResource(R.string.transfer_failed, outcomeText(preparation.outcome)),
                Modifier.testTag(InboxTags.TRANSFER_FAILED),
            )
            PillButton(
                stringResource(R.string.transfer_prepare_again),
                onClick = onPrepareAgain,
                modifier = Modifier.testTag(InboxTags.TRANSFER_AGAIN),
            )
        }
        is Preparation.Ready -> {
            val inspection = preparation.inspection
            val verified = inspection.verdict == Verdict.Verified
            GlassCard {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        if (verified) Glyph.WithinRules else Glyph.Warning,
                        contentDescription = null,
                        tint = if (verified) Nocturne.Accent200 else Nocturne.Danger,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(verdictText(inspection.verdict)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (verified) Nocturne.Text else Nocturne.Danger,
                        modifier = Modifier.testTag(InboxTags.TRANSFER_VERDICT),
                    )
                }
            }
            inspection.facts?.let { facts ->
                GlassCard(spacing = 0.dp) {
                    Field(R.string.request_field_sends, "sends") { Value(amountText(facts)) }
                    CardDivider()
                    Field(R.string.request_field_to, "to") {
                        val recipient = facts.recipient
                        if (recipient == null) {
                            Value(stringResource(R.string.transfer_recipient_unknown))
                        } else {
                            MonoText(recipient)
                        }
                    }
                    facts.destinationAccount?.let {
                        CardDivider()
                        Field(R.string.request_field_token_account, "tokenAccount") { MonoText(it) }
                    }
                    facts.mint?.let {
                        CardDivider()
                        Field(R.string.request_field_token, "token") { MonoText(it) }
                    }
                    CardDivider()
                    Field(R.string.request_field_pays_fee, "paysFee") { MonoText(facts.payer) }
                    if (facts.ensuresRecipientAccount) {
                        CardDivider()
                        Field(R.string.request_field_creates, "creates") {
                            Value(stringResource(R.string.transfer_creates_account))
                        }
                    }
                    facts.computeUnitPrice?.let {
                        CardDivider()
                        Field(R.string.request_field_priority, "priority") {
                            Value(stringResource(R.string.transfer_priority_price, it.toString()))
                        }
                    }
                    // Every program the transaction calls, so that a request nothing could be
                    // verified about still shows what it would run (SAW-028).
                    facts.programs
                        .takeIf { it.isNotEmpty() }
                        ?.let { programs ->
                            CardDivider()
                            Field(R.string.request_field_programs, "programs") {
                                MonoText(programs.joinToString("\n"))
                            }
                        }
                    CardDivider()
                    Field(R.string.request_field_instructions, "instructions") {
                        Value(
                            stringResource(
                                R.string.transfer_instructions_read,
                                facts.recognizedInstructions,
                                facts.instructionCount,
                            )
                        )
                    }
                    CardDivider()
                    Field(R.string.request_field_blockhash, "blockhash") {
                        MonoText(facts.blockhash)
                    }
                }
                Note(
                    stringResource(R.string.transfer_derived_here),
                    Modifier.testTag(InboxTags.TRANSFER_DERIVED),
                )
            }
            if (inspection.findings.isNotEmpty()) {
                GlassCard(modifier = Modifier.testTag(InboxTags.TRANSFER_FINDINGS)) {
                    inspection.findings.forEach { finding ->
                        Text(
                            stringResource(findingText(finding)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Nocturne.Danger,
                        )
                    }
                }
            }
            GlassCard(spacing = 0.dp) {
                // The wallet that would pay and sign, as this phone holds it now, so the owner
                // approves a wallet and a network they can see rather than one they last set.
                Field(R.string.request_field_signs_with, "signsWith") {
                    MonoText(
                        wallet?.let {
                            stringResource(
                                R.string.signing_wallet,
                                it.address,
                                networkText(it.network),
                            )
                        } ?: request.transfer()?.wallet.orEmpty()
                    )
                }
                CardDivider()
                // The fee can't be read out of a transaction: it depends on the network. It is the
                // server's number, and it is labelled as one rather than mixed in with the facts.
                Field(R.string.request_field_estimate, "estimate") {
                    Value(estimateText(preparation.prepared))
                }
            }
            // The owner's own rules, under everything the phone established for itself. What
            // they say changes nothing above: a transaction that failed its own inspection has no
            // Approve button whatever the rules made of it, and this never says otherwise.
            PolicyReview(assessment, onRules = onRules)
            PillButton(
                stringResource(R.string.transfer_prepare_again),
                onClick = onPrepareAgain,
                tone = PillTone.Ghost,
                enabled = !sending,
                modifier = Modifier.testTag(InboxTags.TRANSFER_AGAIN),
            )
            if (!answered) {
                if (inspection.approvable) {
                    // A warning is the owner's to overrule, and overruling it is a thing they say
                    // they are doing, next to the button that does it.
                    if (warns) ApproveAnyway(acknowledged, onAcknowledge)
                    // The only thing that opens the wallet, and only for a transaction this phone
                    // read whole and found to match the request.
                    PillButton(
                        stringResource(
                            if (warns) R.string.approve_and_send_despite_warnings
                            else R.string.approve_and_send
                        ),
                        onClick = onApprove,
                        tone = PillTone.Accent,
                        enabled = !sending && wallet != null && (!warns || acknowledged),
                        modifier = Modifier.fillMaxWidth().testTag(InboxTags.TRANSFER_APPROVE),
                    )
                    if (wallet == null) {
                        Danger(
                            stringResource(R.string.transfer_no_wallet),
                            Modifier.testTag(InboxTags.SIGNING_PROBLEM),
                        )
                    }
                } else {
                    // No button at all rather than one that refuses: nothing this phone could not
                    // account for is ever put in front of a wallet. This is input validation, and
                    // it is not a rule the owner could tick past — there is nothing to tick.
                    Danger(
                        stringResource(R.string.transfer_not_approvable),
                        Modifier.testTag(InboxTags.TRANSFER_NOT_APPROVABLE),
                    )
                }
            }
            if (signingProblem != null) {
                Danger(
                    stringResource(problemText(signingProblem)),
                    Modifier.testTag(InboxTags.SIGNING_PROBLEM),
                )
            }
        }
    }
}

/** The request isn't known here: it's no longer pending, or the inbox hasn't been fetched yet. */
@Composable
fun RequestGoneScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    GlassScreen(
        title = stringResource(R.string.request_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        GlassCard {
            Text(
                stringResource(R.string.status_gone),
                style = MaterialTheme.typography.bodyMedium,
                color = Nocturne.Neutral300,
                modifier = Modifier.testTag(InboxTags.GONE),
            )
        }
    }
}

/** One row of a fact table: the muted label, and the value under it. */
@Composable
private fun Field(@StringRes label: Int, name: String, value: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = Space.Md)
            .testTag(InboxTags.field(name))
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionLabel(stringResource(label))
        value()
    }
}

@Composable
private fun Value(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Nocturne.Text)
}

/** A quiet line: context for what is above it, never an answer to anything. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Nocturne.Neutral500,
        modifier = modifier,
    )
}

/** A line the owner must not read past: a mismatch, a refusal, a wallet that isn't there. */
@Composable
private fun Danger(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Nocturne.Danger,
        modifier = modifier.fillMaxWidth().then(Modifier.padding(0.dp)),
    )
}

/** The shape a dashed warning card uses, kept here so the two that need it agree. */
internal val WarningShape = RoundedCornerShape(Radius.Inner)

/** A tag that says a request is only advisory-flagged, used by the rule card. */
@Composable
internal fun AdvisoryTag(text: String, within: Boolean, modifier: Modifier = Modifier) {
    Tag(text, tone = if (within) TagTone.Accent else TagTone.Dashed, modifier = modifier)
}
