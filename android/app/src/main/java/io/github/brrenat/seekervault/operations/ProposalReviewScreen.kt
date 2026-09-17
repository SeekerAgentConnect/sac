package io.github.brrenat.seekervault.operations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.inbox.PolicyReview
import io.github.brrenat.seekervault.plugins.ParameterField
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterKind
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.policy.AmountEntry
import io.github.brrenat.seekervault.policy.readAmount
import io.github.brrenat.seekervault.proposals.executable
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.seekerTextFieldColors
import io.github.brrenat.seekervault.wallet.SelectedWallet
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
) {
    val proposal = review.record.proposal
    val executed = review.record.execution
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        proposal.operation.value,
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
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Banner(stringResource(standingText(review.standing)), OperationTags.STANDING)
                if (executed != null) {
                    Banner(stringResource(outcomeText(executed.outcome)), OperationTags.OUTCOME)
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
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                if (review.standing.executable && review.served && review.form.problem == null) {
                    Section(R.string.operation_your_part) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                review.inspection?.let { inspection ->
                    Section(R.string.operation_what_this_phone_read) {
                        Column(
                            Modifier.fillMaxWidth().testTag(OperationTags.FACTS),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                }
                if (review.record.dismissed == null && executed == null) {
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
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
            modifier = Modifier.fillMaxWidth().padding(14.dp).testTag(tag),
        )
    }
}

/** A labelled address or identifier, in the monospaced style the rest of the app uses for one. */
@Composable
private fun Address(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
