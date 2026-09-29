package io.github.brrenat.seekervault.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.connections.GatewayException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Discover tab shows (SEE-176). */
data class DiscoverUiState(
    /** False when this build names no catalog gateway; nothing is ever asked then. */
    val configured: Boolean,
    val feeds: List<CatalogFeed> = emptyList(),
    /** True once a first page has been answered, even an empty one. */
    val loaded: Boolean = false,
    /** The first page is in flight and there is nothing to show yet. */
    val loading: Boolean = false,
    /** A pull to refresh is in flight over what is shown. */
    val refreshing: Boolean = false,
    /** The next page is in flight. */
    val loadingMore: Boolean = false,
    /** Why the first page, or the last refresh, could not be read. */
    val failure: GatewayException.Kind? = null,
    /** Why the next page could not be read. What is shown stays. */
    val moreFailure: GatewayException.Kind? = null,
    /** Where the next page starts, or null at the end of the catalog. */
    val nextPageToken: String? = null,
) {
    val hasMore: Boolean
        get() = nextPageToken != null
}

/**
 * The Discover tab's catalog, read page by page from one configured gateway.
 *
 * It only reads. Browsing, paging, refreshing and opening a card's details add no connection, open
 * no wallet, send no access request and subscribe to nothing: every one of those is the owner's
 * explicit action, and each goes through the flow that already does it (Add connection and the
 * connection's own detail sheet). What this phone already holds is not sent anywhere either — the
 * card's local state is worked out on the phone from the connection repository.
 */
class DiscoverViewModel(
    private val catalog: FeedCatalog,
    /** The configured gateway origin, or "" for none. */
    private val gatewayUrl: String,
    private val cleartextPermitted: (host: String) -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState(configured = gatewayUrl.isNotEmpty()))
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    /**
     * Which read the shown list belongs to. A refresh starts a new one, and a page that was asked
     * for under an older one is dropped rather than appended to a list it doesn't continue.
     */
    private var generation = 0

    /** Reads the first page the first time the tab is shown; afterwards it keeps what it has. */
    fun open() {
        val now = _state.value
        if (!now.configured || now.loaded || now.loading) return
        loadFirst(refreshing = false)
    }

    fun refresh() {
        val now = _state.value
        if (!now.configured || now.loading || now.refreshing) return
        if (!now.loaded) loadFirst(refreshing = false) else loadFirst(refreshing = true)
    }

    /** Tries again whatever last failed. */
    fun retry() {
        val now = _state.value
        when {
            !now.configured -> Unit
            !now.loaded -> loadFirst(refreshing = false)
            now.moreFailure != null -> loadMore()
            else -> refresh()
        }
    }

    fun loadMore() {
        val now = _state.value
        val token = now.nextPageToken ?: return
        if (now.loading || now.refreshing || now.loadingMore) return
        val asked = generation
        _state.update { it.copy(loadingMore = true, moreFailure = null) }
        viewModelScope.launch {
            val result = read(token)
            if (asked != generation) return@launch
            _state.update { state ->
                result.fold(
                    onSuccess = { page ->
                        val known = state.feeds.mapTo(HashSet()) { it.key }
                        val feeds = state.feeds + page.feeds.filter { it.key !in known }
                        state.copy(
                            feeds = feeds.take(MAX_FEEDS),
                            loadingMore = false,
                            // A gateway that hands back the token it was asked with, or a catalog
                            // past the bound, is the end of the walk rather than a loop.
                            nextPageToken =
                                page.nextPageToken.takeIf {
                                    it != token && feeds.size < MAX_FEEDS
                                },
                        )
                    },
                    onFailure = { error ->
                        state.copy(loadingMore = false, moreFailure = kindOf(error))
                    },
                )
            }
        }
    }

    private fun loadFirst(refreshing: Boolean) {
        val asked = ++generation
        _state.update {
            it.copy(
                loading = !refreshing,
                refreshing = refreshing,
                loadingMore = false,
                failure = null,
                moreFailure = null,
            )
        }
        viewModelScope.launch {
            val result = read("")
            if (asked != generation) return@launch
            _state.update { state ->
                result.fold(
                    onSuccess = { page ->
                        state.copy(
                            feeds = page.feeds.take(MAX_FEEDS),
                            loaded = true,
                            loading = false,
                            refreshing = false,
                            nextPageToken = page.nextPageToken,
                        )
                    },
                    // A refresh that fails keeps what was shown and says so; a first read that
                    // fails has nothing to keep.
                    onFailure = { error ->
                        state.copy(loading = false, refreshing = false, failure = kindOf(error))
                    },
                )
            }
        }
    }

    private suspend fun read(token: String): Result<CatalogPage> =
        try {
            Result.success(
                CatalogFeeds.page(
                    catalog.recommended(gatewayUrl, PAGE_SIZE, token),
                    cleartextPermitted,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun kindOf(error: Throwable): GatewayException.Kind =
        (error as? GatewayException)?.kind ?: GatewayException.Kind.Other

    companion object {
        /** A screenful and a half of cards per request. */
        const val PAGE_SIZE = 20

        /** The most feeds one catalog walk keeps, whatever the gateway keeps offering. */
        const val MAX_FEEDS = 500
    }
}
