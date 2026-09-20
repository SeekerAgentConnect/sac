package io.github.brrenat.seekervault.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

object RequestCarouselTags {
    const val LIST = "requestCarouselList"
    const val NEW_ITEMS = "requestCarouselNewItems"

    fun item(id: String) = "requestCarouselItem:$id"
}

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
    val initialId = items.getOrNull(initialIndex)?.id
    val activeId by
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
                    ?.key as? String ?: initialId
            }
        }
    var anchorId by remember { mutableStateOf(initialId) }
    var knownIds by remember { mutableStateOf(items.map(RequestCarouselItem::id).toSet()) }
    var unseenIds by remember { mutableStateOf(emptySet<String>()) }
    val itemIds = items.map(RequestCarouselItem::id)

    LaunchedEffect(itemIds) {
        val anchorIndex = anchorId?.let(itemIds::indexOf) ?: -1
        val insertedBeforeAnchor =
            if (anchorIndex < 0) emptySet()
            else itemIds.take(anchorIndex).filterNot(knownIds::contains).toSet()
        knownIds = itemIds.toSet()
        // At the leading edge, item keys cannot keep a former first card in place: the list is
        // pinned at offset 0, so a prepend would otherwise become the new first visible item.
        if (
            insertedBeforeAnchor.isNotEmpty() &&
                anchorIndex >= 0 &&
                carouselState.firstVisibleItemIndex == 0 &&
                carouselState.firstVisibleItemScrollOffset == 0
        ) {
            carouselState.scrollToItem(anchorIndex)
        }
        unseenIds = (unseenIds + insertedBeforeAnchor).intersect(itemIds.toSet())
    }
    LaunchedEffect(activeId) { activeId?.let { anchorId = it } }
    LaunchedEffect(carouselState) {
        snapshotFlow {
            val first = carouselState.firstVisibleItemIndex
            carouselState.layoutInfo.visibleItemsInfo
                .filter { it.index >= first }
                .mapNotNull { it.key as? String }
                .toSet()
        }
            .collect { visible -> unseenIds = unseenIds - visible }
    }

    Box(modifier.fillMaxWidth()) {
        LazyRow(
            state = carouselState,
            modifier = Modifier.fillMaxWidth().testTag(RequestCarouselTags.LIST),
            contentPadding =
                PaddingValues(
                    start = SeekerTheme.spacing.xl,
                    end = SeekerTheme.spacing.xl,
                    bottom = SeekerTheme.spacing.xs,
                ),
            horizontalArrangement = Arrangement.spacedBy(SeekerTheme.spacing.lg),
            flingBehavior = rememberSnapFlingBehavior(carouselState, RequestCarouselSnapPosition),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                RequestTile(
                    model = item.tile,
                    kind = item.kind,
                    railState =
                        when (state) {
                            RequestCarouselState.Rest ->
                                if (item.id == activeId) {
                                    RequestTileRailState.Centred
                                } else {
                                    RequestTileRailState.InRail
                                }
                        },
                    onClick = { onItemClick(item) },
                    modifier =
                        Modifier.testTag(RequestCarouselTags.item(item.id))
                            .size(
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
        if (unseenIds.isNotEmpty()) {
            Surface(
                modifier =
                    Modifier.align(Alignment.TopStart)
                        .padding(start = SeekerTheme.spacing.xl, top = SeekerTheme.spacing.xxs)
                        .testTag(RequestCarouselTags.NEW_ITEMS),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = SeekerTheme.spacing.xxs,
            ) {
                Text(
                    text = "← ${unseenIds.size} new",
                    modifier =
                        Modifier.padding(
                            horizontal = SeekerTheme.spacing.md,
                            vertical = SeekerTheme.spacing.xs,
                        ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
