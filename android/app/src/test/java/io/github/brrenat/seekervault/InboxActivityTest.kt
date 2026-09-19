package io.github.brrenat.seekervault

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRetirement
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.LegacyUpdateTransport
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.inbox.InboxTags
import io.github.brrenat.seekervault.notifications.RequestNotificationIntent
import io.github.brrenat.seekervault.policy.PolicyTags
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
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

    private fun launch(intent: Intent) =
        ActivityScenario.launch<MainActivity>(intent).also {
            scenario = it
        }

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
        compose
            .onNodeWithText(app.getString(R.string.review))
            .performSemanticsAction(SemanticsActions.OnClick)
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
        compose.onNodeWithText("History").performClick()
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
    fun connectionDetailsExposeRulesAndItsInboxDrillIn() {
        val home = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        server.addPending(home.id, text = "For home")
        launch()
        compose
            .onNodeWithTag(ConnectionsTags.item(home.id))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(PolicyTags.RULES).performScrollTo().assertExists()
        compose.onNodeWithTag(ConnectionsTags.PENDING).performScrollTo().performClick()
        compose.onNodeWithTag(InboxTags.LIST).assertExists()
    }

    @Test
    fun notificationTapFetchesAndOpensTheExactRequestWithoutAnyWalletOperation() {
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        val request = server.addPendingMessage(connection.id, WALLET, "Sign only after review")
        val key = RequestKey(connection.id, request.ref.requestId)

        launch(RequestNotificationIntent.intent(app, key))

        compose
            .onNodeWithTag(InboxTags.MESSAGE, useUnmergedTree = true)
            .assertTextEquals("Sign only after review")
        assertTrue(gateway.submits.isEmpty())
        assertTrue(adapter.signings.isEmpty())
        assertTrue(adapter.sendings.isEmpty())
    }

    @Test
    fun staleNotificationTapShowsTheCheckedGoneStateAndNoReviewControls() {
        val connection = runBlocking { app.connectionRepository.pair(server.issue(URL)) }
        val request = server.addPending(connection.id, text = "Already gone")
        val key = RequestKey(connection.id, request.ref.requestId)
        runBlocking { app.connectionRepository.refresh(connection.id) }
        server.cancel(connection.id, request.ref.requestId)

        launch(RequestNotificationIntent.intent(app, key))

        compose
            .onNodeWithTag(InboxTags.NOTIFICATION_STATE)
            .assertTextEquals(app.getString(R.string.notification_open_gone))
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.APPROVE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertDoesNotExist()
        assertTrue(gateway.submits.isEmpty())
        assertTrue(adapter.signings.isEmpty())
        assertTrue(adapter.sendings.isEmpty())
    }

    @Test
    fun retiredConnectionNotificationOpensOnlyTheInertExplanation() {
        val id = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        ConnectionStore(File(app.filesDir, "connections"))
            .put(
                Connection(
                    id = id,
                    label = "Former gateway",
                    serverUrl = "https://gateway.example",
                    serverId = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                    deviceName = "Seeker",
                    pairedAt = Instant.parse("2026-09-18T12:00:00Z"),
                    hasCredential = false,
                    mode = null,
                    retirement = ConnectionRetirement.GatewayPrivateRemoved,
                    server = ServerRecord.Unknown,
                )
            )
        val key = RequestKey(id, "7c9e6679-7425-40de-944b-e07fc1f90ae7")

        launch(RequestNotificationIntent.intent(app, key))

        compose
            .onNodeWithText(app.getString(R.string.connection_status_gateway_private_retired))
            .assertExists()
        compose.onNodeWithTag(InboxTags.ACKNOWLEDGE).assertDoesNotExist()
        compose.onNodeWithTag(InboxTags.APPROVE).assertDoesNotExist()
        assertTrue(gateway.sent.isEmpty())
        assertTrue(adapter.signings.isEmpty())
        assertTrue(adapter.sendings.isEmpty())
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
        compose
            .onNodeWithText(app.getString(R.string.review))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(InboxTags.APPROVE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AppNavigationTags.WALLET_HANDOFF).assertExists()
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(connection.id, request.ref.requestId),
        )
        assertTrue(adapter.signings.isEmpty())
        compose.onNodeWithText("Sign and send").performClick()
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

        // The request is still in History, and the wallet was not asked a second time.
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithTag(InboxTags.item(key)).performClick()
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
        compose
            .onNodeWithText(app.getString(R.string.review))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).performClick()
        compose.waitForIdle()

        // Approve first opens the explicit wallet hand-off over the still-pending review. Back
        // reveals that same review without sending or answering anything.
        compose.onNodeWithTag(AppNavigationTags.WALLET_HANDOFF).assertExists()
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(connection.id, request.ref.requestId),
        )
        assertTrue(adapter.sendings.isEmpty())
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertExists()
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(connection.id, request.ref.requestId),
        )
        compose.mainClock.advanceTimeBy(320)
        compose.waitForIdle()

        // The named non-answer exit has the same semantics as Back.
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).performClick()
        compose
            .onNodeWithText("Leave without answering")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(InboxTags.TRANSFER_APPROVE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).assertExists()
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(connection.id, request.ref.requestId),
        )

        compose.onNodeWithTag(InboxTags.TRANSFER_APPROVE).performClick()
        compose.onNodeWithText("Sign and send").performClick()
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
    }
}
