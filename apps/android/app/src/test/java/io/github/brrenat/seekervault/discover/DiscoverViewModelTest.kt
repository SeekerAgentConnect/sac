package io.github.brrenat.seekervault.discover

import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.gateway.v1.ListRecommendedFeedsResponse
import io.github.brrenat.seekervault.gateway.v1.listRecommendedFeedsResponse
import io.github.brrenat.seekervault.gateway.v1.recommendedFeed
import io.github.brrenat.seekervault.server.v1.FeedAccessPolicy
import io.github.brrenat.seekervault.server.v1.feedAccess
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.channelFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Discover tab's catalog walk (SEE-176): loading, empty, failure and retry, refresh and
 * pagination — and that it only ever reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    /** A catalog that answers each request from a queue, recording what it was asked. */
    private class FakeCatalog : FeedCatalog {
        val asked = mutableListOf<Triple<String, Int, String>>()
        val answers = ArrayDeque<suspend () -> ListRecommendedFeedsResponse>()

        fun answer(response: ListRecommendedFeedsResponse) {
            answers.addLast { response }
        }

        fun fail(kind: GatewayException.Kind) {
            answers.addLast { throw GatewayException(kind, "refused") }
        }

        override suspend fun recommended(
            gatewayUrl: String,
            pageSize: Int,
            pageToken: String,
        ): ListRecommendedFeedsResponse {
            asked += Triple(gatewayUrl, pageSize, pageToken)
            return answers.removeFirst().invoke()
        }
    }

    private val catalog = FakeCatalog()

    private fun viewModel(url: String = GATEWAY) = DiscoverViewModel(catalog, url) { false }

    private fun serverId(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun page(vararg numbers: Int, next: String = "") = listRecommendedFeedsResponse {
        numbers.forEach { n ->
            feeds += recommendedFeed {
                serverId = serverId(n)
                gatewayUrl = GATEWAY
                channel = channelFor(serverId(n))
                displayName = "Feed $n"
                description = "Feed number $n."
                access = feedAccess { policy = FeedAccessPolicy.FEED_ACCESS_POLICY_PUBLIC }
            }
        }
        nextPageToken = next
    }

    private fun DiscoverViewModel.ids() = state.value.feeds.map { it.serverId }

    @Test
    fun aBuildWithNoCatalogAsksNothing() {
        val discover = viewModel(url = "")
        discover.open()
        discover.refresh()
        discover.retry()
        assertFalse(discover.state.value.configured)
        assertTrue(catalog.asked.isEmpty())
    }

    @Test
    fun theFirstPageIsReadOnceWhenTheTabIsShown() {
        catalog.answer(page(1, 2))
        val discover = viewModel()
        assertFalse(discover.state.value.loaded)
        discover.open()
        discover.open() // coming back to the tab keeps what it has
        assertEquals(listOf(Triple(GATEWAY, DiscoverViewModel.PAGE_SIZE, "")), catalog.asked)
        assertEquals(listOf(serverId(1), serverId(2)), discover.ids())
        assertTrue(discover.state.value.loaded)
        assertFalse(discover.state.value.hasMore)
    }

    @Test
    fun anEmptyCatalogIsLoadedAndEmpty() {
        catalog.answer(page())
        val discover = viewModel()
        discover.open()
        assertTrue(discover.state.value.loaded)
        assertTrue(discover.state.value.feeds.isEmpty())
        assertNull(discover.state.value.failure)
    }

    @Test
    fun aFailedFirstReadSaysWhyAndRetryReadsAgain() {
        catalog.fail(GatewayException.Kind.Unreachable)
        catalog.answer(page(1))
        val discover = viewModel()
        discover.open()
        assertFalse(discover.state.value.loaded)
        assertEquals(GatewayException.Kind.Unreachable, discover.state.value.failure)
        discover.retry()
        assertNull(discover.state.value.failure)
        assertEquals(listOf(serverId(1)), discover.ids())
    }

    @Test
    fun theFirstReadIsShownAsLoadingUntilItIsAnswered() {
        val held = CompletableDeferred<ListRecommendedFeedsResponse>()
        catalog.answers.addLast { held.await() }
        val discover = viewModel()
        discover.open()
        assertTrue(discover.state.value.loading)
        held.complete(page(1))
        assertFalse(discover.state.value.loading)
        assertEquals(listOf(serverId(1)), discover.ids())
    }

    @Test
    fun aRefreshReplacesTheListAndAFailedOneKeepsWhatWasShown() {
        catalog.answer(page(1, 2))
        catalog.answer(page(3))
        catalog.fail(GatewayException.Kind.Unreachable)
        val discover = viewModel()
        discover.open()
        discover.refresh()
        assertEquals(listOf(serverId(3)), discover.ids())
        discover.refresh()
        assertEquals(listOf(serverId(3)), discover.ids())
        assertEquals(GatewayException.Kind.Unreachable, discover.state.value.failure)
        assertFalse(discover.state.value.refreshing)
    }

    @Test
    fun pagesAreAppendedInOrderUntilTheLastAndAFailedPageIsRetried() {
        catalog.answer(page(1, 2, next = "after-2"))
        catalog.fail(GatewayException.Kind.Unreachable)
        catalog.answer(page(2, 3, next = "after-3")) // a repeat across pages is one card
        catalog.answer(page(4))
        val discover = viewModel()
        discover.open()
        assertTrue(discover.state.value.hasMore)

        discover.loadMore()
        assertEquals(GatewayException.Kind.Unreachable, discover.state.value.moreFailure)
        assertEquals(listOf(serverId(1), serverId(2)), discover.ids())

        discover.retry()
        assertEquals(listOf(serverId(1), serverId(2), serverId(3)), discover.ids())
        discover.loadMore()
        assertEquals((1..4).map(::serverId), discover.ids())
        assertFalse(discover.state.value.hasMore)
        discover.loadMore() // the end: nothing more is asked
        assertEquals(
            listOf("", "after-2", "after-2", "after-3"),
            catalog.asked.map { it.third },
        )
    }

    @Test
    fun aPageAskedForBeforeARefreshIsNotAppendedToTheNewList() {
        val late = CompletableDeferred<ListRecommendedFeedsResponse>()
        catalog.answer(page(1, next = "after-1"))
        catalog.answers.addLast { late.await() }
        catalog.answer(page(5))
        val discover = viewModel()
        discover.open()
        discover.loadMore() // held
        discover.refresh()
        late.complete(page(2))
        assertEquals(listOf(serverId(5)), discover.ids())
    }

    @Test
    fun aGatewayThatRepeatsItsTokenEndsTheWalk() {
        catalog.answer(page(1, next = "same"))
        catalog.answer(page(2, next = "same"))
        val discover = viewModel()
        discover.open()
        discover.loadMore()
        assertFalse(discover.state.value.hasMore)
        discover.loadMore()
        assertEquals(2, catalog.asked.size)
    }
}
