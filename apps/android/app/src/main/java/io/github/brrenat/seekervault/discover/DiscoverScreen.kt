package io.github.brrenat.seekervault.discover

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.ConnectionsUiState
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.designsystem.CatalogCard
import io.github.brrenat.seekervault.designsystem.CatalogCardAccess
import io.github.brrenat.seekervault.designsystem.CatalogCardModel
import io.github.brrenat.seekervault.designsystem.CatalogDetailFact
import io.github.brrenat.seekervault.designsystem.CatalogDetailSheetState
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.NetworkChipNetwork
import io.github.brrenat.seekervault.designsystem.NoticeCard
import io.github.brrenat.seekervault.designsystem.NoticeCardKind
import io.github.brrenat.seekervault.designsystem.ScreenCaption
import io.github.brrenat.seekervault.designsystem.ScreenDestination
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ScreenScaffold
import io.github.brrenat.seekervault.designsystem.ScreenScrollBody
import io.github.brrenat.seekervault.designsystem.SeekerButton
import io.github.brrenat.seekervault.designsystem.SeekerButtonSize
import io.github.brrenat.seekervault.designsystem.SeekerButtonVariant
import io.github.brrenat.seekervault.wallet.WalletNetwork
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Test tags for the Discover tab. */
object DiscoverTags {
    const val LIST = "discover-list"
    const val LOADING = "discover-loading"
    const val EMPTY = "discover-empty"
    const val NOT_CONFIGURED = "discover-not-configured"
    const val FAILURE = "discover-failure"
    const val MORE = "discover-more"
    const val DETAIL_ACTION = "discover-detail-action"

    fun card(serverId: String) = "discover-card-$serverId"
}

/** Every interaction the Discover tab owns. */
data class DiscoverCallbacks(
    val onOpenFeed: (CatalogFeed) -> Unit,
    val onAction: (CatalogAction) -> Unit,
    val onRefresh: () -> Unit,
    val onRetry: () -> Unit,
    val onLoadMore: () -> Unit,
    val onBack: () -> Unit,
    val navigation: ScreenNavigationCallbacks,
)

/** Collects the catalog and asks for the first page when the tab is shown. */
@Composable
fun DiscoverRoute(
    viewModel: DiscoverViewModel,
    connections: ConnectionsUiState,
    gatewayUrl: String,
    scrollState: ScrollState,
    callbacks: DiscoverCallbacks,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.open() }
    DiscoverScreen(
        state = state,
        standing = { feed -> catalogCardState(feed, connections) },
        gatewayUrl = gatewayUrl,
        scrollState = scrollState,
        callbacks = callbacks,
        modifier = modifier,
    )
}

