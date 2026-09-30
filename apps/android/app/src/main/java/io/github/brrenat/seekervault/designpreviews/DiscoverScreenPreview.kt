package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.CatalogCardStatus
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.discover.CatalogAction
import io.github.brrenat.seekervault.discover.CatalogCardState
import io.github.brrenat.seekervault.discover.CatalogFeed
import io.github.brrenat.seekervault.discover.DiscoverCallbacks
import io.github.brrenat.seekervault.discover.DiscoverScreen
import io.github.brrenat.seekervault.discover.DiscoverUiState
import io.github.brrenat.seekervault.wallet.WalletNetwork

private val PreviewPublic =
    CatalogFeed(
        gatewayUrl = "https://feeds.example.com",
        serverId = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
        name = "CopyTrading signals",
        description =
            "Swap ideas from a desk of three traders, published as they happen. Each one is a " +
                "proposal you review and sign yourself.",
        restricted = false,
        networks = listOf(WalletNetwork.Mainnet),
        plugins = listOf("jupiter.swap"),
    )

private val PreviewRestricted =
    CatalogFeed(
        gatewayUrl = "https://feeds.example.com",
        serverId = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
        name = "Members desk",
        description =
            "The same desk's members-only calls. The publisher approves each device after you " +
                "prove which wallet you hold.",
        restricted = true,
        networks = listOf(WalletNetwork.Mainnet, WalletNetwork.Devnet),
        plugins = listOf("jupiter.swap"),
    )

private val PreviewPrediction =
    CatalogFeed(
        gatewayUrl = "https://feeds.example.com",
        serverId = "c0000000-0000-4000-8000-00000000000c",
        name = "Prediction picks",
        description = "Daily picks on prediction markets, with the reasoning attached.",
        restricted = false,
        networks = listOf(WalletNetwork.Mainnet),
        plugins = listOf("jupiter.prediction"),
    )

private val PreviewCallbacks =
    DiscoverCallbacks(
        onOpenFeed = {},
        onAction = {},
        onRefresh = {},
        onRetry = {},
        onLoadMore = {},
        onBack = {},
        navigation = ScreenNavigationCallbacks({}, {}, {}, {}),
    )

private fun available(feed: CatalogFeed) =
    CatalogCardState(
        CatalogCardStatus.Available,
        null,
        if (feed.restricted) R.string.discover_request_access else R.string.discover_connect,
        CatalogAction.Onboard(feed),
    )

@Composable
private fun DiscoverPreview(standing: (CatalogFeed) -> CatalogCardState) {
    SeekerTheme(darkTheme = true) {
        DiscoverScreen(
            state =
                DiscoverUiState(
                    configured = true,
                    loaded = true,
                    feeds = listOf(PreviewPublic, PreviewRestricted, PreviewPrediction),
                    nextPageToken = "more",
                ),
            standing = standing,
            gatewayUrl = "https://feeds.example.com",
            scrollState = rememberScrollState(),
            callbacks = PreviewCallbacks,
        )
    }
}

/** The catalog on a fresh install: nothing added, a public and a restricted feed (SEE-176). */
@DesignRef(component = "screens", variant = "discover")
@Preview(
    name = "screens/discover",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DiscoverScreenPreview() = DiscoverPreview(::available)

/**
 * The restricted feed added from Discover and waiting for its publisher's approval, beside a public
 * feed already connected: each card says what the phone holds, never "connected" for the first.
 */
@DesignRef(component = "screens", variant = "discover-pending")
@Preview(
    name = "screens/discover-pending",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DiscoverPendingPreview() = DiscoverPreview { feed ->
    when (feed) {
        PreviewRestricted ->
            CatalogCardState(
                CatalogCardStatus.Waiting,
                R.string.discover_status_pending,
                R.string.discover_view,
                CatalogAction.Open("restricted"),
                "restricted",
            )
        PreviewPublic ->
            CatalogCardState(
                CatalogCardStatus.Connected,
                R.string.discover_status_connected,
                R.string.discover_open,
                CatalogAction.Open("public"),
                "public",
            )
        else -> available(feed)
    }
}
