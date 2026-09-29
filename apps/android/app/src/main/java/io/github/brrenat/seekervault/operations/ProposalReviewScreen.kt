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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.PolicyReview
import io.github.brrenat.seekervault.plugins.ParameterField
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.receiptRows
import io.github.brrenat.seekervault.policy.AmountEntry
import io.github.brrenat.seekervault.policy.readAmount
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.proposals.executable
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.seekerTextFieldColors
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.encodeBase58
import io.github.brrenat.seekervault.wallet.networkText
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
    onChoose: (ParameterKey, ParameterValue) -> Unit,
    onPrepare: () -> Unit,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
    onAcknowledge: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRules: (() -> Unit)? = null,
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
                Column(Modifier.weight(1f).padding(start = SeekerTheme.dimensions.dp8)) {
                    Text(
                        proposal.action.value,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.operations_from, label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                Banner(stringResource(standingText(review.standing)), OperationTags.STANDING)
                // Said before anything else about this proposal, because it is what the Approve
                // button at the bottom will do (SEE-97). A rehearsal and a purchase must never
                // look the same.
                if (review.environment == PluginEnvironment.Sandbox) {
                    Banner(stringResource(R.string.operation_sandbox), OperationTags.SANDBOX)
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
                                    receipt.forEach { (name, value) -> Pair(name, value) }
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
                // The publisher's own words. Shown apart from everything the phone established,
                // and never believed.
                if (proposal.note.isNotEmpty()) {
                    Section(R.string.operation_publisher_note) {
                        Text(
                            proposal.note,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(OperationTags.NOTE),
                        )
                    }
                }
                Section(R.string.operation_terms) {
                    Column(
                        Modifier.fillMaxWidth().testTag(OperationTags.TERMS),
                        verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
                    ) {
                        // Carried and not interpreted: which key means what is the plugin's, and
                        // this shows the publisher's own names and values as they were written.
                        proposal.values.forEach { Pair(it.key, it.text) }
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
                review.form.problem?.let {
                    Banner(stringResource(it.message), OperationTags.FAILURE)
                }
                // Who routes or places this, named as the provider requires, with what to know
                // before committing and where to read more (SEE-173). The publisher is someone
                // else, and the wallet that signs is someone else again.
                review.about?.let { about ->
                    Section(R.string.operation_about) {
                        Column(
                            Modifier.fillMaxWidth().testTag(OperationTags.ABOUT),
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
                if (review.standing.executable && review.served && review.form.problem == null) {
                    Section(R.string.operation_your_part) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp12),
                        ) {
                            review.form.fields.forEach { field ->
                                Field(field, review.choice.values[field.key], onChoose)
                            }
                            Text(
                                stringResource(R.string.operation_your_part_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SeekerButton(
                        text =
                            stringResource(
                                if (review.prepared == null) R.string.operation_prepare
                                else R.string.operation_prepare_again
                            ),
                        onClick = onPrepare,
                        role = SeekerButtonRole.Neutral,
                        enabled = !review.preparing && !review.sending,
                        modifier = Modifier.fillMaxWidth().testTag(OperationTags.PREPARE),
                    )
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
                            inspection.details.forEach { Pair(stringResource(it.label), it.value) }
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
                    val sandbox = review.environment == PluginEnvironment.Sandbox
                    SeekerButton(
                        text =
                            stringResource(
                                if (sandbox) R.string.operation_simulate
                                else R.string.operation_approve
                            ),
                        onClick = onApprove,
                        role = SeekerButtonRole.Primary,
                        enabled = !review.sending && (!warns || review.acknowledged),
                        modifier = Modifier.fillMaxWidth().testTag(OperationTags.APPROVE),
                    )
                    Text(
                        stringResource(
                            if (sandbox) R.string.operation_simulate_note
                            else R.string.operation_approve_note
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/** One field the plugin asked for, in the units the transaction carries. */
@Composable
private fun Field(
    field: ParameterField,
    value: ParameterValue?,
    onChoose: (ParameterKey, ParameterValue) -> Unit,
) {
    when (val kind = field.kind) {
        is ParameterKind.Amount -> {
            // Typed with the asset's own decimal places and converted exactly: digits are shifted,
            // never multiplied, so no floating-point type comes between what was typed and what
            // the transaction will carry (`readAmount`).
            var typed by
                rememberSaveable(field.key.value) {
                    mutableStateOf(
                        (value as? ParameterValue.Amount)
                            ?.let { formatBaseUnits(it.baseUnits, kind.decimals) }
                            .orEmpty()
                    )
                }
            val entry = readAmount(typed, kind.decimals)
            TextField(
                value = typed,
                onValueChange = {
                    typed = it
                    (readAmount(it, kind.decimals) as? AmountEntry.Amount)?.let { read ->
                        onChoose(field.key, ParameterValue.Amount(read.baseUnits))
                    }
                },
                label = { Text(stringResource(field.label)) },
                isError = entry is AmountEntry.Problem,
                supportingText = {
                    Text(
                        when {
                            entry is AmountEntry.Problem ->
                                stringResource(R.string.operation_amount_invalid)
                            kind.most != null ->
                                stringResource(
                                    R.string.operation_amount_between,
                                    formatBaseUnits(kind.least, kind.decimals),
                                    formatBaseUnits(kind.most, kind.decimals),
                                )
                            else -> stringResource(R.string.operation_amount_note)
                        }
                    )
                },
                singleLine = true,
                colors = seekerTextFieldColors(),
                modifier = Modifier.fillMaxWidth().testTag(OperationTags.AMOUNT),
            )
        }
        is ParameterKind.Count -> {
            var typed by
                rememberSaveable(field.key.value) {
                    mutableStateOf(
                        ((value as? ParameterValue.Count)?.value ?: kind.initial).toString()
                    )
                }
            val read = typed.toUIntOrNull()
            TextField(
                value = typed,
                onValueChange = {
                    typed = it
                    it.toUIntOrNull()
                        ?.takeIf { count -> count >= kind.least && count <= kind.most }
                        ?.let { count -> onChoose(field.key, ParameterValue.Count(count)) }
                },
                label = { Text(stringResource(field.label)) },
                isError = read == null || read < kind.least || read > kind.most,
                supportingText = {
                    Text(
                        stringResource(
                            R.string.operation_count_between,
                            kind.least.toString(),
                            kind.most.toString(),
                        )
                    )
                },
                singleLine = true,
                colors = seekerTextFieldColors(),
                modifier = Modifier.fillMaxWidth().testTag(OperationTags.SLIPPAGE),
            )
        }
        is ParameterKind.Choice -> {
            // No operation bundled in this build asks for one yet; the prediction plugin (SEE-94)
            // is what brings an outcome to pick, and it will be shown here rather than elsewhere.
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.dimensions.dp6),
            ) {
                Text(stringResource(field.label), style = MaterialTheme.typography.titleSmall)
                kind.options.forEach { option ->
                    SeekerButton(
                        text = stringResource(option.label),
                        onClick = { onChoose(field.key, ParameterValue.Selected(option.key)) },
                        role =
                            if ((value as? ParameterValue.Selected)?.option == option.key)
                                SeekerButtonRole.Primary
                            else SeekerButtonRole.Neutral,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
