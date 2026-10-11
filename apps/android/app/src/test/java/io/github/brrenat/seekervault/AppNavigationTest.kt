package io.github.brrenat.seekervault

import io.github.brrenat.seekervault.wallet.WalletNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {
    @Test
    fun reviewToHandoffBackRevealsTheSameReviewOnItsInboxBase() {
        val identity = ReviewIdentity.Private(CONNECTION, REQUEST)
        val navigator = AppNavigator()

        assertTrue(navigator.selectTab(AppScreen.Inbox))
        assertTrue(navigator.openReview(identity))
        assertTrue(navigator.openWalletHandoff(identity))
        assertEquals(
            NavigationState(
                screen = AppScreen.Inbox,
                sheets =
                    listOf(
                        AppSheet.RequestReview(identity),
                        AppSheet.WalletHandoff(identity),
                    ),
            ),
            navigator.state,
        )

        assertTrue(navigator.back())
        assertEquals(
            NavigationState(AppScreen.Inbox, listOf(AppSheet.RequestReview(identity))),
            navigator.state,
        )
    }

    @Test
    fun detailToRulesToGlobalBackPreservesEachSheetUnderneath() {
        val navigator = AppNavigator()

        assertTrue(navigator.openConnectionDetail(CONNECTION))
        assertTrue(navigator.openConnectionRules(CONNECTION))
        assertTrue(navigator.openGlobalRules())
        assertEquals(
            listOf(
                AppSheet.ConnectionDetail(CONNECTION),
                AppSheet.ConnectionRules(CONNECTION),
                AppSheet.GlobalRules,
            ),
            navigator.state.sheets,
        )

        assertTrue(navigator.back())
        assertEquals(AppSheet.ConnectionRules(CONNECTION), navigator.state.sheets.last())
        assertTrue(navigator.back())
        assertEquals(AppSheet.ConnectionDetail(CONNECTION), navigator.state.sheets.single())
    }

    @Test
    fun graphRejectsEdgesThatAreNotInTheFlowOrDoNotMatchTheirParent() {
        val identity = ReviewIdentity.Private(CONNECTION, REQUEST)
        val otherIdentity = ReviewIdentity.Private(CONNECTION, OTHER_REQUEST)
        val navigator = AppNavigator()

        assertFalse(navigator.openWalletHandoff(identity))
        assertFalse(navigator.openConnectionRules(CONNECTION))
        assertTrue(navigator.selectTab(AppScreen.Wallet))
        assertFalse(navigator.openReview(identity))

        assertTrue(navigator.selectTab(AppScreen.Home))
        assertTrue(navigator.openReview(identity))
        assertFalse(navigator.openWalletHandoff(otherIdentity))
        assertFalse(navigator.openGlobalRules())
        assertFalse(navigator.openAssetEditor(CONNECTION, AssetEditorKind.Allowlisted))
        assertEquals(
            NavigationState(AppScreen.Home, listOf(AppSheet.RequestReview(identity))),
            navigator.state,
        )
    }

    @Test
    fun aSignalReviewStacksItsOwnerInputAndItsConnectionRules() {
        val identity = ReviewIdentity.Signal(CONNECTION, REQUEST)
        val navigator = AppNavigator()

        assertTrue(navigator.openReview(identity))
        assertFalse(navigator.openOwnerInput(ReviewIdentity.Signal(CONNECTION, OTHER_REQUEST)))
        assertTrue(navigator.openOwnerInput(identity))
        assertEquals(
            listOf(AppSheet.RequestReview(identity), AppSheet.OwnerInput(identity)),
            navigator.state.sheets,
        )
        assertEquals(
            navigator.state,
            decodeNavigationState(encodeNavigationState(navigator.state)),
        )
        assertTrue(navigator.back())

        // `[review, rules]` from the verdict's Rules, for the connection the review came from.
        assertFalse(navigator.openConnectionRules(OTHER_CONNECTION))
        assertTrue(navigator.openConnectionRules(CONNECTION))
        assertEquals(
            listOf(AppSheet.RequestReview(identity), AppSheet.ConnectionRules(CONNECTION)),
            navigator.state.sheets,
        )
        assertTrue(navigator.back())
        assertEquals(AppSheet.RequestReview(identity), navigator.state.sheets.single())
    }

    @Test
    fun childEditorsRequireRulesForTheSameConnection() {
        val navigator = AppNavigator()

        assertTrue(navigator.openConnectionDetail(CONNECTION))
        assertTrue(navigator.openConnectionRules(CONNECTION))
        assertFalse(navigator.openAssetEditor(OTHER_CONNECTION, AssetEditorKind.Allowlisted))
        assertFalse(navigator.openAddressEditor(OTHER_CONNECTION, AddressEditorKind.Recipient))
        assertTrue(navigator.openAssetEditor(CONNECTION, AssetEditorKind.SpendingLimit, ASSET))
        assertEquals(
            AppSheet.AssetEditor(CONNECTION, AssetEditorKind.SpendingLimit, ASSET),
            navigator.state.sheets.last(),
        )
    }

    @Test
    fun tabsReplaceTheBaseAndClearSheetsWhileBackPreservesASheetBase() {
        val identity = ReviewIdentity.Signal(CONNECTION, REQUEST)
        val navigator = AppNavigator()

        assertTrue(navigator.openReview(identity))
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Home), navigator.state)

        assertTrue(navigator.openConnectionDetail(CONNECTION))
        assertTrue(navigator.selectTab(AppScreen.Activity))
        assertEquals(NavigationState(AppScreen.Activity), navigator.state)
        assertFalse(navigator.back())

        assertTrue(navigator.selectTab(AppScreen.Home))
        assertTrue(navigator.openAddConnection())
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Home), navigator.state)
    }

    @Test
    fun closingAnAnsweredHandoffClosesItAndItsMatchingReview() {
        val identity = ReviewIdentity.Private(CONNECTION, REQUEST)
        val navigator = AppNavigator()
        navigator.openReview(identity)
        navigator.openWalletHandoff(identity)

        assertFalse(navigator.closeReview(ReviewIdentity.Private(CONNECTION, OTHER_REQUEST)))
        assertTrue(navigator.closeReview(identity))
        assertEquals(NavigationState(), navigator.state)
    }

    @Test
    fun aHistoryRecordOpensFromInboxAsAFullPageAndBackReturnsToInbox() {
        val identity = ReviewIdentity.Signal(CONNECTION, REQUEST)
        val navigator = AppNavigator()

        // Not from Home, and not over a sheet the owner is working in.
        assertFalse(navigator.openHistoryDetail(identity))
        assertTrue(navigator.selectTab(AppScreen.Inbox))
        assertTrue(navigator.openHistoryDetail(identity))
        assertEquals(NavigationState(AppScreen.HistoryDetail(identity)), navigator.state)
        // Nothing is pushed over a record: it is read-only and has no review to hand off to.
        assertFalse(navigator.openReview(identity))

        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Inbox), navigator.state)
    }

    @Test
    fun aReviewAboutAClosedItemIsReplacedByItsRecord() {
        val identity = ReviewIdentity.Private(CONNECTION, REQUEST)
        val other = ReviewIdentity.Private(CONNECTION, OTHER_REQUEST)
        val navigator = AppNavigator()
        assertTrue(navigator.openReview(identity))

        assertFalse(navigator.openHistoryDetail(other))
        assertTrue(navigator.openHistoryDetail(identity))
        assertEquals(NavigationState(AppScreen.HistoryDetail(identity)), navigator.state)
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Inbox), navigator.state)
    }

    @Test
    fun savedStateRoundTripsAHistoryRecord() {
        listOf(
                ReviewIdentity.Private(CONNECTION, REQUEST),
                ReviewIdentity.Signal(CONNECTION, OTHER_REQUEST),
            )
            .forEach { identity ->
                val state = NavigationState(AppScreen.HistoryDetail(identity))
                val saved = encodeNavigationState(state)
                assertEquals(state, decodeNavigationState(saved))
                assertEquals(null, decodeNavigationState(saved.dropLast(1)))
            }
    }

    @Test
    fun savedStateRoundTripsTypedRoutesAndRejectsCorruption() {
        val state =
            NavigationState(
                screen = AppScreen.Home,
                sheets =
                    listOf(
                        AppSheet.ConnectionDetail(CONNECTION),
                        AppSheet.ConnectionRules(CONNECTION),
                        AppSheet.AddressEditor(CONNECTION, AddressEditorKind.Program),
                    ),
            )

        val saved = encodeNavigationState(state)
        val restored = decodeNavigationState(saved)

        assertNotNull(restored)
        assertEquals(state, restored)
        assertEquals(null, decodeNavigationState(saved.dropLast(1)))
        assertEquals(null, decodeNavigationState(listOf("unknown", "home")))
    }

    @Test
    fun savedStateRetainsTheWalletActionKind() {
        val identity = ReviewIdentity.Private(CONNECTION, REQUEST)
        val state =
            NavigationState(
                screen = AppScreen.Inbox,
                sheets =
                    listOf(
                        AppSheet.RequestReview(identity),
                        AppSheet.WalletHandoff(identity, WalletHandoffKind.Signature),
                    ),
            )

        assertEquals(state, decodeNavigationState(encodeNavigationState(state)))
    }

    @Test
    fun walletSheetsOpenOnlyFromTheirDesignedParentsAndRoundTrip() {
        val navigator = AppNavigator()

        assertFalse(navigator.openAddWallet())
        assertFalse(navigator.openConnectionWallet(CONNECTION))

        assertTrue(navigator.selectTab(AppScreen.Wallet))
        assertTrue(navigator.openAddWallet())
        assertEquals(
            navigator.state,
            decodeNavigationState(encodeNavigationState(navigator.state)),
        )

        assertTrue(navigator.selectTab(AppScreen.Home))
        assertTrue(navigator.openConnectionDetail(CONNECTION))
        assertFalse(navigator.openConnectionWallet(OTHER_CONNECTION))
        assertTrue(navigator.openConnectionWallet(CONNECTION))
        assertTrue(navigator.openAddWallet(WalletNetwork.Devnet))
        assertEquals(
            NavigationState(
                AppScreen.Home,
                listOf(
                    AppSheet.ConnectionDetail(CONNECTION),
                    AppSheet.ConnectionWallet(CONNECTION),
                    AppSheet.AddWallet(WalletNetwork.Devnet),
                ),
            ),
            navigator.state,
        )
        assertEquals(
            navigator.state,
            decodeNavigationState(encodeNavigationState(navigator.state)),
        )
    }

    @Test
    fun aSaleReviewOpensOnlyOverItsOwnHistoryItemAndSurvivesARestart() {
        val identity = ReviewIdentity.Signal(CONNECTION, REQUEST)
        val navigator = AppNavigator(NavigationState(AppScreen.Inbox))
        // Not from the Inbox, and not over another item's page (SEE-172).
        assertFalse(navigator.openPositionSale(identity))
        assertTrue(navigator.openHistoryDetail(identity))
        assertFalse(navigator.openPositionSale(ReviewIdentity.Signal(CONNECTION, OTHER_REQUEST)))
        assertTrue(navigator.openPositionSale(identity))
        // One at a time.
        assertFalse(navigator.openPositionSale(identity))
        val restored = decodeNavigationState(encodeNavigationState(navigator.state))
        assertEquals(navigator.state, restored)
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.HistoryDetail(identity)), navigator.state)
    }

    private companion object {
        const val CONNECTION = "connection-a"
        const val GATEWAY_URL = "https://feeds.example.com"
        const val SERVER = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val OTHER_CONNECTION = "connection-b"
        const val REQUEST = "request-a"
        const val OTHER_REQUEST = "request-b"
        const val ASSET = "devnet/sol"
    }

    /**
     * Discover (SEE-176): a peer tab whose card details, onboarding and connections return to it.
     */
    @Test
    fun discoverIsAPeerTabAndEverythingOpenedFromItReturnsToIt() {
        val navigator = AppNavigator()
        assertTrue(navigator.selectTab(AppScreen.Discover))
        assertFalse(navigator.back())

        // A card's details are a sheet over Discover, and Back reveals the same tab.
        assertTrue(navigator.openCatalogDetail(GATEWAY_URL, SERVER))
        assertEquals(
            NavigationState(
                AppScreen.Discover,
                listOf(AppSheet.CatalogDetail(GATEWAY_URL, SERVER)),
            ),
            navigator.state,
        )
        // Connect is from the tab itself, never from over a sheet.
        assertFalse(navigator.openCatalogConnect())
        assertTrue(navigator.back())

        // Connect opens Add connection, and Back returns to Discover, not Home.
        assertTrue(navigator.openCatalogConnect())
        assertEquals(AppScreen.AddConnection(from = AppScreen.Discover), navigator.state.screen)
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Discover), navigator.state)

        // A feed already held opens its connection over Discover, with its rules above it.
        assertTrue(navigator.openConnectionDetail(CONNECTION))
        assertTrue(navigator.openConnectionRules(CONNECTION))
        assertTrue(navigator.back())
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Discover), navigator.state)
    }

    @Test
    fun catalogEdgesExistOnlyOnDiscover() {
        val navigator = AppNavigator()
        assertFalse(navigator.openCatalogDetail(GATEWAY_URL, SERVER))
        assertFalse(navigator.openCatalogConnect())
        // Home's FAB still returns to Home.
        assertTrue(navigator.openAddConnection())
        assertEquals(AppScreen.AddConnection(), navigator.state.screen)
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Home), navigator.state)

        assertTrue(navigator.selectTab(AppScreen.Inbox))
        assertFalse(navigator.openCatalogDetail(GATEWAY_URL, SERVER))
        assertFalse(navigator.openConnectionDetail(CONNECTION))
        assertTrue(navigator.selectTab(AppScreen.Discover))
        assertFalse(navigator.openAddConnection())
    }

    @Test
    fun theSolanaRpcSheetOpensOverWalletOnlyAndSurvivesARestore() {
        val navigator = AppNavigator()
        // Over Home it is not an edge: where each network is asked lives with the wallets.
        assertFalse(navigator.openRpcSettings())
        assertTrue(navigator.selectTab(AppScreen.Wallet))
        assertTrue(navigator.openRpcSettings())
        assertEquals(
            NavigationState(AppScreen.Wallet, listOf(AppSheet.RpcSettings)),
            navigator.state,
        )
        assertFalse(navigator.openRpcSettings())
        assertEquals(
            navigator.state,
            decodeNavigationState(encodeNavigationState(navigator.state)),
        )
        assertTrue(navigator.back())
        assertEquals(NavigationState(AppScreen.Wallet), navigator.state)
    }

    @Test
    fun discoverRoutesSurviveASaveAndRestore() {
        listOf(
                NavigationState(AppScreen.Discover),
                NavigationState(
                    AppScreen.Discover,
                    listOf(AppSheet.CatalogDetail(GATEWAY_URL, SERVER)),
                ),
                NavigationState(AppScreen.Discover, listOf(AppSheet.ConnectionDetail(CONNECTION))),
                NavigationState(AppScreen.AddConnection(from = AppScreen.Discover)),
                NavigationState(AppScreen.AddConnection()),
            )
            .forEach { state ->
                assertEquals(state, decodeNavigationState(encodeNavigationState(state)))
            }
        // A catalog detail cannot be restored anywhere but over Discover.
        val forged =
            encodeNavigationState(
                    NavigationState(
                        AppScreen.Discover,
                        listOf(AppSheet.CatalogDetail(GATEWAY_URL, SERVER)),
                    )
                )
                .toMutableList()
                .also { it[1] = "home" }
        assertEquals(null, decodeNavigationState(forged))
    }
}
