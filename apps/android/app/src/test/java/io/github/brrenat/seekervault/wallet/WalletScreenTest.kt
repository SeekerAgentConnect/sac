package io.github.brrenat.seekervault.wallet

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Wallets screen on Robolectric (SEE-174): every saved profile with its network and wallet app,
 * who uses it, what can be done to it, and the form that adds another.
 */
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
                        onPublishAgain = { actions += "again" },
                        onBack = { actions += "back" },
                        navigation = NAVIGATION,
                        onRename = { actions += "rename:$it" },
                        onSaveName = { id, label -> actions += "save:$id:$label" },
                        onCancelRename = { actions += "cancelRename" },
                        onReconnect = { actions += "reconnect:$it" },
                        onRemove = { actions += "remove:$it" },
                        onConfirmRemove = { actions += "confirmRemove" },
                        onCancelRemove = { actions += "cancelRemove" },
                    ),
            )
        }
    }

    /**
     * Scrolls [this] into view and clicks it through its semantics. A touch would land on whatever
     * is drawn over it — the bottom navigation covers the end of the scrolled body — and click that
     * instead.
     */
    private fun SemanticsNodeInteraction.tap() =
        performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun offersTheNetworksAndAddingWhenNoWalletIsSaved() {
        show(WalletUiState(loaded = true, connections = listOf(HOME)))
        compose
            .onNodeWithTag(WalletTags.STATUS)
            .assertTextContains(context.getString(R.string.wallet_profiles_none_title))
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Mainnet)).assertIsSelected()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).assertIsNotSelected()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Testnet)).assertIsNotSelected()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).tap()
        compose
            .onNodeWithTag(WalletTags.CONNECT)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("network:Devnet", "connect"), actions)
    }

    @Test
    fun marksTheNetworkTheOwnerIsAddingOn() {
        show(WalletUiState(loaded = true, network = WalletNetwork.Testnet))
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Testnet)).assertIsSelected()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Mainnet)).assertIsNotSelected()
    }

    @Test
    fun showsEachProfileWithItsNetworkAndWalletAppApartFromTheAccountLabel() {
        show(WalletUiState(loaded = true, profiles = listOf(MAIN, DEV), connections = listOf(HOME)))

        // No empty state once something is saved.
        compose.onNodeWithTag(WalletTags.STATUS).assertDoesNotExist()
        // The card is named after the account, and says which network and which wallet app it is
        // in separately (SEE-159): "Account 1" is the account, not the wallet.
        val main = compose.onNodeWithTag(WalletTags.profile(MAIN.id))
        main.assertTextContains("Account 1")
        main.assertTextContains(WALLET)
        main.assertTextContains(
            context.getString(R.string.wallet_network_mainnet),
            substring = true,
        )
        main.assertTextContains(SEEKER.label, substring = true)
        main.assert(!hasText(context.getString(R.string.wallet_network_devnet), substring = true))

        // The same address on another network is a card of its own, and one whose wallet app was
        // never learned says so rather than borrowing another's.
        val dev = compose.onNodeWithTag(WalletTags.profile(DEV.id)).performScrollTo()
        dev.assertTextContains(WALLET)
        dev.assertTextContains(context.getString(R.string.wallet_network_devnet), substring = true)
        dev.assertTextContains(
            context.getString(R.string.wallet_profile_unknown_app),
            substring = true,
        )
        dev.assert(!hasText(SEEKER.label, substring = true))
        dev.assertTextContains(context.getString(R.string.wallet_profile_unnamed))
    }

    @Test
    fun saysWhichConnectionsUseEachProfile() {
        show(
            WalletUiState(
                loaded = true,
                profiles = listOf(MAIN, DEV),
                connections =
                    listOf(
                        HOME.copy(walletProfileId = MAIN.id),
                        OFFICE.copy(walletProfileId = MAIN.id),
                    ),
            )
        )

        compose
            .onNodeWithTag(WalletTags.profile(MAIN.id))
            .assertTextContains(
                context.resources.getQuantityString(R.plurals.wallet_profile_used_by, 2, 2),
                substring = true,
            )
        compose
            .onNodeWithTag(WalletTags.profile(DEV.id))
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.wallet_profile_unused),
                substring = true,
            )
    }

    @Test
    fun offersRenameReconnectAndRemoveForEachProfile() {
        show(WalletUiState(loaded = true, profiles = listOf(MAIN, DEV)))

        compose.onNodeWithTag(WalletTags.rename(DEV.id)).tap()
        compose.onNodeWithTag(WalletTags.reconnect(DEV.id)).tap()
        compose.onNodeWithTag(WalletTags.remove(MAIN.id)).tap()

        // Each button acts on its own profile, never on its neighbour.
        assertEquals(
            listOf("rename:${DEV.id}", "reconnect:${DEV.id}", "remove:${MAIN.id}"),
            actions,
        )
    }

    @Test
    fun renamesAProfileInADialog() {
        show(WalletUiState(loaded = true, profiles = listOf(MAIN, DEV), renaming = DEV.id))

        compose.onNodeWithTag(ConnectionsTags.LABEL_FIELD).performTextReplacement("Trading")
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()

        assertEquals(listOf("save:${DEV.id}:Trading"), actions)
    }

    @Test
    fun confirmsARemovalNamingTheConnectionsThatUseIt() {
        show(
            WalletUiState(
                loaded = true,
                profiles = listOf(MAIN, DEV),
                connections =
                    listOf(
                        HOME.copy(walletProfileId = MAIN.id),
                        OFFICE.copy(walletProfileId = MAIN.id),
                        CLOUD.copy(walletProfileId = DEV.id),
                    ),
                removing = MAIN.id,
            )
        )

        compose
            .onNodeWithText(context.getString(R.string.wallet_profile_remove_title, "Account 1"))
            .assertExists()
        // The two connections it leaves without a wallet — and not the one using another profile.
        compose
            .onNodeWithText(
                context.getString(R.string.wallet_profile_remove_used, "Home Mac, Office")
            )
            .assertExists()
        compose.onNodeWithText("Cloud", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(WalletTags.REMOVE_CONFIRM).performClick()
        assertEquals(listOf("confirmRemove"), actions)
    }

    @Test
    fun saysWhenARemovedProfileIsUsedByNoConnection() {
        show(
            WalletUiState(
                loaded = true,
                profiles = listOf(MAIN, DEV),
                connections = listOf(HOME.copy(walletProfileId = MAIN.id)),
                removing = DEV.id,
            )
        )

        compose
            .onNodeWithText(context.getString(R.string.wallet_profile_remove_unused))
            .assertExists()
        compose.onNodeWithTag(ConnectionsTags.DIALOG_DISMISS).performClick()
        assertEquals(listOf("cancelRemove"), actions)
    }

    @Test
    fun warnsOnlyOnTheProfileThatNeedsReconnecting() {
        show(WalletUiState(loaded = true, profiles = listOf(MAIN.copy(authorized = false), DEV)))

        compose
            .onNodeWithTag(WalletTags.profileWarning(MAIN.id))
            .performScrollTo()
            .assertTextContains(context.getString(R.string.wallet_profile_needs_reconnect))
        compose.onNodeWithTag(WalletTags.profileWarning(DEV.id)).assertDoesNotExist()
    }

    @Test
    fun saysWhenTheWalletDidNotConfirmTheNetwork() {
        show(WalletUiState(loaded = true, profiles = listOf(DEV.copy(networkConfirmed = false))))
        compose
            .onNodeWithTag(WalletTags.profileWarning(DEV.id))
            .performScrollTo()
            .assertTextContains(context.getString(R.string.wallet_network_unconfirmed))
    }

    @Test
    fun explainsWhyTheWalletCouldNotBeReached() {
        show(
            WalletUiState(
                loaded = true,
                problem = WalletProblem.NoWallet,
                connections = listOf(HOME),
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
    fun namesTheServerItCouldNotTellAndOffersToTryAgain() {
        show(
            WalletUiState(
                loaded = true,
                profiles = listOf(MAIN),
                connections = listOf(HOME.copy(walletProfileId = MAIN.id), OFFICE),
                unpublished = listOf(HOME.copy(walletProfileId = MAIN.id)),
            )
        )
        compose
            .onNodeWithTag(WalletTags.PUBLISHED)
            .performScrollTo()
            .assertTextContains("Home Mac", substring = true)
            .assert(!hasText("Office", substring = true))
        compose
            .onNodeWithTag(WalletTags.PUBLISH_AGAIN, useUnmergedTree = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("again"), actions)
    }

    @Test
    fun countsTheServersItCouldNotTell() {
        show(
            WalletUiState(
                loaded = true,
                connections = listOf(HOME, OFFICE),
                unpublished = listOf(HOME, OFFICE),
            )
        )
        compose
            .onNodeWithTag(WalletTags.PUBLISHED)
            .performScrollTo()
            .assertTextContains(
                context.getString(R.string.wallet_unpublished_servers, 2, "Home Mac, Office"),
                substring = true,
            )
    }

    @Test
    fun disablesTheButtonsWhileTheWalletIsBusy() {
        show(WalletUiState(loaded = true, profiles = listOf(MAIN), connecting = true))
        compose.onNodeWithText(context.getString(R.string.wallet_connecting)).assertExists()
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).assertIsNotEnabled()
        compose.onNodeWithTag(WalletTags.rename(MAIN.id)).assertIsNotEnabled()
        compose.onNodeWithTag(WalletTags.reconnect(MAIN.id)).assertIsNotEnabled()
        compose.onNodeWithTag(WalletTags.remove(MAIN.id)).assertIsNotEnabled()
    }

    @Test
    fun disablesTheProfileButtonsWhileOneIsBeingWorkedOn() {
        show(WalletUiState(loaded = true, profiles = listOf(MAIN, DEV), working = MAIN.id))
        compose.onNodeWithTag(WalletTags.remove(DEV.id)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun offersTheInstalledWalletAppsWhenThereIsMoreThanOne() {
        show(
            WalletUiState(
                loaded = true,
                apps = listOf(SEEKER, OTHER),
                connections = listOf(HOME),
            )
        )

        compose.onNodeWithTag(WalletTags.app(SEEKER.packageName)).assertIsNotSelected()
        compose.onNodeWithTag(WalletTags.app(OTHER.packageName)).tap()
        // Adding waits for the pick, so nothing goes out able to open Android's chooser.
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().assertIsNotEnabled()
        assertEquals(listOf("app:${OTHER.packageName}"), actions)
    }

    @Test
    fun showsThePickAndLetsTheOwnerAddOnceOneIsMade() {
        show(
            WalletUiState(
                loaded = true,
                apps = listOf(SEEKER, OTHER),
                chosen = SEEKER,
                connections = listOf(HOME),
            )
        )

        compose.onNodeWithTag(WalletTags.app(SEEKER.packageName)).assertIsSelected()
        compose.onNodeWithTag(WalletTags.app(OTHER.packageName)).assertIsNotSelected()
        compose
            .onNodeWithTag(WalletTags.CONNECT)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("connect"), actions)
    }

    @Test
    fun asksNothingWhenThisPhoneHasOneWalletApp() {
        show(WalletUiState(loaded = true, apps = listOf(SEEKER), connections = listOf(HOME)))

        compose.onNodeWithTag(WalletTags.app(SEEKER.packageName)).assertDoesNotExist()
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().assertIsEnabled()
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"

        val SEEKER = InstalledWallet("com.example.seekerwallet", "Seeker Wallet")
        val OTHER = InstalledWallet("com.example.otherwallet", "Other Wallet")

        /**
         * One address saved twice: on Mainnet in the Seeker wallet, and on Devnet in a wallet app
         * this phone never learned.
         */
        val MAIN =
            WalletProfile(
                id = "profile-main",
                address = WALLET,
                network = WalletNetwork.Mainnet,
                accountLabel = "Account 1",
                route = WalletRouting(packageName = SEEKER.packageName, appLabel = SEEKER.label),
                authorizationId = "authorization-main",
                connectedAt = Instant.parse("2026-09-12T09:30:00Z"),
            )

        val DEV =
            WalletProfile(
                id = "profile-dev",
                address = WALLET,
                network = WalletNetwork.Devnet,
                authorizationId = "authorization-dev",
                connectedAt = Instant.parse("2026-09-12T09:40:00Z"),
            )

        private fun connection(id: String, label: String) =
            Connection(
                id = id,
                label = label,
                serverUrl = "https://$id.tailnet.ts.net",
                serverId = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f",
                deviceName = "Seeker",
                pairedAt = Instant.parse("2026-09-12T09:00:00Z"),
                lastCheck =
                    Connection.Check(Instant.parse("2026-09-12T09:29:00Z"), CheckOutcome.Ok, 0),
            )

        val HOME = connection("0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c", "Home Mac")
        val OFFICE = connection("1c9f3c2d-4a5e-4f6b-8c7d-8e9fa0b1c2d3", "Office")
        val CLOUD = connection("2d0a4d3e-5b6f-4a7c-9d8e-9fa0b1c2d3e4", "Cloud")

        val NAVIGATION =
            ScreenNavigationCallbacks(onHome = {}, onInbox = {}, onWallet = {}, onActivity = {})
    }
}
