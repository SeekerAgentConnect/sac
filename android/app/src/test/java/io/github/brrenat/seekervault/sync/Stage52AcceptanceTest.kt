package io.github.brrenat.seekervault.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ConnectConnectionGateway
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.PairingCode
import io.github.brrenat.seekervault.connections.PairingCodeResult
import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.RealSidecar
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.sync.storage.SyncStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Stage 5.2's cross-component acceptance path. A real MCP client creates requests in the real Node
 * sidecar and the production Android repositories consume its real bidirectional gRPC stream and
 * unary Sync endpoint over HTTP/2. This is deliberately separate from the Stage 1 Live diagnostic.
 *
 * It needs the repository's pinned Node dependencies, exactly like [RealSidecar]'s earlier durable
 * queue tests. Device scheduling behavior remains in the physical Seeker checklist.
 */
@RunWith(AndroidJUnit4::class)
class Stage52AcceptanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val sidecars = mutableListOf<RealSidecar>()
    private val managers = mutableListOf<ManagedForeground>()
    private val key = softwareKey()

    @After
    fun stop() {
        managers.forEach { it.close() }
        sidecars.forEach(RealSidecar::close)
    }

    @Test
    fun foregroundEventsLifecycleCatchupAndPersistentCacheUseTheProductionHttp2Path() =
        runBlocking {
            val sidecar = sidecar()
            val app = repository(sidecar)
            app.load()
            val connection = app.pair(code(sidecar))
            val foreground = foreground(app)
            val initial = checkNotNull(app.synchronization).synchronize(connection.id)
            assertTrue(initial is SynchronizeOutcome.Updated)

            foreground.manager.onForeground()
            foreground.manager.onForeground() // navigation/rotation do not own another stream
            await(
                failure = {
                    "foreground was ${foreground.state(connection)}; sidecar log:\n${sidecar.output}"
                }
            ) {
                foreground.state(connection) == ForegroundConnectionState.Live
            }
            await { sidecar.output.count("update stream opened for connection") == 1 }

            val arrived = sidecar.requestAck("Arrived while Inbox is open", "stage52-arrival")
            await { app.pendingIds(connection) == listOf(arrived) }
            assertEquals(1, app.connection(connection.id)?.lastCheck?.pending)

            assertEquals("CANCELLED", sidecar.cancel(arrived))
            await { app.pendingIds(connection).isEmpty() }
            assertEquals(0, app.connection(connection.id)?.lastCheck?.pending)

            val expires =
                sidecar.requestAck(
                    "Expires while the sidecar restarts",
                    "stage52-expiry",
                    expiresInSeconds = 60,
                )
            await { app.pendingIds(connection) == listOf(expires) }
            sidecar.restart(clockAheadSeconds = 120)
            await { foreground.state(connection) == ForegroundConnectionState.Live }
            await { app.pendingIds(connection).isEmpty() }

            val closedBeforeBackground = sidecar.output.count("update stream closed for connection")
            foreground.manager.onBackground()
            await {
                sidecar.output.count("update stream closed for connection") > closedBeforeBackground
            }
            val missed = sidecar.requestAck("Made while the app is backgrounded", "stage52-missed")
            delay(100)
            assertFalse(missed in app.pendingIds(connection))

            // A worker-only repository models an ordinary process start with no Activity. It
            // reloads the credential, catches up through unary HTTP/2 Sync, and commits the result.
            val workerProcess = repository(sidecar)
            assertTrue(workerProcess.synchronizeAll()[connection.id] is SynchronizeOutcome.Updated)
            assertEquals(listOf(missed), workerProcess.pendingIds(connection))

            // With the sidecar unavailable, another process still publishes the complete cached
            // document written above. No half-written snapshot or duplicate row is observable.
            sidecar.stop()
            val reopened = repository(sidecar)
            reopened.load()
            assertEquals(listOf(missed), reopened.pendingIds(connection))
            assertNotNull(reopened.synchronization?.state?.value?.connections?.get(connection.id))
        }

    @Test
    fun twoSidecarsStayIsolatedAndAnOutageRetriesOnlyAnAlreadyRecordedResult() = runBlocking {
        val a = sidecar()
        val b = sidecar()
        val app = repository(a, b)
        app.load()
        val onA = app.pair(code(a))
        val onB = app.pair(code(b))
        val initial = checkNotNull(app.synchronization).synchronizeAll()
        assertTrue(
            "initial HTTP/2 Sync was $initial; A:\n${a.output}\nB:\n${b.output}",
            initial.values.all { it is SynchronizeOutcome.Updated },
        )
        val backoffs = CopyOnWriteArrayList<Long>()
        val foreground = foreground(app, backoffs)
        foreground.manager.onForeground()
        await {
            foreground.state(onA) == ForegroundConnectionState.Live &&
                foreground.state(onB) == ForegroundConnectionState.Live
        }

        val waiting = a.requestAck("Answer survives A's outage", "stage52-queued-result")
        await { app.pendingIds(onA) == listOf(waiting) }
        a.stop()
        assertEquals(
            Delivery.Waiting,
            app.answer(RequestKey(onA.id, waiting), Answer.Acknowledge).delivery,
        )
        await { backoffs.isNotEmpty() }
        assertEquals(1_000L, backoffs.first())
        assertTrue(backoffs.all { it in 0L..30_000L })

        val stillLive = b.requestAck("B remains live", "stage52-independent")
        await { app.pendingIds(onB) == listOf(stillLive) }
        assertFalse(stillLive in app.pendingIds(onA))
        assertEquals(ForegroundConnectionState.Live, foreground.state(onB))

        a.start()
        await { foreground.state(onA) == ForegroundConnectionState.Live }
        await {
            app.inbox.value.result(RequestKey(onA.id, waiting))?.delivery == Delivery.Accepted
        }
        assertEquals("COMPLETED", a.status(waiting))
        assertTrue(app.pendingIds(onA).isEmpty())
        assertEquals(listOf(stillLive), app.pendingIds(onB))

        val closedBeforeBackground =
            listOf(a, b).associateWith {
                it.output.count("update stream closed for connection")
            }
        foreground.manager.onBackground()
        await {
            listOf(a, b).all { sidecar ->
                sidecar.output.count("update stream closed for connection") >
                    closedBeforeBackground.getValue(sidecar)
            }
        }
    }

    private fun sidecar() = RealSidecar(productionUpdates = true).also { sidecars += it }

    private fun repository(vararg trusted: RealSidecar): ConnectionRepository {
        check(trusted.all { it.updateUrl != null })
        val http = ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
        return ConnectionRepository(
            store = ConnectionStore(File(folder.root, "files/connections")),
            vault = CredentialVault(File(folder.root, "no_backup/credentials")) { key },
            results = ResultStore(File(folder.root, "files/results")),
            gateway = ConnectConnectionGateway(http),
            deviceName = "Seeker",
            io = Dispatchers.IO,
            syncStore = SyncStore(File(folder.root, "files/sync")),
            updateTransport = ConnectUpdateTransport(http),
        )
    }

    private fun foreground(
        repository: ConnectionRepository,
        backoffs: MutableList<Long>? = null,
    ): ManagedForeground {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager =
            ForegroundUpdateManager(
                repository.connections,
                checkNotNull(repository.synchronization),
                dispatcher = Dispatchers.IO,
                sleep = { millis ->
                    backoffs?.add(millis)
                    delay(minOf(millis, 100L))
                },
                jitter = { it },
                ownerScope = scope,
            )
        return ManagedForeground(manager, scope).also { managers += it }
    }

    private fun code(sidecar: RealSidecar): PairingCode =
        (PairingCodes.parse(sidecar.pairingCode()) { it == "127.0.0.1" } as PairingCodeResult.Valid)
            .code

    private fun ConnectionRepository.pendingIds(connection: Connection): List<String> =
        inbox.value.pending[connection.id].orEmpty().map { it.ref.requestId }

    private suspend fun await(
        failure: () -> String = { "condition was not met" },
        condition: () -> Boolean,
    ) {
        try {
            withTimeout(30.seconds) {
                while (!condition()) delay(20)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError(failure(), error)
        }
    }

    private fun String.count(needle: String): Int = split(needle).size - 1

    private data class ManagedForeground(
        val manager: ForegroundUpdateManager,
        val scope: CoroutineScope,
    ) {
        fun state(connection: Connection): ForegroundConnectionState? =
            manager.state.value.connections[connection.id]

        fun close() {
            manager.onBackground()
            scope.cancel()
        }
    }
}
