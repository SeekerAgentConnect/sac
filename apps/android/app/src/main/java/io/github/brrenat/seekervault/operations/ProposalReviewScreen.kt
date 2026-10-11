package io.github.brrenat.seekervault.operations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.activity.explorerUrl
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.designsystem.NetworkChip
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.OwnerInputCard
import io.github.brrenat.seekervault.designsystem.OwnerInputCardKind
import io.github.brrenat.seekervault.designsystem.OwnerInputCardState
import io.github.brrenat.seekervault.designsystem.ReviewSheetHeaderChip
import io.github.brrenat.seekervault.designsystem.ReviewSheetOriginChips
import io.github.brrenat.seekervault.designsystem.SourceColour
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.PolicyReview
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.receiptRows
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.executable
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.encodeBase58
import io.github.brrenat.seekervault.wallet.networkText
import io.github.brrenat.seekervault.wallet.walletNetworkOf
import java.time.Instant

/**
 * One of a publisher's proposals, reviewed (SEE-93).
 *
 * The layout is the one the transfer review already established, for the same reasons
 * (docs/guides/transfers.md): what the publisher said is shown as *theirs*, what this phone read
 * out of the bytes is shown separately as fact, and what the owner's rules make of those facts is
 * shown under them and never in place of them. Approve appears only for bytes this phone accounted
 * for completely, and that is input validation rather than a rule anybody can overrule
 * (docs/security.md#verification-versus-advisory-rules).
 *
 * Nothing on this screen is sent anywhere. The amount is the owner's, it stays on this phone, and
 * the publisher is never told that this device received the proposal, let alone acted on it.
 */