/** The catalog, composed entirely from the design system. */
@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    /** Where this phone stands with each feed, from the connection repository. */
    standing: (CatalogFeed) -> CatalogCardState,
    gatewayUrl: String,
    scrollState: ScrollState,
    callbacks: DiscoverCallbacks,
    modifier: Modifier = Modifier,
) {
    // The next page is read as the end of the list comes into view; the button below is the same
    // request for whoever gets there first, and for accessibility.
    LaunchedEffect(scrollState, state.hasMore, state.moreFailure) {
        snapshotFlow { scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue }
            .distinctUntilChanged()
            .filter { it && state.hasMore && state.moreFailure == null }
            .collect { callbacks.onLoadMore() }
    }
    ScreenScaffold(
        title = stringResource(R.string.discover_title),
        selectedDestination = ScreenDestination.Discover,
        navigationCallbacks = callbacks.navigation,
        onBack = callbacks.onBack,
        modifier = modifier,
    ) {
        ScreenScrollBody(modifier = Modifier.testTag(DiscoverTags.LIST), state = scrollState) {
            when {
                !state.configured ->
                    EmptyState(
                        screen = EmptyStateScreen.Servers,
                        title = stringResource(R.string.discover_not_configured_title),
                        body = stringResource(R.string.discover_not_configured),
                        modifier = Modifier.testTag(DiscoverTags.NOT_CONFIGURED),
                    )
                !state.loaded && state.failure != null ->
                    NoticeCard(
                        kind = NoticeCardKind.StaleQuote,
                        message = failureText(state.failure, gatewayUrl),
                        actionLabel = stringResource(R.string.discover_retry),
                        onAction = callbacks.onRetry,
                        modifier = Modifier.testTag(DiscoverTags.FAILURE),
                    )
                !state.loaded ->
                    ScreenCaption(
                        text = stringResource(R.string.discover_loading),
                        modifier = Modifier.testTag(DiscoverTags.LOADING),
                    )
                else -> {
                    ScreenCaption(text = stringResource(R.string.discover_intro))
                    if (state.failure != null) {
                        NoticeCard(
                            kind = NoticeCardKind.StaleQuote,
                            message = stringResource(R.string.discover_refresh_failed),
                            actionLabel = stringResource(R.string.discover_retry),
                            onAction = callbacks.onRetry,
                            modifier = Modifier.testTag(DiscoverTags.FAILURE),
                        )
                    }
                    SeekerButton(
                        label =
                            stringResource(
                                if (state.refreshing) R.string.discover_refreshing
                                else R.string.discover_refresh
                            ),
                        onClick = callbacks.onRefresh,
                        variant =
                            if (state.refreshing) SeekerButtonVariant.Disabled
                            else SeekerButtonVariant.Neutral,
                        size = SeekerButtonSize.Sm,
                        enabled = !state.refreshing,
                    )
                    if (state.feeds.isEmpty()) {
                        EmptyState(
                            screen = EmptyStateScreen.Servers,
                            title = stringResource(R.string.discover_empty_title),
                            body = stringResource(R.string.discover_empty),
                            modifier = Modifier.testTag(DiscoverTags.EMPTY),
                        )
                    }
                    state.feeds.forEach { feed ->
                        val card = standing(feed)
                        CatalogCard(
                            model = cardModel(feed, card),
                            status = card.status,
                            onOpen = { callbacks.onOpenFeed(feed) },
                            onAction = { callbacks.onAction(card.action) },
                            modifier =
                                Modifier.testTag(DiscoverTags.card(feed.serverId)).semantics(
                                    mergeDescendants = false
                                ) {},
                        )
                    }
                    when {
                        state.moreFailure != null ->
                            NoticeCard(
                                kind = NoticeCardKind.StaleQuote,
                                message = stringResource(R.string.discover_more_failed),
                                actionLabel = stringResource(R.string.discover_retry),
                                onAction = callbacks.onRetry,
                                modifier = Modifier.testTag(DiscoverTags.MORE),
                            )
                        state.loadingMore ->
                            ScreenCaption(
                                text = stringResource(R.string.discover_loading_more),
                                modifier = Modifier.testTag(DiscoverTags.MORE),
                            )
                        state.hasMore ->
                            SeekerButton(
                                label = stringResource(R.string.discover_load_more),
                                onClick = callbacks.onLoadMore,
                                variant = SeekerButtonVariant.Tonal,
                                size = SeekerButtonSize.Md,
                                modifier = Modifier.fillMaxWidth().testTag(DiscoverTags.MORE),
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun failureText(failure: GatewayException.Kind, gatewayUrl: String): String =
    when (failure) {
        GatewayException.Kind.CertificateRejected ->
            stringResource(R.string.discover_failed_certificate)
        GatewayException.Kind.CleartextBlocked -> stringResource(R.string.discover_failed_cleartext)
        GatewayException.Kind.Unimplemented ->
            stringResource(R.string.discover_failed_unimplemented)
        GatewayException.Kind.Unreachable ->
            stringResource(R.string.discover_failed_unreachable, gatewayUrl)
        else -> stringResource(R.string.discover_failed_other)
    }

@Composable
internal fun cardModel(feed: CatalogFeed, card: CatalogCardState): CatalogCardModel =
    CatalogCardModel(
        name = feed.name,
        initials = initialsOf(feed.name),
        description = feed.description,
        access = if (feed.restricted) CatalogCardAccess.Restricted else CatalogCardAccess.Public,
        accessLabel =
            stringResource(
                if (feed.restricted) R.string.discover_access_restricted
                else R.string.discover_access_public
            ),
        networks = feed.networks.map { it.chip() },
        statusText = card.statusText?.let { stringResource(it) },
        actionLabel = stringResource(card.actionLabel),
    )

/** The detail sheet for [feed], or for one that has left the catalog since it was opened. */
@Composable
internal fun catalogDetailState(
    feed: CatalogFeed,
    connections: ConnectionsUiState,
): CatalogDetailSheetState {
    val card = catalogCardState(feed, connections)
    return CatalogDetailSheetState(
        card = cardModel(feed, card),
        status = card.status,
        accessExplanation =
            stringResource(
                if (feed.restricted) R.string.discover_detail_access_restricted
                else R.string.discover_detail_access_public
            ),
        facts =
            listOf(
                CatalogDetailFact(
                    stringResource(R.string.discover_detail_networks),
                    feed.networks
                        .joinToString { it.label() }
                        .ifEmpty { stringResource(R.string.discover_detail_networks_none) },
                ),
                CatalogDetailFact(
                    stringResource(R.string.discover_detail_plugins),
                    feed.plugins.joinToString().ifEmpty {
                        stringResource(R.string.discover_detail_plugins_none)
                    },
                ),
                CatalogDetailFact(
                    stringResource(R.string.discover_detail_gateway),
                    feed.gatewayUrl,
                    mono = true,
                ),
                CatalogDetailFact(
                    stringResource(R.string.discover_detail_server),
                    feed.serverId,
                    mono = true,
                ),
            ),
        caption = stringResource(R.string.discover_detail_stale),
        closeLabel = stringResource(R.string.discover_close),
        actionTag = DiscoverTags.DETAIL_ACTION,
    )
}

private fun WalletNetwork.chip(): NetworkChipNetwork =
    when (this) {
        WalletNetwork.Mainnet -> NetworkChipNetwork.Mainnet
        WalletNetwork.Devnet -> NetworkChipNetwork.Devnet
        WalletNetwork.Testnet -> NetworkChipNetwork.Testnet
    }

private fun WalletNetwork.label(): String =
    when (this) {
        WalletNetwork.Mainnet -> "Solana mainnet"
        WalletNetwork.Devnet -> "Solana devnet"
        WalletNetwork.Testnet -> "Solana testnet"
    }

/** Up to two letters from the name's first words, as the connection rows do. */
internal fun initialsOf(name: String): String =
    name
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
