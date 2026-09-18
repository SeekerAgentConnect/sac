package io.github.brrenat.seekervault.activity

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CloseButton
import io.github.brrenat.seekervault.connections.environmentText
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.ui.Identifier
import io.github.brrenat.seekervault.ui.SeekerButton
import io.github.brrenat.seekervault.ui.SeekerButtonRole
import io.github.brrenat.seekervault.ui.SeekerCard
import io.github.brrenat.seekervault.ui.SeekerSnackbarHost

/**
 * One record in full: who asked, what the owner reviewed, which network it was on, how it ended,
 * and the signature. A transfer that was sent offers the explorer for its own cluster; a signed
 * message says in words that it is not a payment and offers nothing, because there is nothing to
 * look at, presented with the approved v4 Material 3 theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailsScreen(
    record: ActivityRecord,
    onOpenExplorer: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Nothing on this phone could open the last link. */
    linkFailed: Boolean = false,
    onMessageShown: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.activity_link_failed)
    LaunchedEffect(linkFailed) {
        if (linkFailed) {
            snackbar.showSnackbar(failed)
            onMessageShown()
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_details_title)) },
                actions = {
                    CloseButton(onBack, MaterialTheme.colorScheme.surfaceContainerHigh)
                },
                expandedHeight = 56.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
        },
        snackbarHost = { SeekerSnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(outcomeText(record.outcome)),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(ActivityTags.OUTCOME),
            )
            Text(
                operationText(record),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(ActivityTags.OPERATION),
            )
            record.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp).testTag(ActivityTags.DETAIL),
                )
            }
            Field(R.string.activity_field_source, record.source, ActivityTags.SOURCE)
            if (record.serverHost.isNotEmpty()) {
                Field(R.string.activity_field_server, record.serverHost)
            }
            Field(R.string.activity_field_request, kindText(record.kind))
            // The cluster is on its own line for a transfer, always: a signature without one says
            // nothing at all.
            clusterText(record)?.let {
                Field(R.string.activity_field_cluster, it, ActivityTags.CLUSTER)
            }
            record.transfer?.let { transfer ->
                Field(R.string.activity_field_wallet, transfer.wallet)
                Field(R.string.activity_field_recipient, transfer.recipient)
                Field(
                    R.string.activity_field_asset,
                    transfer.mint ?: stringResource(R.string.activity_field_asset_sol),
                )
            }
            // An operation from a shared proposal (SEE-89): the wallet that paid, and what this
            // owner chose, by the plugin's own field names. The proposal was common to everyone
            // who received it; these parameters were this owner's, and they never left the phone.
            record.operation?.let { operation ->
                // Beside the cluster above, and for the same reason: the cluster says which chain
                // this belonged to, and this says whether anything reached it at all (SEE-97). A
                // simulated record has no signature and so no explorer link, and neither is
                // invented for it.
                Field(
                    R.string.activity_field_environment,
                    stringResource(environmentText(operation.environment)),
                    ActivityTags.ENVIRONMENT,
                )
                Field(R.string.activity_field_wallet, operation.wallet)
                if (operation.values.isNotEmpty()) {
                    Field(
                        R.string.activity_field_parameters,
                        operation.values.joinToString(separator = "  ·  ") {
                            "${it.key} ${it.text}"
                        },
                    )
                }
            }
            Field(R.string.activity_field_answered, formatInstant(record.answeredAt))
            // What the rules made of it when they answered, kept as it was read. It approved
            // nothing then and it approves nothing now (SAW-028).
            record.policy?.let {
                Field(R.string.activity_field_policy, policyText(it), ActivityTags.POLICY)
            }
            record.signature?.let { signature ->
                Field(
                    if (record.signatureIsTransaction) R.string.activity_field_signature
                    else R.string.activity_field_message_signature,
                    signature,
                    ActivityTags.SIGNATURE,
                    monospace = true,
                )
            }
            record.checkedWith?.let {
                Text(
                    stringResource(R.string.activity_checked_with, it),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(ActivityTags.CHECKED_WITH),
                )
            }
            // A message signature is not a payment, and the screen says so rather than leaving the
            // reader to work it out from the absence of a link.
            if (record.kind == ActivityKind.MessageSignature && record.signature != null) {
                Text(
                    stringResource(R.string.activity_not_a_payment),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp).testTag(ActivityTags.NOT_A_PAYMENT),
                )
            }
            explorerUrl(record)?.let { url ->
                SeekerButton(
                    text = stringResource(R.string.activity_explorer),
                    onClick = { onOpenExplorer(url) },
                    role = SeekerButtonRole.Neutral,
                    modifier = Modifier.padding(16.dp).testTag(ActivityTags.EXPLORER),
                )
                Text(
                    stringResource(R.string.activity_explorer_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    tag: String? = null,
    monospace: Boolean = false,
) {
    val name = stringResource(label)
    SeekerCard(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp)
                .testTag(tag ?: ActivityTags.field(name))
                .semantics(mergeDescendants = true) {}
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (monospace) {
                Identifier(value, Modifier.padding(top = 3.dp), maxLines = 8)
            } else {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
