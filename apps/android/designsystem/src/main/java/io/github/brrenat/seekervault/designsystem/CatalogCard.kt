package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/** Who may read a catalog feed, as its gateway registered it (SEE-176). */
enum class CatalogCardAccess {
    Public,
    Restricted,
}

/** Where this phone stands with a catalog feed, which decides how its action is drawn. */
enum class CatalogCardStatus {
    /** Nothing added: Connect, or Request access. */
    Available,
    /** An add or an access request is in flight. */
    Working,
    /** Added and restricted, waiting for the publisher's decision. */
    Waiting,
    /** Added and readable. */
    Connected,
    /** Added, but rejected, revoked, expired or otherwise not readable. */
    Stopped,
}

data class CatalogCardModel(
    val name: String,
    val initials: String,
    val description: String,
    val access: CatalogCardAccess,
    val accessLabel: String,
    val networks: List<NetworkChipNetwork>,
    /** Where this phone stands, or null when nothing has been added. */
    val statusText: String?,
    val actionLabel: String,
)

/** One feed in the Discover catalog (design/components/catalog-card/spec.md). */
@Composable
fun CatalogCard(
    model: CatalogCardModel,
    status: CatalogCardStatus,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = "Open ${model.name}" }
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceAvatar(sourceName = model.name, initials = model.initials)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
            ) {
                Text(
                    text = model.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = model.accessLabel,
                    color =
                        when (model.access) {
                            CatalogCardAccess.Public -> MaterialTheme.colorScheme.onSurfaceVariant
                            CatalogCardAccess.Restricted -> MaterialTheme.colorScheme.primary
                        },
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            text = model.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (model.networks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs)) {
                model.networks.forEach { NetworkChip(network = it) }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.statusText.orEmpty(),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            SeekerButton(
                label = model.actionLabel,
                onClick = onAction,
                variant =
                    when (status) {
                        CatalogCardStatus.Available -> SeekerButtonVariant.Filled
                        CatalogCardStatus.Working -> SeekerButtonVariant.Disabled
                        CatalogCardStatus.Waiting,
                        CatalogCardStatus.Stopped -> SeekerButtonVariant.Neutral
                        CatalogCardStatus.Connected -> SeekerButtonVariant.Tonal
                    },
                size = SeekerButtonSize.Sm,
                enabled = status != CatalogCardStatus.Working,
            )
        }
    }
}

private const val CatalogCardDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun CatalogCardPreview(model: CatalogCardModel, status: CatalogCardStatus) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            CatalogCard(model = model, status = status, onOpen = {}, onAction = {})
        }
    }
}

private val PreviewPublic =
    CatalogCardModel(
        name = "CopyTrading signals",
        initials = "CT",
        description =
            "Swap ideas from a desk of three traders, published as they happen. Each one is a " +
                "proposal you review and sign yourself.",
        access = CatalogCardAccess.Public,
        accessLabel = "Public",
        networks = listOf(NetworkChipNetwork.Mainnet),
        statusText = null,
        actionLabel = "Connect",
    )

@DesignRef(component = "catalog-card", variant = "status=available")
@Preview(name = "catalog-card/status-available", widthDp = 358, uiMode = CatalogCardDarkMode)
@Composable
internal fun CatalogCardAvailablePreview() =
    CatalogCardPreview(PreviewPublic, CatalogCardStatus.Available)

@DesignRef(component = "catalog-card", variant = "status=waiting")
@Preview(name = "catalog-card/status-waiting", widthDp = 358, uiMode = CatalogCardDarkMode)
@Composable
internal fun CatalogCardWaitingPreview() =
    CatalogCardPreview(
        PreviewPublic.copy(
            name = "Members desk",
            initials = "MD",
            access = CatalogCardAccess.Restricted,
            accessLabel = "Restricted · approval needed",
            networks = listOf(NetworkChipNetwork.Mainnet, NetworkChipNetwork.Devnet),
            statusText = "Waiting for the publisher to approve",
            actionLabel = "View",
        ),
        CatalogCardStatus.Waiting,
    )

@DesignRef(component = "catalog-card", variant = "status=connected")
@Preview(name = "catalog-card/status-connected", widthDp = 358, uiMode = CatalogCardDarkMode)
@Composable
internal fun CatalogCardConnectedPreview() =
    CatalogCardPreview(
        PreviewPublic.copy(statusText = "Connected", actionLabel = "Open"),
        CatalogCardStatus.Connected,
    )
