package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.RequestState
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    private companion object {
        const val URL_A = "https://a.example.com"
        const val URL_B = "https://b.example.com"
        const val SAME_REQUEST = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        const val OTHER_REQUEST = "de03846e-d435-4705-b2e3-ec67da539f12"
    }
}
