package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

private const val RequestTilePreviewDarkMode = Configuration.UI_MODE_NIGHT_YES

private val requestTilePreviews =
    mapOf(
        RequestTileKind.Acknowledgement to
            RequestTileModel(
                title = "Still here?",
                sourceName = "studio-mac",
                supportingText = "studio-mac asks",
                warningCount = 0,
            ),
        RequestTileKind.PredictionSignal to
            RequestTileModel(
                title = "BTC < \$68k",
                sourceName = "Jupiter Prediction demo",
                supportingText = "You pick side and amount",
                warningCount = 1,
            ),
        RequestTileKind.SwapSignal to
            RequestTileModel(
                title = "SOL → USDC",
                sourceName = "CopyTrading demo",
                supportingText = "You set the amount",
                warningCount = 1,
            ),
        RequestTileKind.SignatureRequest to
            RequestTileModel(
                title = "74",
                sourceName = "hermes-box",
                supportingText = "hermes-agent login…",
                warningCount = 0,
                signatureByteCount = 74,
            ),
        RequestTileKind.Transfer to
            RequestTileModel(
                title = "5",
                sourceName = "studio-mac",
                supportingText = "to FyfWsSPW…YSpEA",
                warningCount = 1,
                assetSymbol = "SOL",
            ),
    )

@Composable
private fun RequestTilePreview(kind: RequestTileKind, railState: RequestTileRailState) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            RequestTile(
                model = checkNotNull(requestTilePreviews[kind]),
                kind = kind,
                railState = railState,
                onClick = {},
            )
        }
    }
}

@DesignRef(component = "request-tile", variant = "kind=ack state=centred")
@Preview(name = "request-tile/kind-ack-state-centred", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileAckCentredPreview() =
    RequestTilePreview(RequestTileKind.Acknowledgement, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "kind=ack state=in-rail")
@Preview(name = "request-tile/kind-ack-state-in-rail", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileAckInRailPreview() =
    RequestTilePreview(RequestTileKind.Acknowledgement, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "kind=pred state=centred")
@Preview(name = "request-tile/kind-pred-state-centred", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTilePredictionCentredPreview() =
    RequestTilePreview(RequestTileKind.PredictionSignal, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "kind=pred state=in-rail")
@Preview(name = "request-tile/kind-pred-state-in-rail", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTilePredictionInRailPreview() =
    RequestTilePreview(RequestTileKind.PredictionSignal, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "kind=sig state=centred")
@Preview(name = "request-tile/kind-sig-state-centred", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileSwapCentredPreview() =
    RequestTilePreview(RequestTileKind.SwapSignal, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "kind=sig state=in-rail")
@Preview(name = "request-tile/kind-sig-state-in-rail", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileSwapInRailPreview() =
    RequestTilePreview(RequestTileKind.SwapSignal, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "kind=sign state=centred")
@Preview(name = "request-tile/kind-sign-state-centred", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileSignatureCentredPreview() =
    RequestTilePreview(RequestTileKind.SignatureRequest, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "kind=sign state=in-rail")
@Preview(name = "request-tile/kind-sign-state-in-rail", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileSignatureInRailPreview() =
    RequestTilePreview(RequestTileKind.SignatureRequest, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "kind=tx state=centred")
@Preview(name = "request-tile/kind-tx-state-centred", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileTransferCentredPreview() =
    RequestTilePreview(RequestTileKind.Transfer, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "kind=tx state=in-rail")
@Preview(name = "request-tile/kind-tx-state-in-rail", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileTransferInRailPreview() =
    RequestTilePreview(RequestTileKind.Transfer, RequestTileRailState.InRail)
