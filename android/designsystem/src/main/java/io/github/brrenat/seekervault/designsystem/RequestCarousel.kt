package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import kotlin.math.abs

enum class RequestCarouselState {
    Rest
}

data class RequestCarouselItem(
    val id: String,
    val tile: RequestTileModel,
    val kind: RequestTileKind,
)

@Composable
fun RequestCarousel(
    items: List<RequestCarouselItem>,
    centredIndex: Int,
    state: RequestCarouselState = RequestCarouselState.Rest,
    onItemClick: (RequestCarouselItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialIndex = centredIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val carouselState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val activeIndex by
        remember(carouselState, state) {
            derivedStateOf {
                val layout = carouselState.layoutInfo
                layout.visibleItemsInfo
                    .minByOrNull { item ->
                        val snapOffset =
                            RequestCarouselSnapPosition.position(
                                layout.viewportSize.width,
                                item.size,
                                layout.beforeContentPadding,
                                layout.afterContentPadding,
                                item.index,
                                layout.totalItemsCount,
                            )
                        abs(item.offset - snapOffset)
                    }
                    ?.index ?: initialIndex
            }
        }

    LazyRow(
        state = carouselState,
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            PaddingValues(
                start = SeekerTheme.spacing.xl,
                end = SeekerTheme.spacing.xl,
                bottom = SeekerTheme.spacing.xs,
            ),
        horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
        flingBehavior = rememberSnapFlingBehavior(carouselState, RequestCarouselSnapPosition),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            RequestTile(
                model = item.tile,
                kind = item.kind,
                railState =
                    when (state) {
                        RequestCarouselState.Rest ->
                            if (index == activeIndex) {
                                RequestTileRailState.Centred
                            } else {
                                RequestTileRailState.InRail
                            }
                    },
                onClick = { onItemClick(item) },
                modifier =
                    Modifier.size(
                        width =
                            SeekerTheme.spacing.huge * RequestCarouselTileWidthHugeUnits +
                                SeekerTheme.spacing.xxl - SeekerTheme.spacing.xxs,
                        height =
                            SeekerTheme.spacing.huge * RequestCarouselTileHeightHugeUnits +
                                SeekerTheme.spacing.xs,
                    ),
            )
        }
    }
}

private object RequestCarouselSnapPosition : SnapPosition {
    override fun position(
        layoutSize: Int,
        itemSize: Int,
        beforeContentPadding: Int,
        afterContentPadding: Int,
        itemIndex: Int,
        itemCount: Int,
    ): Int {
        val position =
            when {
                itemIndex == 0 -> SnapPosition.Start
                itemIndex == itemCount - 1 -> SnapPosition.End
                else -> SnapPosition.Center
            }
        return position.position(
            layoutSize,
            itemSize,
            beforeContentPadding,
            afterContentPadding,
            itemIndex,
            itemCount,
        )
    }
}

private const val RequestCarouselPreviewDarkMode = Configuration.UI_MODE_NIGHT_YES
private const val RequestCarouselTileWidthHugeUnits = 7
private const val RequestCarouselTileHeightHugeUnits = 7

@DesignRef(component = "request-carousel", variant = "state=rest centred=0")
@Preview(
    name = "request-carousel/state-rest-centred-0",
    widthDp = 358,
    uiMode = RequestCarouselPreviewDarkMode,
)
@Composable
internal fun RequestCarouselRestPreview() {
    val items =
        listOf(
            RequestCarouselItem(
                id = "ack",
                kind = RequestTileKind.Acknowledgement,
                tile =
                    RequestTileModel(
                        title = "Still here?",
                        sourceName = "studio-mac",
                        supportingText = "studio-mac asks",
                        warningCount = 0,
                    ),
            ),
            RequestCarouselItem(
                id = "prediction",
                kind = RequestTileKind.PredictionSignal,
                tile =
                    RequestTileModel(
                        title = "BTC < \$68k",
                        sourceName = "Jupiter Prediction demo",
                        supportingText = "You pick side and stake",
                        warningCount = 1,
                    ),
            ),
            RequestCarouselItem(
                id = "transfer",
                kind = RequestTileKind.Transfer,
                tile =
                    RequestTileModel(
                        title = "5",
                        sourceName = "studio-mac",
                        supportingText = "to FyfWsSPW…YSpEA",
                        warningCount = 1,
                        assetSymbol = "SOL",
                    ),
            ),
            RequestCarouselItem(
                id = "swap",
                kind = RequestTileKind.SwapSignal,
                tile =
                    RequestTileModel(
                        title = "SOL → USDC",
                        sourceName = "CopyTrading demo",
                        supportingText = "You set the amount",
                        warningCount = 1,
                    ),
            ),
            RequestCarouselItem(
                id = "signature",
                kind = RequestTileKind.SignatureRequest,
                tile =
                    RequestTileModel(
                        title = "74",
                        sourceName = "hermes-box",
                        supportingText = "hermes-agent login…",
                        warningCount = 0,
                        signatureByteCount = 74,
                    ),
            ),
        )
    SeekerTheme(darkTheme = true) {
        Surface(color = SeekerTheme.colors.surface0) {
            RequestCarousel(
                items = items,
                centredIndex = 0,
                onItemClick = {},
            )
        }
    }
}
