package io.github.brrenat.seekervault.activity

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.BackButton
import io.github.brrenat.seekervault.connections.formatInstant

/**
 * One record in full: who asked, what the owner reviewed, which network it was on, how it ended,
 * and the signature. A transfer that was sent offers the explorer for its own cluster; a signed
 * message says in words that it is not a payment and offers nothing, because there is nothing to
 * look at. Stock Material 3 only.
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_details_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
            Field(R.string.activity_field_answered, formatInstant(record.answeredAt))
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
                OutlinedButton(
                    onClick = { onOpenExplorer(url) },
                    modifier = Modifier.padding(16.dp).testTag(ActivityTags.EXPLORER),
                ) {
                    Text(stringResource(R.string.activity_explorer))
                }
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
    ListItem(
        overlineContent = { Text(name) },
        headlineContent = {
            Text(
                value,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        modifier = Modifier.testTag(tag ?: ActivityTags.field(name)),
    )
}
