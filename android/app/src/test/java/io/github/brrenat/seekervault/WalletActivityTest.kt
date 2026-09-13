package io.github.brrenat.seekervault

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletResult
import io.github.brrenat.seekervault.wallet.WalletTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wallet in the real activity, with the app's own storage, a fake wallet, and a fake sidecar:
 * connecting from the Connections screen publishes the address, a restart keeps it, and
 * disconnecting tells the sidecar there's no wallet.
 */
@RunWith(AndroidJUnit4::class)
class WalletActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val key = softwareKey()
    private val adapter = FakeWalletAdapter()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useFakes() {
        app.connectionGateway = { gateway }
        app.credentialKey = { key }
        app.connectionIo = Dispatchers.Unconfined
        app.walletAdapter = { adapter }
    }

    @After fun close() = scenario?.close() ?: Unit

    private fun launch() = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    @Test
    fun connectsAWalletFromTheConnectionsScreenAndTellsTheSidecar() {
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        launch()

        compose
            .onNodeWithTag(ConnectionsTags.WALLET)
            .assertTextContains(app.getString(R.string.wallet_row_none))
            .performClick()
        adapter.answerConnected(WALLET, chains = listOf("solana:devnet"))
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).performClick()
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().performClick()

        compose.onNodeWithTag(WalletTags.field("address")).assertTextContains(WALLET)
        assertEquals(WALLET, server.wallet?.wallet)
        assertEquals(Network.NETWORK_DEVNET, server.wallet?.network)
        // The binding went to this connection's own server, and nowhere else.
        assertEquals(setOf(URL), gateway.published.map { it.first }.toSet())
        assertTrue(app.connectionRepository.connection(connection.id)!!.usable)

        // Back on Connections the row names the wallet, and a restart keeps it.
        compose.onNodeWithTag(ConnectionsTags.BACK).performClick()
        compose
            .onNodeWithTag(ConnectionsTags.WALLET)
            .assertTextContains(WALLET.take(9), substring = true)
        scenario?.recreate()
        compose
            .onNodeWithTag(ConnectionsTags.WALLET)
            .assertTextContains(WALLET.take(9), substring = true)
    }

    @Test
    fun disconnectingTellsTheWalletAndTheSidecar() {
        runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        launch()
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        adapter.answerConnected(WALLET, authToken = SECRET)
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().performClick()

        compose.onNodeWithTag(WalletTags.DISCONNECT).performScrollTo().performClick()
        compose
            .onNodeWithTag(WalletTags.STATUS)
            .assertTextContains(app.getString(R.string.wallet_none_title))
        assertEquals(listOf(SECRET), adapter.disconnects)
        assertNull(server.wallet)
        // The authorization never went anywhere near the sidecar.
        assertTrue(gateway.sent.none { (_, secret) -> secret == SECRET })
    }

    @Test
    fun aConnectionPairedAfterwardsLearnsTheWalletWhenTheAppComesBack() {
        launch()
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        adapter.answerConnected(WALLET)
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().performClick()
        compose.onNodeWithTag(WalletTags.field("address")).assertTextContains(WALLET)
        assertNull(server.wallet)

        runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        scenario?.moveToState(Lifecycle.State.CREATED)
        scenario?.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertEquals(WALLET, server.wallet?.wallet)
    }

    @Test
    fun saysWhyNothingHappenedWhenNoWalletIsInstalled() {
        launch()
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        adapter.answer(WalletResult.NoWallet)
        compose.onNodeWithTag(WalletTags.CONNECT).performScrollTo().performClick()
        compose
            .onNodeWithTag(WalletTags.PROBLEM)
            .assertTextContains(app.getString(R.string.wallet_problem_no_wallet))
        compose.onNodeWithTag(WalletTags.CONNECT).assertExists()
    }

    private companion object {
        const val URL = "http://127.0.0.1:8080"
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val SECRET = "authorization-the-wallet-issued-0123456789"
    }
}
