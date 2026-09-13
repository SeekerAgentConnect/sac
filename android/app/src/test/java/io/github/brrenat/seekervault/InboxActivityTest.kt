package io.github.brrenat.seekervault

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LegacyUpdateTransport
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.inbox.InboxTags
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The inbox in the real activity, with the app's own storage and a fake sidecar: a request made
 * while the app was closed is fetched on opening, answered, and stays answered.
 */
@RunWith(AndroidJUnit4::class)
class InboxActivityTest {
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

    @Test
    fun aRequestMadeWhileTheAppWasClosedIsAnsweredAndStaysAnswered() {
        // Paired earlier; the agent asked while the app was closed.
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        val request = server.addPending(connection.id, text = "Deploy finished")
        val key = RequestKey(connection.id, request.ref.requestId)
        val scenario = launch()

        // Opening the app fetched the request, and answered nothing.
        compose
            .onNodeWithTag(ConnectionsTags.INBOX)
            .assertTextContains(app.getString(R.string.requests_see_all, 1))
            .performClick()
        assertTrue(gateway.submits.isEmpty())
        compose.onNodeWithTag(InboxTags.item(key)).assertExists()
        compose.onNodeWithText("Deploy finished", substring = true).assertExists()
        compose.onNodeWithText(app.getString(R.string.review)).performClick()
        compose
            .onNodeWithTag(InboxTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Deploy finished")
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).performClick()
        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextEquals(app.getString(R.string.status_acknowledged))
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            server.stateOf(connection.id, request.ref.requestId),
        )

