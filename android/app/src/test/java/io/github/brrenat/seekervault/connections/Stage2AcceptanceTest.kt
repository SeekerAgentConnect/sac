package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.request.v1.RequestState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The Stage 2 acceptance scenario (SAW-014) from the app's side: the app's own repository and
 * storage, paired with two real sidecars. The app restarts (a new repository over the same files),
 * the sidecars restart, a request expires while its sidecar is down, the owner rejects one, and the
 * operator revokes a pairing. `pnpm test:queue` drives the agent's side. Neither counts as the
 * owner's check on the physical Seeker. Needs Node 24 and `pnpm install`.
 */
@RunWith(AndroidJUnit4::class)
class Stage2AcceptanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val sidecars = mutableListOf<RealSidecar>()
    private val key = softwareKey()
    private val gateway =
        ConnectConnectionGateway(
            ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
        )

    @After fun stop() = sidecars.forEach(RealSidecar::close)

    private fun sidecar() = RealSidecar().also { sidecars += it }

    // A new repository over the same files is the app starting again.
    private suspend fun app(): ConnectionRepository =
        ConnectionRepository(
                store = ConnectionStore(File(folder.root, "connections")),
                vault = CredentialVault(File(folder.root, "credentials")) { key },
                results = ResultStore(File(folder.root, "results")),
                gateway = gateway,
                deviceName = "Seeker",
                io = Dispatchers.IO,
            )
            .also { it.load() }

    // What the app does when it opens, and when the owner taps Refresh: it fetches every
    // connection.
    private suspend fun ConnectionRepository.refreshAll() =
        connections.value.forEach { refresh(it.id) }

    private fun ConnectionRepository.pendingIds(connection: Connection): List<String> =
        inbox.value.pending[connection.id].orEmpty().map { it.ref.requestId }

    private fun code(sidecar: RealSidecar): PairingCode =
        (PairingCodes.parse(sidecar.pairingCode()) { it == "127.0.0.1" } as PairingCodeResult.Valid)
            .code

    @Test
    fun requestsQueuedWhileTheAppIsClosedSurviveSidecarRestartsAndCompleteWhenItReopens() =
        runBlocking {
            val a = sidecar()
            val b = sidecar()
            val first = app()
            val onA = first.pair(code(a))
            val onB = first.pair(code(b))
            // The app is closed. The agents ask, and both sidecars restart, A killed outright.
            val deploy = a.requestAck("Deploy finished on A", "a-1")
            val rotate = b.requestAck("Rotate the keys on B?", "b-1")
            a.restart(kill = true)
            b.restart()

            // The owner opens the app, and finds each request under its own connection.
            val reopened = app()
            reopened.refreshAll()
            assertEquals(listOf(deploy), reopened.pendingIds(onA))
            assertEquals(listOf(rotate), reopened.pendingIds(onB))
            assertEquals(
                Delivery.Accepted,
                reopened.answer(RequestKey(onA.id, deploy), Answer.Acknowledge).delivery,
            )
            assertEquals("COMPLETED", a.status(deploy))
            assertEquals(
                Delivery.Accepted,
                reopened.answer(RequestKey(onB.id, rotate), Answer.Reject).delivery,
            )
            assertEquals("REJECTED", b.status(rotate))

            // Opened again after another restart, the app shows both outcomes and nothing pending.
            a.restart()
            val again = app()
            again.refreshAll()
            assertEquals(
                RequestState.REQUEST_STATE_COMPLETED,
                again.inbox.value.result(RequestKey(onA.id, deploy))?.request?.state,
            )
            assertEquals(
                RequestState.REQUEST_STATE_REJECTED,
                again.inbox.value.result(RequestKey(onB.id, rotate))?.request?.state,
            )
            assertTrue(again.pendingIds(onA).isEmpty())
            assertTrue(again.pendingIds(onB).isEmpty())
        }

    @Test
    fun anAnswerGivenWhileItsSidecarIsDownIsSentAfterTheAppAndTheSidecarRestart() = runBlocking {
        val a = sidecar()
        val app = app()
        val connection = app.pair(code(a))
        val requestId = a.requestAck("Approve the release notes?", "a-2")
        app.refreshAll()
        val key = RequestKey(connection.id, requestId)

        a.stop()
        val waiting = app.answer(key, Answer.Acknowledge)
        assertEquals(Delivery.Waiting, waiting.delivery)
        assertEquals(CheckOutcome.Unreachable, waiting.lastFailure)

        // The app is closed and opened again while the sidecar is still down: the answer is kept.
        val reopened = app()
        reopened.refreshAll()
        assertEquals(Delivery.Waiting, reopened.inbox.value.result(key)?.delivery)

        // The sidecar is back, and the next refresh sends the answer.
        a.start()
        reopened.refreshAll()
        assertEquals(Delivery.Accepted, reopened.inbox.value.result(key)?.delivery)
        assertEquals("COMPLETED", a.status(requestId))
    }

    @Test
    fun aRequestThatExpiresWhileItsSidecarIsDownIsSupersededNotAnswered() = runBlocking {
        val a = sidecar()
        val app = app()
        val connection = app.pair(code(a))
        val short = a.requestAck("Only for a minute", "a-3", expiresInSeconds = 60)
        val long = a.requestAck("Good for a day", "a-4")
        app.refreshAll()
        assertEquals(setOf(short, long), app.pendingIds(connection).toSet())

        // Two minutes pass while the sidecar is down. The owner answers from the list already open.
        a.restart(clockAheadSeconds = 120)
        val late = app.answer(RequestKey(connection.id, short), Answer.Acknowledge)
        assertEquals(Delivery.Superseded, late.delivery)
        assertEquals(RequestState.REQUEST_STATE_EXPIRED, late.request.state)
        assertEquals("EXPIRED", a.status(short))

        // A refresh drops it. The request with a day to go is still there to answer.
        app.refreshAll()
        assertEquals(listOf(long), app.pendingIds(connection))
        assertEquals(
            Delivery.Accepted,
            app.answer(RequestKey(connection.id, long), Answer.Reject).delivery,
        )
        assertEquals("REJECTED", a.status(long))
    }

    @Test
    fun revokingOnePairingShutsOutOnlyThatConnection() = runBlocking {
        val a = sidecar()
        val b = sidecar()
        val app = app()
        val onA = app.pair(code(a))
        val onB = app.pair(code(b))
        val stillWanted = a.requestAck("Still wanted", "a-5")
        val revokedSoon = b.requestAck("Revoked before it's sent", "b-5")
        app.refreshAll()

        // The owner answers B's request while B is down, and the operator revokes the pairing.
        b.stop()
        assertEquals(
            Delivery.Waiting,
            app.answer(RequestKey(onB.id, revokedSoon), Answer.Acknowledge).delivery,
        )
        b.revokePairedPhone()
        b.start()

        app.refreshAll()
        val revoked = checkNotNull(app.connection(onB.id))
        assertFalse(revoked.usable)
        assertNotNull(revoked.revokedAt)
        assertEquals(
            Delivery.Undeliverable,
            app.inbox.value.result(RequestKey(onB.id, revokedSoon))?.delivery,
        )
        assertEquals("CANCELLED", b.status(revokedSoon))

        // The connection to A is untouched.
        assertTrue(app.connection(onA.id)?.usable == true)
        assertEquals(listOf(stillWanted), app.pendingIds(onA))
        assertEquals(
            Delivery.Accepted,
            app.answer(RequestKey(onA.id, stillWanted), Answer.Acknowledge).delivery,
        )
        assertEquals("COMPLETED", a.status(stillWanted))
    }
}
