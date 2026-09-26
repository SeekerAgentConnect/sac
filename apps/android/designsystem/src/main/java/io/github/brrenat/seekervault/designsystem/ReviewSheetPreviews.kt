package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

private const val ReviewSheetPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun ReviewSheetPreview(state: ReviewSheetState) {
    SeekerTheme(darkTheme = true) {
        ReviewSheet(
            state = state,
            onPrimary = {},
            onSecondary = {},
            onRules = {},
            onChoose = {},
            onClose = {},
            modifier = Modifier,
        )
    }
}

@DesignRef(component = "screens", variant = "sheet-transfer")
@Preview(
    name = "screens/sheet-transfer",
    widthDp = 390,
    uiMode = ReviewSheetPreviewDarkMode,
)
@Composable
internal fun ReviewSheetTransferPreview() = ReviewSheetPreview(ReviewSheetFixtures.Transfer)

@DesignRef(component = "screens", variant = "sheet-swap")
@Preview(name = "screens/sheet-swap", widthDp = 390, uiMode = ReviewSheetPreviewDarkMode)
@Composable
internal fun ReviewSheetSwapPreview() = ReviewSheetPreview(ReviewSheetFixtures.Swap)

@DesignRef(component = "screens", variant = "sheet-prediction")
@Preview(
    name = "screens/sheet-prediction",
    widthDp = 390,
    uiMode = ReviewSheetPreviewDarkMode,
)
@Composable
internal fun ReviewSheetPredictionPreview() = ReviewSheetPreview(ReviewSheetFixtures.Prediction)

@DesignRef(component = "screens", variant = "sheet-signature")
@Preview(
    name = "screens/sheet-signature",
    widthDp = 390,
    uiMode = ReviewSheetPreviewDarkMode,
)
@Composable
internal fun ReviewSheetSignaturePreview() = ReviewSheetPreview(ReviewSheetFixtures.Signature)

@DesignRef(component = "screens", variant = "sheet-acknowledge")
@Preview(
    name = "screens/sheet-acknowledge",
    widthDp = 390,
    uiMode = ReviewSheetPreviewDarkMode,
)
@Composable
internal fun ReviewSheetAcknowledgePreview() = ReviewSheetPreview(ReviewSheetFixtures.Acknowledge)
