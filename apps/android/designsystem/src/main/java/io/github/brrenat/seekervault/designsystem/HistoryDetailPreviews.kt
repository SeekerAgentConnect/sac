package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

/*
 * SEE-161's ten variant screens, each drawn in the 390 x 844 viewport of its reference in
 * docs/design/history-details/screens/, and two whole bodies so the sections below the fold have
 * baselines too.
 */

private const val HistoryDetailDarkMode = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun HistoryDetailScreenPreview(model: HistoryDetailModel) {
    SeekerTheme(darkTheme = true) {
        HistoryDetailScreen(model = model, callbacks = HistoryDetailCallbacks(onBack = {}))
    }
}

@Composable
private fun HistoryDetailBodyPreview(model: HistoryDetailModel) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0, modifier = Modifier.fillMaxSize()) {
            HistoryDetailBody(
                model = model,
                callbacks = HistoryDetailCallbacks(onBack = {}),
                modifier = Modifier.fillMaxWidth().padding(SeekerTheme.spacing.xl),
            )
        }
    }
}

@DesignRef(component = "history-detail", variant = "screen=long-content")
@Preview(
    name = "history-detail/screen-long-content",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailLongContentPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.longContent)

@DesignRef(component = "history-detail", variant = "screen=approved-pending")
@Preview(
    name = "history-detail/screen-approved-pending",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailApprovedPendingPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.approvedPending)

@DesignRef(component = "history-detail", variant = "screen=approved-confirmed")
@Preview(
    name = "history-detail/screen-approved-confirmed",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailApprovedConfirmedPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.approvedConfirmed)

@DesignRef(component = "history-detail", variant = "screen=approved-failed")
@Preview(
    name = "history-detail/screen-approved-failed",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailApprovedFailedPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.approvedFailed)

@DesignRef(component = "history-detail", variant = "screen=declined")
@Preview(
    name = "history-detail/screen-declined",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailDeclinedPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.declined)

@DesignRef(component = "history-detail", variant = "screen=expired")
@Preview(
    name = "history-detail/screen-expired",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailExpiredPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.expired)

@DesignRef(component = "history-detail", variant = "screen=cancelled")
@Preview(
    name = "history-detail/screen-cancelled",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailCancelledPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.cancelled)

@DesignRef(component = "history-detail", variant = "screen=dismissed-signal")
@Preview(
    name = "history-detail/screen-dismissed-signal",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailDismissedSignalPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.dismissedSignal)

@DesignRef(component = "history-detail", variant = "screen=signed-message")
@Preview(
    name = "history-detail/screen-signed-message",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailSignedMessagePreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.signedMessage)

@DesignRef(component = "history-detail", variant = "screen=sandbox")
@Preview(
    name = "history-detail/screen-sandbox",
    widthDp = 390,
    heightDp = 844,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailSandboxPreview() =
    HistoryDetailScreenPreview(HistoryDetailFixtures.sandbox)

@DesignRef(component = "history-detail", variant = "body=approved-confirmed")
@Preview(
    name = "history-detail/body-approved-confirmed",
    widthDp = 390,
    heightDp = 1500,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailBodyApprovedConfirmedPreview() =
    HistoryDetailBodyPreview(HistoryDetailFixtures.approvedConfirmed)

@DesignRef(component = "history-detail", variant = "body=cancelled")
@Preview(
    name = "history-detail/body-cancelled",
    widthDp = 390,
    heightDp = 1500,
    uiMode = HistoryDetailDarkMode,
)
@Composable
internal fun HistoryDetailBodyCancelledPreview() =
    HistoryDetailBodyPreview(HistoryDetailFixtures.cancelled)
