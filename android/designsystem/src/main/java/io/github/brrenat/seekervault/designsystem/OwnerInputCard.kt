package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

enum class OwnerInputCardState {
    Chosen,
    Unchosen,
}

enum class OwnerInputCardKind {
    Swap,
    Prediction,
}

@Composable
fun OwnerInputCard(
    kind: OwnerInputCardKind,
    state: OwnerInputCardState,
    summary: String?,
    onChooseOrEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(state == OwnerInputCardState.Unchosen || !summary.isNullOrBlank()) {
        "A chosen owner input needs a summary"
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SeekerTheme.radii.lg))
                .background(SeekerTheme.colors.surface1)
                .padding(SeekerTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.xxs),
            ) {
                Text(
                    text = kind.title(),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                    text =
                        summary.takeIf { state == OwnerInputCardState.Chosen } ?: "Not chosen yet",
                    color = SeekerTheme.colors.primaryText,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            SeekerButton(
                label =
                    if (state == OwnerInputCardState.Chosen) {
                        "Edit"
                    } else {
                        kind.chooseLabel()
                    },
                onClick = onChooseOrEdit,
                variant =
                    if (state == OwnerInputCardState.Chosen) {
                        SeekerButtonVariant.Tonal
                    } else {
                        SeekerButtonVariant.Filled
                    },
                size = SeekerButtonSize.Md,
            )
        }
        Text(
            text = kind.description(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun OwnerInputCardKind.title(): String =
    when (this) {
        OwnerInputCardKind.Swap -> "Your part · amount"
        OwnerInputCardKind.Prediction -> "Your part · side and stake"
    }

private fun OwnerInputCardKind.chooseLabel(): String =
    when (this) {
        OwnerInputCardKind.Swap -> "Choose an amount"
        OwnerInputCardKind.Prediction -> "Choose side and stake"
    }

private fun OwnerInputCardKind.description(): String =
    when (this) {
        OwnerInputCardKind.Swap ->
            "The signal names the route and caps slippage, not the amount. That is yours to choose."
        OwnerInputCardKind.Prediction ->
            "The signal names the market, not your side or your stake. Both are yours to choose."
    }

private const val OwnerInputCardPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun OwnerInputCardPreview(
    kind: OwnerInputCardKind,
    state: OwnerInputCardState,
    summary: String?,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            OwnerInputCard(
                kind = kind,
                state = state,
                summary = summary,
                onChooseOrEdit = {},
            )
        }
    }
}

@DesignRef(component = "owner-input-card", variant = "state=chosen kind=prediction")
@Preview(
    name = "owner-input-card/state-chosen-kind-prediction",
    widthDp = 358,
    uiMode = OwnerInputCardPreviewDarkMode,
)
@Composable
private fun OwnerInputCardPredictionChosenPreview() =
    OwnerInputCardPreview(
        kind = OwnerInputCardKind.Prediction,
        state = OwnerInputCardState.Chosen,
        summary = "25 USDC on Yes",
    )

@DesignRef(component = "owner-input-card", variant = "state=chosen kind=swap")
@Preview(
    name = "owner-input-card/state-chosen-kind-swap",
    widthDp = 358,
    uiMode = OwnerInputCardPreviewDarkMode,
)
@Composable
private fun OwnerInputCardSwapChosenPreview() =
    OwnerInputCardPreview(
        kind = OwnerInputCardKind.Swap,
        state = OwnerInputCardState.Chosen,
        summary = "1.5 SOL · slippage 0.5%",
    )

@DesignRef(component = "owner-input-card", variant = "state=unchosen kind=swap")
@Preview(
    name = "owner-input-card/state-unchosen-kind-swap",
    widthDp = 358,
    uiMode = OwnerInputCardPreviewDarkMode,
)
@Composable
private fun OwnerInputCardSwapUnchosenPreview() =
    OwnerInputCardPreview(
        kind = OwnerInputCardKind.Swap,
        state = OwnerInputCardState.Unchosen,
        summary = null,
    )
