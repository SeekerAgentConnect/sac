package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/** What the last check of a network's endpoint found (design/components/rpc-endpoint-card). */
enum class RpcEndpointStatus {
    Unknown,
    Checking,
    Serves,
    Problem,
}

/** One Solana network's endpoint on the Solana RPC sheet (SEE-184). */
data class RpcEndpointCardModel(
    val network: NetworkChipNetwork,
    /** The host in use and where it comes from. Only ever a host: a URL can carry a key. */
    val inUse: String,
    /** What the last check found, in a sentence; null before any check. */
    val statusText: String?,
    val fieldLabel: String,
    val fieldValue: String,
    val fieldPlaceholder: String,
    /** Why the typed URL is refused, or why saving it failed. */
    val fieldError: String?,
    val saveLabel: String,
    val resetLabel: String,
    val canSave: Boolean,
    val canReset: Boolean,
)

@Composable
fun RpcEndpointCard(
    model: RpcEndpointCardModel,
    status: RpcEndpointStatus,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    statusModifier: Modifier = Modifier,
    saveModifier: Modifier = Modifier,
    resetModifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NetworkChip(network = model.network)
            Text(
                text = model.inUse,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        model.statusText?.let {
            Text(
                text = it,
                modifier = statusModifier,
                color =
                    when (status) {
                        RpcEndpointStatus.Unknown,
                        RpcEndpointStatus.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
                        RpcEndpointStatus.Serves -> SeekerTheme.colors.primaryText
                        RpcEndpointStatus.Problem -> SeekerTheme.colors.errorText
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SeekerTextField(
            label = model.fieldLabel,
            value = model.fieldValue,
            onValueChange = onValueChange,
            state =
                if (model.fieldError == null) DesignTextFieldState.Rest
                else DesignTextFieldState.Error,
            errorMessage = model.fieldError,
            placeholder = model.fieldPlaceholder,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            reserveErrorSpace = false,
            inputModifier = fieldModifier,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xs)) {
            SeekerButton(
                label = model.saveLabel,
                onClick = onSave,
                variant =
                    if (model.canSave) SeekerButtonVariant.Filled else SeekerButtonVariant.Disabled,
                size = SeekerButtonSize.Sm,
                enabled = model.canSave,
                modifier = saveModifier,
            )
            SeekerButton(
                label = model.resetLabel,
                onClick = onReset,
                variant =
                    if (model.canReset) SeekerButtonVariant.Neutral
                    else SeekerButtonVariant.Disabled,
                size = SeekerButtonSize.Sm,
                enabled = model.canReset,
                modifier = resetModifier,
            )
        }
    }
}

/** One network's card on the sheet, with what the card needs to report back. */
data class SolanaRpcSheetCard(
    val id: String,
    val model: RpcEndpointCardModel,
    val status: RpcEndpointStatus,
)

/**
 * The Solana RPC sheet over the Wallet tab (SEE-184): one card per network, side by side in one
 * list, because each network's endpoint is independent and none of them is "the active one".
 */
@Composable
fun SolanaRpcSheet(
    title: String,
    explanation: String,
    cards: List<SolanaRpcSheetCard>,
    caption: String,
    closeLabel: String,
    onValueChange: (String, String) -> Unit,
    onSave: (String) -> Unit,
    onReset: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: (String) -> Modifier = { Modifier },
    fieldModifier: (String) -> Modifier = { Modifier },
    statusModifier: (String) -> Modifier = { Modifier },
    saveModifier: (String) -> Modifier = { Modifier },
    resetModifier: (String) -> Modifier = { Modifier },
    closeTag: String? = null,
) {
    SheetScaffold(
        title = title,
        variant = SheetScaffoldVariant.Plain,
        onClose = onClose,
        modifier = modifier,
        closeTag = closeTag,
        body = {
            Text(
                text = explanation,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            cards.forEach { card ->
                RpcEndpointCard(
                    model = card.model,
                    status = card.status,
                    onValueChange = { onValueChange(card.id, it) },
                    onSave = { onSave(card.id) },
                    onReset = { onReset(card.id) },
                    modifier = cardModifier(card.id),
                    fieldModifier = fieldModifier(card.id),
                    statusModifier = statusModifier(card.id),
                    saveModifier = saveModifier(card.id),
                    resetModifier = resetModifier(card.id),
                )
            }
            Text(
                text = caption,
                modifier = Modifier.fillMaxWidth().padding(horizontal = SeekerTheme.spacing.xs),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        actions = {
            SeekerButton(
                label = closeLabel,
                onClick = onClose,
                variant = SeekerButtonVariant.Neutral,
                size = SeekerButtonSize.Lg,
                modifier = Modifier.weight(1f),
            )
        },
    )
}

private const val RpcEndpointDarkMode = Configuration.UI_MODE_NIGHT_YES

private val PreviewServes =
    RpcEndpointCardModel(
        network = NetworkChipNetwork.Mainnet,
        inUse = "rpc.example.com · this build's endpoint",
        statusText = "Serves Solana mainnet.",
        fieldLabel = "Your mainnet endpoint",
        fieldValue = "",
        fieldPlaceholder = "https://…",
        fieldError = null,
        saveLabel = "Check and save",
        resetLabel = "Use build default",
        canSave = false,
        canReset = false,
    )

private val PreviewProblem =
    RpcEndpointCardModel(
        network = NetworkChipNetwork.Devnet,
        inUse = "devnet.example.com · your setting",
        statusText = "It serves Solana mainnet, not devnet. Nothing it says counts for devnet.",
        fieldLabel = "Your devnet endpoint",
        fieldValue = "https://devnet.example.com",
        fieldPlaceholder = "https://…",
        fieldError = null,
        saveLabel = "Check and save",
        resetLabel = "Use build default",
        canSave = true,
        canReset = true,
    )

@Composable
private fun RpcEndpointPreviewSurface(content: @Composable () -> Unit) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, content = content)
    }
}

@DesignRef(component = "rpc-endpoint-card", variant = "status=serves")
@Preview(name = "rpc-endpoint-card/status-serves", widthDp = 358, uiMode = RpcEndpointDarkMode)
@Composable
internal fun RpcEndpointCardServesPreview() {
    RpcEndpointPreviewSurface {
        RpcEndpointCard(PreviewServes, RpcEndpointStatus.Serves, {}, {}, {})
    }
}

@DesignRef(component = "rpc-endpoint-card", variant = "status=problem")
@Preview(name = "rpc-endpoint-card/status-problem", widthDp = 358, uiMode = RpcEndpointDarkMode)
@Composable
internal fun RpcEndpointCardProblemPreview() {
    RpcEndpointPreviewSurface {
        RpcEndpointCard(PreviewProblem, RpcEndpointStatus.Problem, {}, {}, {})
    }
}

@DesignRef(component = "rpc-endpoint-card", variant = "sheet=settings")
@Preview(name = "rpc-endpoint-card/sheet-settings", widthDp = 390, uiMode = RpcEndpointDarkMode)
@Composable
internal fun SolanaRpcSheetPreview() {
    RpcEndpointPreviewSurface {
        SolanaRpcSheet(
            title = "Solana RPC",
            explanation =
                "This phone reads each Solana network through its own endpoint. Networks are " +
                    "separate, so a mainnet feed and a devnet server work side by side.",
            cards =
                listOf(
                    SolanaRpcSheetCard("mainnet", PreviewServes, RpcEndpointStatus.Serves),
                    SolanaRpcSheetCard("devnet", PreviewProblem, RpcEndpointStatus.Problem),
                ),
            caption = "Only you and this build set these; no server or feed can.",
            closeLabel = "Close",
            onValueChange = { _, _ -> },
            onSave = {},
            onReset = {},
            onClose = {},
        )
    }
}
