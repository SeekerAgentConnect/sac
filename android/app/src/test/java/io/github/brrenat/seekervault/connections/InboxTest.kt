package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.SubmitResultRequest
import io.github.brrenat.seekervault.request.v1.walletBinding
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The inbox against fake sidecars: fetching, answering once, keeping an answer through failures and
 * restarts, lost responses, overlapping sends, and identical request IDs on two servers.
 */
@RunWith(AndroidJUnit4::class)
class InboxTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val serverA = gateway.serve(URL_A)
    private val serverB = gateway.serve(URL_B)
    private val key = softwareKey()
    private var clock = Instant.parse("2026-09-11T12:00:00Z")

    private fun repository() =
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "files/connections")),
            vault = CredentialVault(File(folder.root, "no_backup/credentials")) { key },
            results = ResultStore(File(folder.root, "files/results")),
            gateway = gateway,
            deviceName = "Seeker",
            now = { clock },
            io = Dispatchers.Unconfined,
        )

    // Lazy: the temporary folder exists only once the rule has run.
    private val repository by lazy { repository() }

    private fun ConnectionRepository.pending(id: String) = inbox.value.pending[id].orEmpty()

    private fun binding(address: String) = walletBinding {
        wallet = address
        network = Network.NETWORK_DEVNET
    }

    private fun ConnectionRepository.result(key: RequestKey) = inbox.value.result(key)

    /** A paired connection on [server] with one PENDING ack, fetched into the inbox. */
    private suspend fun oneRequest(
        server: FakeConnectionGateway.Server = serverA,
        url: String = URL_A,
    ): RequestKey {
        val connection = repository.pair(server.issue(url))
        val request = server.addPending(connection.id)
        repository.refresh(connection.id)
        return RequestKey(connection.id, request.ref.requestId)
    }

    @Test
    fun fetchesEveryPageOfPendingRequestsAndAnswersNothing() = runBlocking {
        val connection = repository.pair(serverA.issue(URL_A))
        serverA.pageSize = 2
        repeat(5) { serverA.addPending(connection.id) }
        repository.refresh(connection.id)
        assertEquals(5, repository.pending(connection.id).size)
        assertEquals(5, repository.connection(connection.id)?.lastCheck?.pending)
        // Loading the inbox never answers anything.
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    @Test
    fun storesAnAnswerSendsItOnceAndKeepsTheOutcome() = runBlocking {
        val key = oneRequest()
        val result = repository.answer(key, Answer.Acknowledge)
        assertEquals(Delivery.Accepted, result.delivery)
        assertEquals(RequestState.REQUEST_STATE_COMPLETED, result.request.state)
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        assertTrue(repository.pending(key.connectionId).isEmpty())
        assertEquals(0, repository.connection(key.connectionId)?.lastCheck?.pending)
        // A second answer, even a different one, returns the first and sends nothing.
        assertEquals(result, repository.answer(key, Answer.Reject))
        assertEquals(1, gateway.submits.size)
        // After a restart, reopening the request still shows its outcome.
        val reopened = repository()
        reopened.load()
        assertEquals(Delivery.Accepted, reopened.result(key)?.delivery)
        assertEquals(Answer.Acknowledge, reopened.result(key)?.answer)
    }

    @Test
    fun rejects() = runBlocking {
        val key = oneRequest()
        val result = repository.answer(key, Answer.Reject)
        assertEquals(Delivery.Accepted, result.delivery)
        assertEquals(
            RequestState.REQUEST_STATE_REJECTED,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        assertTrue(gateway.submits.single().second.hasRejection())
    }

    @Test
    fun keepsAnAnswerWhileTheServerIsUnreachableAndSendsItOnRefresh() = runBlocking {
        val key = oneRequest()
        serverA.failure = GatewayException.Kind.Unreachable
        val waiting = repository.answer(key, Answer.Acknowledge)
        assertEquals(Delivery.Waiting, waiting.delivery)
        assertEquals(CheckOutcome.Unreachable, waiting.lastFailure)
        assertEquals(
            RequestState.REQUEST_STATE_PENDING,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        // The answer survives a restart.
        val reopened = repository()
        reopened.load()
        assertEquals(Delivery.Waiting, reopened.result(key)?.delivery)
        serverA.failure = null
        reopened.refresh(key.connectionId)
        assertEquals(Delivery.Accepted, reopened.result(key)?.delivery)
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        assertEquals(1, gateway.submits.size) // the first attempt never reached the server
    }

    @Test
    fun sendsAnAnswerAgainAfterALostResponseAndTheRepeatChangesNothing() = runBlocking {
        val key = oneRequest()
        serverA.loseNextResponse = true
        val waiting = repository.answer(key, Answer.Acknowledge)
        // The sidecar took it, but the phone didn't hear back, so it keeps the answer.
        assertEquals(Delivery.Waiting, waiting.delivery)
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        repository.refresh(key.connectionId)
        assertEquals(Delivery.Accepted, repository.result(key)?.delivery)
        assertEquals(RequestState.REQUEST_STATE_COMPLETED, repository.result(key)?.request?.state)
        assertEquals(2, gateway.submits.size) // sent twice, applied once
    }

    @Test
    fun sendsOneAnswerAtATimeWhenARefreshOverlapsTheTap() = runBlocking {
        val key = oneRequest()
        val sendingNow = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.beforeSubmit = {
            sendingNow.complete(Unit)
            release.await()
        }
        val answering = async { repository.answer(key, Answer.Acknowledge) }
        sendingNow.await()
        repository.refresh(key.connectionId) // finds the answer already being sent
        assertNull(repository.deliver(key))
        release.complete(Unit)
        assertEquals(Delivery.Accepted, answering.await().delivery)
        assertEquals(1, gateway.submits.size)
    }

    @Test
    fun keepsIdenticalRequestIdsOnTwoServersApart() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        val b = repository.pair(serverB.issue(URL_B))
        serverA.addPending(a.id, SAME_REQUEST, "For A")
        serverB.addPending(b.id, SAME_REQUEST, "For B")
        repository.refresh(a.id)
        repository.refresh(b.id)
        repository.answer(RequestKey(a.id, SAME_REQUEST), Answer.Acknowledge)
        assertEquals(RequestState.REQUEST_STATE_COMPLETED, serverA.stateOf(a.id, SAME_REQUEST))
        assertEquals(RequestState.REQUEST_STATE_PENDING, serverB.stateOf(b.id, SAME_REQUEST))
        assertEquals(listOf("For B"), repository.pending(b.id).map { it.action.ack.text })
        assertNull(repository.result(RequestKey(b.id, SAME_REQUEST)))
        assertEquals(listOf(URL_A), gateway.submits.map { it.first })
        repository.answer(RequestKey(b.id, SAME_REQUEST), Answer.Reject)
        assertEquals(RequestState.REQUEST_STATE_REJECTED, serverB.stateOf(b.id, SAME_REQUEST))
        assertEquals(Answer.Acknowledge, repository.result(RequestKey(a.id, SAME_REQUEST))?.answer)
        assertEquals(listOf(URL_A, URL_B), gateway.submits.map { it.first })
    }

    @Test
    fun anAnswerThatArrivesAfterTheAgentCancelledIsSuperseded() = runBlocking {
        val key = oneRequest()
        serverA.cancel(key.connectionId, key.requestId)
        val result = repository.answer(key, Answer.Acknowledge)
        assertEquals(Delivery.Superseded, result.delivery)
        assertEquals(RequestState.REQUEST_STATE_CANCELLED, result.request.state)
        assertTrue(repository.pending(key.connectionId).isEmpty())
    }

    @Test
    fun anAnswerForARevokedConnectionCantBeSent() = runBlocking {
        val key = oneRequest()
        serverA.failure = GatewayException.Kind.Unreachable
        repository.answer(key, Answer.Acknowledge)
        serverA.failure = null
        serverA.revoke(key.connectionId) // pnpm pair revoke
        repository.refresh(key.connectionId)
        assertEquals(Delivery.Undeliverable, repository.result(key)?.delivery)
        assertNotNull(repository.connection(key.connectionId)?.revokedAt)
        assertTrue(repository.pending(key.connectionId).isEmpty())
    }

    @Test
    fun publishingAnotherWalletTakesTheRequestsItNoLongerFitsOffTheInbox() = runBlocking {
        val connection = repository.pair(serverA.issue(URL_A))
        val ack = serverA.addPending(connection.id, text = "Deploy finished")
        val transfer = serverA.addPendingTransfer(connection.id, WALLET, Network.NETWORK_DEVNET)
        repository.refresh(connection.id)
        assertEquals(2, repository.pending(connection.id).size)

        // The owner connects a different wallet: the sidecar cancels what no longer fits.
        assertTrue(repository.publishWallet(connection.id, binding(OTHER_WALLET)))
        assertEquals(
            listOf(ack.ref.requestId),
            repository.pending(connection.id).map { it.ref.requestId },
        )
        assertEquals(
            RequestState.REQUEST_STATE_CANCELLED,
            serverA.stateOf(connection.id, transfer.ref.requestId),
        )
        assertEquals(1, repository.connection(connection.id)?.lastCheck?.pending)
    }

    @Test
    fun aWalletNeverGoesToAnotherConnectionsServerAndARevokedOneIsNoticed() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        val b = repository.pair(serverB.issue(URL_B))
        assertTrue(repository.publishWallet(a.id, binding(WALLET)))
        assertEquals(listOf(URL_A), gateway.published.map { it.first })
        assertNull(serverB.wallet)

        serverB.revoke(b.id)
        assertFalse(repository.publishWallet(b.id, binding(WALLET)))
        assertNotNull(repository.connection(b.id)?.revokedAt)
    }

    @Test
    fun removingAConnectionRemovesOnlyItsAnswers() = runBlocking {
        val a = oneRequest(serverA, URL_A)
        val b = oneRequest(serverB, URL_B)
        repository.answer(a, Answer.Acknowledge)
        repository.answer(b, Answer.Acknowledge)
        repository.remove(a.connectionId)
        assertNull(repository.result(a))
        assertNotNull(repository.result(b))
        assertFalse(File(folder.root, "files/results/${a.connectionId}").exists())
        assertTrue(File(folder.root, "files/results/${b.connectionId}").isDirectory)
    }

    @Test
    fun answersOnlyARequestThatIsPending() = runBlocking {
        val key = oneRequest()
        val unknown = RequestKey(key.connectionId, OTHER_REQUEST)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.answer(unknown, Answer.Acknowledge) }
        }
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    @Test
    fun anOlderFetchNeverOverwritesANewerOne() = runBlocking {
        val connection = repository.pair(serverA.issue(URL_A))
        val old = serverA.addPending(connection.id, text = "Old")
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = true
        gateway.afterList = {
            if (hold) {
                hold = false
                read.complete(Unit)
                release.await()
            }
        }
        val first = async { repository.refresh(connection.id) } // reads "Old", then waits
        read.await()
        // Meanwhile the agent withdraws that request and asks another, and a second fetch starts.
        serverA.cancel(connection.id, old.ref.requestId)
        val new = serverA.addPending(connection.id, text = "New")
        val second = async { repository.refresh(connection.id) }
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("New"), repository.pending(connection.id).map { it.action.ack.text })
        assertEquals(new.ref.requestId, repository.pending(connection.id).single().ref.requestId)
        assertEquals(1, repository.connection(connection.id)?.lastCheck?.pending)
    }

    @Test
    fun aFetchThatCrossesAnAnswerDoesntBringItsRequestBack() = runBlocking {
        val key = oneRequest()
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var hold = true
        gateway.afterList = {
            if (hold) {
                hold = false
                read.complete(Unit)
                release.await()
            }
        }
        val fetching = async { repository.refresh(key.connectionId) } // reads it as PENDING
        read.await()
        assertEquals(Delivery.Accepted, repository.answer(key, Answer.Acknowledge).delivery)
        release.complete(Unit)
        fetching.await()
        assertTrue(repository.pending(key.connectionId).isEmpty())
        assertEquals(0, repository.connection(key.connectionId)?.lastCheck?.pending)
    }

    @Test
    fun aFetchThatFinishesAfterItsConnectionWasRemovedPublishesNothing() = runBlocking {
        val connection = repository.pair(serverA.issue(URL_A))
        serverA.addPending(connection.id)
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.afterList = {
            read.complete(Unit)
            release.await()
        }
        val fetching = async { repository.refresh(connection.id) }
        read.await()
        repository.remove(connection.id)
        release.complete(Unit)
        fetching.await()
        assertFalse(connection.id in repository.inbox.value.pending)
        assertNull(repository.connection(connection.id))
    }

    @Test
    fun aSuccessfulReplyAfterItsConnectionWasRemovedWritesNothing() = runBlocking {
        removeAWhileItsAnswerIsSent {}
    }

    @Test
    fun aFailedReplyAfterItsConnectionWasRemovedWritesNothing() = runBlocking {
        removeAWhileItsAnswerIsSent {
            throw GatewayException(GatewayException.Kind.Unreachable, "the connection dropped")
        }
    }

    @Test
    fun aLateFailureDoesntUndoARevocationThatSettledTheAnswer() = runBlocking {
        val key = oneRequest()
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.beforeSubmit = {
            sending.complete(Unit)
            release.await()
            throw GatewayException(GatewayException.Kind.Unreachable, "the connection dropped")
        }
        val answering = async { repository.answer(key, Answer.Acknowledge) }
        sending.await()
        // Meanwhile the operator revokes the pairing, and a refresh finds out.
        serverA.revoke(key.connectionId)
        repository.refresh(key.connectionId)
        val settled = checkNotNull(repository.result(key))
        assertEquals(Delivery.Undeliverable, settled.delivery)
        release.complete(Unit)
        answering.await()
        // The late failure changes nothing: not the delivery, and it adds no failure reason.
        assertEquals(settled, repository.result(key))
        val reopened = repository()
        reopened.load()
        assertEquals(settled, reopened.result(key))
    }

    /**
     * Sets up B with one settled answer and one pending request. It then holds A's answer in
     * SubmitResult, removes A, and lets the reply through: [reply] runs as the hold ends, and can
     * throw to fail the send. Afterwards A stays removed, and B is as it was.
     */
    private suspend fun removeAWhileItsAnswerIsSent(reply: suspend () -> Unit) = coroutineScope {
        val b = oneRequest(serverB, URL_B)
        repository.answer(b, Answer.Acknowledge)
        serverB.addPending(b.connectionId, text = "Still for B")
        repository.refresh(b.connectionId)
        val before = repository.snapshot(b.connectionId)
        val a = oneRequest(serverA, URL_A)
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        gateway.beforeSubmit = {
            sending.complete(Unit)
            release.await()
            reply()
        }
        val answering = async { repository.answer(a, Answer.Acknowledge) }
        sending.await()
        repository.remove(a.connectionId)
        release.complete(Unit)
        answering.await()

        assertEquals(before, repository.snapshot(b.connectionId))
        val id = a.connectionId
        // Nothing of A is left on disk: its metadata, its credential, and its answers.
        assertNull(ConnectionStore(File(folder.root, "files/connections")).get(id))
        assertFalse(
            CredentialVault(File(folder.root, "no_backup/credentials")) { key }.contains(id)
        )
        assertFalse(File(folder.root, "files/results/$id").exists())
        // Nor in what the app shows, after refreshing and after a restart.
        repository.refresh(id)
        repository.refresh(b.connectionId)
        val reopened = repository()
        reopened.load()
        for (app in listOf(repository, reopened)) {
            assertNull(app.connection(id))
            assertFalse(id in app.inbox.value.pending)
            assertTrue(app.inbox.value.results.none { it.connectionId == id })
        }
        assertFalse(File(folder.root, "files/results/$id").exists())
        assertEquals(
            before.third,
            reopened.inbox.value.results.filter { it.connectionId == b.connectionId },
        )
    }

    // A connection's metadata, pending requests, and stored answers, as the app shows them.
    private fun ConnectionRepository.snapshot(id: String) =
        Triple(connection(id), pending(id), inbox.value.results.filter { it.connectionId == id })

    @Test
    fun leavesOutARequestWhoseIdCantNameAStoredAnswer() = runBlocking {
        val connection = repository.pair(serverA.issue(URL_A))
        val good = serverA.addPending(connection.id)
        gateway.foreignRequests += FakeConnectionGateway.request(connection.id, "../../prefs")
        repository.refresh(connection.id)
        assertEquals(
            listOf(good.ref.requestId),
            repository.pending(connection.id).map { it.ref.requestId },
        )
        assertEquals(1, repository.connection(connection.id)?.lastCheck?.pending)
    }

    @Test
    fun keepsAnAnswerThatSettledLateForAWeekFromWhenItSettled() = runBlocking {
        val key = oneRequest()
        serverA.failure = GatewayException.Kind.Unreachable
        repository.answer(key, Answer.Acknowledge)
        // The server stays unreachable for eight days, and then takes the answer.
        clock = clock.plusSeconds(8 * 86_400)
        serverA.failure = null
        repository.refresh(key.connectionId)
        assertEquals(Delivery.Accepted, repository.result(key)?.delivery)
        assertEquals(clock, repository.result(key)?.settledAt)

        clock = clock.plusSeconds(86_400)
        val nextDay = repository()
        nextDay.load()
        assertEquals(Delivery.Accepted, nextDay.result(key)?.delivery)
        clock = clock.plusSeconds(7 * 86_400)
        val nextWeek = repository()
        nextWeek.load()
        assertNull(nextWeek.result(key))
    }

    @Test
    fun forgetsSettledAnswersAfterAWeekButKeepsWaitingOnes() = runBlocking {
        val settled = oneRequest(serverA, URL_A)
        val waiting = oneRequest(serverB, URL_B)
        repository.answer(settled, Answer.Acknowledge)
        serverB.failure = GatewayException.Kind.Unreachable
        repository.answer(waiting, Answer.Acknowledge)
        clock = clock.plusSeconds(8 * 86_400)
        val reopened = repository()
        reopened.load()
        assertNull(reopened.result(settled))
        assertEquals(Delivery.Waiting, reopened.result(waiting)?.delivery)
    }

    /** A paired connection with one PENDING message for [WALLET], fetched into the inbox. */
    private suspend fun oneMessage(): RequestKey {
        val connection = repository.pair(serverA.issue(URL_A))
        val request = serverA.addPendingMessage(connection.id, WALLET)
        repository.refresh(connection.id)
        return RequestKey(connection.id, request.ref.requestId)
    }

    @Test
    fun sendsTheApprovalFirstAndTheSignatureAfterIt() = runBlocking {
        val key = oneMessage()
        val approved = repository.answer(key, Answer.Approve)
        // The approval has been accepted, and the request is with the wallet now.
        assertTrue(approved.approved)
        assertEquals(Delivery.Waiting, approved.delivery)
        assertNull(approved.signing)
        assertEquals(
            RequestState.REQUEST_STATE_PROCESSING,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        val signature = ByteString.copyFrom(ByteArray(64) { 3 })
        val signed = checkNotNull(repository.recordSigning(key, SigningOutcome.Signed(signature)))
        assertEquals(Delivery.Accepted, signed.delivery)
        assertEquals(
            RequestState.REQUEST_STATE_COMPLETED,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.MESSAGE_SIGNATURE,
            ),
            gateway.submits.map { it.second.resultCase },
        )
        // The approval names the SHA-256 of exactly the message's bytes, and no preparation.
        val approval = gateway.submits.first().second.approval
        assertEquals(0, approval.preparedVersion)
        assertEquals(
            ByteString.copyFrom(
                MessageDigest.getInstance("SHA-256")
                    .digest("Sign in to Example".toByteArray(Charsets.UTF_8))
            ),
            approval.contentHash,
        )
    }

    @Test
    fun keepsAnApprovalWaitingWhileTheServerCantBeReachedAndSendsBothOnRefresh() = runBlocking {
        val key = oneMessage()
        serverA.failure = GatewayException.Kind.Unreachable
        val stored = repository.answer(key, Answer.Approve)
        assertEquals(Delivery.Waiting, stored.delivery)
        assertFalse(stored.approved)
        val signature = ByteString.copyFrom(ByteArray(64) { 3 })
        repository.recordSigning(key, SigningOutcome.Signed(signature))
        assertEquals(emptyList<Any>(), gateway.submits)

        serverA.failure = null
        repository.refresh(key.connectionId)

        assertEquals(
            listOf(
                SubmitResultRequest.ResultCase.APPROVAL,
                SubmitResultRequest.ResultCase.MESSAGE_SIGNATURE,
            ),
            gateway.submits.map { it.second.resultCase },
        )
        assertEquals(Delivery.Accepted, repository.result(key)?.delivery)
    }

    @Test
    fun recordsAnApprovalTheWalletNeverAnsweredAsAFailureWhenTheAppOpensAgain() = runBlocking {
        val key = oneMessage()
        // The owner approved, and the app closed before the wallet came back with anything.
        serverA.failure = GatewayException.Kind.Unreachable
        repository.answer(key, Answer.Approve)
        serverA.failure = null

        val reopened = repository()
        reopened.load()

        val outcome = reopened.result(key)?.signing
        assertEquals(
            SigningOutcome.Failed(
                "The app closed before the wallet answered, so nothing was signed."
            ),
            outcome,
        )
        // Nothing was signed and nothing was broadcast, so the request fails rather than hanging.
        reopened.deliver(key)
        assertEquals(
            RequestState.REQUEST_STATE_FAILED,
            serverA.stateOf(key.connectionId, key.requestId),
        )
        assertEquals(Delivery.Accepted, reopened.result(key)?.delivery)
    }

    @Test
    fun keepsTheFirstSigningOutcomeAndIgnoresALaterOne() = runBlocking {
        val key = oneMessage()
        repository.answer(key, Answer.Approve)
        val signature = ByteString.copyFrom(ByteArray(64) { 3 })
        repository.recordSigning(key, SigningOutcome.Signed(signature))
        repository.recordSigning(key, SigningOutcome.Declined)
        assertEquals(SigningOutcome.Signed(signature), repository.result(key)?.signing)
        assertEquals(2, gateway.submits.size)
    }

    @Test
    fun refusesAnApprovalOfSomethingThatIsNotAMessage() = runBlocking {
        val key = oneRequest()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.answer(key, Answer.Approve) }
        }
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    @Test
    fun letsTheOwnerRejectAMessageWithoutAnyApproval() = runBlocking {
        val key = oneMessage()
        val rejected = repository.answer(key, Answer.Reject)
        assertEquals(Delivery.Accepted, rejected.delivery)
        assertFalse(rejected.approved)
        assertEquals(
            listOf(SubmitResultRequest.ResultCase.REJECTION),
            gateway.submits.map { it.second.resultCase },
        )
        assertEquals(
            RequestState.REQUEST_STATE_REJECTED,
            serverA.stateOf(key.connectionId, key.requestId),
        )
    }

    private companion object {
        const val URL_A = "https://a.example.com"
        const val URL_B = "https://b.example.com"
        const val SAME_REQUEST = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        const val OTHER_REQUEST = "de03846e-d435-4705-b2e3-ec67da539f12"
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
    }
}
