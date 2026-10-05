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
                time = "9:41 PM",
                warningCount = 0,
            ),
        RequestTileKind.PredictionSignal to
            RequestTileModel(
                title = "What price will Bitcoin hit on September 25?",
                sourceName = "Jupiter Prediction demo",
                time = "9:37 PM",
                warningCount = 1,
            ),
        RequestTileKind.SwapSignal to
            RequestTileModel(
                title = "SOL → USDC",
                sourceName = "CopyTrading demo",
                time = "9:30 PM",
                warningCount = 1,
            ),
        RequestTileKind.SignatureRequest to
            RequestTileModel(
                title = "74 bytes",
                sourceName = "hermes-box",
                time = "9:12 PM",
                warningCount = 0,
            ),
        RequestTileKind.Transfer to
            RequestTileModel(
                title = "5 SOL",
                sourceName = "studio-mac",
                time = "9:36 PM",
                warningCount = 3,
            ),
    )

/** The SEE-183 reference tiles (`docs/design/request-tile/request-tile.html`). */
private val inRules =
    RequestTileModel(
        title = "What price will Bitcoin hit on September 25?",
        sourceName = "Polymarket",
        time = "9:37 PM",
        warningCount = 0,
        sourceColour = SourceColour.Tangerine,
    )
private val oneWarning = inRules.copy(title = "Bitcoin above ___ on Sept 26?", warningCount = 1)
private val threeWarnings =
    RequestTileModel(
        title = "5 SOL",
        sourceName = "Personal MCP",
        time = "9:36 PM",
        warningCount = 3,
        sourceColour = SourceColour.Sky,
    )
private val longServer =
    inRules.copy(title = "Baltimore Orioles", sourceName = "Very Long Server Name MCP")

@Composable
private fun RequestTilePreview(
    model: RequestTileModel,
    kind: RequestTileKind,
    railState: RequestTileRailState,
) {
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            RequestTile(model = model, kind = kind, railState = railState, onClick = {})
        }
    }
}

@Composable
private fun RequestTilePreview(kind: RequestTileKind, railState: RequestTileRailState) =
    RequestTilePreview(checkNotNull(requestTilePreviews[kind]), kind, railState)

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

@DesignRef(component = "request-tile", variant = "state=centred status=ok")
@Preview(name = "request-tile/state-centred-status-ok", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileCentredOkPreview() =
    RequestTilePreview(inRules, RequestTileKind.PredictionSignal, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "state=centred status=warning")
@Preview(name = "request-tile/state-centred-status-warning", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileCentredWarningPreview() =
    RequestTilePreview(oneWarning, RequestTileKind.PredictionSignal, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "state=centred status=warnings")
@Preview(name = "request-tile/state-centred-status-warnings", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileCentredWarningsPreview() =
    RequestTilePreview(threeWarnings, RequestTileKind.Transfer, RequestTileRailState.Centred)

@DesignRef(component = "request-tile", variant = "state=in-rail status=ok")
@Preview(name = "request-tile/state-in-rail-status-ok", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileInRailOkPreview() =
    RequestTilePreview(inRules, RequestTileKind.PredictionSignal, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "state=in-rail status=warning")
@Preview(name = "request-tile/state-in-rail-status-warning", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileInRailWarningPreview() =
    RequestTilePreview(oneWarning, RequestTileKind.PredictionSignal, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "state=in-rail status=warnings")
@Preview(name = "request-tile/state-in-rail-status-warnings", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileInRailWarningsPreview() =
    RequestTilePreview(threeWarnings, RequestTileKind.Transfer, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "title=l")
@Preview(name = "request-tile/title-l", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileTitleLargePreview() =
    RequestTilePreview(longServer, RequestTileKind.PredictionSignal, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "title=m")
@Preview(name = "request-tile/title-m", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileTitleMediumPreview() =
    RequestTilePreview(inRules, RequestTileKind.PredictionSignal, RequestTileRailState.InRail)

@DesignRef(component = "request-tile", variant = "title=s")
@Preview(name = "request-tile/title-s", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileTitleSmallPreview() =
    RequestTilePreview(
        inRules.copy(
            title = "Bitcoin Up or Down - September 25, 4:00PM-8:00PM ET · Polymarket hourly"
        ),
        RequestTileKind.PredictionSignal,
        RequestTileRailState.InRail,
    )

@DesignRef(component = "request-tile", variant = "title=longest")
@Preview(name = "request-tile/title-longest", uiMode = RequestTilePreviewDarkMode)
@Composable
internal fun RequestTileTitleLongestPreview() =
    RequestTilePreview(
        inRules.copy(
            title =
                "Will the Federal Reserve cut interest rates by 50 basis points or more at " +
                    "the December meeting?"
        ),
        RequestTileKind.PredictionSignal,
        RequestTileRailState.Centred,
    )
