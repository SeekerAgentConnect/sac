package io.github.brrenat.seekervault.activity

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.formatInstant
import io.github.brrenat.seekervault.ui.CardDivider
import io.github.brrenat.seekervault.ui.GlassCard
import io.github.brrenat.seekervault.ui.GlassScreen
import io.github.brrenat.seekervault.ui.Glyph
import io.github.brrenat.seekervault.ui.MessageOverlay
import io.github.brrenat.seekervault.ui.MonoText
import io.github.brrenat.seekervault.ui.Nocturne
import io.github.brrenat.seekervault.ui.PillButton
import io.github.brrenat.seekervault.ui.SectionLabel
import io.github.brrenat.seekervault.ui.Space

/**
 * One record in full: who asked, what the owner reviewed, which network it was on, how it ended,
 * and the signature. A transfer that was sent offers the explorer for its own cluster; a signed
 * message says in words that it is not a payment and offers nothing, because there is nothing to
 * look at.
 */
@Composable
@Suppress("LongMethod")
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
    GlassScreen(
        title = stringResource(R.string.activity_details_title),
        subtitle = record.source,
        onBack = onBack,
        modifier = modifier,
        overlay = {
            MessageOverlay(snackbar, Modifier.align(Alignment.BottomCenter), overTabBar = false)
        },
    ) {
        GlassCard {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Glyph.Activity,
                    contentDescription = null,
                    tint = Nocturne.Accent200,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(outcomeText(record.outcome)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Nocturne.Text,
                    modifier = Modifier.testTag(ActivityTags.OUTCOME),
                )
            }
            Text(
                operationText(record),
                style = MaterialTheme.typography.bodyMedium,
                color = Nocturne.Neutral200,
                modifier = Modifier.testTag(ActivityTags.OPERATION),
            )
            record.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Nocturne.Neutral500,
                    modifier = Modifier.testTag(ActivityTags.DETAIL),
                )
            }
        }
        GlassCard(spacing = 0.dp) {
            Field(R.string.activity_field_source, record.source, ActivityTags.SOURCE)
            if (record.serverHost.isNotEmpty()) {
                CardDivider()
                Field(R.string.activity_field_server, record.serverHost, monospace = true)
            }
            CardDivider()
            Field(R.string.activity_field_request, kindText(record.kind))
            // The cluster is on its own line for a transfer, always: a signature without one says
            // nothing at all.
            clusterText(record)?.let {
                CardDivider()
                Field(R.string.activity_field_cluster, it, ActivityTags.CLUSTER)
            }
            record.transfer?.let { transfer ->
                CardDivider()
                Field(R.string.activity_field_wallet, transfer.wallet, monospace = true)
                CardDivider()
                Field(R.string.activity_field_recipient, transfer.recipient, monospace = true)
                CardDivider()
                Field(
                    R.string.activity_field_asset,
                    transfer.mint ?: stringResource(R.string.activity_field_asset_sol),
                    monospace = transfer.mint != null,
                )
            }
            CardDivider()
            Field(R.string.activity_field_answered, formatInstant(record.answeredAt))
            // What the rules made of it when they answered, kept as it was read. It approved
            // nothing then and it approves nothing now (SAW-028).
            record.policy?.let {
                CardDivider()
                Field(R.string.activity_field_policy, policyText(it), ActivityTags.POLICY)
            }
            record.signature?.let { signature ->
                CardDivider()
                Field(
                    if (record.signatureIsTransaction) R.string.activity_field_signature
                    else R.string.activity_field_message_signature,
                    signature,
                    ActivityTags.SIGNATURE,
                    monospace = true,
                )
            }
        }
        record.checkedWith?.let {
            Text(
                stringResource(R.string.activity_checked_with, it),
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
                modifier = Modifier.testTag(ActivityTags.CHECKED_WITH),
            )
        }
        // A message signature is not a payment, and the screen says so rather than leaving the
        // reader to work it out from the absence of a link.
        if (record.kind == ActivityKind.MessageSignature && record.signature != null) {
            Text(
                stringResource(R.string.activity_not_a_payment),
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
                modifier = Modifier.testTag(ActivityTags.NOT_A_PAYMENT),
            )
        }
        explorerUrl(record)?.let { url ->
            PillButton(
                stringResource(R.string.activity_explorer),
                { onOpenExplorer(url) },
                icon = Glyph.Next,
                modifier = Modifier.fillMaxWidth().testTag(ActivityTags.EXPLORER),
            )
            Text(
                stringResource(R.string.activity_explorer_note),
                style = MaterialTheme.typography.bodySmall,
                color = Nocturne.Neutral500,
            )
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
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = Space.Md)
            .testTag(tag ?: ActivityTags.field(name))
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SectionLabel(name)
        if (monospace) {
            MonoText(value, color = Nocturne.Text)
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = Nocturne.Text)
        }
    }
}
