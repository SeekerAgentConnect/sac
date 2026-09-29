package io.github.brrenat.seekervault

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.LegacyUpdateTransport
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.servers.ALL_NETWORKS
import io.github.brrenat.seekervault.servers.directManifest
import io.github.brrenat.seekervault.wallet.BindOutcome
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
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
 * Wallet profiles in the real activity, with the app's own storage, a fake wallet, and fake
 * sidecars (SEE-174): a wallet added on the Wallets tab is saved and chosen for nobody, a
 * connection's own sheet chooses it and only that server is told, removing it leaves its connection
 * without a wallet, and a server that couldn't be told hears when the app comes back.
 */
@RunWith(AndroidJUnit4::class)
class WalletActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val other = gateway.serve(OTHER_URL)
    private val key = softwareKey()
    private val adapter = FakeWalletAdapter()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useFakes() {
        app.connectionGateway = { gateway }
        app.updateTransport = { LegacyUpdateTransport() }
        app.credentialKey = { key }
        app.connectionIo = Dispatchers.Unconfined
        app.walletAdapter = { adapter }
    }

    @After fun close() = scenario?.close() ?: Unit

    private fun launch() = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    /**
     * Pairs with [sidecar], which declares every network, and reads its manifest: a server that
     * hasn't said which networks it supports can't be given a wallet to sign with.
     */
    private fun pair(
        sidecar: FakeConnectionGateway.Server = server,
        url: String = URL,
    ): Connection = runBlocking {
        sidecar.manifest = directManifest(sidecar.serverId, url, networks = ALL_NETWORKS)
        val connection = app.connectionRepository.pair(sidecar.issue(url))
        app.connectionRepository.resolveManifest(connection.id)
        connection
    }

    /** Adds [WALLET] on Devnet from the Wallets tab, the way the owner does, and returns it. */
    private fun addOnTheWalletsTab(token: String = SECRET): WalletProfile {
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        adapter.answerConnected(WALLET, authToken = token, chains = listOf("solana:devnet"))
        compose.onNodeWithTag(WalletTags.network(WalletNetwork.Devnet)).tap()
        compose
            .onNodeWithTag(WalletTags.CONNECT)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        return app.walletRepository.profiles.value.single()
    }

    private val FakeConnectionGateway.Server.bound
        get() = wallet?.let { it.wallet to it.network }

    /**
     * Scrolls [this] into view and clicks it through its semantics. A touch would land on whatever
     * is drawn over it — the bottom navigation covers the end of the scrolled body — and click that
     * instead.
     */
    private fun SemanticsNodeInteraction.tap() =
        performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun systemBackDoesNotReinterpretAPeerTabAsHome() {
        val scenario = launch()
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        compose.onNodeWithTag(WalletTags.STATUS).assertExists()

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithTag(WalletTags.STATUS).assertExists()
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
    }

    @Test
    fun addsAWalletOnTheWalletsTabAndChoosesItForNobody() {
        val connection = pair()
        launch()
        compose
            .onNodeWithTag(ConnectionsTags.WALLET)
            .assertTextContains(app.getString(R.string.wallet_row_none), substring = true)

        val profile = addOnTheWalletsTab()

        assertEquals(WALLET to WalletNetwork.Devnet, profile.address to profile.network)
        compose
            .onNodeWithTag(WalletTags.profile(profile.id))
            .assertTextContains(WALLET)
            .assertTextContains(app.getString(R.string.wallet_network_devnet), substring = true)
            .assertTextContains(app.getString(R.string.wallet_profile_unused), substring = true)
        // Saved, and nobody's: the connection has no wallet until the owner chooses one for it,
        // and its server has only ever heard "no wallet".
        assertNull(app.connectionRepository.connection(connection.id)?.walletProfileId)
        assertNull(server.wallet)
        assertTrue(gateway.published.all { (_, binding) -> binding == null })
        // The authorization never went anywhere near the sidecar.
        assertTrue(gateway.sent.none { (_, secret) -> secret == SECRET })

        // Back on Home the banner names the one saved wallet, and a restart keeps it.
        compose.onNodeWithTag(ConnectionsTags.BACK).performClick()
        compose
            .onNodeWithTag(ConnectionsTags.WALLET)
            .assertTextContains(WALLET.take(4), substring = true)
        scenario?.recreate()
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        compose.onNodeWithTag(WalletTags.profile(profile.id)).assertTextContains(WALLET)
    }

    @Test
    fun choosesTheWalletOnTheConnectionsOwnSheetAndTellsOnlyItsServer() {
        val first = pair()
        val second = pair(other, OTHER_URL)
        launch()
        val profile = addOnTheWalletsTab()
        compose.onNodeWithTag(ConnectionsTags.BACK).performClick()

        compose
            .onNodeWithTag(ConnectionsTags.item(first.id))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose
            .onNodeWithTag(ConnectionsTags.WALLET_ROW)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(WalletTags.choice(profile.id)).performClick()
        compose.onNodeWithTag(WalletTags.PICKER_USE).performClick()
        compose.waitForIdle()

        assertEquals(profile.id, app.connectionRepository.connection(first.id)?.walletProfileId)
        assertEquals(WALLET to Network.NETWORK_DEVNET, server.bound)
        // The other server heard nothing but "no wallet", and its connection still has none.
        assertNull(other.wallet)
        assertNull(app.connectionRepository.connection(second.id)?.walletProfileId)
        assertEquals(
            listOf(URL),
            gateway.published.filter { (_, binding) -> binding != null }.map { it.first },
        )
        assertEquals(profile.selected(), app.walletRepository.walletFor(first.id))
        assertNull(app.walletRepository.walletFor(second.id))
    }

    @Test
    fun removingAWalletLeavesItsConnectionWithoutOne() {
        val connection = pair()
        launch()
        val profile = addOnTheWalletsTab()
        assertEquals(
            BindOutcome.Published(0),
            runBlocking { app.walletRepository.bind(connection.id, profile.id) },
        )
        assertEquals(WALLET to Network.NETWORK_DEVNET, server.bound)

        compose.onNodeWithTag(WalletTags.remove(profile.id)).tap()
        // The confirmation names the connection it leaves without a wallet.
        compose
            .onNodeWithText(app.getString(R.string.wallet_profile_remove_used, connection.label))
            .assertExists()
        compose.onNodeWithTag(WalletTags.REMOVE_CONFIRM).performClick()
        compose.waitForIdle()

        compose
            .onNodeWithTag(WalletTags.STATUS)
            .assertTextContains(app.getString(R.string.wallet_profiles_none_title))
        assertEquals(emptyList<WalletProfile>(), app.walletRepository.profiles.value)
        assertNull(app.connectionRepository.connection(connection.id)?.walletProfileId)
        // Its server was told there is no wallet, and the wallet was asked to forget the
        // authorization no profile uses any more.
        assertNull(server.wallet)
        assertEquals(listOf(SECRET), adapter.disconnects)
        assertTrue(gateway.sent.none { (_, secret) -> secret == SECRET })
    }

    @Test
    fun aServerThatCouldNotBeToldHearsItsWalletWhenTheAppComesBack() {
        val connection = pair()
        launch()
        val profile = addOnTheWalletsTab()
        server.failure = GatewayException.Kind.Unreachable
        assertEquals(
            BindOutcome.PublicationFailed,
            runBlocking { app.walletRepository.bind(connection.id, profile.id) },
        )
        // Nothing signs against a binding the server may not have.
        assertNull(app.walletRepository.walletFor(connection.id))

        // Still unreachable when the app comes back, so the Wallets tab names it.
        scenario?.moveToState(Lifecycle.State.CREATED)
        scenario?.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose
            .onNodeWithTag(WalletTags.PUBLISHED)
            .performScrollTo()
            .assertTextContains(connection.label, substring = true)

        server.failure = null
        compose
            .onNodeWithTag(WalletTags.PUBLISH_AGAIN, useUnmergedTree = true)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(WALLET to Network.NETWORK_DEVNET, server.bound)
        compose.onNodeWithTag(WalletTags.PUBLISHED).assertDoesNotExist()
        assertEquals(profile.selected(), app.walletRepository.walletFor(connection.id))
    }

    @Test
    fun saysWhyNothingHappenedWhenNoWalletIsInstalled() {
        launch()
        compose.onNodeWithTag(ConnectionsTags.WALLET).performClick()
        adapter.answer(WalletResult.NoWallet)
        compose
            .onNodeWithTag(WalletTags.CONNECT)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose
            .onNodeWithTag(WalletTags.PROBLEM)
            .assertTextContains(app.getString(R.string.wallet_problem_no_wallet))
        // Nothing was saved, and adding is still offered.
        assertEquals(emptyList<WalletProfile>(), app.walletRepository.profiles.value)
        compose.onNodeWithTag(WalletTags.STATUS).assertExists()
        compose.onNodeWithTag(WalletTags.CONNECT).assertExists()
    }

    private companion object {
        const val URL = "http://127.0.0.1:8080"
        const val OTHER_URL = "http://127.0.0.1:8081"
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val SECRET = "authorization-the-wallet-issued-0123456789"
    }
}
