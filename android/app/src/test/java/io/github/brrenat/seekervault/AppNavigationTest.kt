package io.github.brrenat.seekervault

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

    private companion object {
        const val CONNECTION = "connection-a"
        const val OTHER_CONNECTION = "connection-b"
        const val REQUEST = "request-a"
        const val OTHER_REQUEST = "request-b"
        const val ASSET = "devnet/sol"
    }
}