        // Back in the list it's answered; reopened, it shows the outcome, not the buttons.
        compose.onNodeWithTag(ConnectionsTags.CLOSE).performClick()
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag(InboxTags.SECTION_ANSWERED).assertExists()
        compose.onNodeWithTag(InboxTags.item(key)).performClick()
        scenario.recreate()
        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextEquals(app.getString(R.string.status_acknowledged))
        compose.onNodeWithTag(InboxTags.REJECT).assertDoesNotExist()
        assertEquals(1, gateway.submits.size)
    }

    @Test
    fun aRequestMadeWhileTheAppWasInTheBackgroundShowsWhenItComesBack() {
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        val scenario = launch()
        compose.waitForIdle()
        server.addPending(connection.id, text = "While away")
        // A rotation doesn't fetch.
        scenario.recreate()
        compose.waitForIdle()
        assertTrue(app.connectionRepository.inbox.value.pending[connection.id].orEmpty().isEmpty())
        // Leaving the app and coming back does, as opening it does.
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose
            .onNodeWithTag(ConnectionsTags.INBOX)
            .assertTextContains(app.getString(R.string.requests_see_all, 1))
        assertTrue(gateway.submits.isEmpty())
    }

    @Test
    fun aConnectionsDetailsOpenItsOwnRequestsOnly() {
        val home = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        val vps = runBlocking { app.connectionRepository.pair(other.issue(OTHER_URL)) }
        val mine = server.addPending(home.id, text = "For home")
        val theirs = other.addPending(vps.id, text = "For the VPS")
        launch()
        compose.onNodeWithTag(ConnectionsTags.LIST).performScrollToIndex(5)
        compose.onNodeWithTag(ConnectionsTags.item(home.id)).performClick()
        compose.onNodeWithTag(ConnectionsTags.PENDING).performScrollTo().performClick()
        compose
            .onNodeWithTag(InboxTags.item(RequestKey(home.id, mine.ref.requestId)))
            .assertExists()
        compose
            .onNodeWithTag(InboxTags.item(RequestKey(vps.id, theirs.ref.requestId)))
            .assertDoesNotExist()
    }

    @Test
    fun aRotationWhileTheWalletHasTheMessageKeepsTheRequestAndItsSignature() {
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        adapter.answerConnected(WALLET)
        runBlocking { app.walletRepository.connect(WalletNetwork.Mainnet) }
        val request = server.addPendingMessage(connection.id, WALLET, "Sign in to Example")
        val key = RequestKey(connection.id, request.ref.requestId)
        val signature = ByteString.copyFrom(ByteArray(64) { 6 })
        val release = CompletableDeferred<Unit>()
        adapter.signWith(signature)
        adapter.beforeSigning = { release.await() }
        val scenario = launch()

        compose.onNodeWithTag(ConnectionsTags.INBOX).performClick()
        compose.onNodeWithTag(InboxTags.item(key)).assertExists()
        compose.onNodeWithText(app.getString(R.string.review)).performClick()
        compose.onNodeWithTag(InboxTags.APPROVE).performClick()
        compose.waitForIdle()
        // The approval has gone, and the message is with the wallet.
        assertEquals(1, adapter.signings.size)
        assertEquals(
            RequestState.REQUEST_STATE_PROCESSING,
            server.stateOf(connection.id, request.ref.requestId),
        )

        // The screen is recreated while the wallet has it, as a rotation does.
        scenario.recreate()
        compose.waitForIdle()

        // The request is still there, and the wallet was not asked a second time.
        compose.onNodeWithTag(InboxTags.STATUS).assertExists()
        assertEquals(1, adapter.signings.size)
        assertEquals(
            RequestState.REQUEST_STATE_PROCESSING,
            server.stateOf(connection.id, request.ref.requestId),
        )

        release.complete(Unit)
        compose.waitForIdle()

        compose
            .onNodeWithTag(InboxTags.STATUS)
            .assertTextEquals(app.getString(R.string.status_signed))
        assertEquals(1, adapter.signings.size)
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            server.stateOf(connection.id, request.ref.requestId),
        )
    }

    @Test
    fun aRotationWhileTheWalletHasTheTransactionKeepsTheApprovalAndAsksItOnlyOnce() {
        val case =
            org.json
                .JSONObject(
                    checkNotNull(javaClass.getResourceAsStream("/transactions/cases.json")).use {
                        it.readBytes().decodeToString()
                    }
                )
                .getJSONArray("cases")
                .let { cases ->
                    (0 until cases.length())
                        .map { cases.getJSONObject(it) }
                        .first { it.getString("name") == "sol_transfer" }
                }
        val fields = case.getJSONObject("request")
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        adapter.answerConnected(fields.getString("wallet"))
        runBlocking { app.walletRepository.connect(WalletNetwork.Devnet) }
        val request =
            server.addPendingTransfer(
                connection.id,
                fields.getString("wallet"),
                Network.NETWORK_DEVNET,
                recipient = fields.getString("recipient"),
                amount = fields.getString("amount"),
            )
        val key = RequestKey(connection.id, request.ref.requestId)
        gateway.transactions[key] =
            java.util.Base64.getDecoder().decode(case.getString("transaction"))
        val release = CompletableDeferred<Unit>()
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 2 }))
        adapter.beforeSending = { release.await() }
        val scenario = launch()

        compose.onNodeWithTag(ConnectionsTags.INBOX).performClick()
        compose.onNodeWithTag(InboxTags.item(key)).assertExists()
        compose.onNodeWithText(app.getString(R.string.review)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).performClick()
        compose.waitForIdle()

        // The approval was accepted before the wallet was opened, and the wallet got the bytes.
        assertEquals(
            RequestState.REQUEST_STATE_PROCESSING,
            server.stateOf(connection.id, request.ref.requestId),
        )
        assertEquals(1, adapter.sendings.size)
        assertEquals(
            gateway.transactions.getValue(key).toList(),
            adapter.sendings.single().first.toByteArray().toList(),
        )

        // The screen is recreated while the wallet has it, as a rotation does.
        scenario.recreate()
        compose.waitForIdle()
        assertEquals(1, adapter.sendings.size)
        assertEquals(
            RequestState.REQUEST_STATE_PROCESSING,
            server.stateOf(connection.id, request.ref.requestId),
        )

        release.complete(Unit)
        compose.waitForIdle()

        assertEquals(1, adapter.sendings.size)
        assertEquals(
            RequestState.REQUEST_STATE_SUBMITTED,
            server.stateOf(connection.id, request.ref.requestId),
        )
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val URL = "https://mac.tailnet.ts.net"
        const val OTHER_URL = "https://vps.example.com"
    }
}
