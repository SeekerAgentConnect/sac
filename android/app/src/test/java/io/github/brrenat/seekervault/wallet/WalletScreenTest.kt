package io.github.brrenat.seekervault.wallet

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Wallet screen on Robolectric. */
@RunWith(AndroidJUnit4::class)
class WalletScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actions = mutableListOf<String>()

    private fun show(state: WalletUiState) = compose.setContent {
        SeekerTheme {
            WalletScreen(
                state = walletScreenState(state),
                callbacks =
                    WalletScreenCallbacks(
                        onChooseWalletApp = { actions += "app:$it" },
                        onChooseNetwork = { actions += "network:${it.name}" },
                        onConnect = { actions += "connect" },
                        onDisconnect = { actions += "disconnect" },
                        onPublishAgain = { actions += "again" },
                        onBack = { actions += "back" },
                        navigation = NAVIGATION,
                    ),
            )
        }
    }

    @Test
    fun offersTheNetworksAndConnectingWhenNoWalletIsConnected() {
        show(WalletUiState(loaded = true, connections = listOf(CONNECTION)))
        compose
            .onNodeWithTag(WalletTags.STATUS)
            .assertTextContains(context.getString(R.string.wallet_none_title))
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Mainnet)).assertIsSelected()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).assertIsNotSelected()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).performClick()
        compose
            .onNodeWithTag(WalletTags.CONNECT)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("network:Devnet", "connect"), actions)
    }

    @Test
    fun showsTheAddressAndNetworkOnceAWalletIsConnected() {
        show(WalletUiState(wallet = SELECTED, loaded = true, connections = listOf(CONNECTION)))
        compose.onNodeWithTag(WalletTags.STATUS).assertTextContains(WALLET, substring = true)
        compose
            .onNodeWithTag(WalletTags.STATUS)
            .assertTextContains(
                context.getString(R.string.wallet_network_devnet),
                substring = true,
            )
        compose.onNodeWithTag(WalletTags.STATUS).assertTextContains("Account 1")
        compose.onNodeWithTag(WalletTags.DISCONNECT).performScrollTo().performClick()
        assertEquals(listOf("disconnect"), actions)
    }

    @Test
    fun saysWhenTheWalletDidNotConfirmTheNetwork() {
        show(WalletUiState(wallet = SELECTED.copy(networkConfirmed = false), loaded = true))
        compose
            .onNodeWithTag(WalletTags.UNCONFIRMED)
            .performScrollTo()
            .assertTextContains(context.getString(R.string.wallet_network_unconfirmed))
    }

    @Test
    fun explainsWhyTheWalletCouldNotBeReached() {
        show(
            WalletUiState(
                loaded = true,
                problem = WalletProblem.NoWallet,
                connections = listOf(CONNECTION),
            )
        )
        compose
            .onNodeWithTag(WalletTags.PROBLEM)
            .assertTextContains(context.getString(R.string.wallet_problem_no_wallet))
    }

    @Test
    fun addsWhatTheWalletSaidToAnUnknownFailure() {
        show(
            WalletUiState(
                loaded = true,
                problem = WalletProblem.Failed,
                detail = "the wallet timed out",
            )
        )
        compose
            .onNodeWithTag(WalletTags.PROBLEM)
            .assertTextContains("the wallet timed out", substring = true)
    }

    @Test
    fun namesTheConnectionsItCouldNotTellAndOffersToTryAgain() {
        show(
            WalletUiState(
                wallet = SELECTED,
                loaded = true,
                connections = listOf(CONNECTION),
                unpublished = listOf(CONNECTION),
            )
        )
        compose
            .onNodeWithTag(WalletTags.PUBLISHED)
            .performScrollTo()
            .assertTextContains("Home Mac", substring = true)
        compose
            .onNodeWithTag(WalletTags.PUBLISH_AGAIN, useUnmergedTree = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("again"), actions)
    }

    @Test
    fun disablesTheButtonsWhileTheWalletIsBusy() {
        show(WalletUiState(loaded = true, connecting = true))
        compose
            .onNodeWithTag(WalletTags.STATUS)
            .assertTextContains(context.getString(R.string.wallet_connecting))
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).assertIsNotEnabled()
    }

    @Test
    fun offersTheInstalledWalletAppsWhenThereIsMoreThanOne() {
        show(
            WalletUiState(
                loaded = true,
                apps = listOf(SEEKER, OTHER),
                connections = listOf(CONNECTION),
            )
        )

        compose.onNodeWithTag(WalletTags.app(SEEKER.packageName)).assertIsNotSelected()
        compose.onNodeWithTag(WalletTags.app(OTHER.packageName)).performScrollTo().performClick()
        // Connecting waits for the pick, so nothing goes out able to open Android's chooser.
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().assertIsNotEnabled()
        assertEquals(listOf("app:${OTHER.packageName}"), actions)
    }

    @Test
    fun showsThePickAndLetsTheOwnerConnectOnceOneIsMade() {
        show(
            WalletUiState(
                loaded = true,
                apps = listOf(SEEKER, OTHER),
                chosen = SEEKER,
                connections = listOf(CONNECTION),
            )
        )

        compose.onNodeWithTag(WalletTags.app(SEEKER.packageName)).assertIsSelected()
        compose
            .onNodeWithTag(WalletTags.CONNECT)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("connect"), actions)
    }

    @Test
    fun asksNothingWhenThisPhoneHasOneWalletApp() {
        show(WalletUiState(loaded = true, apps = listOf(SEEKER), connections = listOf(CONNECTION)))

        compose.onNodeWithTag(WalletTags.app(SEEKER.packageName)).assertDoesNotExist()
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().assertIsEnabled()
    }

    @Test
    fun namesTheWalletAppRatherThanTheAccountLabel() {
        show(
            WalletUiState(
                wallet = SELECTED,
                walletApp = SEEKER.label,
                loaded = true,
                connections = listOf(CONNECTION),
            )
        )

        // The card is the wallet, and "Account 1" is the account inside it (SEE-159).
        compose.onNodeWithTag(WalletTags.STATUS).assertTextContains(SEEKER.label, substring = true)
        compose
            .onNodeWithTag(WalletTags.STATUS)
            .assertTextContains(checkNotNull(SELECTED.label), substring = true)
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"

        val SEEKER = InstalledWallet("com.example.seekerwallet", "Seeker Wallet")
        val OTHER = InstalledWallet("com.example.otherwallet", "Other Wallet")

        val SELECTED =
            SelectedWallet(
                address = WALLET,
                network = WalletNetwork.Devnet,
                label = "Account 1",
                selectedAt = Instant.parse("2026-09-12T09:30:00Z"),
            )

        val CONNECTION =
            Connection(
                id = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c",
                label = "Home Mac",
                serverUrl = "https://mac.tailnet.ts.net",
                serverId = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-12T09:00:00Z"),
                lastCheck =
                    Connection.Check(Instant.parse("2026-09-12T09:29:00Z"), CheckOutcome.Ok, 0),
            )

        val NAVIGATION =
            ScreenNavigationCallbacks(onHome = {}, onInbox = {}, onWallet = {}, onActivity = {})
    }
}