@Composable
fun ProposalReviewScreen(
    review: OperationReview,
    label: String,
    wallet: SelectedWallet?,
    now: Instant,
    /** Opens the owner's part — amount, slippage and wallet — stacked over this review. */
    onOwnerInput: () -> Unit,
    onPrepare: () -> Unit,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
    onAcknowledge: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The connection's colour for the source chip; null derives one from the label. */
    colour: SourceColour? = null,
    onRules: (() -> Unit)? = null,
    /** Previews what was prepared without sending it (SEE-185). Null hides the action. */
    onPreview: (() -> Unit)? = null,
    /**
     * Hands a destination — a web address, and the provider's own app link when there is one — to
     * whatever opens it. The app fetches nothing from any of them.
     */
    onOpenLink: (String, String?) -> Unit = { _, _ -> },
) {
    val proposal = review.record.proposal
    val executed = review.record.execution
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .height(SeekerTheme.dimensions.dp56)
                    .padding(horizontal = SeekerTheme.dimensions.dp8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Titled by what the signal does, as the prediction sheet is; where it came from is
                // said by the chips under the title, the same as there.
                Text(
                    stringResource(
                        if (proposal.action == SWAP_ACTION) R.string.operation_title_swap
                        else R.string.operation_title
                    ),
                    modifier = Modifier.weight(1f).padding(start = SeekerTheme.dimensions.dp8),
                    style = MaterialTheme.typography.headlineSmall,
                )
                CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = SeekerTheme.dimensions.dp16,
                        vertical = SeekerTheme.dimensions.dp8,
                    ),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
            ) {
                ReviewSheetOriginChips(
                    buildList {
                        add(ReviewSheetHeaderChip.Signal)
                        if (label.isNotEmpty()) add(ReviewSheetHeaderChip.Feed(label, colour))
                    }
                )
                // The publisher's own words. Shown apart from everything the phone established,
                // and never believed.
                if (proposal.note.isNotEmpty()) {
                    Text(
                        proposal.note,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth().testTag(OperationTags.NOTE),
                    )
                }
                // The network the signal declares, never the wallet's, flagged when it is a test
                // network, as on the prediction sheet (SEE-185).
                when (review.network?.let(::walletNetworkOf)) {
                    WalletNetwork.Mainnet -> NetworkChip(NetworkChipNetwork.Mainnet)
                    WalletNetwork.Devnet -> NetworkChip(NetworkChipNetwork.Devnet, flagged = true)
                    WalletNetwork.Testnet,
                    null -> Unit
                }
                // An open signal needs no banner saying so: the actions below already ask.
                if (review.standing !is ProposalStanding.Open) {
                    Banner(stringResource(standingText(review.standing)), OperationTags.STANDING)
                }
                // A preview just happened: said before anything else, because what was on screen
                // is gone and nothing was signed or sent (SEE-185).
                if (review.previewed && executed == null) {
                    Banner(stringResource(R.string.operation_previewed), OperationTags.PREVIEWED)
                }
                if (executed != null) {
                    Banner(stringResource(outcomeText(executed.outcome)), OperationTags.OUTCOME)
                    // What the owner has afterwards, and the app's own limit stated beside it: it
                    // submitted an operation and does not follow it (SEE-94). The transaction can
                    // be read on the explorer; the operation itself continues at the provider's,
                    // if the provider has anywhere truthful to send them.
                    Section(R.string.operation_afterwards) {
                        Column(
                            Modifier.fillMaxWidth().testTag(OperationTags.AFTERWARDS),
                            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
                        ) {
                            val signature =
                                (executed.outcome as? ProposalOutcome.Submitted)?.signature
                            signature?.let {
                                Address(
                                    stringResource(R.string.operation_signature),
                                    encodeBase58(it.toByteArray()),
                                )
                            }
                            val link = signature?.let {
                                explorerUrl(
                                    encodeBase58(it.toByteArray()),
                                    executed.binding.network,
                                )
                            }
                            link?.let {
                                // The explorer is the web and nothing else: no app claims it here,
                                // and none is pretended.
                                Link(
                                    stringResource(R.string.operation_explorer),
                                    it,
                                    deepLink = null,
                                    onOpenLink = onOpenLink,
                                )
                            }
                            // Who routed it and the service fee it carried, as approved (SEE-173).
                            // Kept with the binding, so it is what the owner saw and not what
                            // anybody says today.
                            val receipt = receiptRows(executed.binding.receipt)
                            if (receipt.isNotEmpty()) {
                                Column(
                                    Modifier.fillMaxWidth().testTag(OperationTags.RECEIPT),
                                    verticalArrangement =
                                        Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                                ) {
                                    receipt.forEach { (name, value) -> Fact(name, value) }
                                }
                            }
                            review.destinations.forEach {
                                Link(stringResource(it.label), it.url, it.deepLink, onOpenLink)
                            }
                            // Every one of these is a place to look, and none of them is this app
                            // claiming to know what happened.
                            Text(
                                stringResource(R.string.operation_afterwards_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!review.served) {
                    // A server this build has no plugin for is read in full and executed never:
                    // there is nothing here that would carry the operation out (SEE-88).
                    Banner(
                        stringResource(R.string.operation_unsupported),
                        OperationTags.UNSUPPORTED,
                    )
                }
                review.form.problem?.let {
                    Banner(stringResource(it.message), OperationTags.FAILURE)
                }
                // What the execution provider says about this action right now, read when the
                // review opened (SEE-145). It is the venue's own words about its own market, shown
                // apart from anything this phone established for itself and evaluated by nothing.
                if (review.details.isNotEmpty()) {
                    Section(R.string.operation_provider_says) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                        ) {
                            review.details.forEach { Pair(stringResource(it.label), it.value) }
                        }
                    }
                }
                // The owner's part — the amount and the wallet that signs it — chosen in a sheet
                // stacked over this one, as a prediction's side and stake are (SEE-158, SEE-185).
                // An operation is never bound to the feed's own wallet: the owner picks the account
                // on the signal's network before anything is prepared, and "Use these" prepares.
                if (
                    executed == null &&
                        review.standing.executable &&
                        review.served &&
                        review.form.problem == null
                ) {
                    val summary = yourPartSummary(review, wallet)
                    OwnerInputCard(
                        kind = OwnerInputCardKind.Swap,
                        state =
                            if (summary == null) OwnerInputCardState.Unchosen
                            else OwnerInputCardState.Chosen,
                        summary = summary,
                        onChooseOrEdit = { if (!review.sending) onOwnerInput() },
                        modifier = Modifier.testTag(OperationTags.YOUR_PART),
                    )
                    // A quote runs out, and a failed one can be asked for again, without opening
                    // the owner's part to change nothing.
                    if (summary != null && (review.prepared != null || review.failure != null)) {
                        SeekerButton(
                            text = stringResource(R.string.operation_prepare_again),
                            onClick = onPrepare,
                            role = SeekerButtonRole.Neutral,
                            enabled = !review.preparing && !review.sending,
                            modifier = Modifier.fillMaxWidth().testTag(OperationTags.PREPARE),
                        )
                    }
                }
                if (review.preparing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                review.failure?.let { failure ->
                    Section(R.string.operation_not_prepared) {
                        Column(
                            Modifier.fillMaxWidth().testTag(OperationTags.FAILURE),
                            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                        ) {
                            Text(
                                failure.explanation?.let { stringResource(it) }
                                    ?: stringResource(R.string.operation_not_prepared_unknown),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            // A provider's own words, marked as theirs, parsed by nobody.
                            failure.detail?.takeIf(String::isNotEmpty)?.let {
                                Text(
                                    stringResource(R.string.operation_provider_said, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                review.inspection
                    ?.references
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { references ->
                        Section(R.string.operation_identifiers) {
                            Column(
                                Modifier.fillMaxWidth().testTag(OperationTags.REFERENCES),
                                verticalArrangement =
                                    Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                            ) {
                                // The provider's own names for what is being submitted, read out of
                                // the bytes. They are what the owner's record keeps.
                                references.forEach { Address(it.key, it.value) }
                            }
                        }
                    }
                review.inspection?.let { inspection ->
                    Section(R.string.operation_what_this_phone_read) {
                        Column(
                            Modifier.fillMaxWidth().testTag(OperationTags.FACTS),
                            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                        ) {
                            inspection.facts?.let { facts ->
                                Pair(
                                    stringResource(R.string.operation_fact_spends),
                                    formatBaseUnits(facts.amount ?: 0UL, facts.decimals),
                                )
                                facts.wallet?.let {
                                    Address(stringResource(R.string.operation_fact_payer), it)
                                }
                                facts.recipient?.let {
                                    Address(stringResource(R.string.operation_fact_receives), it)
                                }
                            }
                            // What else the bytes said, in the plugin's own words and values.
                            inspection.details.forEach { Fact(stringResource(it.label), it.value) }
                            wallet?.let {
                                Pair(
                                    stringResource(R.string.operation_fact_network),
                                    networkText(it.network),
                                )
                            }
                            if (inspection.findings.isNotEmpty()) {
                                Column(
                                    Modifier.fillMaxWidth().testTag(OperationTags.FINDINGS),
                                    verticalArrangement =
                                        Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                                ) {
                                    inspection.findings.forEach {
                                        Text(
                                            stringResource(it.message),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Under the facts, and never in place of them (SAW-028).
                if (review.inspection != null) {
                    PolicyReview(
                        assessment = review.assessment,
                        onRules = onRules,
                        transaction = true,
                    )
                }
                review.problem?.let {
                    Banner(stringResource(problemText(it)), OperationTags.PROBLEM)
                }
                val warns = review.assessment?.decision?.warns == true
                if (review.inspection?.approvable == true && review.standing.executable) {
                    if (warns) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                        ) {
                            Checkbox(
                                checked = review.acknowledged,
                                onCheckedChange = onAcknowledge,
                                modifier = Modifier.testTag(OperationTags.ACKNOWLEDGE),
                            )
                            Text(
                                stringResource(R.string.operation_acknowledge),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    SeekerButton(
                        text = stringResource(R.string.operation_approve),
                        onClick = onApprove,
                        role = SeekerButtonRole.Primary,
                        enabled = !review.sending && (!warns || review.acknowledged),
                        modifier = Modifier.fillMaxWidth().testTag(OperationTags.APPROVE),
                    )
                    Text(
                        stringResource(R.string.operation_approve_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The same bytes, the same rules, and no wallet: chosen here, per operation,
                    // never by where the signal came from (SEE-185).
                    if (onPreview != null && review.previewable) {
                        SeekerButton(
                            text = stringResource(R.string.operation_simulate),
                            onClick = onPreview,
                            role = SeekerButtonRole.Neutral,
                            enabled = !review.sending && (!warns || review.acknowledged),
                            modifier = Modifier.fillMaxWidth().testTag(OperationTags.PREVIEW),
                        )
                        Text(
                            stringResource(R.string.operation_simulate_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (review.standing is ProposalStanding.Open && executed == null) {
                    SeekerButton(
                        text = stringResource(R.string.operation_dismiss),
                        onClick = onDismiss,
                        role = SeekerButtonRole.Neutral,
                        enabled = !review.sending,
                        modifier = Modifier.fillMaxWidth().testTag(OperationTags.DISMISS),
                    )
                }
                // Who routes or places this, named as the provider requires, with what to know
                // before committing and where to read more (SEE-173), under the buttons so the
                // decision comes first. The publisher is someone else, and the wallet that signs
                // is someone else again.
                review.about?.let { about ->
                    Folded(R.string.operation_about, OperationTags.ABOUT) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp8),
                        ) {
                            Pair(stringResource(about.role), about.name)
                            about.notes.forEach { note ->
                                Text(
                                    stringResource(note.text, *note.args.toTypedArray()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(R.string.operation_about_signer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            about.links.forEach {
                                Link(stringResource(it.label), it.url, it.deepLink, onOpenLink)
                            }
                        }
                    }
                }
                // The publisher's terms as written, folded at the bottom: the review above already
                // says what the owner decides on, and these are there to check it against.
                Folded(R.string.operation_terms_raw, OperationTags.TERMS) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                    ) {
                        // Carried and not interpreted: which key means what is the plugin's, and
                        // this shows the publisher's own names and values as they were written.
                        proposal.values.forEach { Fact(it.key, it.text) }
                        Text(
                            stringResource(
                                R.string.operation_expires,
                                formatInstant(proposal.expiresAt),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Address(stringResource(R.string.operation_proposal_id), proposal.key.proposalId)
                Address(stringResource(R.string.operation_publisher), proposal.key.serverId)
                Text(
                    stringResource(R.string.operation_revision, proposal.revision),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the owner chose for their part, as the card says it: "1.5 SOL · Main wallet". Null until
 * both an amount and the wallet that signs are chosen.
 */
@Composable
private fun yourPartSummary(review: OperationReview, wallet: SelectedWallet?): String? {
    wallet ?: return null
    val field = review.form.fields.firstOrNull { it.kind is ParameterKind.Amount } ?: return null
    val kind = field.kind as ParameterKind.Amount
    val amount =
        (review.choice.values[field.key] as? ParameterValue.Amount)?.baseUnits ?: return null
    val unit = (review.payload as? ActionPayload.Swap)?.payload?.inputSymbol.orEmpty()
    return stringResource(
        R.string.swap_review_your_part,
        "${formatBaseUnits(amount, kind.decimals)} $unit".trim(),
        walletName(wallet),
    )
}

/** A section that stays folded until the owner taps it: there to read, not in the way. */
@Composable
private fun Folded(labelRes: Int, tag: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(labelRes) { mutableStateOf(false) }
    val label = stringResource(labelRes)
    SeekerCard(Modifier.fillMaxWidth().testTag(tag), onClick = { expanded = !expanded }) {
        Column(
            Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.operation_section_hide
                            else R.string.operation_section_show,
                            label,
                        ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun Section(labelRes: Int, content: @Composable () -> Unit) {
    SeekerCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp16),
            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp10),
        ) {
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun Banner(text: String, tag: String) {
    SeekerCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(SeekerTheme.dimensions.dp14).testTag(tag),
        )
    }
}

/**
 * One place to look, outside this app.
 *
 * Tapping hands it to whoever opens it, which is the provider's own app when [deepLink] names one
 * and it is installed, and a browser otherwise (SEE-157). The screen learns nothing about which
 * happened, and there is nothing here about any particular provider: it carries two addresses a
 * provider gave it and knows what neither of them is.
 */
@Composable
private fun Link(
    name: String,
    url: String,
    deepLink: String?,
    onOpenLink: (String, String?) -> Unit,
) {
    SeekerButton(
        text = name,
        onClick = { onOpenLink(url, deepLink) },
        role = SeekerButtonRole.Neutral,
        modifier = Modifier.fillMaxWidth().testTag(OperationTags.link(name)),
    )
}

/** A labelled address or identifier, in the monospaced style the rest of the app uses for one. */
@Composable
private fun Address(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Identifier(value, Modifier.weight(2f))
    }
}

/**
 * A labelled value, laid out as an identifier when it is one: an account address cannot share a
 * line with its label and still be read whole (SEE-173's fee recipient is one).
 */
@Composable
private fun Fact(name: String, value: String) {
    if (value.length >= 32 && value.none(Char::isWhitespace)) Address(name, value)
    else Pair(name, value)
}

@Composable
private fun Pair(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
