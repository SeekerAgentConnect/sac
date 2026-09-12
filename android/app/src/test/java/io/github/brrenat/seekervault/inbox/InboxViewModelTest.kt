package io.github.brrenat.seekervault.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.transactions.Finding
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SignResult
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletRepository
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The inbox's state and actions against fake sidecars. Coroutines run eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class InboxViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val other = gateway.serve(OTHER_URL)
    private val key = softwareKey()
    private val repository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = CredentialVault(File(folder.root, "credentials")) { key },
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            deviceName = "Seeker",
            io = Dispatchers.Unconfined,
        )
    }

    private val adapter = FakeWalletAdapter()
    private val wallet by lazy {
        WalletRepository(
            WalletStore(File(folder.root, "wallet"), File(folder.root, "no_backup/wallet")) { key },
            adapter,
            repository,
            io = Dispatchers.Unconfined,
        )
    }

    private val scheduler = TestCoroutineScheduler()

    private fun viewModel() = InboxViewModel(repository, wallet)

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @After fun resetMain() = Dispatchers.resetMain()

    private fun pendingRequest(
        target: FakeConnectionGateway.Server = server,
        url: String = URL,
    ): RequestKey = runBlocking {
        val connection = repository.pair(target.issue(url))
        val request = target.addPending(connection.id)
        repository.refresh(connection.id)
        RequestKey(connection.id, request.ref.requestId)
    }

    @Test
    fun aRapidSecondTapSendsNothingMore() {
        val request = pendingRequest()
        val release = CompletableDeferred<Unit>()
        gateway.beforeSubmit = { release.await() }
        val viewModel = viewModel()
        viewModel.answer(request, Answer.Acknowledge)
        assertEquals(setOf(request), viewModel.state.value.sending)
        viewModel.answer(request, Answer.Acknowledge)
        viewModel.answer(request, Answer.Reject)
        release.complete(Unit)
        assertEquals(1, gateway.submits.size)
        assertTrue(gateway.submits.single().second.hasAcknowledgement())
        assertTrue(viewModel.state.value.sending.isEmpty())
        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(request)?.delivery)
        // Tapping again on the answered request changes nothing either.
        viewModel.answer(request, Answer.Reject)
        assertEquals(1, gateway.submits.size)
    }

    @Test
    fun refreshFetchesEveryUsableConnection() {
        val a = pendingRequest(server, URL)
        val b = pendingRequest(other, OTHER_URL)
        server.addPending(a.connectionId)
        other.addPending(b.connectionId)
        val viewModel = viewModel()
        viewModel.refresh()
        assertFalse(viewModel.state.value.refreshing)
        assertEquals(2, viewModel.state.value.inbox.pending[a.connectionId]?.size)
        assertEquals(2, viewModel.state.value.inbox.pending[b.connectionId]?.size)
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    @Test
    fun sendsAWaitingAnswerAgainOnRequest() {
        val request = pendingRequest()
        server.failure = GatewayException.Kind.Unreachable
        val viewModel = viewModel()
        viewModel.answer(request, Answer.Acknowledge)
        assertEquals(Delivery.Waiting, viewModel.state.value.inbox.result(request)?.delivery)
        server.failure = null
        viewModel.sendAgain(request)
        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(request)?.delivery)
    }

    @Test
    fun ignoresAnAnswerForARequestThatIsNotPending() {
        val request = pendingRequest()
        val viewModel = viewModel()
        viewModel.answer(request.copy(requestId = OTHER_REQUEST), Answer.Acknowledge)
        assertTrue(viewModel.state.value.sending.isEmpty())
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    // A wallet the owner connected, and a message waiting for their approval.
    private fun readyToSign(
        address: String = WALLET,
        network: WalletNetwork = WalletNetwork.Mainnet,
        text: String = "Sign in to Example",
    ): Pair<RequestKey, SelectedWallet> = runBlocking {
        val connection = repository.pair(server.issue(URL))
        adapter.answerConnected(address)
        wallet.connect(network)
        val request = server.addPendingMessage(connection.id, address, text)
        repository.refresh(connection.id)
        RequestKey(connection.id, request.ref.requestId) to
            checkNotNull(wallet.wallet.value) { "the wallet is connected" }
    }

    private fun submitted() = gateway.submits.map { it.second.resultCase }

    @Test
    fun asksTheWalletOnlyOnceTheOwnerHasApproved() {
        val (key, selected) = readyToSign()
        val signature = ByteString.copyFrom(ByteArray(64) { 7 })
        adapter.signWith(signature)
        val viewModel = viewModel()
        // Opening the request, and everything before the tap, reaches no wallet at all.
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(emptyList<Any>(), submitted())

        viewModel.approve(key, selected)

        // The approval goes first, and the wallet is asked only afterwards.
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.MESSAGE_SIGNATURE,
            ),
            submitted(),
        )
        val asked = adapter.signings.single()
        assertEquals(ByteString.copyFromUtf8("Sign in to Example"), asked.first)
        assertEquals(selected, asked.second)
        val result = checkNotNull(viewModel.state.value.inbox.result(key))
        assertEquals(Delivery.Accepted, result.delivery)
        assertEquals(SigningOutcome.Signed(signature), result.signing)
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun asksNothingWhenTheWalletChangedWhileTheOwnerWasReviewing() {
        val (key, reviewed) = readyToSign()
        // The owner connected another wallet on the Wallet screen while this was on screen. The
        // server couldn't be told, so this phone still has the request in front of them.
        runBlocking {
            server.failure = GatewayException.Kind.Unreachable
            adapter.answerConnected(OTHER_WALLET)
            wallet.connect(WalletNetwork.Mainnet)
            server.failure = null
        }
        assertNotNull(repository.inbox.value.pendingRequest(key))
        val viewModel = viewModel()

        viewModel.approve(key, reviewed)

        assertEquals(SigningProblem.Changed, viewModel.state.value.problem)
        assertEquals(key, viewModel.state.value.problemKey)
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(emptyList<Any>(), submitted())
        assertNull(viewModel.state.value.inbox.result(key))
    }

    @Test
    fun approvesNothingWithoutAWalletToSignWith() {
        val connection = runBlocking { repository.pair(server.issue(URL)) }
        val request = server.addPendingMessage(connection.id, WALLET)
        runBlocking { repository.refresh(connection.id) }
        val key = RequestKey(connection.id, request.ref.requestId)
        val viewModel = viewModel()

        viewModel.approve(key, null)

        assertEquals(SigningProblem.NoWallet, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(emptyList<Any>(), submitted())
    }

    @Test
    fun reportsARequestForAnotherWalletInsteadOfSigningWithThisOne() {
        val (first, selected) = readyToSign()
        // An agent's request naming a wallet the owner doesn't have: it can only be rejected.
        val request = server.addPendingMessage(first.connectionId, OTHER_WALLET)
        runBlocking { repository.refresh(first.connectionId) }
        val other = RequestKey(first.connectionId, request.ref.requestId)
        val viewModel = viewModel()

        viewModel.approve(other, selected)

        assertEquals(SigningProblem.OtherWallet, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(emptyList<Any>(), submitted())
    }

    @Test
    fun asksTheWalletNothingWhenTheRequestMovedOnWhileItWasReviewed() {
        val (key, selected) = readyToSign()
        // The agent withdrew it, or it expired, while it was on screen: the approval is refused,
        // and the wallet is never asked.
        server.cancel(key.connectionId, key.requestId)
        val viewModel = viewModel()

        viewModel.approve(key, selected)

        assertEquals(listOf(SubmitResultRequest.ResultCase.APPROVAL), submitted())
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(Delivery.Superseded, viewModel.state.value.inbox.result(key)?.delivery)
    }

    @Test
    fun recordsAWalletThatDeclined() {
        val (key, selected) = readyToSign()
        adapter.answerSigning(SignResult.Declined)
        val viewModel = viewModel()

        viewModel.approve(key, selected)

        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.REJECTION,
            ),
            submitted(),
        )
        assertEquals(SigningOutcome.Declined, viewModel.state.value.inbox.result(key)?.signing)
        assertEquals(
            RequestState.REQUEST_STATE_REJECTED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun recordsAWalletThatCouldNotSign() {
        val (key, selected) = readyToSign()
        adapter.answerSigning(SignResult.Failed("the wallet is locked"))
        val viewModel = viewModel()

        viewModel.approve(key, selected)

        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.EXECUTION_FAILURE,
            ),
            submitted(),
        )
        val outcome = viewModel.state.value.inbox.result(key)?.signing
        assertTrue("$outcome", outcome is SigningOutcome.Failed)
        assertEquals(
            "The wallet could not sign: the wallet is locked",
            (outcome as SigningOutcome.Failed).detail,
        )
        assertEquals(
            RequestState.REQUEST_STATE_FAILED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun keepsNoSignatureThatIsOverOtherBytes() {
        val (key, selected) = readyToSign()
        // A wallet that signed something else has signed nothing this request asked for.
        adapter.answerSigning(
            SignResult.Signed(
                ByteString.copyFromUtf8("Sign in to Example "),
                selected.address,
                ByteString.copyFrom(ByteArray(64) { 7 }),
            )
        )
        val viewModel = viewModel()

        viewModel.approve(key, selected)

        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.EXECUTION_FAILURE,
            ),
            submitted(),
        )
        assertEquals(
            SigningOutcome.Failed("The wallet signed other bytes than the message."),
            viewModel.state.value.inbox.result(key)?.signing,
        )
    }

    @Test
    fun approvesOnceHoweverFastTheOwnerTaps() {
        val (key, selected) = readyToSign()
        val signature = ByteString.copyFrom(ByteArray(64) { 7 })
        adapter.signWith(signature)
        val release = CompletableDeferred<Unit>()
        adapter.beforeSigning = { release.await() }
        val viewModel = viewModel()

        viewModel.approve(key, selected)
        assertEquals(setOf(key), viewModel.state.value.sending)
        // More taps while the wallet is in front, on Approve and on Reject alike.
        viewModel.approve(key, selected)
        viewModel.answer(key, Answer.Reject)
        release.complete(Unit)

        assertEquals(1, adapter.signings.size)
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.MESSAGE_SIGNATURE,
            ),
            submitted(),
        )
        assertEquals(
            SigningOutcome.Signed(signature),
            viewModel.state.value.inbox.result(key)?.signing,
        )
    }

    @Test
    fun settlesAnApprovalWhoseAnswerNeverArrivedWhenTheAppComesBack() {
        val (key, selected) = readyToSign()
        val release = CompletableDeferred<Unit>()
        adapter.beforeSigning = { release.await() }
        val open = viewModel()
        open.approve(key, selected)
        assertEquals(1, adapter.signings.size)

        // Coming back to a screen that knows this signing leaves it alone.
        open.onAppVisible()
        assertNull(viewModel().state.value.inbox.result(key)?.signing)

        // Coming back to a new screen, after the process died in the wallet, settles it: no
        // signature reached this phone, so none exists anywhere.
        val reopened = viewModel()
        reopened.onAppVisible()

        assertEquals(
            SigningOutcome.Unresolved(
                "The wallet's answer never reached this phone, so nothing was signed."
            ),
            reopened.state.value.inbox.result(key)?.signing,
        )
        assertEquals(
            RequestState.REQUEST_STATE_FAILED,
            server.stateOf(key.connectionId, key.requestId),
        )
        // And an answer that turns up afterwards changes nothing, and asks no wallet again.
        release.complete(Unit)
        assertEquals(1, adapter.signings.size)
        assertTrue(reopened.state.value.inbox.result(key)?.signing is SigningOutcome.Unresolved)
    }

    @Test
    fun givesUpOnAWalletThatNeverAnswersAtAll() {
        val (key, selected) = readyToSign()
        adapter.beforeSigning = { CompletableDeferred<Unit>().await() }
        val viewModel = viewModel()

        viewModel.approve(key, selected)
        assertEquals(setOf(key), viewModel.state.value.sending)
        scheduler.advanceUntilIdle()

        assertEquals(
            SigningOutcome.Unresolved(
                "The wallet didn't answer, so nothing reached this phone and nothing was signed."
            ),
            viewModel.state.value.inbox.result(key)?.signing,
        )
        assertEquals(
            RequestState.REQUEST_STATE_FAILED,
            server.stateOf(key.connectionId, key.requestId),
        )
        assertTrue(viewModel.state.value.sending.isEmpty())
    }

    @Test
    fun sendsTheSignatureAgainAfterALostResponseWithoutAskingTheWalletTwice() {
        val (key, selected) = readyToSign()
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        // The signature reaches the sidecar, and the response is lost on the way back.
        adapter.beforeSigning = { server.loseNextResponse = true }
        val viewModel = viewModel()

        viewModel.approve(key, selected)
        assertEquals(Delivery.Waiting, viewModel.state.value.inbox.result(key)?.delivery)

        viewModel.sendAgain(key)

        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(key)?.delivery)
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            server.stateOf(key.connectionId, key.requestId),
        )
        // Sending the result again never reaches the wallet: it was asked exactly once.
        assertEquals(1, adapter.signings.size)
    }

    @Test
    fun neverSettlesAnotherConnectionsRequestWithThisOnesReply() {
        val first = runBlocking { repository.pair(server.issue(URL)) }
        val second = runBlocking { repository.pair(other.issue(OTHER_URL)) }
        adapter.answerConnected(WALLET)
        val selected = runBlocking {
            wallet.connect(WalletNetwork.Mainnet)
            checkNotNull(wallet.wallet.value)
        }
        // The same request ID on both servers: only the connection tells them apart.
        server.addPendingMessage(first.id, WALLET, "For the first", OTHER_REQUEST)
        other.addPendingMessage(second.id, WALLET, "For the second", OTHER_REQUEST)
        runBlocking {
            repository.refresh(first.id)
            repository.refresh(second.id)
        }
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 4 }))
        val viewModel = viewModel()

        viewModel.approve(RequestKey(first.id, OTHER_REQUEST), selected)

        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            server.stateOf(first.id, OTHER_REQUEST),
        )
        // The other connection's request is untouched, and nothing went to its server.
        assertEquals(RequestState.REQUEST_STATE_PENDING, other.stateOf(second.id, OTHER_REQUEST))
        assertNull(viewModel.state.value.inbox.result(RequestKey(second.id, OTHER_REQUEST)))
        assertEquals(listOf(URL, URL), gateway.submits.map { it.first })
        assertEquals(
            listOf(first.id),
            gateway.submits.map { it.second.ref.connectionId }.distinct(),
        )
        // And the wallet signed the first connection's message, not the other's.
        assertEquals(ByteString.copyFromUtf8("For the first"), adapter.signings.single().first)
    }

    @Test
    fun rejectsAMessageWithoutTouchingTheWallet() {
        val (key, _) = readyToSign()
        val viewModel = viewModel()

        viewModel.answer(key, Answer.Reject)

        assertEquals(listOf(SubmitResultRequest.ResultCase.REJECTION), submitted())
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(
            RequestState.REQUEST_STATE_REJECTED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    /** A PENDING transfer, and the real transaction bytes the sidecar would hand over for it. */
    private fun pendingTransfer(
        caseName: String = "sol_transfer"
    ): Pair<RequestKey, org.json.JSONObject> = runBlocking {
        val case = transferCase(caseName)
        val fields = case.getJSONObject("request")
        val connection = repository.pair(server.issue(URL))
        adapter.answerConnected(fields.getString("wallet"))
        wallet.connect(WalletNetwork.Devnet)
        val request =
            server.addPendingTransfer(
                connection.id,
                fields.getString("wallet"),
                io.github.brrenat.seekervault.request.v1.Network.NETWORK_DEVNET,
                recipient = fields.getString("recipient"),
                amount = fields.getString("amount"),
            )
        repository.refresh(connection.id)
        val key = RequestKey(connection.id, request.ref.requestId)
        gateway.transactions[key] =
            java.util.Base64.getDecoder().decode(case.getString("transaction"))
        key to case
    }

    private fun transferCase(name: String): org.json.JSONObject {
        val cases =
            org.json
                .JSONObject(
                    checkNotNull(javaClass.getResourceAsStream("/transactions/cases.json")).use {
                        it.readBytes().decodeToString()
                    }
                )
                .getJSONArray("cases")
        return (0 until cases.length())
            .map { cases.getJSONObject(it) }
            .first { it.getString("name") == name }
    }

    @Test
    fun openingATransferFetchesItsTransactionAndReadsItHere() {
        val (key, _) = pendingTransfer()
        val viewModel = viewModel()

        viewModel.prepare(key)

        val preparation = viewModel.state.value.preparations[key]
        assertTrue("$preparation", preparation is Preparation.Ready)
        val ready = preparation as Preparation.Ready
        assertEquals(Verdict.Verified, ready.inspection.verdict)
        // The amount came out of the bytes, not out of the request it was checked against.
        assertEquals(2_500_000_000UL, ready.inspection.facts?.amount)
        // Reading a transaction answers nothing: no result was ever sent.
        assertEquals(emptyList<Any>(), gateway.submits)
        assertNull(viewModel.state.value.inbox.result(key))
    }

    @Test
    fun readingItAgainAsksForANewVersion() {
        val (key, _) = pendingTransfer()
        val viewModel = viewModel()

        viewModel.prepare(key)
        // Opening it again changes nothing; only asking for it does.
        viewModel.prepare(key)
        assertEquals(1, gateway.preparations[key])

        viewModel.prepare(key, force = true)
        assertEquals(2, gateway.preparations[key])
    }

    @Test
    fun refusesATransactionThatPaysSomebodyElse() {
        val (key, case) = pendingTransfer("changed_recipient")
        val viewModel = viewModel()

        viewModel.prepare(key)

        val ready = viewModel.state.value.preparations[key] as Preparation.Ready
        assertEquals(Verdict.Invalid, ready.inspection.verdict)
        assertTrue(Finding.RecipientMismatch in ready.inspection.findings)
        assertFalse(ready.inspection.approvable)
        assertEquals("changed_recipient", case.getString("name"))
    }

    @Test
    fun saysSoWhenTheTransactionCouldNotBeFetched() {
        val (key, _) = pendingTransfer()
        server.failure = GatewayException.Kind.Unreachable
        val viewModel = viewModel()

        viewModel.prepare(key)

        assertTrue(viewModel.state.value.preparations[key] is Preparation.Failed)
        // The request is untouched, so asking again once the sidecar answers still works.
        server.failure = null
        viewModel.prepare(key, force = true)
        assertTrue(viewModel.state.value.preparations[key] is Preparation.Ready)
    }

    @Test
    fun preparesNothingForARequestWithNoTransactionToBuild() {
        val request = pendingRequest()
        val viewModel = viewModel()

        viewModel.prepare(request)

        assertNull(viewModel.state.value.preparations[request])
        assertTrue(gateway.preparations.isEmpty())
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val URL = "https://vault.example.com"
        const val OTHER_URL = "https://other.example.com"
        const val OTHER_REQUEST = "de03846e-d435-4705-b2e3-ec67da539f12"
    }
}
