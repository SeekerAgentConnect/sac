package io.github.brrenat.seekervault

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.confirmations.ChainReader
import io.github.brrenat.seekervault.confirmations.ChainTransaction
import io.github.brrenat.seekervault.confirmations.GENESIS_HASHES
import io.github.brrenat.seekervault.confirmations.SignatureStatus
import io.github.brrenat.seekervault.confirmations.hostOf
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.rpc.RpcDefaults
import io.github.brrenat.seekervault.rpc.RpcSource
import io.github.brrenat.seekervault.rpc.RpcTags
import io.github.brrenat.seekervault.rpc.SolanaRpc
import io.github.brrenat.seekervault.rpc.storage.RpcSettingsStore
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import io.github.brrenat.seekervault.wallet.WalletTags
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The Solana RPC sheet in the real activity (SEE-184): each network is shown on its own with where
 * it is asked and what that endpoint serves; an endpoint for another cluster is refused with the
 * reason; a good one is saved and used at once; reset goes back to the build's own. The chain is a
 * fake per URL, answering only the genesis question the sheet asks.
 */
@RunWith(AndroidJUnit4::class)
class RpcSettingsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val dir by lazy { File(folder.root, "rpc") }

    private val chains =
        mapOf(
            MAINNET_URL to GENESIS_HASHES.getValue(Network.NETWORK_MAINNET),
            DEVNET_BUILD_URL to GENESIS_HASHES.getValue(Network.NETWORK_DEVNET),
            DEVNET_OWN_URL to GENESIS_HASHES.getValue(Network.NETWORK_DEVNET),
        )

    @Before
    fun useFakes() {
        app.connectionIo = Dispatchers.Unconfined
        app.solanaRpc = {
            SolanaRpc(
                RpcSettingsStore(dir),
                RpcDefaults(
                    perNetwork = mapOf(Network.NETWORK_DEVNET to DEVNET_BUILD_URL),
                    general = "",
                ),
                reader = { url -> GenesisOnly(url, chains[url]) },
                accounts = { error("the sheet reads no accounts") },
            )
        }
    }

    @After fun close() = scenario?.close() ?: Unit

    private fun SemanticsNodeInteraction.tap() =
        performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun shows(text: String) =
        compose.onNodeWithText(text, useUnmergedTree = true).assertExists()

    @Test
    fun eachNetworkIsSetCheckedSavedAndResetOnItsOwn() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        compose.onNodeWithTag(WalletTags.RPC_SETTINGS).tap()
        compose.onNodeWithTag(RpcTags.SHEET).assertExists()

        // Devnet: the build's endpoint, checked when the sheet opens. Mainnet: nothing set.
        val devnet = Network.NETWORK_DEVNET
        shows(text(R.string.rpc_in_use_build, "devnet-build.example.com"))
        compose
            .onNodeWithTag(RpcTags.status(devnet), useUnmergedTree = true)
            .assertTextContains(text(R.string.rpc_status_serves, "devnet"))
        compose
            .onNodeWithTag(RpcTags.status(Network.NETWORK_MAINNET), useUnmergedTree = true)
            .assertTextContains(text(R.string.rpc_status_none, "mainnet"))
        compose.onNodeWithTag(RpcTags.reset(devnet), useUnmergedTree = true).assertIsNotEnabled()

        // A mainnet endpoint offered for devnet is refused, with the reason, and nothing changes.
        compose.onNodeWithTag(RpcTags.field(devnet), useUnmergedTree = true).performScrollTo()
        compose
            .onNodeWithTag(RpcTags.field(devnet), useUnmergedTree = true)
            .performTextInput(MAINNET_URL)
        compose.onNodeWithTag(RpcTags.save(devnet), useUnmergedTree = true).tap()
        compose
            .onNodeWithText(
                text(
                    R.string.rpc_not_saved,
                    text(R.string.rpc_status_other_network, "mainnet", "devnet"),
                ),
                useUnmergedTree = true,
            )
            .assertExists()
        assertEquals(RpcSource.Build, app.rpc.endpoint(devnet)?.source)
        assertFalse(dir.resolve("settings.json").exists())

        // The owner's own devnet endpoint: checked, saved, and in use at once.
        compose.onNodeWithTag(RpcTags.field(devnet), useUnmergedTree = true).performTextClearance()
        compose
            .onNodeWithTag(RpcTags.field(devnet), useUnmergedTree = true)
            .performTextInput(DEVNET_OWN_URL)
        compose.onNodeWithTag(RpcTags.save(devnet), useUnmergedTree = true).tap()
        shows(text(R.string.rpc_in_use_owner, hostOf(DEVNET_OWN_URL)))
        assertEquals(DEVNET_OWN_URL, app.rpc.endpoint(devnet)?.url)
        assertEquals(DEVNET_OWN_URL, RpcSettingsStore(dir).read()[devnet])
        // Mainnet was not touched.
        assertEquals(null, app.rpc.endpoint(Network.NETWORK_MAINNET))

        // Reset: the build's endpoint again.
        compose.onNodeWithTag(RpcTags.reset(devnet), useUnmergedTree = true).tap()
        shows(text(R.string.rpc_in_use_build, "devnet-build.example.com"))
        assertEquals(RpcSource.Build, app.rpc.endpoint(devnet)?.source)
        assertEquals(emptyMap<Network, String>(), RpcSettingsStore(dir).read())
    }

    /** A chain that answers which cluster it is and nothing else. */
    private class GenesisOnly(url: String, private val genesis: String?) : ChainReader {
        override val host = hostOf(url)

        override suspend fun genesisHash(): String =
            genesis ?: throw SolanaException(SolanaProblem.Unreachable)

        override suspend fun statuses(signatures: List<String>, searchHistory: Boolean) =
            emptyList<SignatureStatus?>()

        override suspend fun transaction(signature: String): ChainTransaction? = null

        override suspend fun blockhashValid(blockhash: String) = true

        override suspend fun retainedSince(): Instant? = null

        override suspend fun signaturesFor(address: String, limit: Int) = emptyList<String>()
    }

    private companion object {
        const val MAINNET_URL = "https://mainnet.example.com/?api-key=k"
        const val DEVNET_BUILD_URL = "https://devnet-build.example.com"
        const val DEVNET_OWN_URL = "https://devnet-own.example.com/rpc"
    }
}
