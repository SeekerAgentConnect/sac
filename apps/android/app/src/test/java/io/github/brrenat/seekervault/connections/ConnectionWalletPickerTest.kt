package io.github.brrenat.seekervault.connections

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.InstalledWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import io.github.brrenat.seekervault.wallet.WalletRouting
import io.github.brrenat.seekervault.wallet.WalletTags
import io.github.brrenat.seekervault.wallet.WalletUiState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The SEE-178 wallet picker contract, independent of binding and wallet connection logic. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class ConnectionWalletPickerTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val used = mutableListOf<String>()
    private val added = mutableListOf<WalletNetwork?>()

    @Test
    fun declaredNetworkShowsEveryWalletButOnlyMatchingWalletsCanBeUsed() {
        show(setOf(WalletNetwork.Devnet))

        val blocked = compose.onNodeWithTag(WalletTags.choice(MAIN.id))
        blocked
            .assertExists()
            .assertIsNotEnabled()
            .assertTextContains(
                context.getString(
                    R.string.connection_wallet_picker_blocked,
                    context.getString(R.string.wallet_network_mainnet),
                )
            )
        val matching = compose.onNodeWithTag(WalletTags.choice(DEV.id))
        matching
            .assertExists()
            .assertIsEnabled()
            .assertTextContains(
                context.getString(R.string.connection_wallet_picker_opens_in, DEV_APP)
            )
        compose.onNodeWithTag(WalletTags.PICKER_USE).assertIsNotEnabled()

        // A disabled mismatch absorbs a real touch without changing the picker selection.
        blocked.performTouchInput { click() }
        compose.onNodeWithTag(WalletTags.PICKER_USE).assertIsNotEnabled()
        assertEquals(emptyList<String>(), used)

        matching.performClick()
        compose.onNodeWithTag(WalletTags.PICKER_USE).assertIsEnabled().performClick()
        assertEquals(listOf(DEV.id), used)

        compose.onNodeWithTag(WalletTags.PICKER_ADD).performClick()
        assertEquals(listOf(WalletNetwork.Devnet), added)
    }

    @Test
    fun undeclaredNetworkAllowsEveryWalletHidesAppNotesAndAddsWithoutAPreset() {
        show(emptySet())

        compose.onNodeWithTag(WalletTags.choice(MAIN.id)).assertExists().assertIsEnabled()
        compose.onNodeWithTag(WalletTags.choice(DEV.id)).assertExists().assertIsEnabled()
        compose
            .onNodeWithText(context.getString(R.string.connection_wallet_picker_opens_in, MAIN_APP))
            .assertDoesNotExist()
        compose
            .onNodeWithText(context.getString(R.string.connection_wallet_picker_opens_in, DEV_APP))
            .assertDoesNotExist()

        compose.onNodeWithTag(WalletTags.PICKER_ADD).performClick()
        assertEquals(listOf<WalletNetwork?>(null), added)
    }

    @Test
    fun pickerNeverOffersTheInstalledWalletAppList() {
        show(setOf(WalletNetwork.Devnet))

        compose.onNodeWithText(PICKER_ONLY_APP_A).assertDoesNotExist()
        compose.onNodeWithText(PICKER_ONLY_APP_B).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.wallet_app_label)).assertDoesNotExist()
    }

    @Test
    fun manyWalletsScrollWhileTheActionsStayReachable() {
        val profiles =
            List(12) { index ->
                DEV.copy(
                    id = "dev-$index",
                    address = DEV.address.dropLast(2) + index.toString().padStart(2, '0'),
                    accountLabel = "Dev wallet $index",
                )
            }
        show(setOf(WalletNetwork.Devnet), profiles)

        compose.onNodeWithTag(ConnectionsTags.DIALOG_DISMISS).assertIsDisplayed()
        compose.onNodeWithTag(WalletTags.PICKER_USE).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(WalletTags.PICKER_ADD))
        compose.onNodeWithTag(WalletTags.PICKER_ADD).assertIsDisplayed().performClick()
        assertEquals(listOf(WalletNetwork.Devnet), added)
    }

    private fun show(
        networks: Set<WalletNetwork>,
        profiles: List<WalletProfile> = listOf(MAIN, DEV),
    ) {
        compose.setContent {
            SeekerTheme(darkTheme = true) {
                ConnectionWalletPicker(
                    connection = connection(networks),
                    wallet =
                        WalletUiState(
                            profiles = profiles,
                            apps =
                                listOf(
                                    InstalledWallet("test.wallet.a", PICKER_ONLY_APP_A),
                                    InstalledWallet("test.wallet.b", PICKER_ONLY_APP_B),
                                ),
                            loaded = true,
                        ),
                    onUse = used::add,
                    onAdd = added::add,
                    onDismiss = {},
                )
            }
        }
    }

    private fun connection(networks: Set<WalletNetwork>) =
        Connection(
            id = CONNECTION,
            label = "Polymarket",
            serverUrl = GATEWAY,
            serverId = SERVER,
            deviceName = "",
            pairedAt = Instant.EPOCH,
            hasCredential = false,
            mode = ConnectionMode.GatewayFeed,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER,
                        protocolVersion = SERVER_PROTOCOL,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference = ServerReference.Feed(GATEWAY, channelFor(SERVER)),
                        environments = setOf(PluginEnvironment.Production),
                        supportedNetworks = networks,
                    )
                ),
        )

    private companion object {
        const val CONNECTION = "connection"
        const val GATEWAY = "https://gateway.example.com"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val ADDRESS = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val MAIN_APP = "Phantom"
        const val DEV_APP = "Jupiter"
        const val PICKER_ONLY_APP_A = "Picker-only Alpha"
        const val PICKER_ONLY_APP_B = "Picker-only Beta"

        val MAIN =
            WalletProfile(
                id = "main",
                address = ADDRESS,
                network = WalletNetwork.Mainnet,
                accountLabel = "phantom",
                route = WalletRouting(packageName = "test.phantom", appLabel = MAIN_APP),
                authorizationId = "main-authorization",
                connectedAt = Instant.EPOCH,
            )
        val DEV =
            WalletProfile(
                id = "dev",
                address = ADDRESS.reversed(),
                network = WalletNetwork.Devnet,
                accountLabel = "renatnomad.skr",
                route = WalletRouting(packageName = "test.jupiter", appLabel = DEV_APP),
                authorizationId = "dev-authorization",
                connectedAt = Instant.EPOCH,
            )
    }
}
