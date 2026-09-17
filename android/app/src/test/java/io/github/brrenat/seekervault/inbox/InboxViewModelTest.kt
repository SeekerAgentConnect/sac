package io.github.brrenat.seekervault.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.explorerUrl
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.MAX_DETAIL_BYTES
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.plugins.TestPlugin
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.ConnectionAssetLimits
import io.github.brrenat.seekervault.policy.ConnectionPolicy
import io.github.brrenat.seekervault.policy.ConnectionPolicyOverrides
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAssessment
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.PolicyReason
import io.github.brrenat.seekervault.policy.RuleOverride
import io.github.brrenat.seekervault.policy.RuleSource
import io.github.brrenat.seekervault.policy.record as policyRecord
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.directManifest
import io.github.brrenat.seekervault.transactions.Finding
import io.github.brrenat.seekervault.transactions.Verdict
import io.github.brrenat.seekervault.wallet.FakeWalletAdapter
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.SignResult
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletRepository
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    private val history by lazy { ActivityLog(ActivityStore(File(folder.root, "activity"))) }
    private val policies by lazy { PolicyStore(File(folder.root, "policies")) }
    private val evaluator by lazy {
        PolicyEvaluator(policies, records = { history.records.value })
    }
    private val repository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = CredentialVault(File(folder.root, "credentials")) { key },
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            history = history,
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

    private fun viewModel() =
        InboxViewModel(repository, wallet, evaluator, history, io = Dispatchers.Unconfined)

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
    fun notificationOpenFetchesCurrentStateWithoutAnsweringOrOpeningAWallet() {
        val request = pendingRequest()
        val viewModel = viewModel()

        viewModel.openFromNotification(request)

        assertEquals(
            NotificationOpen(request, NotificationOpenStatus.Current),
            viewModel.state.value.notificationOpen,
        )
        assertTrue(gateway.submits.isEmpty())
        assertTrue(adapter.signings.isEmpty())
        assertTrue(adapter.sendings.isEmpty())
    }

    @Test
    fun staleNotificationStatesAreReportedOnlyAfterTheSidecarIsChecked() {
        for (state in
            listOf(
                RequestState.REQUEST_STATE_CANCELLED,
                RequestState.REQUEST_STATE_EXPIRED,
                RequestState.REQUEST_STATE_COMPLETED,
            )) {
            val key = pendingRequest()
            val pending = server.pending.getValue(key.connectionId)
            val request = pending.single { it.ref.requestId == key.requestId }
            pending.remove(request)
            server.settled.getOrPut(key.connectionId) { mutableMapOf() }[key.requestId] =
                request.toBuilder().setState(state).build()
            val viewModel = viewModel()

            viewModel.openFromNotification(key)

            assertEquals(
                state.name,
                NotificationOpen(key, NotificationOpenStatus.Gone),
                viewModel.state.value.notificationOpen,
            )
        }
        assertTrue(gateway.submits.isEmpty())
        assertTrue(adapter.signings.isEmpty())
        assertTrue(adapter.sendings.isEmpty())
    }

    @Test
    fun answeredHereStillOpensItsStoredOutcomeAndUnavailableRevokedOrRemovedConnectionsSaySo() {
        val answered = pendingRequest()
        runBlocking { repository.answer(answered, Answer.Acknowledge) }
        val viewModel = viewModel()
        viewModel.openFromNotification(answered)
        assertEquals(
            NotificationOpen(answered, NotificationOpenStatus.Current),
            viewModel.state.value.notificationOpen,
        )
        assertNotNull(viewModel.state.value.inbox.result(answered))

        val unreachable = pendingRequest(other, OTHER_URL)
        other.failure = GatewayException.Kind.Unreachable
        viewModel.openFromNotification(unreachable)
        assertEquals(
            NotificationOpen(unreachable, NotificationOpenStatus.Unavailable),
            viewModel.state.value.notificationOpen,
        )

        server.failure = GatewayException.Kind.Unauthenticated
        viewModel.openFromNotification(answered)
        assertEquals(
            NotificationOpen(answered, NotificationOpenStatus.Revoked),
            viewModel.state.value.notificationOpen,
        )
        server.failure = null

        runBlocking { repository.remove(answered.connectionId) }
        viewModel.openFromNotification(answered)
        assertEquals(
            NotificationOpen(answered, NotificationOpenStatus.Removed),
            viewModel.state.value.notificationOpen,
        )
        assertTrue(adapter.signings.isEmpty())
        assertTrue(adapter.sendings.isEmpty())
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
    fun opensNoWalletForAnApprovalTheServerHasNotTaken() {
        val (key, selected) = readyToSign()
        // The server can't be reached, so the approval is stored here and taken by nobody. A
        // request the sidecar may have cancelled or let expire meanwhile must not reach the wallet:
        // it is asked only once the approval has moved the request to PROCESSING there.
        server.failure = GatewayException.Kind.Unreachable
        val viewModel = viewModel()

        viewModel.approve(key, selected)

        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(SigningProblem.NotSentYet, viewModel.state.value.problem)
        assertEquals(key, viewModel.state.value.problemKey)
        // The owner's approval is not lost: it is stored, unaccepted, and sent again by itself.
        val result = checkNotNull(viewModel.state.value.inbox.result(key))
        assertEquals(Delivery.Waiting, result.delivery)
        assertFalse(result.approved)
        assertNull(result.signing)
    }

    @Test
    fun anApprovalTheServerNeverTookEndsAsAFailureWithNoWalletEverOpened() {
        val (key, selected) = readyToSign()
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        server.failure = GatewayException.Kind.Unreachable
        val viewModel = viewModel()
        viewModel.approve(key, selected)
        assertEquals(emptyList<Any>(), adapter.signings)

        // The server answers again: the approval this phone kept reaches it, and the approval with
        // no wallet answer settles as unresolved. The agent is told the request failed rather than
        // being left with a PROCESSING request nothing can settle, and no wallet was ever opened.
        server.failure = null
        val reopened = viewModel()
        reopened.onAppVisible()

        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.EXECUTION_FAILURE,
            ),
            submitted(),
        )
        assertEquals(
            RequestState.REQUEST_STATE_FAILED,
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
    fun cutsAWalletMessageTheSidecarWouldRefuse() {
        val (key, selected) = readyToSign()
        // The wallet's text is the wallet's own, of whatever length it likes. A detail over the
        // protocol's 1024 bytes would be refused, and this phone never replaces a stored outcome,
        // so the request would stay PROCESSING with nothing able to settle it.
        adapter.answerSigning(SignResult.Failed("é".repeat(2000)))
        val viewModel = viewModel()

        viewModel.approve(key, selected)

        val outcome = viewModel.state.value.inbox.result(key)?.signing
        val detail = (outcome as SigningOutcome.Failed).detail
        assertTrue("$detail", detail.startsWith("The wallet could not sign: é"))
        assertTrue("${detail.length}", detail.toByteArray(Charsets.UTF_8).size <= MAX_DETAIL_BYTES)
        // Cut between characters, so what is stored is still the text the wallet sent.
        assertFalse(detail.endsWith("\uFFFD"))
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.EXECUTION_FAILURE,
            ),
            submitted(),
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
        val (key, case) = pendingTransfer()
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

    // --- Approving a transfer through the wallet (SAW-021) ---

    /** A transfer read and inspected, ready for the owner to decide on. */
    private fun reviewedTransfer(
        caseName: String = "sol_transfer"
    ): Triple<RequestKey, InboxViewModel, Preparation.Ready> {
        val (key, _) = pendingTransfer(caseName)
        val viewModel = viewModel()
        viewModel.prepare(key)
        return Triple(key, viewModel, viewModel.state.value.preparations[key] as Preparation.Ready)
    }

    /**
     * The gap the sidecar's own freshness check cannot close: it checks when it accepts the
     * approval, and the phone may then wait on the one wallet lock for as long as the owner is in
     * the wallet app with something else.
     */
    @Test
    fun aWindowThatClosesWhileTheWalletIsBusyNeverReachesTheWallet() {
        var clock = Instant.parse("2026-09-12T12:00:00Z")
        gateway.preparedExpiry = clock.plusSeconds(60)
        val (key, _) = pendingTransfer()
        val viewModel =
            InboxViewModel(
                repository,
                wallet,
                evaluator,
                history,
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        viewModel.prepare(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        assertEquals(1, gateway.preparations[key])

        // Another wallet interaction holds the one wallet lock.
        val release = CompletableDeferred<Unit>()
        val holder = CoroutineScope(Dispatchers.Unconfined)
        val busy = holder.launch { wallet.withWallet { release.await() } }

        viewModel.approveTransfer(key, reviewed)
        // While the approval waits for the lock, the blockhash window closes.
        clock = clock.plusSeconds(61)
        release.complete(Unit)
        runBlocking { busy.join() }

        assertTrue("no wallet was opened", adapter.sendings.isEmpty())
        assertEquals("and nothing was approved anywhere", emptyList<Any>(), gateway.submits)
        assertNull(viewModel.state.value.inbox.result(key))
        assertEquals(SigningProblem.Stale, viewModel.state.value.problem)
        assertEquals(key, viewModel.state.value.problemKey)
        // The owner is given a fresh preparation to review instead.
        assertEquals(2, gateway.preparations[key])
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun aWindowThatClosesWhileTheApprovalIsCommittedNeverReachesTheWalletEither() {
        var clock = Instant.parse("2026-09-12T12:00:00Z")
        gateway.preparedExpiry = clock.plusSeconds(60)
        val (key, _) = pendingTransfer()
        val viewModel =
            InboxViewModel(
                repository,
                wallet,
                evaluator,
                history,
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        viewModel.prepare(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        // The sidecar takes the approval, and the round trip outlasts the window.
        gateway.beforeSubmit = { clock = clock.plusSeconds(61) }

        viewModel.approveTransfer(key, reviewed)

        assertTrue("stale bytes never reach the wallet", adapter.sendings.isEmpty())
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.EXECUTION_FAILURE,
            ),
            submitted(),
        )
        val signing = viewModel.state.value.inbox.result(key)?.signing
        assertTrue("$signing", signing is SigningOutcome.Failed)
        assertEquals(
            RequestState.REQUEST_STATE_FAILED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun approvingHandsTheWalletExactlyTheBytesThatWereReviewed() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 7 }))

        viewModel.approveTransfer(key, reviewed)

        // The approval goes first and names the version and hash of what was on screen.
        val approval = gateway.submits.first().second
        assertTrue(approval.hasApproval())
        assertEquals(reviewed.prepared.version, approval.approval.preparedVersion)
        assertEquals(reviewed.prepared.contentHash, approval.approval.contentHash)
        // Then the wallet, with those bytes and no others.
        assertEquals(reviewed.prepared.transaction, adapter.sendings.single().first)
        // And what the wallet did is reported as a submission, not as a message signature.
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.TRANSACTION_SUBMISSION,
            ),
            submitted(),
        )
        assertEquals(
            RequestState.REQUEST_STATE_SUBMITTED,
            server.stateOf(key.connectionId, key.requestId),
        )
        assertEquals(
            SigningOutcome.Sent(ByteString.copyFrom(ByteArray(64) { 7 })),
            viewModel.state.value.inbox.result(key)?.signing,
        )
    }

    @Test
    fun theWalletGetsTheApprovedBytesEvenWhenTheServerHasBuiltANewerVersionSince() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 3 }))
        // The sidecar builds something else the moment the approval arrives. The owner approved
        // the transaction they read, and that is the one the wallet is handed.
        gateway.beforeSubmit = {
            gateway.transactions[key] = "a different transaction".toByteArray()
        }

        viewModel.approveTransfer(key, reviewed)

        assertEquals(reviewed.prepared.transaction, adapter.sendings.single().first)
    }

    @Test
    fun aSecondTapNeverOpensTheWalletTwice() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        val release = CompletableDeferred<Unit>()
        adapter.beforeSending = { release.await() }
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 5 }))

        viewModel.approveTransfer(key, reviewed)
        assertEquals(setOf(key), viewModel.state.value.sending)
        viewModel.approveTransfer(key, reviewed)
        viewModel.approveTransfer(key, reviewed)
        release.complete(Unit)

        assertEquals(1, adapter.sendings.size)
        assertEquals(1, gateway.submits.count { it.second.hasApproval() })
        // And once it is answered, tapping again does nothing at all.
        viewModel.approveTransfer(key, reviewed)
        assertEquals(1, adapter.sendings.size)
    }

    @Test
    fun aStalePreparationIsRefusedAndReadAgainRatherThanApproved() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        // The sidecar prepares a new version, as it does whenever anyone asks: the owner's
        // approval of the old one names a version that is no longer the latest.
        runBlocking { repository.prepare(key) }

        viewModel.approveTransfer(key, reviewed)

        // Nothing reached the wallet, and nothing is stored as an approval.
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertNull(viewModel.state.value.inbox.result(key))
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(key.connectionId, key.requestId),
        )
        assertEquals(SigningProblem.Stale, viewModel.state.value.problem)
        // And it has been read again, so the owner reviews the version that exists now.
        val now = viewModel.state.value.preparations[key] as Preparation.Ready
        assertEquals(3, now.prepared.version)
    }

    @Test
    fun aVersionReadAgainWhileTheOwnerWasLookingIsNotTheOneTheyApprove() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        viewModel.prepare(key, force = true)

        viewModel.approveTransfer(key, reviewed)

        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(SigningProblem.NotVerified, viewModel.state.value.problem)
    }

    @Test
    fun aTransactionThisPhoneCouldNotAccountForNeverReachesTheWallet() {
        val (key, viewModel, reviewed) = reviewedTransfer("changed_amount")
        assertFalse(reviewed.inspection.approvable)

        viewModel.approveTransfer(key, reviewed)

        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(SigningProblem.NotVerified, viewModel.state.value.problem)
    }

    @Test
    fun aWalletOtherThanTheOneOnScreenStopsTheApproval() {
        val (key, viewModel, held) = reviewedTransfer()
        // The screen showed another wallet than the phone holds now. The transaction is the same
        // one, and that is not enough: they approve a wallet as much as a transaction.
        val reviewed =
            held.copy(
                wallet =
                    SelectedWallet(
                        address = OTHER_WALLET,
                        network = WalletNetwork.Devnet,
                        selectedAt = java.time.Instant.EPOCH,
                    )
            )

        viewModel.approveTransfer(key, reviewed)

        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(SigningProblem.Changed, viewModel.state.value.problem)
    }

    @Test
    fun connectingAnotherWalletTakesTheTransferOffThisPhoneEntirely() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        adapter.answerConnected(OTHER_WALLET)
        runBlocking { wallet.connect(WalletNetwork.Devnet) }

        // The sidecar cancelled it when the new binding was published, so there is nothing left to
        // approve, and the approval of what they reviewed goes nowhere.
        viewModel.approveTransfer(key, reviewed)

        assertNull(viewModel.state.value.inbox.pendingRequest(key))
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(
            RequestState.REQUEST_STATE_CANCELLED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun anApprovalTheServerRefusedOpensNoWalletAndIsNotKept() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        server.failure = GatewayException.Kind.CertificateRejected

        viewModel.approveTransfer(key, reviewed)

        assertEquals(emptyList<Any>(), adapter.sendings)
        // The call never left this phone, so nothing was approved: the transfer is the owner's to
        // review and approve again.
        assertNull(viewModel.state.value.inbox.result(key))
        assertEquals(SigningProblem.NotApproved, viewModel.state.value.problem)
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun anApprovalWithNoAnswerIsKeptAndDroppedOnlyOnceTheServerSaysItNeverArrived() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        server.failure = GatewayException.Kind.Unreachable

        viewModel.approveTransfer(key, reviewed)

        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(SigningProblem.NotApproved, viewModel.state.value.problem)
        // The approval is kept: a dropped connection is not an answer, and the server may have
        // taken it. Only the server can say.
        assertNotNull(viewModel.state.value.inbox.result(key))

        server.failure = null
        viewModel.sendAgain(key)

        // It never arrived, so the request is the server's again and the owner reviews afresh.
        assertNull(viewModel.state.value.inbox.result(key))
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun anApprovalTheServerTookButNeverConfirmedEndsTheTransferInsteadOfStrandingIt() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        // The server commits the approval and the response is lost on the way back.
        server.loseNextResponse = true

        viewModel.approveTransfer(key, reviewed)

        // As far as this phone knows nothing was approved, so no wallet was opened.
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(
            RequestState.REQUEST_STATE_PROCESSING,
            server.stateOf(key.connectionId, key.requestId),
        )

        viewModel.sendAgain(key)

        // The approval is never sent a second time; the request is read, and the server is told
        // what happened, so it ends instead of waiting for a wallet that was never asked.
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.EXECUTION_FAILURE,
            ),
            submitted(),
        )
        assertEquals(
            RequestState.REQUEST_STATE_FAILED,
            server.stateOf(key.connectionId, key.requestId),
        )
        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(key)?.delivery)
    }

    @Test
    fun aLostWalletCallbackIsUnknownAndTheWalletIsNeverAskedAgain() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        // The app goes away while the transaction is with the wallet: the answer never arrives.
        val never = CompletableDeferred<Unit>()
        adapter.beforeSending = { never.await() }
        viewModel.approveTransfer(key, reviewed)
        assertEquals(1, adapter.sendings.size)

        // Coming back to the foreground with nothing in flight here settles it.
        val next = viewModel()
        next.onAppVisible()

        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.UNKNOWN_OUTCOME,
            ),
            submitted(),
        )
        assertEquals(
            RequestState.REQUEST_STATE_UNKNOWN,
            server.stateOf(key.connectionId, key.requestId),
        )
        // No second wallet call, then or ever: an unknown outcome is not a retry.
        assertEquals(1, adapter.sendings.size)
        next.sendAgain(key)
        assertEquals(1, adapter.sendings.size)
    }

    @Test
    fun aWalletThatCannotSayWhetherItSentLeavesTheOutcomeUnknown() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        adapter.answerSending(SendResult.Unknown("the session ended"))

        viewModel.approveTransfer(key, reviewed)

        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.UNKNOWN_OUTCOME,
            ),
            submitted(),
        )
        assertEquals(
            RequestState.REQUEST_STATE_UNKNOWN,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun decliningInTheWalletRejectsTheTransferAndSendsNothing() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        adapter.answerSending(SendResult.Declined)

        viewModel.approveTransfer(key, reviewed)

        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.REJECTION,
            ),
            submitted(),
        )
        assertEquals(
            RequestState.REQUEST_STATE_REJECTED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun rejectingATransferInTheAppNeverTouchesTheWallet() {
        val (key, viewModel, _) = reviewedTransfer()

        viewModel.answer(key, Answer.Reject)

        assertEquals(listOf(SubmitResultRequest.ResultCase.REJECTION), submitted())
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(
            RequestState.REQUEST_STATE_REJECTED,
            server.stateOf(key.connectionId, key.requestId),
        )
    }

    @Test
    fun theTransactionsIdIsSentAgainAfterALostResponseWithoutAskingTheWalletTwice() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 8 }))
        adapter.beforeSending = { server.loseNextResponse = true }

        viewModel.approveTransfer(key, reviewed)
        assertEquals(Delivery.Waiting, viewModel.state.value.inbox.result(key)?.delivery)

        viewModel.sendAgain(key)

        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(key)?.delivery)
        assertEquals(
            RequestState.REQUEST_STATE_SUBMITTED,
            server.stateOf(key.connectionId, key.requestId),
        )
        assertEquals(1, adapter.sendings.size)
    }

    // --- Following a sent transaction to the chain (SAW-022) ---

    /**
     * A transfer the wallet has sent, so the server has it as SUBMITTED and this phone knows it.
     */
    private fun sentTransfer(): Pair<RequestKey, InboxViewModel> {
        val (key, viewModel, reviewed) = reviewedTransfer()
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 9 }))
        viewModel.approveTransfer(key, reviewed)
        assertEquals(
            RequestState.REQUEST_STATE_SUBMITTED,
            server.stateOf(key.connectionId, key.requestId),
        )
        return key to viewModel
    }

    @Test
    fun checkingAConfirmationKeepsWhatTheServerReadAndOpensNoWallet() {
        val (key, viewModel) = sentTransfer()
        val sendings = adapter.sendings.size
        val submits = gateway.submits.size
        server.putOnChain(
            key.connectionId,
            key.requestId,
            FakeConnectionGateway.Server.Confirmed(
                RequestState.REQUEST_STATE_CONFIRMED,
                "The approved transaction succeeded on chain in slot 4242.",
            ),
        )

        viewModel.checkStatus(key)

        val result = checkNotNull(viewModel.state.value.inbox.result(key))
        assertEquals(RequestState.REQUEST_STATE_CONFIRMED, result.request.state)
        assertEquals("rpc.test.invalid", result.request.outcome.confirmation.endpoint)
        // The wallet's own answer stands: a confirmation says what the chain did with it, not
        // what the wallet did.
        assertEquals(SigningOutcome.Sent(ByteString.copyFrom(ByteArray(64) { 9 })), result.signing)
        assertEquals(Delivery.Accepted, result.delivery)
        // And nothing was asked of the wallet, and no second result was sent.
        assertEquals(sendings, adapter.sendings.size)
        assertEquals(submits, gateway.submits.size)
    }

    @Test
    fun theHistoryKeepsTheTransferItsClusterAndItsTransactionId() {
        // The owner's own record of a payment, written on the way through and nowhere else
        // (SAW-023). It carries the cluster, because a signature without one names nothing.
        val (key, viewModel) = sentTransfer()
        val sent = history.records.value.single { it.requestId == key.requestId }
        assertEquals(ActivityKind.Transfer, sent.kind)
        assertEquals(ActivityOutcome.Sent, sent.outcome)
        assertEquals(WalletNetwork.Devnet.network, sent.transfer?.network)
        assertTrue(sent.signatureIsTransaction)
        assertTrue(explorerUrl(sent).orEmpty().endsWith("?cluster=devnet"))

        server.putOnChain(
            key.connectionId,
            key.requestId,
            FakeConnectionGateway.Server.Confirmed(
                RequestState.REQUEST_STATE_CONFIRMED,
                "The approved transaction succeeded on chain in slot 4242.",
            ),
        )
        viewModel.checkStatus(key)
        // Checking twice is one thing that happened, not two.
        viewModel.checkStatus(key)

        val records = history.records.value.filter { it.requestId == key.requestId }
        assertEquals(1, records.size)
        assertEquals(ActivityOutcome.Confirmed, records.single().outcome)
        assertEquals("rpc.test.invalid", records.single().checkedWith)
        assertEquals(sent.signature, records.single().signature)
    }

    @Test
    fun theHistoryNeverCallsASignedMessageAPayment() {
        val (request, selected) = readyToSign()
        adapter.signWith(ByteString.copyFrom(ByteArray(64) { 3 }))
        viewModel().approve(request, selected)

        val record = history.records.value.single { it.requestId == request.requestId }
        assertEquals(ActivityKind.MessageSignature, record.kind)
        assertEquals(ActivityOutcome.MessageSigned, record.outcome)
        assertNotNull(record.signature)
        // There is a signature and there is no payment: no cluster, no transaction, no link.
        assertFalse(record.signatureIsTransaction)
        assertNull(record.transfer)
        assertNull(explorerUrl(record))
    }

    @Test
    fun aTransactionThatFailedOnChainIsKeptAsAFailureWithItsReason() {
        val (key, viewModel) = sentTransfer()
        server.putOnChain(
            key.connectionId,
            key.requestId,
            FakeConnectionGateway.Server.Confirmed(
                RequestState.REQUEST_STATE_FAILED,
                "The transaction ran on chain and failed: insufficient funds.",
            ),
        )

        viewModel.checkStatus(key)

        val result = checkNotNull(viewModel.state.value.inbox.result(key))
        assertEquals(RequestState.REQUEST_STATE_FAILED, result.request.state)
        assertTrue(result.request.outcome.detail.contains("insufficient funds"))
        // A failure on chain is not an excuse to try again: nothing here sends a replacement.
        assertEquals(1, adapter.sendings.size)
    }

    @Test
    fun aTransferTheChainCannotSettleStaysExactlyWhereItWas() {
        val (key, viewModel) = sentTransfer()

        viewModel.checkStatus(key)

        val result = checkNotNull(viewModel.state.value.inbox.result(key))
        assertEquals(RequestState.REQUEST_STATE_SUBMITTED, result.request.state)
        assertEquals(true, result.awaitingChain)
        assertEquals(1, adapter.sendings.size)
    }

    @Test
    fun aSecondTapWhileAChecksIsRunningAsksOnlyOnce() {
        val (key, viewModel) = sentTransfer()
        val release = CompletableDeferred<Unit>()
        gateway.beforeSubmit = {}
        gateway.beforeCheck = { release.await() }

        viewModel.checkStatus(key)
        assertEquals(setOf(key), viewModel.state.value.checking)
        viewModel.checkStatus(key)
        viewModel.checkStatus(key)
        release.complete(Unit)

        assertEquals(1, server.checks)
        assertEquals(emptySet<RequestKey>(), viewModel.state.value.checking)
    }

    @Test
    fun aServerThatCannotBeReachedChangesNothingAboutTheTransaction() {
        val (key, viewModel) = sentTransfer()
        val before = checkNotNull(viewModel.state.value.inbox.result(key))
        server.failure = GatewayException.Kind.Unreachable

        viewModel.checkStatus(key)

        assertEquals(SigningProblem.NotChecked, viewModel.state.value.problem)
        val after = checkNotNull(viewModel.state.value.inbox.result(key))
        assertEquals(before.request.state, after.request.state)
        assertEquals(before.signing, after.signing)
        assertEquals(before.delivery, after.delivery)
        assertEquals(1, adapter.sendings.size)
    }

    @Test
    fun aSettledTransferIsNotCheckedAgain() {
        val (key, viewModel) = sentTransfer()
        server.putOnChain(
            key.connectionId,
            key.requestId,
            FakeConnectionGateway.Server.Confirmed(
                RequestState.REQUEST_STATE_CONFIRMED,
                "It went through.",
            ),
        )
        viewModel.checkStatus(key)
        val asked = server.checks

        viewModel.checkStatus(key)

        assertEquals(asked, server.checks)
        assertEquals(
            false,
            checkNotNull(viewModel.state.value.inbox.result(key)).awaitingChain,
        )
    }

    @Test
    fun aTransferTheWalletNeverAnsweredIsNeverSentAgainToSettleIt() {
        val (key, viewModel, reviewed) = reviewedTransfer()
        // The wallet never answers, so the phone reports an unknown outcome (SAW-021).
        adapter.answerSending(SendResult.Unknown("the wallet never came back"))
        viewModel.approveTransfer(key, reviewed)
        assertEquals(
            RequestState.REQUEST_STATE_UNKNOWN,
            server.stateOf(key.connectionId, key.requestId),
        )
        val sendings = adapter.sendings.size

        viewModel.checkStatus(key)

        // The server looked, found nothing to look up, and said so. Nothing went to the wallet.
        assertEquals(sendings, adapter.sendings.size)
        assertEquals(
            RequestState.REQUEST_STATE_UNKNOWN,
            checkNotNull(viewModel.state.value.inbox.result(key)).request.state,
        )
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.UNKNOWN_OUTCOME,
            ),
            submitted(),
        )
    }

    // --- What the rules make of a request, on the screen where it is answered (SAW-028) ---

    /** Rules for [connectionId], written the way the editor writes them. */
    private fun rules(
        connectionId: String,
        actions: Allowlist<PolicyAction>? = null,
        recipients: Allowlist<String>? = null,
    ) =
        policies.put(
            ConnectionPolicy(
                connectionId = connectionId,
                actions = actions,
                recipients = recipients,
                updatedAt = Instant.parse("2026-09-12T10:00:00Z"),
            )
        )

    private fun globalRules(
        actions: Allowlist<PolicyAction>? = null,
        recipients: Allowlist<String>? = null,
        daily: ULong? = null,
    ) =
        policies.putGlobal(
            GlobalPolicy.default(Instant.parse("2026-09-12T10:00:00Z"))
                .copy(
                    actions = actions,
                    recipients = recipients,
                    limits =
                        daily?.let {
                            mapOf(
                                PolicyAsset.sol(
                                    io.github.brrenat.seekervault.request.v1.Network.NETWORK_DEVNET
                                ) to AssetLimits(daily = it)
                            )
                        } ?: emptyMap(),
                )
        )

    @Test
    fun aConnectionWithoutOverridesIsAssessedAgainstGlobalRules() {
        val (key, _) = pendingTransfer()
        globalRules(actions = Allowlist.of(PolicyAction.Transfer))
        val viewModel = viewModel()
        viewModel.prepare(key)

        val decision = checkNotNull(viewModel.state.value.assessments[key]).decision

        assertEquals(PolicyAssessment.Allowed, decision.assessment)
        assertEquals(RuleSource.Global, decision.checks.first().source)
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun aGlobalEditWithTheSameRenderedWarningStopsAfterTheWalletLockWait() {
        val (key, _) = pendingTransfer()
        globalRules(recipients = Allowlist.of(OTHER_WALLET))
        val viewModel = viewModel()
        viewModel.prepare(key)
        viewModel.acknowledge(key, true)
        val shown = checkNotNull(viewModel.state.value.assessments[key]).decision
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready

        // Another wallet interaction holds the lock after the owner taps. Editing the effective
        // allowlist still matters even though this recipient remains outside it and the words shown
        // by every check remain identical.
        val release = CompletableDeferred<Unit>()
        val holder = CoroutineScope(Dispatchers.Unconfined)
        val busy = holder.launch { wallet.withWallet { release.await() } }
        viewModel.approveTransfer(key, reviewed)
        globalRules(recipients = Allowlist.of(OTHER_WALLET, THIRD_WALLET))
        release.complete(Unit)
        runBlocking { busy.join() }

        assertEquals(shown, checkNotNull(viewModel.state.value.assessments[key]).decision)
        assertEquals(SigningProblem.RulesChanged, viewModel.state.value.problem)
        assertNull(viewModel.state.value.acknowledged[key])
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun resettingLocalOverridesToGlobalStopsAStaleApproval() {
        val (key, case) = pendingTransfer()
        val recipient = case.getJSONObject("request").getString("recipient")
        globalRules(recipients = Allowlist.of(OTHER_WALLET))
        policies.putOverrides(
            ConnectionPolicyOverrides.inheritAll(
                    key.connectionId,
                    Instant.parse("2026-09-12T10:00:00Z"),
                )
                .copy(recipients = RuleOverride.Replace(Allowlist.of(recipient)))
        )
        val viewModel = viewModel()
        viewModel.prepare(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        assertEquals(
            PolicyAssessment.Allowed,
            viewModel.state.value.assessments[key]?.decision?.assessment,
        )

        policies.delete(key.connectionId)
        viewModel.approveTransfer(key, reviewed)

        assertEquals(SigningProblem.RulesChanged, viewModel.state.value.problem)
        assertTrue(checkNotNull(viewModel.state.value.assessments[key]).decision.warns)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun unresolvedExposureAddedOnAnotherConnectionStopsAStaleApproval() {
        val (key, _) = pendingTransfer()
        globalRules(
            actions = Allowlist.of(PolicyAction.Transfer),
            daily = 4_000_000_000UL,
        )
        val viewModel = viewModel()
        viewModel.prepare(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        assertEquals(
            PolicyAssessment.Allowed,
            viewModel.state.value.assessments[key]?.decision?.assessment,
        )

        ActivityStore(File(folder.root, "activity"))
            .put(
                policyRecord(
                    requestId = OTHER_ACTIVITY_REQUEST,
                    connectionId = OTHER_ACTIVITY_CONNECTION,
                    wallet = checkNotNull(reviewed.inspection.facts).payer,
                    network = io.github.brrenat.seekervault.request.v1.Network.NETWORK_DEVNET,
                    amount = "2000000000",
                    outcome = ActivityOutcome.Unknown,
                    answeredAt = Instant.now(),
                )
            )
        viewModel.approveTransfer(key, reviewed)

        assertEquals(SigningProblem.RulesChanged, viewModel.state.value.problem)
        val daily =
            checkNotNull(viewModel.state.value.assessments[key]).decision.dailyChecks.first()
        assertEquals(2_000_000_000UL, daily.total?.unresolved)
        assertEquals(
            PolicyAssessment.UnderRestrictions,
            viewModel.state.value.assessments[key]?.decision?.assessment,
        )
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun anotherConnectionsConfirmationAndFailureEachClearConsentOnForegroundRefresh() {
        val (key, _) = pendingTransfer()
        globalRules(
            recipients = Allowlist.of(OTHER_WALLET),
            daily = 10_000_000_000UL,
        )
        val store = ActivityStore(File(folder.root, "activity"))
        val viewModel = viewModel()
        viewModel.prepare(key)
        val payer =
            checkNotNull(
                    (viewModel.state.value.preparations[key] as Preparation.Ready).inspection.facts
                )
                .payer
        val unresolved =
            policyRecord(
                requestId = OTHER_ACTIVITY_REQUEST,
                connectionId = OTHER_ACTIVITY_CONNECTION,
                wallet = payer,
                network = io.github.brrenat.seekervault.request.v1.Network.NETWORK_DEVNET,
                amount = "1000000000",
                outcome = ActivityOutcome.Unknown,
                answeredAt = Instant.now(),
            )
        store.put(unresolved)
        viewModel.review(key)
        viewModel.acknowledge(key, true)
        assertNotNull(viewModel.state.value.acknowledged[key])

        store.put(unresolved.copy(outcome = ActivityOutcome.Confirmed))
        viewModel.onAppVisible()
        assertNull(
            "confirmation changes the shown daily composition",
            viewModel.state.value.acknowledged[key],
        )
        assertEquals(
            1_000_000_000UL,
            viewModel.state.value.assessments[key]
                ?.decision
                ?.dailyChecks
                ?.first()
                ?.total
                ?.confirmed,
        )

        store.put(unresolved)
        viewModel.review(key)
        viewModel.acknowledge(key, true)
        assertNotNull(viewModel.state.value.acknowledged[key])
        store.put(unresolved.copy(outcome = ActivityOutcome.ChainFailed))
        viewModel.onAppVisible()

        assertNull(
            "a chain failure removes unresolved exposure",
            viewModel.state.value.acknowledged[key],
        )
        assertEquals(
            0UL,
            viewModel.state.value.assessments[key]
                ?.decision
                ?.dailyChecks
                ?.first()
                ?.total
                ?.projected,
        )
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun bothDailyWarningsUseOneDeliberateOverrideAndKeepTheirScopesInActivity() {
        val (key, _) = pendingTransfer()
        val asset = PolicyAsset.sol(io.github.brrenat.seekervault.request.v1.Network.NETWORK_DEVNET)
        globalRules(daily = 2_000_000_000UL)
        policies.putOverrides(
            ConnectionPolicyOverrides.inheritAll(
                    key.connectionId,
                    Instant.parse("2026-09-12T10:00:00Z"),
                )
                .copy(limits = mapOf(asset to ConnectionAssetLimits(daily = 2_000_000_000UL)))
        )
        val viewModel = viewModel()
        viewModel.prepare(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready

        viewModel.approveTransfer(key, reviewed)
        assertEquals(SigningProblem.NotAcknowledged, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)

        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 6 }))
        viewModel.acknowledge(key, true)
        viewModel.approveTransfer(key, reviewed)

        val snapshot = checkNotNull(history.records.value.single().policy)
        assertEquals(listOf("global", "connection"), snapshot.dailyChecks.map { it.scope })
        assertEquals(listOf("global", "connection"), snapshot.dailyChecks.map { it.source })
        assertEquals(listOf("failed", "failed"), snapshot.dailyChecks.map { it.status })
        assertTrue(snapshot.approvedAnyway)
        assertEquals(1, adapter.sendings.size)
    }

    @Test
    fun aRequestThatMatchesTheRulesIsAllowedAndWarnsAboutNothing() {
        val (key, case) = pendingTransfer()
        rules(
            key.connectionId,
            recipients = Allowlist.of(case.getJSONObject("request").getString("recipient")),
        )
        val viewModel = viewModel()
        viewModel.prepare(key)

        viewModel.review(key)

        val decision = checkNotNull(viewModel.state.value.assessments[key]).decision
        assertEquals(PolicyAssessment.Allowed, decision.assessment)
        assertFalse(decision.warns)
        // Allowed is not approved: nothing was answered and no wallet was opened by looking.
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun aConnectionWithNoRulesIsNotSomethingToWarnAbout() {
        val (key, _) = pendingTransfer()
        val viewModel = viewModel()
        viewModel.prepare(key)

        viewModel.review(key)

        val decision = checkNotNull(viewModel.state.value.assessments[key]).decision
        assertEquals(PolicyAssessment.UnderRestrictions, decision.assessment)
        assertEquals(PolicyReason.NoPolicyConfigured, decision.reason)
        assertFalse(decision.warns)
    }

    @Test
    fun nothingReachesTheWalletByOpeningRefreshingOrAssessingARequest() {
        val (key, case) = pendingTransfer()
        rules(
            key.connectionId,
            recipients = Allowlist.of(case.getJSONObject("request").getString("recipient")),
        )
        // Connecting the wallet in the setup is the only thing that has reached it so far.
        val connects = adapter.connects.size
        val viewModel = viewModel()

        viewModel.prepare(key)
        viewModel.review(key)
        viewModel.refresh(key.connectionId)
        viewModel.prepare(key, force = true)
        viewModel.onAppVisible()

        assertEquals(
            PolicyAssessment.Allowed,
            viewModel.state.value.assessments[key]?.decision?.assessment,
        )
        assertEquals(connects, adapter.connects.size)
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(emptyList<Any>(), adapter.disconnects)
    }

    @Test
    fun aTransferOutsideTheRulesIsApprovedOnlyOnceTheOwnerSaysSo() {
        val (key, _) = pendingTransfer()
        rules(key.connectionId, recipients = Allowlist.of(OTHER_WALLET))
        val viewModel = viewModel()
        viewModel.prepare(key)
        viewModel.review(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 9 }))

        viewModel.approveTransfer(key, reviewed)

        // Nothing was approved and no wallet was opened: the warning is theirs to overrule.
        assertEquals(SigningProblem.NotAcknowledged, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)

        viewModel.acknowledge(key, true)
        viewModel.approveTransfer(key, reviewed)

        assertEquals(reviewed.prepared.transaction, adapter.sendings.single().first)
        assertTrue(gateway.submits.first().second.hasApproval())
    }

    @Test
    fun rulesChangedWhileTheRequestWasOnScreenStopTheAnswerRatherThanBeingReadPastIt() {
        val (key, case) = pendingTransfer()
        val recipient = case.getJSONObject("request").getString("recipient")
        rules(key.connectionId, recipients = Allowlist.of(recipient))
        val viewModel = viewModel()
        viewModel.prepare(key)
        viewModel.review(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        assertEquals(
            PolicyAssessment.Allowed,
            viewModel.state.value.assessments[key]?.decision?.assessment,
        )
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 2 }))

        // The owner edits the rules on another screen, and comes back to this one.
        rules(key.connectionId, recipients = Allowlist.of(OTHER_WALLET))
        viewModel.approveTransfer(key, reviewed)

        assertEquals(SigningProblem.RulesChanged, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)
        // And what is on screen is the assessment that stands now, not the one they read.
        val decision = checkNotNull(viewModel.state.value.assessments[key]).decision
        assertEquals(PolicyAssessment.UnderRestrictions, decision.assessment)
        assertTrue(decision.warns)
    }

    @Test
    fun theOwnersWordIsForTheReasonsTheyReadAndNotForTheRequest() {
        val (key, _) = pendingTransfer()
        rules(key.connectionId, recipients = Allowlist.of(OTHER_WALLET))
        val viewModel = viewModel()
        viewModel.prepare(key)
        viewModel.review(key)
        viewModel.acknowledge(key, true)
        assertNotNull(viewModel.state.value.acknowledged[key])

        // Another assessment is another thing to agree to, so the agreement goes.
        rules(key.connectionId, actions = Allowlist.of(PolicyAction.MessageSignature))
        viewModel.review(key)

        assertNull(viewModel.state.value.acknowledged[key])
    }

    @Test
    fun aNewPreparationIsANewAssessmentAndTakesTheOwnersWordWithIt() {
        val (key, _) = pendingTransfer()
        rules(key.connectionId, recipients = Allowlist.of(OTHER_WALLET))
        val viewModel = viewModel()
        viewModel.prepare(key)
        viewModel.acknowledge(key, true)
        assertNotNull(viewModel.state.value.acknowledged[key])

        // A version read again is a different transaction, assessed on its own.
        gateway.transactions[key] =
            java.util.Base64.getDecoder()
                .decode(transferCase("changed_recipient").getString("transaction"))
        viewModel.prepare(key, force = true)

        assertNull(viewModel.state.value.acknowledged[key])
    }

    @Test
    fun aTransactionPreparedAgainTakesTheOwnersWordWithItHoweverTheRulesRead() {
        // The same bytes, prepared again. The version, the hash and the blockhash are the
        // sidecar's to change, and a priority fee it raised is real value leaving the wallet that
        // no threshold counts — so what the rules make of it can be word for word the same and it
        // is still not the transaction the owner said they wanted to go ahead with.
        val (key, _) = pendingTransfer()
        rules(key.connectionId, recipients = Allowlist.of(OTHER_WALLET))
        val viewModel = viewModel()
        viewModel.prepare(key)
        val first = checkNotNull(viewModel.state.value.assessments[key])
        viewModel.acknowledge(key, true)
        assertNotNull(viewModel.state.value.acknowledged[key])

        viewModel.prepare(key, force = true)

        val second = checkNotNull(viewModel.state.value.assessments[key])
        assertEquals("the rules read the same", first.decision, second.decision)
        assertNotEquals("and it is a different preparation", first.facts, second.facts)
        assertNull(viewModel.state.value.acknowledged[key])
    }

    @Test
    fun aTransactionThisPhoneCouldNotAccountForIsRefusedBeforeAnyRuleIsConsulted() {
        // Input validation is not an advisory rule and is never relabelled as one: there is no
        // warning to tick past here, because there is no Approve at all (SAW-020).
        val (key, case) = pendingTransfer("changed_amount")
        rules(
            key.connectionId,
            recipients = Allowlist.of(case.getJSONObject("request").getString("recipient")),
        )
        val viewModel = viewModel()
        viewModel.prepare(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        assertFalse(reviewed.inspection.approvable)
        viewModel.acknowledge(key, true)

        viewModel.approveTransfer(key, reviewed)

        assertEquals(SigningProblem.NotVerified, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun sayingNoNeverNeedsAWordAboutAWarning() {
        val request = pendingRequest()
        rules(request.connectionId, actions = Allowlist.of(PolicyAction.Transfer))
        val viewModel = viewModel()
        viewModel.review(request)
        assertTrue(checkNotNull(viewModel.state.value.assessments[request]).decision.warns)

        viewModel.answer(request, Answer.Reject)

        assertEquals(1, gateway.submits.size)
        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(request)?.delivery)
    }

    @Test
    fun sayingYesToSomethingOutsideTheRulesNeedsTheOwnersWordFirst() {
        val request = pendingRequest()
        rules(request.connectionId, actions = Allowlist.of(PolicyAction.Transfer))
        val viewModel = viewModel()
        viewModel.review(request)

        viewModel.answer(request, Answer.Acknowledge)

        assertEquals(SigningProblem.NotAcknowledged, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), gateway.submits)

        viewModel.acknowledge(request, true)
        viewModel.answer(request, Answer.Acknowledge)

        assertEquals(1, gateway.submits.size)
        assertTrue(gateway.submits.single().second.hasAcknowledgement())
    }

    @Test
    fun theAssessmentTheOwnerReadIsKeptWithTheRecordAndNeverSentAnywhere() {
        val (key, _) = pendingTransfer()
        rules(key.connectionId, recipients = Allowlist.of(OTHER_WALLET))
        val viewModel = viewModel()
        viewModel.prepare(key)
        viewModel.review(key)
        val reviewed = viewModel.state.value.preparations[key] as Preparation.Ready
        adapter.sendWith(ByteString.copyFrom(ByteArray(64) { 4 }))
        viewModel.acknowledge(key, true)

        viewModel.approveTransfer(key, reviewed)

        val stored =
            checkNotNull(history.records.value.single { it.requestId == key.requestId }.policy)
        assertEquals("under_restrictions", stored.assessment)
        assertEquals(listOf("recipient_not_allowed"), stored.reasons)
        assertTrue("action" in stored.notChecked)
        assertTrue(stored.approvedAnyway)
        assertTrue(
            stored.ruleSources.any {
                it.check == "recipient" && it.source == RuleSource.ConnectionOverride.code
            }
        )
        // Later edits cannot rewrite which scope supplied the assessment the owner answered.
        globalRules(recipients = Allowlist.of(OTHER_WALLET))
        policies.delete(key.connectionId)
        assertEquals(
            RuleSource.ConnectionOverride.code,
            history.records.value
                .single { it.requestId == key.requestId }
                .policy
                ?.ruleSources
                ?.single { it.check == "recipient" }
                ?.source,
        )

        // And none of it, nor anything the rules themselves say, went to the sidecar. The rules
        // are the owner\'s own note, and the agent can neither read them nor learn of them.
        val sent = gateway.submits.joinToString("\n") { it.second.toString() }
        PolicyReason.entries.forEach { assertFalse(it.code, it.code in sent) }
        PolicyAssessment.entries.forEach { assertFalse(it.code, it.code in sent) }
        assertFalse(OTHER_WALLET in sent)
    }

    /**
     * A PENDING swap, which a client plugin would carry out (SEE-86). This build bundles none, so
     * the phone establishes nothing about it — which is what these two tests are about.
     */
    private fun pendingSwap(): RequestKey = runBlocking {
        val connection = repository.pair(server.issue(URL))
        adapter.answerConnected(WALLET)
        wallet.connect(WalletNetwork.Devnet)
        val request =
            server.addPendingSwap(
                connection.id,
                WALLET,
                io.github.brrenat.seekervault.request.v1.Network.NETWORK_DEVNET,
            )
        repository.refresh(connection.id)
        RequestKey(connection.id, request.ref.requestId)
    }

    @Test
    fun anOperationNoBundledPluginServesIsNeverAllowedHoweverGenerouslyTheRulesRead() {
        // The rules name the action and the recipient, and this build carries no plugin for the
        // operation. Nothing about the swap was read, so nothing about it can be allowed: a missing
        // plugin is a gap in the review and never a byte that turned out to be fine (SEE-86).
        val key = pendingSwap()
        globalRules(actions = Allowlist.of(PolicyAction.Swap), recipients = Allowlist.of(WALLET))
        val viewModel = viewModel()

        viewModel.review(key)

        val decision = checkNotNull(viewModel.state.value.assessments[key]).decision
        assertEquals(PolicyAssessment.UnderRestrictions, decision.assessment)
        assertTrue("the owner is warned rather than quietly refused", decision.warns)
        assertFalse(checkNotNull(viewModel.state.value.assessments[key]).facts.fullyRead)
        // Reading the rules answers nothing and opens nothing.
        assertEquals(emptyList<Any>(), gateway.submits)
        assertEquals(emptyList<Any>(), adapter.sendings)
        assertEquals(emptyList<Any>(), adapter.signings)
    }

    @Test
    fun aRegisteredPluginIsNeverAskedAboutAnActionTheAppCarriesOutItself() {
        // The app's own actions stay the app's: an acknowledgement, a message and a transfer are
        // read exactly as they were before this stage, and no plugin can change what they mean.
        val plugin = TestPlugin()
        val (transfer, _) = pendingTransfer()
        val message = runBlocking {
            val request = server.addPendingMessage(transfer.connectionId, WALLET)
            repository.refresh(transfer.connectionId)
            RequestKey(transfer.connectionId, request.ref.requestId)
        }
        val ack = runBlocking {
            val request = server.addPending(transfer.connectionId)
            repository.refresh(transfer.connectionId)
            RequestKey(transfer.connectionId, request.ref.requestId)
        }
        val viewModel =
            InboxViewModel(
                repository,
                wallet,
                evaluator,
                history,
                plugins = PluginRegistry.of(plugin),
                io = Dispatchers.Unconfined,
            )

        viewModel.prepare(transfer)
        listOf(transfer, message, ack).forEach(viewModel::review)

        assertEquals(emptyList<String>(), plugin.calls)
        // And the transfer is still read by this app's own parser, as it always has been.
        val reviewed = viewModel.state.value.preparations[transfer] as Preparation.Ready
        assertEquals(Verdict.Verified, reviewed.inspection.verdict)
        assertTrue(checkNotNull(viewModel.state.value.assessments[transfer]).facts.fullyRead)
    }

    @Test
    fun aRequestFromAServerThisBuildDoesNotSupportIsReadButNeverApproved() {
        // SEE-88: the server needs a client plugin this build doesn't carry, so there is nothing
        // here that would carry its operations out. The request is still read, and can still be
        // rejected; the affirmative answer is refused before the wallet is even looked at.
        val (key, selected) = readyToSign()
        server.manifest =
            directManifest(
                serverId = server.serverId,
                url = URL,
                required = listOf("jupiter.swap" to 1..1),
            )
        runBlocking { repository.refresh(key.connectionId) }
        val viewModel = viewModel()

        viewModel.approve(key, selected)

        assertEquals(
            ServerSupport.PluginMissing(listOf(PluginId("jupiter.swap"))),
            viewModel.support(key.connectionId),
        )
        assertEquals(SigningProblem.ServerUnsupported, viewModel.state.value.problem)
        assertEquals(key, viewModel.state.value.problemKey)
        // No wallet was opened, and nothing was sent: not an approval the sidecar refused, but one
        // this phone never made.
        assertEquals(emptyList<Any>(), adapter.signings)
        assertEquals(emptyList<Any>(), gateway.submits)
        assertNull(repository.inbox.value.result(key))
    }

    @Test
    fun anAcknowledgementIsNotAnsweredForAnUnsupportedServerEither() {
        // The button is disabled on screen, and the rule holds in the ViewModel too, so it does
        // not depend on which caller asked (SEE-88).
        val key = pendingRequest()
        server.manifest =
            directManifest(
                serverId = server.serverId,
                url = URL,
                required = listOf("jupiter.swap" to 1..1),
            )
        runBlocking { repository.refresh(key.connectionId) }
        val viewModel = viewModel()

        viewModel.answer(key, Answer.Acknowledge)

        assertEquals(SigningProblem.ServerUnsupported, viewModel.state.value.problem)
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    @Test
    fun rejectingARequestFromAnUnsupportedServerStillWorks() {
        // Viewable and refusable: what is missing is on this phone, and the owner is not stuck
        // with a request they can neither answer nor clear.
        val (key, _) = readyToSign()
        server.manifest =
            directManifest(
                serverId = server.serverId,
                url = URL,
                required = listOf("jupiter.swap" to 1..1),
            )
        runBlocking { repository.refresh(key.connectionId) }
        val viewModel = viewModel()

        viewModel.answer(key, Answer.Reject)

        assertEquals(
            listOf(SubmitResultRequest.ResultCase.REJECTION),
            gateway.submits.map { it.second.resultCase },
        )
    }

    @Test
    fun nothingIsPreparedForAServerThisBuildDoesNotSupport() {
        // Preparing is the first step of executing, so it stops with the rest: no transaction is
        // built for bytes that could never be signed here.
        val (key, _) = pendingTransfer()
        server.manifest =
            directManifest(
                serverId = server.serverId,
                url = URL,
                required = listOf("jupiter.prediction" to 1..1),
            )
        runBlocking { repository.refresh(key.connectionId) }
        val viewModel = viewModel()

        viewModel.prepare(key)

        assertEquals(SigningProblem.ServerUnsupported, viewModel.state.value.problem)
        assertNull(viewModel.state.value.preparations[key])
        assertNull(gateway.preparations[key])
    }

    @Test
    fun theSameServerIsSupportedByABuildThatCarriesWhatItNeeds() {
        // The other half of the rule: support is derived from the plugins compiled in, so the same
        // manifest is executable in a build that has them. Nothing on disk changes.
        val (key, selected) = readyToSign()
        server.manifest =
            directManifest(
                serverId = server.serverId,
                url = URL,
                required = listOf("jupiter.swap" to 1..1),
            )
        runBlocking { repository.refresh(key.connectionId) }
        adapter.answerSigning(
            SignResult.Signed(
                ByteString.copyFromUtf8("Sign in to Example"),
                selected.address,
                ByteString.copyFrom(ByteArray(64) { 7 }),
            )
        )
        val viewModel =
            InboxViewModel(
                repository,
                wallet,
                evaluator,
                history,
                plugins = PluginRegistry.of(TestPlugin(id = "jupiter.swap")),
                io = Dispatchers.Unconfined,
            )

        assertEquals(ServerSupport.Supported, viewModel.support(key.connectionId))

        viewModel.approve(key, selected)

        assertNull(viewModel.state.value.problem)
        assertEquals(1, adapter.signings.size)
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val THIRD_WALLET = "So11111111111111111111111111111111111111112"
        const val URL = "https://vault.example.com"
        const val OTHER_URL = "https://other.example.com"
        const val OTHER_REQUEST = "de03846e-d435-4705-b2e3-ec67da539f12"
        const val OTHER_ACTIVITY_CONNECTION = "9c1d7b3a-8e4f-4a52-b0c6-1d2e3f4a5b6c"
        const val OTHER_ACTIVITY_REQUEST = "a1111111-1111-4111-8111-111111111111"
    }
}
